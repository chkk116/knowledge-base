/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.repository.SysSettingRepository;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * LDAP / AD 登录（纯 JNDI 实现，零新依赖）。
 *
 * 流程：服务账号 bind → 按 userFilter 搜出用户 DN → 用「该 DN + 用户密码」再 bind 一次验证。
 * 直接拿用户密码去 bind 是最可靠的校验方式（不需要读 userPassword 属性，AD 也不允许读）。
 *
 * 配置（SysSetting，改完即生效）：
 *   sso.ldap.enabled / url / baseDn / userFilter / bindDn / bindPassword
 *   userFilter 里的 {0} 会被替换成转义后的用户名，例如：
 *     (sAMAccountName={0})       —— AD
 *     (uid={0})                  —— OpenLDAP
 *   可选属性映射：sso.ldap.attrMail（默认 mail）、sso.ldap.attrName（默认 displayName|cn）
 *
 * 安全约束：
 * 1. 密码为空直接拒绝 —— 否则会变成匿名 bind，等于任何账号都能登录；
 * 2. 用户名做 RFC4515 转义，防过滤器注入；
 * 3. 连接与查询超时 5 秒，LDAP 挂了不能把登录线程挂死。
 */
@Service
public class LdapAuthService {

    private static final Logger log = LoggerFactory.getLogger(LdapAuthService.class);

    private static final String K_ENABLED = "sso.ldap.enabled";
    private static final String K_URL = "sso.ldap.url";
    private static final String K_BASE = "sso.ldap.baseDn";
    private static final String K_FILTER = "sso.ldap.userFilter";
    private static final String K_BIND_DN = "sso.ldap.bindDn";
    private static final String K_BIND_PWD = "sso.ldap.bindPassword";
    private static final String K_ATTR_MAIL = "sso.ldap.attrMail";
    private static final String K_ATTR_NAME = "sso.ldap.attrName";

    private final SysSettingRepository repo;

    public LdapAuthService(SysSettingRepository repo) {
        this.repo = repo;
    }

    public boolean enabled() {
        return "true".equals(get(K_ENABLED)) && notBlank(get(K_URL));
    }

