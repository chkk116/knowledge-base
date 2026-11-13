/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.repository.SysSettingRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * WPS 365 账号登录（开放平台 OAuth2 授权码）。
 *
 * 与微信 / QQ / GitHub / Gitee 的差别：WPS 不是标准 OIDC，不返回 id_token，
 * 用户标识要再调一次用户信息接口才拿得到。因此授权页 / 换 token / 用户信息
 * 三个地址都做成可配置——开放平台控制台给的地址以实际文档为准，换版本不用改代码。
 *
 * 同时兼容两种响应形态：
 *   1. 标准 OAuth2：{"access_token":"...","expires_in":7200}
 *   2. WPS 老版接口：{"result":0,"token":{"access_token":"...","openid":"..."}}
 * access_token 两小时过期，这里只用来取一次身份，不落库也不做刷新。
 *
 * 配置（SysSetting，改完即生效）：sso.wps.enabled / clientId / clientSecret /
 * authorizeUrl / tokenUrl / userinfoUrl / scope / redirectUri / buttonLabel / autoRegister
 */
@Service
public class WpsAuthService {

    private static final Logger log = LoggerFactory.getLogger(WpsAuthService.class);

    private static final String K_ENABLED = "sso.wps.enabled";
    private static final String K_CLIENT_ID = "sso.wps.clientId";
    private static final String K_CLIENT_SECRET = "sso.wps.clientSecret";
    private static final String K_AUTHORIZE = "sso.wps.authorizeUrl";
    private static final String K_TOKEN = "sso.wps.tokenUrl";
    private static final String K_USERINFO = "sso.wps.userinfoUrl";
    private static final String K_SCOPE = "sso.wps.scope";
    private static final String K_REDIRECT = "sso.wps.redirectUri";
    private static final String K_LABEL = "sso.wps.buttonLabel";
    private static final String K_AUTO_REG = "sso.wps.autoRegister";

    /** 默认取开放平台的通用端点；控制台给的地址不一致时在管理端改。 */
    private static final String DEF_AUTHORIZE = "https://openapi.wps.cn/oauth2/authorize";
    private static final String DEF_TOKEN = "https://openapi.wps.cn/oauth2/token";
    private static final String DEF_USERINFO = "https://openapi.wps.cn/oauth2/userinfo";
    private static final String DEF_SCOPE = "openid profile";

    /** state 前缀：多家登录共用一个 KV，互不干扰 */
    private static final String STATE_PREFIX = "sso:state:wps:";

    private final SysSettingRepository repo;
    private final KvStore kv;

    public WpsAuthService(SysSettingRepository repo, KvStore kv) {
        this.repo = repo;
        this.kv = kv;
    }

    public record WpsUser(String externalId, String login, String nickname, String avatar, String email, Long bindUserId) {}

    public boolean enabled() {
        return "true".equals(get(K_ENABLED)) && notBlank(get(K_CLIENT_ID)) && notBlank(get(K_CLIENT_SECRET));
    }

    public String buttonLabel() {
        String v = nvl(get(K_LABEL)).trim();
        return v.isEmpty() ? "WPS 登录" : v;
    }

    public boolean autoRegister() {
        return "true".equals(get(K_AUTO_REG));
    }

