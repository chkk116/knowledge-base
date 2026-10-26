/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.repository.SysSettingRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * GitHub 登录（OAuth2 标准流程）。
 *
 * 三个第三方登录里 GitHub 门槛最低：不需要备案、不需要认证费，
 * 只要在 github.com/settings/developers 建一个 OAuth App 就能用。
 *
 * 坑点：
 *   1. 换 token 是 **POST** 表单（不是 GET），且必须带 `Accept: application/json`，
 *      否则返回 `access_token=xxx&scope=...` 的纯文本；
 *   2. 调 `api.github.com` **必须带 User-Agent**，否则直接 403；
 *   3. 用数字 `id` 作为外部标识，**不要用 login** —— 用户名可以改，改了就对不上账号了。
 *
 * 配置（SysSetting，改完即生效）：sso.github.enabled / clientId / clientSecret / redirectUri / buttonLabel / autoRegister
 */
@Service
public class GithubAuthService {

    private static final Logger log = LoggerFactory.getLogger(GithubAuthService.class);

    private static final String K_ENABLED = "sso.github.enabled";
    private static final String K_CLIENT_ID = "sso.github.clientId";
    private static final String K_CLIENT_SECRET = "sso.github.clientSecret";
    private static final String K_REDIRECT = "sso.github.redirectUri";
    private static final String K_LABEL = "sso.github.buttonLabel";
    private static final String K_AUTO_REG = "sso.github.autoRegister";

    private static final String AUTHORIZE = "https://github.com/login/oauth/authorize";
    private static final String TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String USER_URL = "https://api.github.com/user";
    private static final String UA = "kb-platform";

    private final SysSettingRepository repo;
    private final KvStore kv;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();


    public GithubAuthService(SysSettingRepository repo, KvStore kv) {
        this.repo = repo;
        this.kv = kv;
    }

    public record GithubUser(String externalId, String login, String nickname, String avatar, Long bindUserId) {}

    public boolean enabled() {
        return "true".equals(get(K_ENABLED)) && notBlank(get(K_CLIENT_ID)) && notBlank(get(K_CLIENT_SECRET));
    }

    public String buttonLabel() {
        String v = nvl(get(K_LABEL)).trim();
        return v.isEmpty() ? "GitHub 登录" : v;
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
        if (!enabled()) throw new IllegalArgumentException("未启用 GitHub 登录");
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirect = redirectUri(loginBase);
        putState(state, 
                String.valueOf(System.currentTimeMillis() + 10 * 60 * 1000L),
                redirect,
                bindUserId == null ? "" : String.valueOf(bindUserId)
        );
        return AUTHORIZE
                + "?client_id=" + enc(get(K_CLIENT_ID))
                + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc("read:user")
                + "&state=" + enc(state);
    }

    public GithubUser callback(String code, String state) {
        if (!enabled()) throw new IllegalArgumentException("未启用 GitHub 登录");
        String[] st = state == null ? null : takeState(state);
        if (st == null || Long.parseLong(st[0]) < System.currentTimeMillis()) {
            throw new IllegalArgumentException("登录状态已失效，请重新登录");
        }
        if (code == null || code.isBlank()) throw new IllegalArgumentException("未取得授权码（可能取消了授权）");

        try {
            // 1) code → access_token（POST 表单 + Accept: application/json）
            String form = "client_id=" + enc(get(K_CLIENT_ID))
                    + "&client_secret=" + enc(get(K_CLIENT_SECRET))
                    + "&code=" + enc(code)
                    + "&redirect_uri=" + enc(st[1]);
            HttpRequest treq = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("User-Agent", UA)
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> tr = http.send(treq, HttpResponse.BodyHandlers.ofString());
            if (tr.statusCode() >= 300) throw new IllegalStateException("换 access_token 失败：HTTP " + tr.statusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> tok = parse(tr.body());
            String access = str(tok.get("access_token"));
            if (access == null || access.isBlank()) {
                String err = str(tok.get("error_description"));
                throw new IllegalStateException("GitHub 未返回 access_token" + (err == null ? "" : "：" + err));
            }

            // 2) 取用户（必须带 User-Agent）
            HttpRequest ureq = HttpRequest.newBuilder(URI.create(USER_URL))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + access)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<String> ur = http.send(ureq, HttpResponse.BodyHandlers.ofString());
            if (ur.statusCode() >= 300) throw new IllegalStateException("取用户信息失败：HTTP " + ur.statusCode());
            @SuppressWarnings("unchecked")
            Map<String, Object> u = parse(ur.body());

            String id = str(u.get("id"));
            String login = str(u.get("login"));
            if (id == null || id.isBlank()) throw new IllegalStateException("GitHub 未返回用户 id");
            Long bindUserId = st[2] == null || st[2].isBlank() ? null : Long.valueOf(st[2]);
            return new GithubUser(
                    "gh_" + id,
                    nvl(login),
                    firstNonBlank(str(u.get("name")), nvl(login)),
                    firstNonBlank(str(u.get("avatar_url")), ""),
                    bindUserId
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("GitHub 登录失败：{}", e.getMessage());
            throw new IllegalArgumentException("GitHub 登录失败：" + e.getMessage());
        }
    }

    public String redirectUri(String loginBase) {
        String cfg = nvl(get(K_REDIRECT)).trim();
        if (!cfg.isEmpty()) return cfg;
        String base = loginBase == null ? "" : loginBase.replaceAll("/+$", "");
        return base + "/api/auth/sso/github/callback";
    }


    /** state 前缀：同一个 Redis 里三家/多家登录互不干扰 */
    private static final String STATE_PREFIX = "sso:state:github:";

    /**
     * 写 state（10 分钟过期，一次性消费）。
     * 放 Redis 而不是进程内 Map：多实例部署时 start 和 callback 可能落到不同实例，
     * 放内存会「登录状态已失效」；后端重启也不会把正在登录的用户打断。
     */
    private void putState(String state, String... parts) {
        kv.put(STATE_PREFIX + state, String.join("\n", parts), java.time.Duration.ofMinutes(10));
    }

    /** 取 state 并立即删除（一次性）；不存在返回 null */
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
        return URLEncoder.encode(nvl(v), StandardCharsets.UTF_8);
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

    /** 本项目用 Jackson 3（Spring Boot 4），包名是 tools.jackson.*，不是 com.fasterxml */
    private static Map<String, Object> parse(String body) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build().readValue(body, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON");
        }
    }

    private static String firstNonBlank(String... vs) {
        for (String v : vs) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
