/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页剪藏：抓一个网页，抽正文，交给前端存成文档。
 *
 * 只做「抓取 + 提取」，不落库——保存复用既有的建文档接口，
 * 权限/配额/空间归属都由那条路径统一处理，这里不重复实现。
 *
 * ⚠️ 安全（这是本项目里唯一「服务端主动访问用户给的地址」的地方）：
 *   - 只允许 http/https；
 *   - **每一跳**（含重定向）都做 DNS 解析并拒绝内网/回环/链路本地地址，防 SSRF
 *     （否则用户可以让服务器去读 127.0.0.1:8090 或云厂商元数据 169.254.169.254）；
 *   - 手动跟随重定向（最多 5 跳），每跳重新校验；
 *   - 限制响应体大小与超时，避免被大文件或慢连接拖死；
 *   - 同一用户 1.5 秒内只允许一次，简单防滥用。
 */
@Service
public class WebClipService {

    private static final Logger log = LoggerFactory.getLogger(WebClipService.class);

    private static final int MAX_BYTES = 3 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_CONTENT_CHARS = 200_000;
    private static final Duration TIMEOUT = Duration.ofSeconds(12);
    private static final long MIN_INTERVAL_MS = 1500;

    private static final Pattern META_CHARSET =
            Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9_\\-]+)");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)   // 重定向必须自己跟，才能逐跳校验
            .build();

    /** userId → 上次调用时间（防连点） */
    private final Map<Long, Long> lastCall = new ConcurrentHashMap<>();

    /** 抓取并提取正文，返回 { url, title, site, excerpt, content, length } */
    public Map<String, Object> preview(String rawUrl, Long userId) {
        throttle(userId);
        URI uri = safeUri(rawUrl);
        Fetched page;
        try {
            page = fetch(uri);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("网页剪藏抓取失败 url={} err={}", uri, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "抓取失败：" + e.getMessage());
        }
        return extract(page);
    }

    /**
     * 供内部批量场景（AI 出题取参考资料）使用：**不做用户级节流**——一次要抓多篇，
     * 逐个 1.5s 会把整个请求拖到十几秒。SSRF 校验（safeUri/fetch 逐跳）照旧全走。
     * 调用方需自己控频（见 AiQuestionService 的整体限流）。
     */
    public Map<String, Object> fetchForAi(String rawUrl) {
        URI uri = safeUri(rawUrl);
        Fetched page;
        try {
            page = fetch(uri);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 出题取资料抓取失败 url={} err={}", uri, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "抓取失败：" + e.getMessage());
        }
        return extract(page);
    }

    private void throttle(Long userId) {
        long now = System.currentTimeMillis();
        Long prev = lastCall.get(userId);
        if (prev != null && now - prev < MIN_INTERVAL_MS) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "操作太快了，请稍后再试");
        }
        lastCall.put(userId, now);
        if (lastCall.size() > 5000) lastCall.clear();   // 简单防泄漏，够用
    }

    // ---------- 地址校验（SSRF） ----------

    private URI safeUri(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写网页链接");
        // 先判协议：带了别的协议（file://、javascript: 等）要明确拒绝，
        // 不能当成「裸域名」去补 https，否则报错文案会变成莫名其妙的「域名无法解析」
        int proto = s.indexOf("://");
        if (proto > 0) {
            String sch = s.substring(0, proto).toLowerCase(Locale.ROOT);
            if (!sch.equals("http") && !sch.equals("https"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 http / https 链接");
        } else {
            s = "https://" + s;   // 允许只贴 example.com/xxx
        }
        URI u;
        try {
            u = new URI(s);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "链接格式不正确");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只支持 http / https 链接");
        if (u.getHost() == null || u.getHost().trim().isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "链接缺少域名");
        assertPublicHost(u.getHost());
        return u;
    }

    /** 拒绝内网/回环/链路本地地址：防 SSRF（云元数据 169.254.169.254 也在这里被挡） */
    private void assertPublicHost(String host) {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "域名无法解析：" + host);
        }
        for (InetAddress a : addrs) {
            // 与 AI 模型的地址校验同一口径（含 IPv4 映射 IPv6 / IPv4 兼容地址）
            if (com.kb.kbplatform.security.NetAddressSafety.isInternal(a)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持抓取内网地址");
            }
        }
    }

    private static boolean isUniqueLocalV6(InetAddress a) {
        if (!(a instanceof Inet6Address)) return false;
        return (a.getAddress()[0] & 0xFE) == 0xFC;   // fc00::/7
    }

    // ---------- 抓取 ----------

    private record Fetched(String url, String html) {}

    private Fetched fetch(URI start) throws Exception {
        URI cur = start;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest req = HttpRequest.newBuilder(cur)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36 KBClip/1.0")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .GET()
                    .build();

            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            int code = res.statusCode();
            try {
                if (code >= 300 && code < 400) {
                    String loc = res.headers().firstValue("location").orElse(null);
                    if (loc == null || loc.isBlank())
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "页面跳转异常（无 Location）");
                    cur = cur.resolve(loc);
                    String scheme = cur.getScheme() == null ? "" : cur.getScheme().toLowerCase(Locale.ROOT);
                    if (!scheme.equals("http") && !scheme.equals("https"))
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的跳转地址");
                    assertPublicHost(cur.getHost());     // 每一跳都要重新校验，别被重定向绕过
                    continue;
                }

                if (code >= 400)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "抓取失败（HTTP " + code + "）");

                String ct = res.headers().firstValue("content-type").orElse("");
                String ctLower = ct.toLowerCase(Locale.ROOT);
                if (!ctLower.isEmpty() && !ctLower.contains("html") && !ctLower.contains("xml")
                        && !ctLower.contains("text/plain")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "这不是网页内容（" + ct + "），无法剪藏");
                }

                byte[] bytes = readLimited(res.body(), MAX_BYTES);
                Charset cs = charsetOf(ct, bytes);
                return new Fetched(cur.toString(), new String(bytes, cs));
            } finally {
                // 任何分支（重定向 continue / 4xx / 非网页 throw）都必须关响应流（审查 r8-r10 High）：
                // 否则连接与文件句柄泄漏，高并发或恶意目标可耗尽
                try { res.body().close(); } catch (Exception ignore) { /* ignore */ }
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "跳转次数过多，已中止");
    }

    private static byte[] readLimited(InputStream in, int max) throws Exception {
        try (in) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) {
                    bos.write(buf, 0, Math.max(0, n - (total - max)));
                    break;
                }
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /** 编码判定：先看响应头，再看 <meta charset>，都没有就用 UTF-8 */
    private static Charset charsetOf(String contentType, byte[] bytes) {
        try {
            int i = contentType == null ? -1 : contentType.toLowerCase(Locale.ROOT).indexOf("charset=");
            if (i >= 0) {
                String cs = contentType.substring(i + 8).split("[;\\s]")[0].replace("\"", "").trim();
                if (!cs.isEmpty()) return Charset.forName(cs);
            }
        } catch (Exception ignored) { /* 落到下面的嗅探 */ }
        try {
            String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
            Matcher m = META_CHARSET.matcher(head);
            if (m.find()) return Charset.forName(m.group(1));
        } catch (Exception ignored) { /* 用默认 */ }
        return StandardCharsets.UTF_8;
    }

    // ---------- 正文提取 ----------

    private Map<String, Object> extract(Fetched page) {
        Document doc = Jsoup.parse(page.html(), page.url());

        // 先摘掉明显不是正文的区块
        doc.select("script,style,noscript,iframe,svg,canvas,form,button,nav,header,footer,aside,"
                + "[class*=advert],[id*=advert],[class*=comment],[id*=comment],[class*=sidebar],[id*=sidebar]")
                .remove();

        Element main = null;
        for (String sel : new String[]{"article", "main", "[role=main]", ".article-content", ".post-content",
                "#content", ".content", ".markdown-body"}) {
            Element el = doc.selectFirst(sel);
            if (el != null && el.text().length() > 200) { main = el; break; }
        }
        if (main == null) main = doc.body();

        Safelist sl = Safelist.relaxed()
                .addTags("figure", "figcaption", "picture", "mark", "del", "ins", "details", "summary")
                .addAttributes("img", "src", "alt", "title", "width", "height")
                .addAttributes("a", "href", "title")
                .addAttributes("td", "colspan", "rowspan")
                .addAttributes("th", "colspan", "rowspan")
                .addProtocols("img", "src", "http", "https", "data")
                .addProtocols("a", "href", "http", "https", "mailto");

        String clean = Jsoup.clean(main.html(), page.url(), sl,
                new Document.OutputSettings().prettyPrint(false));
        if (clean.length() > MAX_CONTENT_CHARS) clean = clean.substring(0, MAX_CONTENT_CHARS) + "<p>（内容过长，已截断）</p>";

        String title = firstNonBlank(
                doc.select("meta[property=og:title]").attr("content"),
                doc.title(),
                doc.select("h1").first() == null ? "" : doc.select("h1").first().text(),
                hostOf(page.url()));
        String excerpt = firstNonBlank(
                doc.select("meta[name=description]").attr("content"),
                doc.select("meta[property=og:description]").attr("content"));
        if (excerpt.isEmpty()) {
            String plain = main.text();
            excerpt = plain.length() > 120 ? plain.substring(0, 120) + "…" : plain;
        }

        if (Jsoup.parse(clean).text().trim().length() < 20)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "没提取到正文（页面可能是动态渲染或需要登录），可以试试直接复制正文用速记保存");

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("url", page.url());
        ret.put("title", title.length() > 120 ? title.substring(0, 120) : title);
        ret.put("site", hostOf(page.url()));
        ret.put("excerpt", excerpt);
        ret.put("content", clean);
        ret.put("length", Jsoup.parse(clean).text().length());
        return ret;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    private static String hostOf(String url) {
        try { return URI.create(url).getHost(); } catch (Exception e) { return ""; }
    }
}
