/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.repository.SysSettingRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 通知外发（站内 SSE 之外的「出站」通道）：企业微信 / 钉钉群机器人 / 通用 JSON Webhook。
 *
 * 配置存 SysSetting（与站内其他设置一致，改完立即生效，无需重启）：
 *   notify.webhook.enabled  true/false
 *   notify.webhook.type     wecom | dingtalk | generic
 *   notify.webhook.url      机器人 Webhook 地址（含 access_token，属敏感信息，只在管理端回显脱敏值）
 *   notify.webhook.secret   钉钉加签密钥（可选）
 *
 * 只推送「需要人看」的通知：点赞这类高频低价值事件不出去（见 shouldPush）。
 * 发送一律异步、失败只记日志——外发通道挂了不能影响站内通知与主流程。
 */
@Service
public class WebhookNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebhookNotifier.class);

    private static final String K_ENABLED = "notify.webhook.enabled";
    private static final String K_TYPE = "notify.webhook.type";
    private static final String K_URL = "notify.webhook.url";
    private static final String K_SECRET = "notify.webhook.secret";

    private final SysSettingRepository repo;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WebhookNotifier(SysSettingRepository repo) {
        this.repo = repo;
    }

    /** 管理端回显：URL 与密钥一律脱敏，只给 hasXxx 标记 */
    public Map<String, Object> config() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("enabled", "true".equals(get(K_ENABLED)));
        ret.put("type", get(K_TYPE) == null || get(K_TYPE).isBlank() ? "wecom" : get(K_TYPE));
        String url = get(K_URL);
        ret.put("urlMasked", maskUrl(url));
        ret.put("hasUrl", url != null && !url.isBlank());
        ret.put("hasSecret", get(K_SECRET) != null && !get(K_SECRET).isBlank());
        return ret;
    }

    /** 保存配置：url/secret 传空串表示「不改原值」，与 AI 模型管理的约定一致 */
    public Map<String, Object> save(Map<String, Object> body) {
        Object enabled = body.get("enabled");
        if (enabled != null) put(K_ENABLED, Boolean.parseBoolean(String.valueOf(enabled)) ? "true" : "false");
        Object type = body.get("type");
        if (type != null && !String.valueOf(type).isBlank()) put(K_TYPE, String.valueOf(type).trim());
        Object url = body.get("url");
        if (url != null && !String.valueOf(url).isBlank()) {
            String u = String.valueOf(url).trim();
            if (!u.startsWith("https://") && !u.startsWith("http://"))
                throw new IllegalArgumentException("Webhook 地址必须以 http:// 或 https:// 开头");
            put(K_URL, u);
        }
        Object secret = body.get("secret");
        if (secret != null && !String.valueOf(secret).isBlank()) put(K_SECRET, String.valueOf(secret).trim());
        return config();
    }

    /** 管理端「发送测试消息」：同步执行并把结果回给用户，便于当场排错 */
    public Map<String, Object> test() {
        Map<String, Object> ret = new LinkedHashMap<>();
        String url = get(K_URL);
        if (url == null || url.isBlank()) throw new IllegalArgumentException("请先填写 Webhook 地址");
        try {
            send(url, get(K_TYPE), get(K_SECRET), "【知识库】通知外发测试",
                    "这是一条测试消息，收到说明 Webhook 配置正确。");
            ret.put("ok", true);
            ret.put("message", "已发送，请到群里确认");
        } catch (Exception e) {
            ret.put("ok", false);
            ret.put("message", "发送失败：" + e.getMessage());
        }
        return ret;
    }

    /** 站内通知落库后调用：异步外发，绝不阻塞、绝不抛出 */
    public void pushAsync(String type, String title, String content) {
        if (!shouldPush(type)) return;
        if (!"true".equals(get(K_ENABLED))) return;
        String url = get(K_URL);
        if (url == null || url.isBlank()) return;
        String t = title == null ? "知识库通知" : title;
        String c = content == null ? "" : content;
        Thread th = new Thread(() -> {
            try {
                send(url, get(K_TYPE), get(K_SECRET), t, c);
            } catch (Exception e) {
                log.warn("Webhook 外发失败：{}", e.getMessage());
            }
        }, "kb-webhook");
        th.setDaemon(true);
        th.start();
    }

    /** 点赞、订阅刷新这类高频低价值事件不外发 */
    private boolean shouldPush(String type) {
        if (type == null) return true;
        return !"LIKE".equals(type) && !"SUBSCRIBE".equals(type) && !"READ".equals(type);
    }

    private void send(String url, String type, String secret, String title, String content) throws Exception {
        String text = title + "\n" + content;
        String body;
        String finalUrl = url;
        if ("dingtalk".equalsIgnoreCase(type)) {
            if (secret != null && !secret.isBlank()) {
                long ts = System.currentTimeMillis();
                finalUrl = url + (url.contains("?") ? "&" : "?") + "timestamp=" + ts + "&sign=" + dingTalkSign(ts, secret);
            }
            body = "{\"msgtype\":\"text\",\"text\":{\"content\":" + json(text) + "}}";
        } else if ("generic".equalsIgnoreCase(type)) {
            body = "{\"title\":" + json(title) + ",\"content\":" + json(content) + ",\"text\":" + json(text) + "}";
        } else {
            // 企业微信群机器人（也兼容飞书的 text 结构差异请选 generic 自行适配）
            body = "{\"msgtype\":\"text\",\"text\":{\"content\":" + json(text) + "}}";
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(finalUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 300) throw new IllegalStateException("HTTP " + resp.statusCode());
        // 钉钉/企微成功也返回 200 + errcode!=0，这里做一次业务码判断
        String r = resp.body() == null ? "" : resp.body();
        if (r.contains("\"errcode\"") && !r.contains("\"errcode\":0")) {
            throw new IllegalStateException(shorten(r));
        }
    }

    private String dingTalkSign(long ts, String secret) throws Exception {
        String stringToSign = ts + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
        return java.net.URLEncoder.encode(java.util.Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
    }

    /** 极简 JSON 字符串转义（只处理必要字符，避免为此引依赖） */
    private String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }

    private String shorten(String s) {
        String t = s.replaceAll("\\s+", " ");
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    private String maskUrl(String url) {
        if (url == null || url.isBlank()) return "";
        int q = url.indexOf('?');
        String base = q > 0 ? url.substring(0, q) : url;
        String tail = q > 0 ? url.substring(q) : "";
        // access_token 之类的查询参数整段打码，只留长度提示
        return base + (tail.isEmpty() ? "" : "?****") + "（已配置）";
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
}
