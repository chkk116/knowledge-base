package com.kb.kbplatform.security;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

/**
 * 富文本（文档正文 / 评论）XSS 净化工具。
 * 采用 jsoup 白名单，仅放行常见排版标签与必要属性，避免存储型 XSS。
 */
public final class HtmlSanitizer {

    private HtmlSanitizer() {}

    // 注意：jsoup 1.18 的 preserveRelativeLinks 只控制“是否改写为绝对地址”，并不会真正保留
    // 相对链接——当 clean 时不传 baseUri，相对路径（如 /uploads/...）会因“无协议”被删除（标签保留、src 被剥离）。
    // 因此这里用匿名子类覆写 isSafeAttribute，对本系统上传的相对路径 /uploads/ 开头的 img.src 强制放行。
    private static final Safelist WHITELIST = new Safelist(Safelist.relaxed()) {
        @Override
        public boolean isSafeAttribute(String tagName, Element el, Attribute attr) {
            String key = attr.getKey();
            String val = attr.getValue();
            if (val == null) return super.isSafeAttribute(tagName, el, attr);
            // 放行本站上传的图片 / 站内表情包相对路径，避免被净化剥离导致裂图；
            // audio（语音条）的 src 同样是 /uploads/ 相对路径，一并放行
            if (("img".equals(tagName) || "audio".equals(tagName)) && "src".equals(key)
                    && (val.startsWith("/uploads/") || val.startsWith("/emojis/"))) {
                return true;
            }
            // 放行站内相对链接（如 /post/123、/#anchor），避免文章内跳转失效；
            // 注意禁止协议相对地址（//evil.com）——否则会被浏览器当作 https://evil.com 用于钓鱼/跳转
            if ("a".equals(tagName) && "href".equals(key)
                    && ((val.startsWith("/") && !val.startsWith("//")) || val.startsWith("#"))) {
                return true;
            }
            return super.isSafeAttribute(tagName, el, attr);
        }
    }
            .addAttributes("span", "style")
            .addAttributes("p", "style")
            .addAttributes("div", "style")
            .addAttributes("a", "target", "rel")
            .addAttributes("img", "style", "class", "width", "height", "loading", "decoding")
            // 语音条 <audio controls src>：src 相对路径经上方覆写放行，其余按协议白名单
            .addTags("audio")
            .addAttributes("audio", "src", "controls", "style", "preload")
            .addProtocols("audio", "src", "http", "https")
            .addProtocols("img", "src", "data", "http", "https")  // 允许 data:/http(s): 以及经上方覆写放行的 /uploads 相对路径
            .preserveRelativeLinks(true);

    /** 净化富文本 HTML，去除危险标签/属性；并为正文图片注入 loading="lazy" 实现懒加载 */
    public static String sanitize(String html) {
        if (html == null) return "";
        String clean = Jsoup.clean(html, WHITELIST);
        // 二次解析，统一给所有 <img> 加懒加载属性（已在白名单内，不会被剥离）
        org.jsoup.nodes.Document doc = Jsoup.parse(clean);
        doc.select("img").forEach(img -> {
            img.attr("loading", "lazy");
            img.attr("decoding", "async");
        });
        return doc.body().html();
    }

    /** 彻底去除所有标签，仅保留纯文本（用于不需要 HTML 的场景） */
    public static String toText(String html) {
        if (html == null) return "";
        return Jsoup.clean(html, Safelist.none());
    }
}
