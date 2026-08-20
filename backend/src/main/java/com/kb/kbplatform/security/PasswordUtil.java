package com.kb.kbplatform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 密码工具：随机 salt + SHA-256（256 位）。
 * 存储格式： salt(16字节hex) + ":" + sha256hex(64字符)
 */
public final class PasswordUtil {

    private PasswordUtil() {}

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

    /** 生成可存储的密文：salt:sha256hex */
    public static String encode(String rawPassword) {
        String salt = generateSalt();
        return salt + ":" + hash(rawPassword, salt);
    }

    /** 校验明文密码是否匹配存储值 */
    public static boolean matches(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) return false;
        int idx = stored.indexOf(':');
        if (idx < 0) return false;
        String salt = stored.substring(0, idx);
        String expected = stored.substring(idx + 1);
        byte[] a = toBytes(hash(rawPassword, salt));
        byte[] b = toBytes(expected);
        return MessageDigest.isEqual(a, b);
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
