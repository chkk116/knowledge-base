package com.kb.kbplatform.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 安全响应头过滤器：给所有响应统一补上浏览器安全基线头。
 *  - X-Content-Type-Options: nosniff      防 MIME 嗅探（上传的图片/音频不会被当作脚本执行）
 *  - X-Frame-Options: DENY                防点击劫持
 *  - Referrer-Policy                      控制引用来源泄露
 *  - /uploads/** 额外加 CSP sandbox：上传目录即使被塞入 HTML/脚本文件也不会被浏览器执行
 */
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        res.setHeader("X-Content-Type-Options", "nosniff");
        res.setHeader("X-Frame-Options", "DENY");
        res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        // 上传目录：仅对「可被当作文档执行脚本」的响应（HTML / SVG / XHTML）施加 CSP sandbox，
        // 杜绝直接导航到上传的 .html/.svg 时被站点源执行（XSS 收敛到 opaque 源）。
        // 注意：音频/图片/视频等被动资源绝不能加 sandbox —— 浏览器对媒体走带源缓存，
        // 响应带 opaque 源(CSP sandbox) 会让媒体缓存不可用，报
        // net::ERR_CACHE_OPERATION_NOT_SUPPORTED（音频/视频直接播不了）。故按扩展名收口。
        if (req.getRequestURI().startsWith("/uploads/")
                && isSandboxableUpload(req.getRequestURI())) {
            res.setHeader("Content-Security-Policy", "sandbox");
        }

        // 上传的音频/视频：屏蔽 Range 分段，一律整包 200 返回。
        // 原因：Chrome 对媒体做 Range 请求时会建立 sparse（稀疏）缓存条目，当浏览器侧缓存后端
        // 不支持 sparse 操作时（老旧 blockfile 缓存、磁盘缓存损坏后回退内存后端、只读回退等），
        // 会抛 net::ERR_CACHE_OPERATION_NOT_SUPPORTED，音频/视频直接加载失败。该错误属浏览器侧状态，
        // 服务端响应头本身完全合规（实测 206 / Accept-Ranges / Content-Range 均正确），无法从服务端
        // 修复，只能规避：不声明 Accept-Ranges + 忽略 Range 头 → 浏览器不会建 sparse 条目。
        // 实测（29.8MB FLAC，headless Chrome）：整包 200 + 保留 max-age 缓存 → 零失败请求、
        // readyState=4 可播放、缓冲完成后可拖动进度，且 HTTP 缓存仍生效（复用不再重下）。
        // 代价：大媒体需整包缓冲后才能拖动进度（局域网内 30MB 传输无感）。图片不受影响（本就整包）。
        if (req.getRequestURI().startsWith("/uploads/") && isRangeSensitiveMedia(req.getRequestURI())) {
            chain.doFilter(new NoRangeRequestWrapper(req), new NoAcceptRangesResponseWrapper(res));
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * 仅 HTML / SVG / XHTML 这类「可被浏览器作为文档执行脚本」的上传响应需要 CSP sandbox。
     * 其余（图片/音频/视频/字体等被动资源）不应加，否则会破坏媒体缓存。
     */
    private static boolean isSandboxableUpload(String uri) {
        String lower = uri.toLowerCase();
        return lower.endsWith(".html") || lower.endsWith(".htm")
                || lower.endsWith(".svg") || lower.endsWith(".xhtml") || lower.endsWith(".xml");
    }

    /** 会被浏览器以 Range 分段方式流式加载的媒体扩展名（音频 + 视频） */
    private static final String[] MEDIA_EXT = {
            ".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg", ".oga", ".opus",
            ".mp4", ".webm", ".mov", ".avi", ".mkv", ".m4v", ".ogv"
    };

    /** 是否是需要规避 Range 分段的媒体资源 */
    private static boolean isRangeSensitiveMedia(String uri) {
        String lower = uri.toLowerCase();
        int q = lower.indexOf('?');
        if (q >= 0) lower = lower.substring(0, q);
        for (String ext : MEDIA_EXT) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /** 隐藏 Range 请求头，使下游按普通整包请求处理（返回 200 而非 206） */
    private static class NoRangeRequestWrapper extends HttpServletRequestWrapper {
        NoRangeRequestWrapper(HttpServletRequest request) {
            super(request);
        }

        @Override
        public String getHeader(String name) {
            return "Range".equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return "Range".equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>();
            Enumeration<String> e = super.getHeaderNames();
            if (e != null) {
                while (e.hasMoreElements()) {
                    String n = e.nextElement();
                    if (!"Range".equalsIgnoreCase(n)) names.add(n);
                }
            }
            return Collections.enumeration(names);
        }
    }

    /** 屏蔽 Accept-Ranges 响应头，使浏览器不再对该资源发起 Range 分段请求 */
    private static class NoAcceptRangesResponseWrapper extends HttpServletResponseWrapper {
        NoAcceptRangesResponseWrapper(HttpServletResponse response) {
            super(response);
        }

        @Override
        public void setHeader(String name, String value) {
            if ("Accept-Ranges".equalsIgnoreCase(name)) return;
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            if ("Accept-Ranges".equalsIgnoreCase(name)) return;
            super.addHeader(name, value);
        }

        /** 兜底：即便下游仍判定为分段响应（206），也降为 200，避免浏览器建立 sparse 缓存条目 */
        @Override
        public void setStatus(int sc) {
            super.setStatus(sc == 206 ? 200 : sc);
        }
    }
}