    public Map<String, Object> config() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("enabled", "true".equals(get(K_ENABLED)));
        ret.put("url", nvl(get(K_URL)));
        ret.put("baseDn", nvl(get(K_BASE)));
        ret.put("userFilter", nvl(get(K_FILTER)).isEmpty() ? "(sAMAccountName={0})" : get(K_FILTER));
        ret.put("bindDn", nvl(get(K_BIND_DN)));
        ret.put("hasBindPassword", notBlank(get(K_BIND_PWD)));
        ret.put("attrMail", nvl(get(K_ATTR_MAIL)).isEmpty() ? "mail" : get(K_ATTR_MAIL));
        ret.put("attrName", nvl(get(K_ATTR_NAME)).isEmpty() ? "displayName" : get(K_ATTR_NAME));
        return ret;
    }

    /** 保存配置：bindPassword 传空 = 不改原值（与 AI 模型管理的约定一致） */
    public Map<String, Object> save(Map<String, Object> body) {
        putIf(body, "enabled", K_ENABLED, true);
        putIf(body, "url", K_URL, false);
        putIf(body, "baseDn", K_BASE, false);
        putIf(body, "userFilter", K_FILTER, false);
        putIf(body, "bindDn", K_BIND_DN, false);
        putIf(body, "bindPassword", K_BIND_PWD, false);
        putIf(body, "attrMail", K_ATTR_MAIL, false);
        putIf(body, "attrName", K_ATTR_NAME, false);
        return config();
    }

    /**
     * 校验账号密码。
     * @return { username, displayName, email }（displayName/email 可能为空串）
     * @throws IllegalArgumentException 认证失败（前端直接展示该文案）
     */
    public Map<String, String> authenticate(String username, String password) {
        if (!enabled()) throw new IllegalArgumentException("未启用域账号登录");
        String user = username == null ? "" : username.trim();
        if (user.isEmpty() || password == null || password.isEmpty()) {
            // 密码为空时会变成匿名 bind，必须在这里拦住
            throw new IllegalArgumentException("请输入域账号和密码");
        }

        String url = get(K_URL).trim();
        String baseDn = nvl(get(K_BASE)).trim();
        String filterTpl = nvl(get(K_FILTER)).trim().isEmpty() ? "(sAMAccountName={0})" : get(K_FILTER).trim();
        if (baseDn.isEmpty()) throw new IllegalArgumentException("LDAP 配置不完整：缺少 Base DN");

        DirContext svc = null;
        try {
            svc = bind(url, nvl(get(K_BIND_DN)).trim(), nvl(get(K_BIND_PWD)));
            String filter = filterTpl.replace("{0}", escapeFilter(user));
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.SUBTREE_SCOPE);
            sc.setCountLimit(2);
            sc.setTimeLimit(5000);
            String attrMail = nvl(get(K_ATTR_MAIL)).trim().isEmpty() ? "mail" : get(K_ATTR_MAIL).trim();
            String attrName = nvl(get(K_ATTR_NAME)).trim().isEmpty() ? "displayName" : get(K_ATTR_NAME).trim();
            sc.setReturningAttributes(new String[]{attrMail, attrName, "cn", "sAMAccountName"});

            String dn = null;
            String mail = "";
            String display = "";
            NamingEnumeration<SearchResult> rs = null;
            try {
                rs = svc.search(baseDn, filter, sc);
                while (rs.hasMore()) {
                    SearchResult r = rs.next();
                    dn = r.getNameInNamespace();
                    Attributes a = r.getAttributes();
                    mail = firstAttr(a, attrMail);
                    if (mail.isEmpty()) mail = firstAttr(a, "mail");
                    display = firstAttr(a, attrName);
                    if (display.isEmpty()) display = firstAttr(a, "cn");
                    if (display.isEmpty()) display = firstAttr(a, "sAMAccountName");
                    break;   // 同名多条时只取第一条，避免歧义
                }
            } finally {
                if (rs != null) { try { rs.close(); } catch (Exception ignore) { } }
            }
            if (dn == null || dn.isBlank()) throw new IllegalArgumentException("域账号或密码错误");

            // 关键一步：用用户 DN + 用户密码 bind，成功才算认证通过
            DirContext check = null;
            try {
                Hashtable<String, String> env = env(url, dn, password);
                check = new InitialDirContext(env);
            } catch (javax.naming.AuthenticationException e) {
                throw new IllegalArgumentException("域账号或密码错误");
            } finally {
                close(check);
            }

            Map<String, String> ret = new LinkedHashMap<>();
            ret.put("username", user);
            ret.put("displayName", display);
            ret.put("email", mail);
            return ret;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (javax.naming.CommunicationException e) {
            log.warn("LDAP 连接失败：{}", e.getMessage());
            throw new IllegalArgumentException("无法连接域控服务器，请联系管理员");
        } catch (Exception e) {
            log.warn("LDAP 认证异常：{}", e.getMessage());
            throw new IllegalArgumentException("域账号登录失败，请稍后再试");
        } finally {
            close(svc);
        }
    }

    /** 管理端「测试连接」：用服务账号 bind 一次，返回可见的 Base DN 条数估计 */
    public Map<String, Object> test() {
        Map<String, Object> ret = new LinkedHashMap<>();
        if (!enabled()) {
            ret.put("ok", false);
            ret.put("message", "请先填写地址并启用");
            return ret;
        }
        DirContext ctx = null;
        try {
            ctx = bind(get(K_URL).trim(), nvl(get(K_BIND_DN)).trim(), nvl(get(K_BIND_PWD)));
            SearchControls sc = new SearchControls();
            sc.setSearchScope(SearchControls.ONELEVEL_SCOPE);
            sc.setCountLimit(1);
            sc.setTimeLimit(5000);
            NamingEnumeration<SearchResult> rs = null;
            try {
                rs = ctx.search(nvl(get(K_BASE)).trim(), "(objectClass=*)", sc);
                boolean any = rs.hasMore();
                ret.put("ok", true);
                ret.put("message", any ? "连接成功，Base DN 可读" : "连接成功（Base DN 下没有可见条目，请检查权限）");
            } finally {
                if (rs != null) { try { rs.close(); } catch (Exception ignore) { } }
            }
        } catch (Exception e) {
            ret.put("ok", false);
            ret.put("message", "连接失败：" + e.getMessage());
        } finally {
            close(ctx);
        }
        return ret;
    }

    private DirContext bind(String url, String dn, String pwd) throws Exception {
        return new InitialDirContext(env(url, dn, pwd));
    }

    private Hashtable<String, String> env(String url, String dn, String pwd) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL, url);
        env.put("com.sun.jndi.ldap.connect.timeout", "5000");
        env.put("com.sun.jndi.ldap.read.timeout", "5000");
        env.put(Context.REFERRAL, "ignore");
        // 只做简单 bind，不协商 SASL/StartTLS：内网 389 明文是常见部署，需要加密时把 url 改成 ldaps://
        if (dn != null && !dn.isEmpty()) {
            env.put(Context.SECURITY_AUTHENTICATION, "simple");
            env.put(Context.SECURITY_PRINCIPAL, dn);
            env.put(Context.SECURITY_CREDENTIALS, pwd);
        }
        return env;
    }

    /** RFC4515：过滤值里的特殊字符必须转义，否则可注入 (&(...)) 绕过 */
    private String escapeFilter(String v) {
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\u0000' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private String firstAttr(Attributes a, String name) {
        try {
            Attribute at = a.get(name);
            if (at == null || at.size() == 0) return "";
            Object v = at.get();
            return v == null ? "" : String.valueOf(v).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private void close(DirContext ctx) {
        if (ctx != null) {
            try { ctx.close(); } catch (Exception ignore) { }
        }
    }

    private void putIf(Map<String, Object> body, String key, String settingKey, boolean bool) {
        Object v = body.get(key);
        if (v == null) return;
        String sv = bool ? (Boolean.parseBoolean(String.valueOf(v)) ? "true" : "false") : String.valueOf(v).trim();
        if (!bool && sv.isEmpty()) return;   // 字符串空值 = 不改
        put(settingKey, sv);
    }

    private String get(String key) {
        return repo.findByKey(key).map(SysSetting::getValue).orElse(null);
    }

    private void put(String key, String value) {
        SysSetting s = repo.findByKey(key).orElseGet(() -> {
            SysSetting n = new SysSetting();
            n.setKey(key);
            return n;
        });
        s.setValue(value);
        repo.save(s);
    }

    private boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private String nvl(String v) {
        return v == null ? "" : v;
    }
}
