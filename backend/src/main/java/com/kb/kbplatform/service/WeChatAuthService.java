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
 * 微信扫码登录（网站应用 · 开放平台 snsapi_login）。
 *
 * 流程（与微信官方文档一致，JDK HttpClient 实现，零新依赖）：
 *   1. 本站生成一次性 state，把二维码指向
 *      https://open.weixin.qq.com/connect/qrconnect?appid=..&redirect_uri=..&scope=snsapi_login&state=..#wechat_redirect
 *   2. 手机微信扫码授权 → 微信 302 回 redirect_uri?code=..&state=..
 *   3. 用 code 换 access_token（同时拿到 openid / unionid）
 *   4. 用 access_token + openid 取昵称头像
 *   5. 用 unionid（优先）或 openid 去 kb_user_identity 找本站用户 → 签发会话
 *
 * 配置（SysSetting，改完即生效，不需要重启）：
 *   sso.wechat.enabled / appId / appSecret / redirectUri / buttonLabel / autoRegister
 *
 * 前置条件（微信侧）：
 *   开放平台「网站应用」审核通过，且应用域名与本站一致（备案已满足）。
 *
 * state 校验：进程内保存 10 分钟、一次性消费，防 CSRF；多实例部署需换共享存储。
 * bindUserId 也挂在 state 上：绑定流程（已登录用户点「绑定微信」）靠它区分，
 * **不能**从回调参数里读，否则可被伪造成给任意账号绑定。
 */
@Service
public class WeChatAuthService {

    private static final Logger log = LoggerFactory.getLogger(WeChatAuthService.class);

    private static final String K_ENABLED = "sso.wechat.enabled";
    private static final String K_APP_ID = "sso.wechat.appId";
    private static final String K_APP_SECRET = "sso.wechat.appSecret";
    private static final String K_REDIRECT = "sso.wechat.redirectUri";
    private static final String K_LABEL = "sso.wechat.buttonLabel";
    private static final String K_AUTO_REG = "sso.wechat.autoRegister";

    private static final String AUTHORIZE = "https://open.weixin.qq.com/connect/qrconnect";
    private static final String TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final String USERINFO_URL = "https://api.weixin.qq.com/sns/userinfo";

    private final SysSettingRepository repo;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /** state → {过期毫秒, 发起时的 redirect_uri, bindUserId 或空串} */
    private final Map<String, String[]> states = new ConcurrentHashMap<>();

    public WeChatAuthService(SysSettingRepository repo) {
        this.repo = repo;
    }

    /** 扫码回来的人是谁 + 这次是不是绑定流程 */
    public record WeChatUser(String openId, String unionId, String nickname, String avatar, Long bindUserId) {}

    public boolean enabled() {
        return "true".equals(get(K_ENABLED)) && notBlank(get(K_APP_ID)) && notBlank(get(K_APP_SECRET));
    }

    public String buttonLabel() {
        String v = nvl(get(K_LABEL)).trim();
        return v.isEmpty() ? "微信扫码登录" : v;
    }

    public boolean autoRegister() {
        return "true".equals(get(K_AUTO_REG));
    }

