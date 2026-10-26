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
 * QQ 登录（QQ 互联 · OAuth2）。
 *
 * 与微信同一套思路，但 QQ 的接口有三处坑，都已在代码里处理：
 *   1. **换 token 默认返回 `a=b&c=d` 的纯文本**，要加 `fmt=json` 才是 JSON；
 *   2. **拿 openid 的接口 `/oauth2.0/me` 默认返回 `callback({...});`**，同样加 `fmt=json`；
 *   3. 取用户信息的接口**错误也返回 HTTP 200**，要看响应体里的 `ret`（0 才是成功）。
 *
 * 流程：授权页 → 回调带 code → 换 access_token → 换 openid → 取昵称头像 → 映射本站账号。
 *
 * 配置（SysSetting，改完即生效）：sso.qq.enabled / appId / appKey / redirectUri / buttonLabel / autoRegister
 * 前置：QQ 互联（connect.qq.com）创建网站应用并审核通过，回调域名与本站一致（备案已满足）。
 */
@Service
public class QqAuthService {

    private static final Logger log = LoggerFactory.getLogger(QqAuthService.class);

    private static final String K_ENABLED = "sso.qq.enabled";
    private static final String K_APP_ID = "sso.qq.appId";
    private static final String K_APP_KEY = "sso.qq.appKey";
    private static final String K_REDIRECT = "sso.qq.redirectUri";
    private static final String K_LABEL = "sso.qq.buttonLabel";
    private static final String K_AUTO_REG = "sso.qq.autoRegister";

    private static final String AUTHORIZE = "https://graph.qq.com/oauth2.0/authorize";
    private static final String TOKEN_URL = "https://graph.qq.com/oauth2.0/token";
    private static final String ME_URL = "https://graph.qq.com/oauth2.0/me";
    private static final String USERINFO_URL = "https://graph.qq.com/user/get_user_info";

    private final SysSettingRepository repo;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** state → {过期毫秒, 发起时的 redirect_uri, bindUserId 或空串} */
    private final Map<String, String[]> states = new ConcurrentHashMap<>();

    public QqAuthService(SysSettingRepository repo) {
        this.repo = repo;
    }

    public record QqUser(String openId, String nickname, String avatar, Long bindUserId) {}

    public boolean enabled() {
        return "true".equals(get(K_ENABLED)) && notBlank(get(K_APP_ID)) && notBlank(get(K_APP_KEY));
    }

    public String buttonLabel() {
        String v = nvl(get(K_LABEL)).trim();
        return v.isEmpty() ? "QQ 登录" : v;
    }

    public boolean autoRegister() {
        return "true".equals(get(K_AUTO_REG));
    }

