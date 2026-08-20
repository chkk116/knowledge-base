package com.kb.kbplatform.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

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

        // 上传目录：内容仅作为被动资源（图片/音频），沙箱化杜绝任何脚本执行面
        if (req.getRequestURI().startsWith("/uploads/")) {
            res.setHeader("Content-Security-Policy", "sandbox");
        }

        chain.doFilter(request, response);
    }
}
