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
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Gitee 登录（码云 OAuth2）。
 *
 * 门槛最低的一家：gitee.com/oauth/applications 建个应用就行，**个人账号可申请**，
 * 不要企业资质、不要认证费。站点的开源仓库就在 Gitee 上，用户重合度高。
 *
 * 流程与 GitHub 基本一致，差异：
 *   1. 换 token 是 POST 表单，但**参数走 query 或 form 都行**，这里统一放 form；
 *   2. 取用户信息可以直接把 access_token 放 query（`/api/v5/user?access_token=`），
 *      也支持 `Authorization: token xxx` —— 这里用 query，避免个别网关改写 Authorization；
 *   3. 用户标识用数字 `id`（login 可以改名），前缀 gitee_ 与其它身份源区分。
 *
 * 配置（SysSetting，改完即生效）：sso.gitee.enabled / clientId / clientSecret / redirectUri / buttonLabel / autoRegister
 */
@Service
public class GiteeAuthService {

    private static final Logger log = LoggerFactory.getLogger(GiteeAuthService.class);

    private static final String K_ENABLED = "sso.gitee.enabled";
    private static final String K_CLIENT_ID = "sso.gitee.clientId";
    private static final String K_CLIENT_SECRET = "sso.gitee.clientSecret";
    private static final String K_REDIRECT = "sso.gitee.redirectUri";
    private static final String K_LABEL = "sso.gitee.buttonLabel";
    private static final String K_AUTO_REG = "sso.gitee.autoRegister";

    private static final String AUTHORIZE = "https://gitee.com/oauth/authorize";
    private static final String TOKEN_URL = "https://gitee.com/oauth/token";
    private static final String USER_URL = "https://gitee.com/api/v5/user";

    private final SysSettingRepository repo;
    private final KvStore kv;

    public GiteeAuthService(SysSettingRepository repo, KvStore kv) {
        this.repo = repo;
        this.kv = kv;
    }

    /** state 前缀：多家登录共用一个 Redis，互不干扰 */
    private static final String STATE_PREFIX = "sso:state:gitee:";

    public record GiteeUser(String externalId, String login, String nickname, String avatar, String email, Long bindUserId) {}

    public boolean enabled() {
        return "true".equals(get(K_ENABLED)) && notBlank(get(K_CLIENT_ID)) && notBlank(get(K_CLIENT_SECRET));
    }

    public String buttonLabel() {
        String v = nvl(get(K_LABEL)).trim();
        return v.isEmpty() ? "Gitee 登录" : v;
    }

    public boolean autoRegister() {
        return "true".equals(get(K_AUTO_REG));
    }

