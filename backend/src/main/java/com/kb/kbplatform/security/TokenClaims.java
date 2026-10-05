/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

/** 令牌解析后的声明 */
public record TokenClaims(long userId, String username, String role, String permissions, long exp, long tokenVersion) {

    /** 是否拥有指定权限码。
     *  ADMIN 角色隐式拥有全部；权限串为空则无任何细粒度权限；
     *  权限串含通配 * 视为拥有全部；否则按逗号分隔逐项匹配。
     *  兼容：持有旧码 DOC_SHARE 视为拥有全部 SHARE_* 细分权限（权限码拆分前的存量授权平滑迁移）。 */
    public boolean hasPerm(String code) {
        if ("ADMIN".equals(role)) return true;
        if (permissions == null || permissions.isEmpty()) return false;
        if (permissions.contains("*")) return true;
        for (String p : permissions.split(",")) {
            String t = p.trim();
            if (t.equals(code)) return true;
            if ("DOC_SHARE".equals(t) && code != null && code.startsWith("SHARE_")) return true;
        }
        return false;
    }

    /**
     * 是否覆盖全部指定权限码。
     * 语义（2026-09-12 拍板）：「覆盖」——某角色权限码覆盖 Perm.WHITELIST 全集时，
     * 持有该角色的用户视同完全管理员（AuthFilter 的 /api/admin/** 门禁按 ADMIN 放行），
     * 而不是"仍是普通角色、部分接口 403"的半吊子状态，避免权限认知混乱。
     */
    public boolean hasAllPerms(java.util.Collection<String> codes) {
        if (codes == null || codes.isEmpty()) return false;
        for (String c : codes) if (!hasPerm(c)) return false;
        return true;
    }

    /**
     * 完全管理员判定（覆盖语义）：role=ADMIN，或权限码覆盖**管理员权限全集**（Perm.ADMIN_PERM_SET）。
     * 注意全集已剔除 DEMO_MODE 这类「只读约束标记」。**但演示账号是个例外**：
     * 演示角色通常勾满全部真实权限，若这里不先把 DEMO_MODE 排除，演示号会被判成 ADMIN 而绕过只读约束。
     * 这违反直觉（role 仍是 USER），因此 isAdmin() 对演示号恒为 false。
     * 供各控制器统一使用；注意 AdminUserController 里「操作 ADMIN 账号本身」的保护
     * 刻意不用本方法——把人提升为/降级出 ADMIN、停用管理员账号，只允许真实 ADMIN。
     */
    public boolean isAdmin() {
        if (isDemo()) return false;   // 演示账号永不算管理员（否则只读约束失效）
        return "ADMIN".equals(role) || hasAllPerms(com.kb.kbplatform.security.Perm.ADMIN_PERM_SET);
    }

    /** 是否演示账号（携带 DEMO_MODE 只读约束标记）；所有写操作将被服务端拒绝。
     *  注意：只看权限串，不看 isAdmin()——真 ADMIN 若被误加 DEMO_MODE 也应按演示处理更安全，
     *  但实践上 ADMIN 的 permissions 段通常为空，不会带该标记。 */
    public boolean isDemo() {
        return com.kb.kbplatform.security.Perm.isDemo(permissions);
    }
}
