/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 密码工具（安全加固版）：
 *
 * 新格式：pbkdf2$iter$saltB64$hashB64
 *   - PBKDF2WithHmacSHA256，21 万次迭代（纯 JDK 实现，无需新增依赖，离线环境可用）
 *   - 128 位随机盐，256 位派生密钥，恒定时间比较
 * 旧格式：salt(16字节hex) + ":" + sha256hex —— 仍可校验（存量用户可登录），
 *   登录成功后由 AuthService 透明重哈希升级为新格式（transparent upgrade）。
 */
public final class PasswordUtil {

    private PasswordUtil() {}

    private static final int ITERATIONS = 210_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final String PREFIX = "pbkdf2$";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return toHex(salt);
    }

    public static String hash(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((salt + password).getBytes(StandardCharsets.UTF_8));
            return toHex(h);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** 生成可存储的密文（新格式：PBKDF2） */
    public static String encode(String rawPassword) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            new SecureRandom().nextBytes(salt);
            byte[] key = pbkdf2(rawPassword, salt, ITERATIONS);
            return PREFIX + ITERATIONS + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(key);
        } catch (Exception e) {
            throw new RuntimeException("密码哈希失败", e);
        }
    }

    /** 校验明文密码是否匹配存储值（兼容旧 salt:sha256 与新 pbkdf2 格式） */
    public static boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null || stored.isEmpty()) return false;
        if (stored.startsWith(PREFIX)) {
            try {
                String[] p = stored.split("\\$");
                int iter = Integer.parseInt(p[1]);
                byte[] salt = Base64.getDecoder().decode(p[2]);
                byte[] expected = Base64.getDecoder().decode(p[3]);
                byte[] actual = pbkdf2(rawPassword, salt, iter);
                return MessageDigest.isEqual(expected, actual);
            } catch (Exception e) {
                return false;
            }
        }
        // 旧格式：salt:sha256hex
        int idx = stored.indexOf(':');
        if (idx < 0) return false;
        String salt = stored.substring(0, idx);
        String expected = stored.substring(idx + 1);
        byte[] a = toBytes(hash(rawPassword, salt));
        byte[] b = toBytes(expected);
        return MessageDigest.isEqual(a, b);
    }

    /** 存储值是否已是新格式（用于登录后判断是否需要透明升级） */
    public static boolean isUpgraded(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt, iterations, KEY_BITS);
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).getEncoded();
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte x : data) {
            sb.append(HEX[(x >> 4) & 0xf]);
            sb.append(HEX[x & 0xf]);
        }
        return sb.toString();
    }

    private static byte[] toBytes(String s) {
        int n = s.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) ((digit(s.charAt(2 * i)) << 4) | digit(s.charAt(2 * i + 1)));
        }
        return out;
    }

    private static int digit(char c) {
        int d = Character.digit(c, 16);
        if (d < 0) throw new RuntimeException("bad hex");
        return d;
    }
}
