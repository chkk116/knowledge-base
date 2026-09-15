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

        boolean publicRequest = isPublic(path, method);
        String auth = req.getHeader("Authorization");
        if (!publicRequest && (auth == null || !auth.startsWith("Bearer "))) {
            // EventSource 无法自定义请求头：SSE 通知流允许通过 ?token= 查询参数传递令牌
            if (path.equals("/api/notifications/stream")) {
                String q = req.getParameter("token");
                if (q != null && !q.isBlank()) auth = "Bearer " + q;
            }
        }
        TokenClaims claims = null;
        if (auth != null && auth.startsWith("Bearer ")) {
            try {
                claims = TokenUtil.parse(secret, auth.substring(7).trim());
            } catch (IllegalArgumentException e) {
                // 仅令牌格式、签名或过期错误按认证失败处理；公开接口降级为匿名。
                if (!publicRequest) {
                    send401(res);
                    return;
                }
            }
        } else if (!publicRequest) {
            send401(res);
            return;
        }

        if (claims != null) {
            // 公开与受保护接口使用相同的实时吊销校验；数据库故障必须原样传播。
            User u = userRepo.findById(claims.userId()).orElse(null);
            if (u == null || !u.isEnabled() || u.getTokenVersion() != claims.tokenVersion()) {
                if (!publicRequest) {
                    send401(res);
                    return;
                }
                claims = null;
            }
        }
        if (!publicRequest && path.startsWith("/api/admin")) {
            // 功能开关状态与水印配置只读接口对所有登录用户开放；写入仍需细粒度权限。
            boolean loginOnlyRead = "GET".equals(method)
                    && ("/api/admin/features/map".equals(path) || "/api/admin/settings/watermark".equals(path));
            if (!loginOnlyRead) {
                String need = adminPermRequired(path, method);
                // 全权限覆盖视同 ADMIN，包括没有细粒度权限码的仅管理员接口。
                boolean roleOk = claims.isAdmin();
                boolean permOk = need != null && claims.hasPerm(need);
                if (!roleOk && !permOk) {
                    send403(res);
                    return;
                }
            }
        }
        if (claims != null) req.setAttribute("kbUser", claims);   // 供控制器读取当前用户
        chain.doFilter(request, response);
    }

    /** 公开（免登录）的接口 */
    private boolean isPublic(String path, String method) {
        if (path.startsWith("/uploads")) return true;   // 已上传的图片可公开访问
        if (path.startsWith("/api/thumb")) return true;  // 文章正文缩略图：被动图片资源，公开可读（路径穿越防护在 ThumbController 内）
        if (path.startsWith("/api/public/") && "GET".equals(method)) return true;  // 公开站点配置（登录页二维码等未认证页面读取）
        if ("/api/auth/login".equals(path) && "POST".equals(method)) return true;
        if ("/api/auth/register".equals(path) && "POST".equals(method)) return true;
        if ("/api/auth/captcha".equals(path) && "GET".equals(method)) return true;
        if ("/api/auth/captcha/slide".equals(path) && "POST".equals(method)) return true;  // 滑动成功回执，免登录
        if (path.startsWith("/api/users/") && "GET".equals(method)) return true;  // 作者卡片公开可读
        if (path.startsWith("/api/search") && "GET".equals(method)) return true;
        // 文档只读接口公开；/api/docs/mine 含个人数据，需登录
        if ("GET".equals(method) && isPublicDocRead(path)) return true;
        // 培训/考试浏览公开（列表、详情、试卷），个人进度/作答等写接口仍走登录校验
        if ("GET".equals(method) && (path.startsWith("/api/courses") || path.startsWith("/api/exams"))) return true;
        if ("/error".equals(path)) return true;
        return false;
    }

    /** 允许公开访问的文档读取路径（列表 / 分类 / 数字 id 详情 / 数字 id 评论 / 相关推荐 / 订阅状态只读） */
    private boolean isPublicDocRead(String path) {
        if ("/api/docs".equals(path)) return true;
        if ("/api/docs/categories".equals(path)) return true;
        if (path.matches("/api/docs/\\d+/comments$")) return true;  // 评论列表
        if (path.matches("/api/docs/\\d+$")) return true;            // 文档详情
        if (path.matches("/api/docs/\\d+/related$")) return true;    // 相关文档推荐（仅回标题等公开字段）
        if (path.matches("/api/docs/\\d+/subscribed$")) return true; // 订阅状态只读：未登录时返回 subscribed=false
        return false;
    }

    /** 返回该 /api/admin 接口所需的最小细粒度权限码；null 表示仍需 ADMIN 角色（未开放细粒度授权）
     *  共享文档管理拆分：查看 SHARE_VIEW / 删除 SHARE_DELETE / 容量 SHARE_CAPACITY */
    private String adminPermRequired(String path, String method) {
        if (path.startsWith("/api/admin/users")) return "USER_MANAGE";
        if (path.startsWith("/api/admin/assets")) return "ASSET_MANAGE";
        if (path.startsWith("/api/admin/docs")) {
            // 拆分：文章审核（待审核队列 + 通过/驳回）= DOC_REVIEW；其余帖子管理操作 = POST_MANAGE
            if (path.equals("/api/admin/docs/pending")) return "DOC_REVIEW";
            if (path.matches("/api/admin/docs/\\d+/(approve|reject)")) return "DOC_REVIEW";
            return "POST_MANAGE";
        }
        if (path.startsWith("/api/admin/avatars")) return "AVATAR_REVIEW";
        if (path.startsWith("/api/admin/stats")) return "STATS_VIEW";
        if (path.startsWith("/api/admin/categories")) return "TAXONOMY_MANAGE";
        if (path.startsWith("/api/admin/tags")) return "TAXONOMY_MANAGE";
        if (path.startsWith("/api/admin/announcements")) return "ANNOUNCEMENT_MANAGE";
        if (path.startsWith("/api/admin/courses")) return "COURSE_MANAGE";
        if (path.startsWith("/api/admin/exams")) return "EXAM_MANAGE";
        if (path.startsWith("/api/admin/doc-shares/capacity-requests"))
            return null;   // 扩容申请审批/驳回/列表：仅完全管理员（ADMIN 角色）
        if (path.startsWith("/api/admin/doc-shares")) {
            if ("DELETE".equals(method)) return "SHARE_DELETE";
            if ("PUT".equals(method)) return null;   // 直接设容量：仅完全管理员
            // 建空间申请 / 扩容申请的「审批、驳回」是写操作，不能按只读的 SHARE_VIEW 放行，
            // 与扩容审批一致收归 ADMIN 角色。
            if ("POST".equals(method) && (path.contains("/create-requests/") || path.contains("/capacity-requests/"))) {
                return null;
            }
            return "SHARE_VIEW";
        }
        if (path.startsWith("/api/admin/features")) return "CORE_MAINTAIN";
        if (path.startsWith("/api/admin/settings")) return "CORE_MAINTAIN";
        if (path.startsWith("/api/admin/banned-words")) return "BANNED_WORDS_MANAGE";
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
