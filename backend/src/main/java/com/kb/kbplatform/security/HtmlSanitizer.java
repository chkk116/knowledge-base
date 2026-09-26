/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

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
            // 限制 data: 协议仅允许图片类型，杜绝 data:image/svg+xml 等可含脚本的向量
            if ("img".equals(tagName) && "src".equals(key) && val.startsWith("data:")) {
                return val.matches("^data:image/(png|jpe?g|gif|webp|bmp);base64,.*");
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
            // 图块节点（思维导图/流程图/电子表格）以 <div data-type="xxx-block" data="..." locked="" title="..."> 存储：
            // 放行这四个属性，否则种子/快照会被剥成 <div></div>（块丢失、前端无法识别专属文档）。
            // 属性值是纯字符串（JSON/枚举），无脚本执行面。
            .addAttributes("div", "style", "data-type", "data", "locked", "title")
            .addAttributes("a", "target", "rel")
            .addAttributes("img", "style", "class", "width", "height", "loading", "decoding")
            // 语音条 <audio controls src>：src 相对路径经上方覆写放行，其余按协议白名单
            .addTags("audio")
            .addAttributes("audio", "src", "controls", "style", "preload")
            .addProtocols("audio", "src", "http", "https")
            .addProtocols("img", "src", "data", "http", "https")  // 允许 data:/http(s): 以及经上方覆写放行的 /uploads 相对路径
            .preserveRelativeLinks(true);

    /** 仅保留无害的排版类 style 属性，剔除 position/z-index/background-url 等可造成点击劫持或追踪信标的 CSS */
    private static final java.util.Set<String> SAFE_STYLE_PROPS = java.util.Set.of(
            "color", "background-color", "font-size", "font-weight", "font-style", "font-family",
            "text-align", "text-decoration", "line-height", "letter-spacing", "text-indent",
            "vertical-align", "white-space", "word-break", "text-transform", "list-style-type");

    private static String safeStyle(String style) {
        if (style == null || style.isBlank()) return "";
        String lower = style.toLowerCase();
        if (lower.contains("url(") || lower.contains("expression(")) return ""; // 整体含 url()/expression 直接作废
        StringBuilder sb = new StringBuilder();
        for (String decl : style.split(";")) {
            int idx = decl.indexOf(':');
            if (idx <= 0) continue;
            String prop = decl.substring(0, idx).trim().toLowerCase();
            String val = decl.substring(idx + 1).trim();
            if (!SAFE_STYLE_PROPS.contains(prop)) continue;
            if (val.contains("<") || val.contains(">")) continue;
            sb.append(prop).append(":").append(val).append(";");
        }
        return sb.toString();
    }

    /** 净化富文本 HTML，去除危险标签/属性；收紧 style、外链强制 noopener、正文图片注入懒加载 */
    public static String sanitize(String html) {
        if (html == null) return "";
        String clean = Jsoup.clean(html, WHITELIST);
        org.jsoup.nodes.Document doc = Jsoup.parse(clean);
        // 收紧 style：仅保留白名单排版属性，剔除危险 CSS（点击劫持 / 追踪信标）
        doc.select("[style]").forEach(el -> {
            String safe = safeStyle(el.attr("style"));
            if (safe.isEmpty()) el.removeAttr("style");
            else el.attr("style", safe);
        });
        // 外链强制 noopener，防 reverse tabnabbing
        doc.select("a[target=_blank]").forEach(a -> a.attr("rel", "noopener noreferrer"));
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

    /**
     * 生成通知/看板等“摘要预览”：优先保留原始富文本（含站内表情 {@code <img class="kb-emoji">}），
     * 仅当可见纯文本超过 max 时才退化为截断的纯文本（避免把表情 <img> 标签从中间切断导致裂图/裸标签）。
     */
    public static String preview(String html, int max) {
        if (html == null) return "";
        String clean = sanitize(html);
        String text = toText(clean);
        if (text.length() <= max) return clean;
        return text.substring(0, max) + "…";
    }

    /** 转义纯文本用于安全插入 HTML 属性/标题（防注入与标签截断） */
    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
