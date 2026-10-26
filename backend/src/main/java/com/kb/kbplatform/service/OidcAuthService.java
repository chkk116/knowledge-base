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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * OIDC / OAuth2 授权码登录（JDK HttpClient 实现，零新依赖）。
 *
 * 兼容标准 OIDC 提供方（Keycloak / 企业微信 OAuth / 钉钉 / 飞书 / Azure AD）：
 *   授权页 → 回调带 code → 换 token → 取 userinfo → 用 preferred_username 建会话。
 *
 * 配置（SysSetting，改完即生效）：
 *   sso.oidc.enabled / authUrl / tokenUrl / userinfoUrl / clientId / clientSecret
 *   sso.oidc.scopes（默认 "openid profile email"） / redirectUri
 *   sso.oidc.userField（默认 preferred_username，企业微信可设 userid，钉钉设 nick）
 *   sso.oidc.buttonLabel（登录页按钮文案，如「企业微信登录」）
 *
 * state 校验：进程内保存 10 分钟，一次性消费，防 CSRF。多实例部署需换成共享存储（Redis）。
 */
@Service
public class OidcAuthService {

    private static final Logger log = LoggerFactory.getLogger(OidcAuthService.class);

    private static final String K_ENABLED = "sso.oidc.enabled";
    private static final String K_AUTH_URL = "sso.oidc.authUrl";
    private static final String K_TOKEN_URL = "sso.oidc.tokenUrl";
    private static final String K_USERINFO = "sso.oidc.userinfoUrl";
    private static final String K_CLIENT_ID = "sso.oidc.clientId";
    private static final String K_CLIENT_SECRET = "sso.oidc.clientSecret";
    private static final String K_SCOPES = "sso.oidc.scopes";
    private static final String K_REDIRECT = "sso.oidc.redirectUri";
    private static final String K_USER_FIELD = "sso.oidc.userField";
    private static final String K_LABEL = "sso.oidc.buttonLabel";

    private final SysSettingRepository repo;
    private final KvStore kv;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    public OidcAuthService(SysSettingRepository repo, KvStore kv) {
        this.repo = repo;
        this.kv = kv;
    }

    public boolean enabled() {
        return "true".equals(get(K_ENABLED)) && notBlank(get(K_AUTH_URL))
                && notBlank(get(K_TOKEN_URL)) && notBlank(get(K_CLIENT_ID));
    }

