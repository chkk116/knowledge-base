/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

/**
 * 用户名合法性策略（注册 / 管理员建号共用，单一事实来源）。
 *
 * 关键约束：用户名不能包含竖线 '|'。
 * 令牌载荷形如 username|role|exp|userId|permissions|tokenVersion（见 TokenUtil），
 * 用户名里出现分隔符会让 TokenUtil.parse 的分段整体错位——后续每次请求解析令牌都失败（401），
 * 该账号将永久无法登录（F17）。因此必须在写入时就拦住，而不是等登录时才发现。
 */
public final class UsernamePolicy {

    public static final int MAX_LENGTH = 64;

    private UsernamePolicy() {
    }

    /**
     * 校验并规整用户名。
     *
     * @param raw 原始输入
     * @return 去空白后的用户名
     * @throws IllegalArgumentException 不合法时抛出（全局异常处理会转成 400 + 该消息）
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String name = raw.trim();
        if (name.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("用户名过长（最多 " + MAX_LENGTH + " 个字符）");
        }
        if (name.contains("|")) {
            throw new IllegalArgumentException("用户名不能包含竖线字符「|」");
        }
        // 控制字符会污染令牌载荷与日志展示，一并拒绝
        for (int i = 0; i < name.length(); i++) {
            if (Character.isISOControl(name.charAt(i))) {
                throw new IllegalArgumentException("用户名不能包含控制字符");
            }
        }
        return name;
    }
}
