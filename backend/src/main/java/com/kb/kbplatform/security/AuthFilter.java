/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

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
        // 路径规范化止血：Spring MVC 路由匹配时会百分号解码并忽略 ; 矩阵参数，而本过滤器
        // 依赖原始字符串前缀判断——/api/%61dmin/ai/models、/api;x=1/admin/ai/models 可绕过
        // 管理接口鉴权（2026-10-06 外部安全审查）。含这些特征的原始 URI 直接 400。
        if (path.contains("%") || path.contains(";") || path.contains("//")
                || path.contains("/./") || path.contains("/..")) {
            res.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String method = req.getMethod();

        // CORS 预检（OPTIONS）必须直接放行：按规范预检不带凭证，浏览器只关心响应头。
        // 若不放行会被下面的鉴权判成 401 → 浏览器直接判定跨域请求失败（前端显示「服务器异常」）。
        // App 改为内置资源模式后页面源是 https://localhost，所有 JSON 请求都变成跨域并触发预检，
        // 表现为「App 里验证码怎么都过不了 + 服务器异常」（2026-10-06 定位）。
        if ("OPTIONS".equalsIgnoreCase(method)) {
            chain.doFilter(request, response);
            return;
        }

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
            // 功能开关状态/水印配置/外观配置（站点 Logo、资料页背景）只读接口对所有登录用户开放；写入仍需细粒度权限。
            boolean loginOnlyRead = "GET".equals(method)
                    && ("/api/admin/features/map".equals(path) || "/api/admin/settings/watermark".equals(path)
                        || "/api/admin/settings/appearance".equals(path));
            if (!loginOnlyRead) {
                String need = adminPermRequired(path, method);
                // 全权限覆盖视同 ADMIN，包括没有细粒度权限码的仅管理员接口。
                boolean roleOk = claims.isAdmin();
                // need 支持「A|B」形式（任一命中即通过）：用于新旧权限码兼容过渡
                boolean permOk = need != null
                        && java.util.Arrays.stream(need.split("\\|")).anyMatch(claims::hasPerm);
                if (!roleOk && !permOk) {
                    send403(res);
                    return;
                }
            }
        }
        // 演示账号（DEMO_MODE）：放行一切读取，但拦截所有会改动数据的写操作。
        // 判定放在权限校验之后，保证「没权限的接口」仍按 403 处理，演示提示只针对本可执行却被只读约束拦下的操作。
        if (!publicRequest && claims != null && claims.isDemo() && isWriteMethod(method)
                && !Perm.demoWriteAllowed(path)) {
            sendDemo403(res);
            return;
        }
        // 演示账号禁止「批量取数」：导出类与检索测试都是 GET，上面的写方法拦截覆盖不到。
        // 公开的演示凭据 + 导出接口 = 任何访客都能把数据拉走（2026-10-06 外部安全审查）。
        if (!publicRequest && claims != null && claims.isDemo() && isDemoBulkReadBlocked(path)) {
            send403(res);
            return;
        }
        if (claims != null) req.setAttribute("kbUser", claims);   // 供控制器读取当前用户
        chain.doFilter(request, response);
    }

    /**
     * 演示账号禁止的批量读取接口：附件/资产导出、文档导出、检索测试。
     * 这些都是 GET，写方法拦截覆盖不到；演示凭据公开，等于任何访客都能拉走数据。
     * 前缀匹配保持宽松（endsWith("/export")），新增导出端点默认即被覆盖。
     */
    private boolean isDemoBulkReadBlocked(String path) {
        if (path == null) return false;
        return path.endsWith("/export") || path.startsWith("/api/admin/search/test");
    }

    /** 是否为会改动数据的写方法（GET/HEAD/OPTIONS 放行） */
    private boolean isWriteMethod(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
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
        // 角色/部门管理与用户管理同域（控制器内 requirePerm 也是 USER_MANAGE）：
        // 显式映射后，持有 DEMO_MODE 的演示账号（isAdmin()=false）也能打开这两个页面查看。
        if (path.startsWith("/api/admin/roles")) return "USER_MANAGE";
        if (path.startsWith("/api/admin/departments")) return "USER_MANAGE";
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
        // AI 模型管理：细粒度权限 AI_MANAGE（2026-10-06 起可在角色管理里授予）。
        // 列表接口对 Key 一律脱敏（keyMasked）；演示号（DEMO_MODE）的写请求仍被上方统一拦截。
        // ⚠️ 仅放行 GET：AI_MANAGE = 「能看模型配置，改不了」——该控制器写接口（增删改/测试连接）
        // 无自查，历史上一度依赖本过滤器的 admin 兜底，放宽后非 GET 必须回归 null（仅完全管理员），
        // 否则 AI_MANAGE 持有者可改凭证/把 Key 发往任意 baseUrl（SSRF）。
        if (path.startsWith("/api/admin/ai")) {
            return "GET".equalsIgnoreCase(method) ? "AI_MANAGE" : null;
        }
        // 原本仅 ADMIN 可访问的**只读**管理接口：映射到 ADMIN_VIEW，使演示账号（isAdmin()=false）
        // 也能打开查看。写操作仍由各控制器内的 isAdmin() 把守（且 AuthFilter 会拦演示号的写请求），
        // 因此这里只需在 GET 时放行。
        if ("GET".equals(method)) {
            // 操作审计 / 登录日志：**严格**按各自独立权限码判定（2026-10-06 二次调整）。
            // 不再回退 ADMIN_VIEW —— 否则「取消勾选也还能进」，权限形同虚设。
            if (path.startsWith("/api/admin/audit")) return "AUDIT_VIEW";
            if (path.startsWith("/api/admin/login-logs")) return "LOGIN_LOG_VIEW";
            if (path.startsWith("/api/admin/files")) return Perm.ADMIN_VIEW;
            if (path.startsWith("/api/admin/search")) return Perm.ADMIN_VIEW;
            if (path.startsWith("/api/admin/blacklist")) return Perm.ADMIN_VIEW;
        }
        return null;   // 其他管理接口仍仅 ADMIN 可访问
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

    /** 演示账号写操作拒绝：403 + demo 标记，前端据此弹出「演示账户不能…」提示而非普通无权提示 */
    private void sendDemo403(HttpServletResponse res) throws IOException {
        res.setStatus(HttpStatus.FORBIDDEN.value());
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"error\":\"演示账户不能执行该操作\",\"demo\":true}");
    }
}
