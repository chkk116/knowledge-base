package com.kb.kbplatform.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;

import java.io.IOException;

/**
 * 鉴权过滤器：对受保护接口校验 Bearer 令牌；公开接口直接放行；
 * /api/admin/** 要求 ADMIN 角色或对应细粒度权限（双重门禁，控制器内也会再校验一次）。
 * 通过 FilterConfig 注册并设置高优先级（-100）。
 */
public class AuthFilter implements Filter {

    private final String secret;
    private final long ttlMillis;
    private final UserRepository userRepo;

    public AuthFilter(String secret, long ttlMillis, UserRepository userRepo) {
        this.secret = secret;
        this.ttlMillis = ttlMillis;
        this.userRepo = userRepo;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        String path = req.getRequestURI();
        String method = req.getMethod();

        if (isPublic(path, method)) {
            // 公开接口仍尝试解析登录态：让管理员/作者能预览非发布文档（如待审核、草稿），
            // 匿名请求不受影响仍可访问；仅当携带合法 Bearer 令牌时才注入 kbUser。
            String anonAuth = req.getHeader("Authorization");
            if (anonAuth != null && anonAuth.startsWith("Bearer ")) {
                try {
                    TokenClaims claims = TokenUtil.parse(secret, anonAuth.substring(7).trim());
                    req.setAttribute("kbUser", claims);
                } catch (Exception ignored) { /* 无效令牌：按匿名处理，不阻断公开访问 */ }
            }
            chain.doFilter(request, response);
            return;
        }

        String auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            // EventSource 无法自定义请求头：SSE 通知流允许通过 ?token= 查询参数传递令牌
            if (path.equals("/api/notifications/stream")) {
                String q = req.getParameter("token");
                if (q != null && !q.isBlank()) auth = "Bearer " + q;
            }
        }
        if (auth == null || !auth.startsWith("Bearer ")) {
            send401(res);
            return;
        }
        try {
            TokenClaims claims = TokenUtil.parse(secret, auth.substring(7).trim());
            // 实时吊销校验：账号已被删除 / 禁用，或令牌版本不匹配（改密 / 重置密码后），
            // 旧 token 立即失效，无需等待 24h 有效期过期
            User u = userRepo.findById(claims.userId()).orElse(null);
            if (u == null || !u.isEnabled() || u.getTokenVersion() != claims.tokenVersion()) {
                send401(res);
                return;
            }
            // 管理员接口：ADMIN 角色放行；否则按接口路径映射所需细粒度权限校验
            if (path.startsWith("/api/admin")) {
                String need = adminPermRequired(path);
                if (need != null && !claims.hasPerm(need)) {
                    send403(res);
                    return;
                }
            }
            req.setAttribute("kbUser", claims);   // 供控制器读取当前用户
            chain.doFilter(request, response);
        } catch (Exception e) {
            send401(res);
        }
    }

    /** 公开（免登录）的接口 */
    private boolean isPublic(String path, String method) {
        if (path.startsWith("/uploads")) return true;   // 已上传的图片可公开访问
        if ("/api/auth/login".equals(path) && "POST".equals(method)) return true;
        if ("/api/auth/register".equals(path) && "POST".equals(method)) return true;
        if ("/api/auth/captcha".equals(path) && "GET".equals(method)) return true;
        if ("/api/auth/captcha/slide".equals(path) && "POST".equals(method)) return true;  // 滑动成功回执，免登录
        if (path.startsWith("/api/users/") && "GET".equals(method)) return true;  // 作者卡片公开可读
        if (path.startsWith("/api/search") && "GET".equals(method)) return true;
        // 文档只读接口公开；/api/docs/mine 含个人数据，需登录
        if ("GET".equals(method) && isPublicDocRead(path)) return true;
        if ("/error".equals(path)) return true;
        return false;
    }

    /** 允许公开访问的文档读取路径（列表 / 分类 / 数字 id 详情 / 数字 id 评论） */
    private boolean isPublicDocRead(String path) {
        if ("/api/docs".equals(path)) return true;
        if ("/api/docs/categories".equals(path)) return true;
        if (path.matches("/api/docs/\\d+/comments$")) return true;  // 评论列表
        if (path.matches("/api/docs/\\d+$")) return true;            // 文档详情
        return false;
    }

    /** 返回该 /api/admin 接口所需的最小细粒度权限码；null 表示仍需 ADMIN 角色（未开放细粒度授权） */
    private String adminPermRequired(String path) {
        if (path.startsWith("/api/admin/users")) return "USER_MANAGE";
        if (path.startsWith("/api/admin/docs")) return "DOC_REVIEW";
        if (path.startsWith("/api/admin/stats")) return "STATS_VIEW";
        if (path.startsWith("/api/admin/categories")) return "TAXONOMY_MANAGE";
        if (path.startsWith("/api/admin/tags")) return "TAXONOMY_MANAGE";
        if (path.startsWith("/api/admin/announcements")) return "ANNOUNCEMENT_MANAGE";
        return null;   // 其他管理接口（如黑名单）仍仅 ADMIN 可访问
    }

    private void send401(HttpServletResponse res) throws IOException {
        res.setStatus(HttpStatus.UNAUTHORIZED.value());
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"未登录或登录已过期\"}");
    }

    private void send403(HttpServletResponse res) throws IOException {
        res.setStatus(HttpStatus.FORBIDDEN.value());
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"需要管理员权限\"}");
    }
}