    public Map<String, Object> config() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("enabled", "true".equals(get(K_ENABLED)));
        ret.put("appId", nvl(get(K_APP_ID)));
        ret.put("hasAppKey", notBlank(get(K_APP_KEY)));
        ret.put("redirectUri", nvl(get(K_REDIRECT)));
        ret.put("buttonLabel", buttonLabel());
        ret.put("autoRegister", autoRegister());
        return ret;
    }

    public Map<String, Object> save(Map<String, Object> body) {
        putIf(body, "enabled", K_ENABLED, true);
        putIf(body, "appId", K_APP_ID, false);
        putIf(body, "appKey", K_APP_KEY, false);
        putIf(body, "redirectUri", K_REDIRECT, false);
        putIf(body, "buttonLabel", K_LABEL, false);
        putIf(body, "autoRegister", K_AUTO_REG, true);
        return config();
    }

    public String authorizeUrl(String loginBase, Long bindUserId) {
        if (!enabled()) throw new IllegalArgumentException("未启用 QQ 登录");
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirect = redirectUri(loginBase);
        states.put(state, new String[]{
                String.valueOf(System.currentTimeMillis() + 10 * 60 * 1000L),
                redirect,
                bindUserId == null ? "" : String.valueOf(bindUserId)
        });
        sweep();
        return AUTHORIZE
                + "?response_type=code"
                + "&client_id=" + enc(get(K_APP_ID))
                + "&redirect_uri=" + enc(redirect)
                + "&state=" + enc(state)
                + "&scope=get_user_info";
    }

    public QqUser callback(String code, String state) {
        if (!enabled()) throw new IllegalArgumentException("未启用 QQ 登录");
        String[] st = state == null ? null : states.remove(state);
        if (st == null || Long.parseLong(st[0]) < System.currentTimeMillis()) {
            throw new IllegalArgumentException("登录状态已失效，请重新登录");
        }
        if (code == null || code.isBlank()) throw new IllegalArgumentException("未取得授权码（可能取消了授权）");

        try {
            // 1) code → access_token（fmt=json 必须加，否则是 a=b&c=d 文本）
            String tokenUrl = TOKEN_URL
                    + "?grant_type=authorization_code"
                    + "&client_id=" + enc(get(K_APP_ID))
                    + "&client_secret=" + enc(get(K_APP_KEY))
                    + "&code=" + enc(code)
                    + "&redirect_uri=" + enc(st[1])
                    + "&fmt=json";
            Map<String, Object> tok = getJson(tokenUrl, "换 access_token");
            String access = str(tok.get("access_token"));
            if (access == null || access.isBlank()) throw new IllegalStateException("QQ 未返回 access_token");

            // 2) access_token → openid（同样是 fmt=json，否则是 callback({...});）
            Map<String, Object> me = getJson(ME_URL + "?access_token=" + enc(access) + "&fmt=json", "取 openid");
            String openId = str(me.get("openid"));
            if (openId == null || openId.isBlank()) throw new IllegalStateException("QQ 未返回 openid");

            // 3) openid → 昵称头像
            String infoUrl = USERINFO_URL
                    + "?access_token=" + enc(access)
                    + "&oauth_consumer_key=" + enc(get(K_APP_ID))
                    + "&openid=" + enc(openId);
            Map<String, Object> u = getJson(infoUrl, "取用户信息");
            Object ret = u.get("ret");
            if (ret != null && !"0".equals(String.valueOf(ret))) {
                // ret != 0 常见于「未开通 get_user_info 权限」，此时仍可登录，只是没有昵称
                log.info("QQ 取用户信息返回 ret={} msg={}", ret, u.get("msg"));
            }

            Long bindUserId = st[2] == null || st[2].isBlank() ? null : Long.valueOf(st[2]);
            return new QqUser(
                    openId,
                    firstNonBlank(str(u.get("nickname")), ""),
                    firstNonBlank(str(u.get("figureurl_qq_2")), str(u.get("figureurl_qq_1")), ""),
                    bindUserId
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("QQ 登录失败：{}", e.getMessage());
            throw new IllegalArgumentException("QQ 登录失败：" + e.getMessage());
        }
    }

    private Map<String, Object> getJson(String url, String what) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) throw new IllegalStateException(what + "失败：HTTP " + r.statusCode());
        String body = r.body() == null ? "" : r.body().trim();
        // 保险：万一 fmt=json 没生效，把 callback({...}); 包裹剥掉
        if (body.startsWith("callback(")) {
            int s = body.indexOf('(');
            int e = body.lastIndexOf(')');
            if (s > 0 && e > s) body = body.substring(s + 1, e);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = parse(body);
        Object errcode = m.get("error");
        if (errcode != null && !"0".equals(String.valueOf(errcode))) {
            throw new IllegalStateException(what + "失败：" + m.get("error_description"));
        }
        return m;
    }

    public String redirectUri(String loginBase) {
        String cfg = nvl(get(K_REDIRECT)).trim();
        if (!cfg.isEmpty()) return cfg;
        String base = loginBase == null ? "" : loginBase.replaceAll("/+$", "");
        return base + "/api/auth/sso/qq/callback";
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> Long.parseLong(e.getValue()[0]) < now);
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
