/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import java.util.Set;

/**
 * 细粒度权限码白名单（单一事实来源）。
 * 旧实现散落在 AdminService.PERM_WHITELIST，现集中于此，供 AdminService / OrgService 共用，
 * 新增权限码时只改这一处即可。
 */
public final class Perm {

    public static final Set<String> WHITELIST = Set.of(
            "USER_MANAGE", "DOC_REVIEW", "POST_MANAGE", "STATS_VIEW", "BLACKLIST_MANAGE", "TAXONOMY_MANAGE",
            "ANNOUNCEMENT_MANAGE", "COURSE_MANAGE", "EXAM_MANAGE", "AI_MANAGE",
            "SHARE_CREATE", "SHARE_VIEW", "SHARE_DELETE", "SHARE_CAPACITY", "CORE_MAINTAIN",
            // 站点设置细分（2026-10-28）：原来整块只有 CORE_MAINTAIN 一个码，勾了等于把 8 个页面全给出去。
            // CORE_MAINTAIN 保留为「站点设置（全部）」总码，老角色不受影响。
            "SITE_FEATURE_MANAGE", "SITE_SETTINGS_MANAGE", "SSO_MANAGE", "SYSTEM_STATUS_VIEW",
            "DOC_EXPORT", "BANNED_WORDS_MANAGE", "ASSET_MANAGE", "AVATAR_REVIEW",
            "AUDIT_VIEW", "LOGIN_LOG_VIEW",   // 操作审计 / 登录日志：独立只读权限（2026-10-06 从 ADMIN_VIEW 拆出）
            "ADMIN_VIEW", "DEMO_MODE"
    );

    /**
     * 演示权限码（2026-10-05）。语义：持有该码的账号为「演示账号」——
     * 可查看后台几乎所有页面（通常与绝大部分真实权限码一起授予），
     * 但**一切会改动数据的写操作被服务端拒绝**（返回 403 + `{"error":"演示账户不能执行该操作","demo":true}`），
     * 前端据此弹出统一提示、把编辑入口降级为只读。
     * 注意：DEMO_MODE 本身不是一种「权限」，而是「只读约束」标记，
     * 因此它**不参与** isAdmin() 的「覆盖全集即视同 ADMIN」判定（见 ADMIN_PERM_SET），
     * 否则演示号勾满权限后会变成全权管理员，约束形同虚设。
     */
    public static final String DEMO_MODE = "DEMO_MODE";

    /**
     * 「后台只读浏览」权限码（2026-10-05）。用于原本只有 ADMIN 角色能访问的**只读**管理接口
     * （操作审计、登录日志、附件列表、检索测试、共享空间列表等），使非 ADMIN 的演示账号也能打开查看。
     * 注意：它**只授予读**——写操作由各控制器单独校验（AuthFilter 也会拦掉演示号的写请求），
     * 不会因为持有 ADMIN_VIEW 而获得任何改动数据的能力。
     */
    public static final String ADMIN_VIEW = "ADMIN_VIEW";

    /**
     * 操作审计 / 登录日志的独立只读权限码（2026-10-06）。
     * 从 ADMIN_VIEW 里拆出来，便于在「角色管理 → 系统与运营」里按需单独授予。
     * 注意：2026-10-05 的严格化已去掉 ADMIN_VIEW 兜底——旧角色只勾 ADMIN_VIEW 将无法访问
     * 审计/登录日志，需显式授予本码（AuthFilter 映射为单码，不走「新码|ADMIN_VIEW」兼容）。
     */
    public static final String AUDIT_VIEW = "AUDIT_VIEW";
    public static final String LOGIN_LOG_VIEW = "LOGIN_LOG_VIEW";

    /**
     * 演示账号允许的写操作白名单：改密码/退出这类不涉及业务数据变更的操作必须放行，
     *  否则演示号连自己密码都改不了。
     *  2026-10-06 起放开「AI 问答」：提问（/ai/ask）、建/删自己的会话（/ai/conversations）、
     *  改个人偏好（/ai/preferences）——这些只产生演示者个人的新数据，不触碰任何现有业务数据；
     *  其余写操作依旧全部拦截。
     *  ⚠️ /api/auth/change-password 已于 2026-10-06 移出：公开演示号的密码若可被任何访客修改，
     *  等于把演示账号拱手交给第一个改它的人（外部安全审查）。 */
    public static final Set<String> DEMO_WRITE_ALLOW = Set.of(
            "/api/ai/ask",
            "/api/ai/conversations",
            "/api/ai/preferences"
    );

    /** isAdmin() 判定用的「管理员权限全集」：= WHITELIST 去掉 DEMO_MODE 这类约束标记。
     *  即「拿到全部真实管理权限」才算视同 ADMIN，持有演示标记不提升为管理员。 */
    public static final Set<String> ADMIN_PERM_SET = WHITELIST.stream()
            .filter(c -> !DEMO_MODE.equals(c))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private Perm() {
    }

    /** 是否演示账号：权限串含 DEMO_MODE */
    public static boolean isDemo(String permissions) {
        if (permissions == null || permissions.isBlank()) return false;
        for (String p : permissions.split(",")) {
            if (DEMO_MODE.equalsIgnoreCase(p.trim())) return true;
        }
        return false;
    }

    /** 演示账号是否允许执行该写操作（按路径判定） */
    public static boolean demoWriteAllowed(String path) {
        if (path == null) return false;
        for (String allow : DEMO_WRITE_ALLOW) {
            if (path.equals(allow) || path.startsWith(allow + "/")) return true;
        }
        return false;
    }

    /** 校验单个码是否在白名单内 */
    public static boolean valid(String code) {
        return code != null && WHITELIST.contains(code.trim().toUpperCase());
    }
}
