/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.security;

import java.util.Set;

/**
 * 注册/改密的密码强度校验。
 *
 * 背景：原先只有「长度 ≥ 6」，实测 6 位纯数字可以直接注册成功（123456），
 * 加上种子账号曾用固定口令，等于把「弱口令 + 可枚举」两件事凑齐了。
 *
 * 规则（不追溯存量账号，只在设置新密码时校验）：
 *   · 长度 ≥ 8
 *   · 至少包含两类字符（字母 / 数字 / 符号）
 *   · 不在常见弱口令表里，也不等于用户名
 */
public final class PasswordPolicy {

    private PasswordPolicy() { }

    /** 常见弱口令（小写比对）：够用即可，不追求大字典 */
    private static final Set<String> WEAK = Set.of(
            "12345678", "123456789", "1234567890", "password", "password1", "passw0rd",
            "qwerty123", "abc12345", "admin123", "admin888", "administrator",
            "11111111", "88888888", "66666666", "a1234567", "aa123456",
            "iloveyou", "welcome1", "letmein1", "test1234", "kb123456"
    );

    /** 校验并返回错误提示；通过时返回 null */
    public static String check(String password, String username) {
        if (password == null || password.length() < 8) {
            return "密码至少 8 位";
        }
        if (password.length() > 72) {
            return "密码过长（最多 72 位）";
        }
        int kinds = 0;
        if (password.matches(".*[A-Za-z].*")) kinds++;
        if (password.matches(".*\\d.*")) kinds++;
        if (password.matches(".*[^A-Za-z0-9].*")) kinds++;
        if (kinds < 2) {
            return "密码需包含字母、数字、符号中的至少两类";
        }
        String lower = password.toLowerCase();
        if (WEAK.contains(lower)) {
            return "该密码过于常见，请换一个";
        }
        if (username != null && !username.isBlank()
                && (lower.equals(username.toLowerCase()) || lower.equals(username.toLowerCase() + "123"))) {
            return "密码不能与用户名相同或仅在其后加数字";
        }
        // 连续重复字符（aaaaaaaa、11111111 这类已在弱口令表里，这里兜更长的）
        if (password.matches("(.)\\1{7,}")) {
            return "密码不能是同一个字符重复";
        }
        return null;
    }
}