    public Map<String, Object> config() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("enabled", "true".equals(get(K_ENABLED)));
        ret.put("authUrl", nvl(get(K_AUTH_URL)));
        ret.put("tokenUrl", nvl(get(K_TOKEN_URL)));
        ret.put("userinfoUrl", nvl(get(K_USERINFO)));
        ret.put("clientId", nvl(get(K_CLIENT_ID)));
        ret.put("hasClientSecret", notBlank(get(K_CLIENT_SECRET)));
        ret.put("scopes", nvl(get(K_SCOPES)).isEmpty() ? "openid profile email" : get(K_SCOPES));
        ret.put("redirectUri", nvl(get(K_REDIRECT)));
        ret.put("userField", nvl(get(K_USER_FIELD)).isEmpty() ? "preferred_username" : get(K_USER_FIELD));
        ret.put("buttonLabel", nvl(get(K_LABEL)).isEmpty() ? "企业单点登录" : get(K_LABEL));
        return ret;
    }

    public Map<String, Object> save(Map<String, Object> body) {
        putIf(body, "enabled", K_ENABLED, true);
        putIf(body, "authUrl", K_AUTH_URL, false);
        putIf(body, "tokenUrl", K_TOKEN_URL, false);
        putIf(body, "userinfoUrl", K_USERINFO, false);
        putIf(body, "clientId", K_CLIENT_ID, false);
        putIf(body, "clientSecret", K_CLIENT_SECRET, false);
        putIf(body, "scopes", K_SCOPES, false);
        putIf(body, "redirectUri", K_REDIRECT, false);
        putIf(body, "userField", K_USER_FIELD, false);
        putIf(body, "buttonLabel", K_LABEL, false);
        return config();
    }

    /** 构造授权页地址，并把 state 记入待校验表 */
    public String authorizeUrl(String loginBase) {
        if (!enabled()) throw new IllegalArgumentException("未启用单点登录");
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirect = redirectUri(loginBase);
        putState(state, String.valueOf(System.currentTimeMillis() + 10 * 60 * 1000L), redirect);
        StringBuilder sb = new StringBuilder(get(K_AUTH_URL).trim());
        sb.append(get(K_AUTH_URL).contains("?") ? "&" : "?");
        sb.append("response_type=code");
        sb.append("&client_id=").append(enc(get(K_CLIENT_ID)));
        sb.append("&redirect_uri=").append(enc(redirect));
        sb.append("&scope=").append(enc(scopes()));
        sb.append("&state=").append(enc(state));
        return sb.toString();
    }

    /** 回调：校验 state → 换 token → 取 userinfo */
    public Map<String, String> callback(String code, String state, String loginBase) {
        if (!enabled()) throw new IllegalArgumentException("未启用单点登录");
        String[] st = state == null ? null : takeState(state);
        if (st == null || Long.parseLong(st[0]) < System.currentTimeMillis()) {
            throw new IllegalArgumentException("登录状态已失效，请重新发起登录");
        }
        // 用发起时存下的 redirect_uri 换 token：拿回调方传入的 origin 现算 = 可被换成攻击者域名
        String redirect = st[1];
        if (code == null || code.isBlank()) throw new IllegalArgumentException("未取得授权码");
        try {
            String body = "grant_type=authorization_code"
                    + "&code=" + enc(code)
                    + "&client_id=" + enc(get(K_CLIENT_ID))
                    + "&redirect_uri=" + enc(redirect);
            String secret = get(K_CLIENT_SECRET);
            if (notBlank(secret)) body += "&client_secret=" + enc(secret);

            HttpRequest req = HttpRequest.newBuilder(URI.create(get(K_TOKEN_URL).trim()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> tr = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (tr.statusCode() >= 300) throw new IllegalStateException("换 token 失败：HTTP " + tr.statusCode());
            Map<?, ?> tok = parse(tr.body());
            String access = str(tok.get("access_token"));
            if (access == null || access.isBlank()) throw new IllegalStateException("token 响应里没有 access_token");

            String info = get(K_USERINFO);
            if (!notBlank(info)) throw new IllegalStateException("未配置 userinfo 地址");
            HttpRequest ureq = HttpRequest.newBuilder(URI.create(info.trim()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + access)
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> ur = http.send(ureq, HttpResponse.BodyHandlers.ofString());
            if (ur.statusCode() >= 300) throw new IllegalStateException("取用户信息失败：HTTP " + ur.statusCode());
            Map<?, ?> u = parse(ur.body());

            String field = nvl(get(K_USER_FIELD)).trim().isEmpty() ? "preferred_username" : get(K_USER_FIELD).trim();
            String username = firstNonBlank(str(u.get(field)), str(u.get("preferred_username")),
                    str(u.get("userid")), str(u.get("email")), str(u.get("sub")));
            if (username == null || username.isBlank()) throw new IllegalStateException("身份源未返回用户标识");
            Map<String, String> ret = new LinkedHashMap<>();
            ret.put("username", username);
            ret.put("displayName", firstNonBlank(str(u.get("name")), str(u.get("nick")), str(u.get("display_name")), ""));
            ret.put("email", firstNonBlank(str(u.get("email")), ""));
            return ret;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("OIDC 回调失败：{}", e.getMessage());
            throw new IllegalArgumentException("单点登录失败：" + e.getMessage());
        }
    }

    /** 回调地址：优先配置值；未配置时由调用方按当前域名拼 */
    public String redirectUri(String loginBase) {
        String cfg = nvl(get(K_REDIRECT)).trim();
        if (!cfg.isEmpty()) return cfg;
        String base = loginBase == null ? "" : loginBase.replaceAll("/+$", "");
        return base + "/api/auth/sso/oidc/callback";
    }

    public String buttonLabel() {
        return nvl(get(K_LABEL)).trim().isEmpty() ? "企业单点登录" : get(K_LABEL).trim();
    }

    private String scopes() {
        String s = nvl(get(K_SCOPES)).trim();
        return s.isEmpty() ? "openid profile email" : s;
    }

    /** 用 Jackson 3（项目统一）解析，避免为两种 JSON 库写两套 */
    private Map<?, ?> parse(String json) {
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON");
        }
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }


    private String enc(String v) {
        return URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8);
    }

    private void putIf(Map<String, Object> body, String key, String settingKey, boolean bool) {
        Object v = body.get(key);
        if (v == null) return;
        String sv = bool ? (Boolean.parseBoolean(String.valueOf(v)) ? "true" : "false") : String.valueOf(v).trim();
        if (!bool && sv.isEmpty()) return;
        put(settingKey, sv);
    }

    /** state 前缀：同一个 Redis 里三家/多家登录互不干扰 */
    private static final String STATE_PREFIX = "sso:state:oidc:";

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