    public Map<String, Object> config() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("enabled", "true".equals(get(K_ENABLED)));
        ret.put("clientId", nvl(get(K_CLIENT_ID)));
        ret.put("hasClientSecret", notBlank(get(K_CLIENT_SECRET)));
        ret.put("authorizeUrl", url(K_AUTHORIZE, DEF_AUTHORIZE));
        ret.put("tokenUrl", url(K_TOKEN, DEF_TOKEN));
        ret.put("userinfoUrl", url(K_USERINFO, DEF_USERINFO));
        ret.put("scope", scope());
        ret.put("redirectUri", redirectUri(null));
        ret.put("buttonLabel", buttonLabel());
        ret.put("autoRegister", autoRegister());
        return ret;
    }

    public Map<String, Object> save(Map<String, Object> body) {
        putIf(body, "enabled", K_ENABLED, true);
        putIf(body, "clientId", K_CLIENT_ID, false);
        putIf(body, "clientSecret", K_CLIENT_SECRET, false);
        putIf(body, "authorizeUrl", K_AUTHORIZE, false);
        putIf(body, "tokenUrl", K_TOKEN, false);
        putIf(body, "userinfoUrl", K_USERINFO, false);
        putIf(body, "scope", K_SCOPE, false);
        putIf(body, "redirectUri", K_REDIRECT, false);
        putIf(body, "buttonLabel", K_LABEL, false);
        putIf(body, "autoRegister", K_AUTO_REG, true);
        return config();
    }

    public String authorizeUrl(String loginBase, Long bindUserId) {
        if (!enabled()) throw new IllegalArgumentException("未启用 WPS 登录");
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirect = redirectUri(loginBase);
        kv.put(STATE_PREFIX + state,
                String.join("\n", String.valueOf(System.currentTimeMillis() + 10 * 60 * 1000L), redirect,
                        bindUserId == null ? "" : String.valueOf(bindUserId)),
                java.time.Duration.ofMinutes(10));
        return url(K_AUTHORIZE, DEF_AUTHORIZE)
                + "?response_type=code"
                + "&client_id=" + enc(get(K_CLIENT_ID))
                + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc(scope())
                + "&state=" + enc(state);
    }

    public WpsUser callback(String code, String state) {
        if (!enabled()) throw new IllegalArgumentException("未启用 WPS 登录");
        String[] st = takeState(state);
        if (st == null || Long.parseLong(st[0]) < System.currentTimeMillis()) {
            throw new IllegalArgumentException("登录状态已失效，请重新登录");
        }
        if (code == null || code.isBlank()) throw new IllegalArgumentException("未取得授权码（可能取消了授权）");

        try {
            String tokenUrl = url(K_TOKEN, DEF_TOKEN);
            String form = "grant_type=authorization_code"
                    + "&code=" + enc(code)
                    + "&client_id=" + enc(get(K_CLIENT_ID))
                    + "&client_secret=" + enc(get(K_CLIENT_SECRET))
                    + "&redirect_uri=" + enc(st[1]);
            HttpFetch.Resp tr = HttpFetch.postForm(tokenUrl, form, null);
            if (tr.status() >= 300) {
                // 老版接口是 GET + query（appid / appkey / code），部分应用还在用，回退试一次
                HttpFetch.Resp alt = HttpFetch.get(tokenUrl
                        + "?appid=" + enc(get(K_CLIENT_ID))
                        + "&appkey=" + enc(get(K_CLIENT_SECRET))
                        + "&code=" + enc(code), null);
                if (alt.status() < 300) tr = alt;
            }
            if (tr.status() >= 300) throw new IllegalStateException("换 access_token 失败：HTTP " + tr.status());

            Map<String, Object> tok = parse(tr.body());
            String access = str(tok.get("access_token"));
            String openIdInToken = str(tok.get("openid"));
            if (access == null || access.isBlank()) {
                // WPS 老版把结果包在 token 里
                if (tok.get("token") instanceof Map<?, ?> inner) {
                    access = str(inner.get("access_token"));
                    if (openIdInToken == null) openIdInToken = str(inner.get("openid"));
                }
            }
            if (access == null || access.isBlank()) {
                String err = str(tok.get("message"));
                throw new IllegalStateException("WPS 未返回 access_token" + (err == null ? "" : "：" + err));
            }

            HttpFetch.Resp ur = HttpFetch.get(url(K_USERINFO, DEF_USERINFO),
                    Map.of("Authorization", "Bearer " + access));
            if (ur.status() >= 300) throw new IllegalStateException("取用户信息失败：HTTP " + ur.status());
            Map<String, Object> body = parse(ur.body());
            // 有的形态包一层 data
            Map<String, Object> u = body;
            if (body.get("data") instanceof Map<?, ?> data) {
                u = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : data.entrySet()) u.put(String.valueOf(e.getKey()), e.getValue());
            }

            String id = firstNonBlank(
                    str(u.get("openid")), str(u.get("userid")), str(u.get("userId")),
                    str(u.get("id")), str(u.get("unionId")), openIdInToken);
            if (id == null || id.isBlank()) throw new IllegalStateException("WPS 未返回用户标识");

            String nick = firstNonBlank(str(u.get("name")), str(u.get("nickName")),
                    str(u.get("nickname")), "WPS 用户");
            String avatar = firstNonBlank(str(u.get("avatar")), str(u.get("avatarUrl")), "");
            String email = str(u.get("email"));
            Long bindUserId = st[2] == null || st[2].isBlank() ? null : Long.valueOf(st[2]);
            return new WpsUser(
                    "wps_" + id,
                    nvl(nick),
                    nvl(nick),
                    avatar,
                    email == null || email.isBlank() ? null : email.trim(),
                    bindUserId
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("WPS 登录失败：{}", e.getMessage());
            throw new IllegalArgumentException("WPS 登录失败：" + e.getMessage());
        }
    }

    public String redirectUri(String loginBase) {
        String cfg = nvl(get(K_REDIRECT)).trim();
        if (!cfg.isEmpty()) return cfg;
        String base = loginBase == null ? "" : loginBase.replaceAll("/+$", "");
        return base + "/api/auth/sso/wps/callback";
    }

    private String scope() {
        String v = nvl(get(K_SCOPE)).trim();
        return v.isEmpty() ? DEF_SCOPE : v;
    }

    /** 配置项留空时回退默认端点 */
    private String url(String key, String def) {
        String v = nvl(get(key)).trim();
        return v.isEmpty() ? def : v;
    }

    private String[] takeState(String state) {
        if (state == null) return null;
        String key = STATE_PREFIX + state;
        String v = kv.get(key);
        kv.delete(key);
        return v == null ? null : v.split("\n", -1);
    }

    private String get(String key) {
        return repo.findByKey(key).map(SysSetting::getValue).orElse("");
    }

    private void putIf(Map<String, Object> body, String field, String key, boolean bool) {
        if (!body.containsKey(field)) return;
        Object v = body.get(field);
        String s;
        if (bool) {
            s = "true".equals(String.valueOf(v)) ? "true" : "false";
        } else {
            s = v == null ? "" : String.valueOf(v);
            if (s.isBlank()) return;
        }
        SysSetting st = repo.findByKey(key).orElseGet(() -> {
            SysSetting n = new SysSetting();
            n.setKey(key);
            return n;
        });
        st.setValue(s);
        repo.save(st);
    }

    private static String enc(String v) {
        return java.net.URLEncoder.encode(nvl(v), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }

    private static String nvl(String v) {
        return v == null ? "" : v;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** 本项目用 Jackson 3（Spring Boot 4），包名是 tools.jackson.*，不是 com.fasterxml */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String body) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build().readValue(body, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON");
        }
    }
}