    public Map<String, Object> config() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("enabled", "true".equals(get(K_ENABLED)));
        ret.put("appId", nvl(get(K_APP_ID)));
        ret.put("hasAppSecret", notBlank(get(K_APP_SECRET)));
        ret.put("redirectUri", nvl(get(K_REDIRECT)));
        ret.put("buttonLabel", buttonLabel());
        ret.put("autoRegister", autoRegister());
        return ret;
    }

    public Map<String, Object> save(Map<String, Object> body) {
        putIf(body, "enabled", K_ENABLED, true);
        putIf(body, "appId", K_APP_ID, false);
        // appSecret 空值 = 不改（与 LDAP/OIDC 的约定一致，避免前端回显密钥）
        putIf(body, "appSecret", K_APP_SECRET, false);
        putIf(body, "redirectUri", K_REDIRECT, false);
        putIf(body, "buttonLabel", K_LABEL, false);
        putIf(body, "autoRegister", K_AUTO_REG, true);
        return config();
    }

    /**
     * 构造二维码地址，并把 state 记入待校验表。
     *
     * @param bindUserId 非空表示「已登录用户在绑定微信」，回调时写绑定而不是直接登录
     */
    public String authorizeUrl(String loginBase, Long bindUserId) {
        if (!enabled()) throw new IllegalArgumentException("未启用微信登录");
        String state = UUID.randomUUID().toString().replace("-", "");
        String redirect = redirectUri(loginBase);
        states.put(state, new String[]{
                String.valueOf(System.currentTimeMillis() + 10 * 60 * 1000L),
                redirect,
                bindUserId == null ? "" : String.valueOf(bindUserId)
        });
        sweep();
        return AUTHORIZE
                + "?appid=" + enc(get(K_APP_ID))
                + "&redirect_uri=" + enc(redirect)
                + "&response_type=code"
                + "&scope=snsapi_login"
                + "&state=" + enc(state)
                // 微信要求这个锚点必须带，否则会停在授权页不跳转
                + "#wechat_redirect";
    }

    /** 回调：校验 state → code 换 token → 取用户信息 */
    public WeChatUser callback(String code, String state) {
        if (!enabled()) throw new IllegalArgumentException("未启用微信登录");
        String[] st = state == null ? null : states.remove(state);
        if (st == null || Long.parseLong(st[0]) < System.currentTimeMillis()) {
            throw new IllegalArgumentException("登录状态已失效，请重新扫码");
        }
        if (code == null || code.isBlank()) throw new IllegalArgumentException("未取得授权码（可能取消了授权）");

        try {
            String tokenUrl = TOKEN_URL
                    + "?appid=" + enc(get(K_APP_ID))
                    + "&secret=" + enc(get(K_APP_SECRET))
                    + "&code=" + enc(code)
                    + "&grant_type=authorization_code";
            Map<String, Object> tok = getJson(tokenUrl, "换 access_token");
            String access = str(tok.get("access_token"));
            String openId = str(tok.get("openid"));
            if (access == null || access.isBlank() || openId == null || openId.isBlank()) {
                throw new IllegalStateException("微信未返回 access_token / openid");
            }
            String unionId = str(tok.get("unionid"));

            String infoUrl = USERINFO_URL
                    + "?access_token=" + enc(access)
                    + "&openid=" + enc(openId)
                    + "&lang=zh_CN";
            Map<String, Object> u = getJson(infoUrl, "取用户信息");
            // userinfo 里的 unionid 更权威（换 token 那步有时不带）
            String uUnion = str(u.get("unionid"));
            if (uUnion != null && !uUnion.isBlank()) unionId = uUnion;

            Long bindUserId = st[2] == null || st[2].isBlank() ? null : Long.valueOf(st[2]);
            return new WeChatUser(
                    openId,
                    unionId,
                    firstNonBlank(str(u.get("nickname")), ""),
                    firstNonBlank(str(u.get("headimgurl")), ""),
                    bindUserId
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("微信扫码登录失败：{}", e.getMessage());
            throw new IllegalArgumentException("微信登录失败：" + e.getMessage());
        }
    }

    /** 微信接口会把错误放在 errcode/errmsg 里且 HTTP 仍是 200，必须显式判 */
    private Map<String, Object> getJson(String url, String what) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) throw new IllegalStateException(what + "失败：HTTP " + r.statusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> m = parse(r.body());
        Object errcode = m.get("errcode");
        if (errcode != null && !"0".equals(String.valueOf(errcode))) {
            throw new IllegalStateException(what + "失败：" + m.get("errmsg") + "（errcode=" + errcode + "）");
        }
        return m;
    }

    /** 回调地址：优先配置值；未配置时按当前域名拼（必须与开放平台登记的一致） */
    public String redirectUri(String loginBase) {
        String cfg = nvl(get(K_REDIRECT)).trim();
        if (!cfg.isEmpty()) return cfg;
        String base = loginBase == null ? "" : loginBase.replaceAll("/+$", "");
        return base + "/api/auth/sso/wechat/callback";
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
            // 空字符串表示「不修改」，只有显式清空才需要写入 —— 与 LDAP/OIDC 保持一致
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
