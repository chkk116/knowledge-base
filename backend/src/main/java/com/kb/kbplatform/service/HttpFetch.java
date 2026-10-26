/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 第三方 HTTP 调用（2026-10-26）。
 *
 * 为什么单独抽出来：原来三个登录服务各自持有一个**长期存活的 HttpClient**，
 * 结果生产上出现了「curl 2 秒能通、应用里每次 15 秒超时」——
 * 长连接池里残留的失效连接不会自己消失，HttpClient 会一直拿它去发请求直到超时。
 *
 * 这里的做法：
 * 1. **每次请求新建 HttpClient**（创建开销远小于一次超时），彻底绕开脏连接池；
 * 2. 强制 **HTTP/1.1**（h2 在中间设备上偶发握手卡死）；
 * 3. 失败**自动重试一次**（3xx/4xx/5xx 不重试，只有网络层异常才重试）；
 * 4. 记录耗时，超过 3 秒打 info，失败打 warn 并带上 URL host —— 下次再出问题能直接定位。
 */
public final class HttpFetch {

    private static final Logger log = LoggerFactory.getLogger(HttpFetch.class);

    /** 连接超时：10s（跨境链路抖动留余量） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /** 单次请求超时：25s（原来 15s 在跨境链路上偏紧） */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(25);

    private HttpFetch() {}

    public record Resp(int status, String body, long millis) {
        public boolean ok() { return status >= 200 && status < 300; }
    }

    public static Resp get(String url, Map<String, String> headers) throws Exception {
        return send("GET", url, null, headers);
    }

    public static Resp postForm(String url, String formBody, Map<String, String> headers) throws Exception {
        return send("POST", url, formBody, headers);
    }

    private static Resp send(String method, String url, String formBody, Map<String, String> headers) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            long started = System.currentTimeMillis();
            try {
                HttpClient client = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(CONNECT_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build();

                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                        .timeout(REQUEST_TIMEOUT)
                        // 所有第三方接口都要求带 UA（GitHub 不带直接 403）
                        .header("User-Agent", "kb-platform");
                boolean hasAccept = headers != null
                        && headers.keySet().stream().anyMatch(k -> "accept".equalsIgnoreCase(k));
                if (!hasAccept) b.header("Accept", "application/json");
                if (headers != null) headers.forEach(b::header);

                if ("POST".equals(method)) {
                    b.header("Content-Type", "application/x-www-form-urlencoded");
                    b.POST(HttpRequest.BodyPublishers.ofString(
                            formBody == null ? "" : formBody, StandardCharsets.UTF_8));
                } else {
                    b.GET();
                }

                HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                long ms = System.currentTimeMillis() - started;
                if (ms > 3000) log.info("第三方请求较慢：{} {} 用时 {}ms", method, host(url), ms);
                return new Resp(r.statusCode(), r.body(), ms);
            } catch (Exception e) {
                last = e;
                long ms = System.currentTimeMillis() - started;
                log.warn("第三方请求失败：{} {} 第 {} 次，用时 {}ms，{}: {}",
                        method, host(url), attempt, ms, e.getClass().getSimpleName(), e.getMessage());
                if (attempt < 2) {
                    try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
        throw last == null ? new IllegalStateException("请求失败") : last;
    }

    /** 只取 host 记日志，避免把 access_token / code 之类写进日志 */
    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    /** 诊断用：探测某个 host 是否可达（给后台「连通性诊断」用） */
    public static Map<String, Object> probe(String url) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", url);
        try {
            Resp r = get(url, null);
            out.put("ok", true);
            out.put("status", r.status());
            out.put("millis", r.millis());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }
}
