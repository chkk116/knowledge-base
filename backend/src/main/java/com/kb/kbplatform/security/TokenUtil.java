/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 轻量令牌（避免引入第三方 JWT 依赖）：
 *   token = base64url(payload) + "." + HmacSHA256(secret, payload)
 *   payload = username + "|" + role + "|" + exp(ms)
 */
public final class TokenUtil {

    private TokenUtil() {}

    public static String generate(String secret, long userId, String username, String role, String permissions, long ttlMillis, long tokenVersion) {
        long exp = System.currentTimeMillis() + ttlMillis;
        // payload = username|role|exp|userId|permissions|tokenVersion；末尾两段做了兼容回退
        String payload = b64url(username + "|" + role + "|" + exp + "|" + userId + "|" + (permissions == null ? "" : permissions) + "|" + tokenVersion);
        return payload + "." + hmac(secret, payload);
    }

    public static TokenClaims parse(String secret, String token) {
        if (token == null || token.indexOf('.') < 0) throw new IllegalArgumentException("invalid token");
        int dot = token.indexOf('.');
        String payload = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        byte[] expected = hmac(secret, payload).getBytes(StandardCharsets.UTF_8);
        byte[] actual = sig.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) throw new IllegalArgumentException("bad signature");
        String decoded = new String(b64urlDecode(payload), StandardCharsets.UTF_8);
        String[] f = decoded.split("\\|", 7);
        // 只接受 6 段新格式（username|role|exp|userId|permissions|tokenVersion）。
        // 旧 5 段令牌没有版本号、回退成 0，一旦某账号 tokenVersion 恰好为 0，改密也失效不了（白盒 T8）。
        // 拒绝旧格式的代价是旧客户端需要重登一次，换的是「改密/重置后旧令牌必然失效」。
        if (f.length < 6) throw new IllegalArgumentException("bad payload");
        long exp = Long.parseLong(f[2]);
        if (exp < System.currentTimeMillis()) throw new IllegalArgumentException("token expired");
        long userId = f.length > 3 && !f[3].isEmpty() ? Long.parseLong(f[3]) : 0;
        // 第 5 段 permissions：旧版 4 段 token（无权限）回退为空串
        String permissions = f.length > 4 ? f[4] : "";
        // 第 6 段 tokenVersion：旧版 5 段 token（无版本）回退为 0
        long tokenVersion = f.length > 5 && !f[5].isEmpty() ? Long.parseLong(f[5]) : 0;
        return new TokenClaims(userId, f[0], f[1], permissions, exp, tokenVersion);
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] b64urlDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
