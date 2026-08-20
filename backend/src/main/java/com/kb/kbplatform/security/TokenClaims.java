package com.kb.kbplatform.security;

/** 令牌解析后的声明 */
public record TokenClaims(long userId, String username, String role, String permissions, long exp, long tokenVersion) {

    /** 是否拥有指定权限码。
     *  ADMIN 角色隐式拥有全部；权限串为空则无任何细粒度权限；
     *  权限串含通配 * 视为拥有全部；否则按逗号分隔逐项匹配。 */
    public boolean hasPerm(String code) {
        if ("ADMIN".equals(role)) return true;
        if (permissions == null || permissions.isEmpty()) return false;
        if (permissions.contains("*")) return true;
        for (String p : permissions.split(",")) {
            if (p.trim().equals(code)) return true;
        }
        return false;
    }
}