    public Map<String, Object> config() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("enabled", "true".equals(get(K_ENABLED)));
        ret.put("clientId", nvl(get(K_CLIENT_ID)));
        ret.put("hasClientSecret", notBlank(get(K_CLIENT_SECRET)));
        ret.put("redirectUri", nvl(get(K_REDIRECT)));
        ret.put("buttonLabel", buttonLabel());
        ret.put("autoRegister", autoRegister());
        return ret;
    }

    public Map<String, Object> save(Map<String, Object> body) {
        putIf(body, "enabled", K_ENABLED, true);
        putIf(body, "clientId", K_CLIENT_ID, false);
        putIf(body, "clientSecret", K_CLIENT_SECRET, false);
        putIf(body, "redirectUri", K_REDIRECT, false);
        putIf(body, "buttonLabel", K_LABEL, false);
        putIf(body, "autoRegister", K_AUTO_REG, true);
        return config();
    }

    public String authorizeUrl(String loginBase, Long bindUserId) {
        if (!enabled()) throw new IllegalArgumentException("未启用 Gitee 登录");
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirect = redirectUri(loginBase);
        kv.put(STATE_PREFIX + state,
                String.join("\n", String.valueOf(System.currentTimeMillis() + 10 * 60 * 1000L), redirect,
                        bindUserId == null ? "" : String.valueOf(bindUserId)),
                java.time.Duration.ofMinutes(10));
        return AUTHORIZE
                + "?client_id=" + enc(get(K_CLIENT_ID))
                + "&redirect_uri=" + enc(redirect)
                + "&response_type=code"
                + "&scope=" + enc("user_info emails")
                + "&state=" + enc(state);
    }

    public GiteeUser callback(String code, String state) {
        if (!enabled()) throw new IllegalArgumentException("未启用 Gitee 登录");
        String[] st = takeState(state);
        if (st == null || Long.parseLong(st[0]) < System.currentTimeMillis()) {
            throw new IllegalArgumentException("登录状态已失效，请重新登录");
        }
        if (code == null || code.isBlank()) throw new IllegalArgumentException("未取得授权码（可能取消了授权）");

        try {
            String form = "grant_type=authorization_code"
                    + "&code=" + enc(code)
                    + "&client_id=" + enc(get(K_CLIENT_ID))
                    + "&client_secret=" + enc(get(K_CLIENT_SECRET))
                    + "&redirect_uri=" + enc(st[1]);
            HttpFetch.Resp tr = HttpFetch.postForm(TOKEN_URL, form, null);
            if (tr.status() >= 300) throw new IllegalStateException("换 access_token 失败：HTTP " + tr.status());
            Map<String, Object> tok = parse(tr.body());
            String access = str(tok.get("access_token"));
            if (access == null || access.isBlank()) {
                String err = str(tok.get("error_description"));
                throw new IllegalStateException("Gitee 未返回 access_token" + (err == null ? "" : "：" + err));
            }

            HttpFetch.Resp ur = HttpFetch.get(USER_URL + "?access_token=" + enc(access), null);
            if (ur.status() >= 300) throw new IllegalStateException("取用户信息失败：HTTP " + ur.status());
            Map<String, Object> u = parse(ur.body());

            String id = str(u.get("id"));
            String login = str(u.get("login"));
            if (id == null || id.isBlank()) throw new IllegalStateException("Gitee 未返回用户 id");
            Long bindUserId = st[2] == null || st[2].isBlank() ? null : Long.valueOf(st[2]);
            // user_info 只在用户公开邮箱时返回 email；没公开就用 emails 权限取第一个
            String email = str(u.get("email"));
            if (email == null || email.isBlank()) email = fetchFirstEmail(access);
            return new GiteeUser(
                    "gitee_" + id,
                    nvl(login),
                    firstNonBlank(str(u.get("name")), nvl(login)),
                    firstNonBlank(str(u.get("avatar_url")), ""),
                    email == null || email.isBlank() ? null : email.trim(),
                    bindUserId
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Gitee 登录失败：{}", e.getMessage());
            throw new IllegalArgumentException("Gitee 登录失败：" + e.getMessage());
        }
    }

    public String redirectUri(String loginBase) {
        String cfg = nvl(get(K_REDIRECT)).trim();
        if (!cfg.isEmpty()) return cfg;
        String base = loginBase == null ? "" : loginBase.replaceAll("/+$", "");
        return base + "/api/auth/sso/gitee/callback";
    }

    /** 取 Gitee 邮箱列表的第一个（需要 emails 权限；用户没公开邮箱时兜底） */
    private String fetchFirstEmail(String access) {
        try {
            HttpFetch.Resp r = HttpFetch.get("https://gitee.com/api/v5/emails?access_token=" + enc(access), null);
            if (r.status() >= 300) return null;
            java.util.List<?> list = tools.jackson.databind.json.JsonMapper.builder()
                    .build().readValue(r.body(), java.util.List.class);
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> e)) continue;
                Object addr = e.get("email");
                if (addr != null && !String.valueOf(addr).isBlank()) return String.valueOf(addr);
            }
            return null;
        } catch (Exception ex) {
            log.debug("取 Gitee 邮箱失败：{}", ex.getMessage());
            return null;
        }
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
    private static Map<String, Object> parse(String body) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build().readValue(body, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON");
        }
    }
}
