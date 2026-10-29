/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 令牌签名密钥的唯一来源（AuthFilter / AuthService / 协同中继共用同一把，避免各处 @Value 取值不一致）。
 *
 * 安全要求：
 *  1) 配置文件里不再内置任何默认密钥（application.yml 只写 ${KB_TOKEN_SECRET:}），
 *     避免「拿到代码就能离线伪造管理员令牌」的完全失陷风险；
 *  2) 若有人把历史上泄漏过的弱默认串写回配置 → 直接拒绝启动（fail-fast）；
 *  3) 未配置时（本地开发）生成一次性强随机密钥并在日志里告警：安全但重启后令牌失效，
 *     生产环境必须显式注入 KB_TOKEN_SECRET（≥32 位随机串）。
 */
@Component
public class TokenSecretProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenSecretProvider.class);

    /** 历史上写死在 application.yml 里、已随仓库外泄的弱密钥：出现即拒绝启动 */
    private static final String COMPROMISED = "kb-dev-secret-please-change-this-long-string-2026";

    private final String secret;

    public TokenSecretProvider(@Value("${kb.security.token-secret:}") String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v != null && v.toUpperCase().startsWith("CHANGE_ME")) {
            throw new IllegalStateException(
                    "检测到 deploy/env.prod 模板占位符（CHANGE_ME_...）被原样用作令牌密钥，拒绝启动。" +
                    "请替换 KB_TOKEN_SECRET 为不少于 32 位的随机串后重启。");
        }
        if (COMPROMISED.equalsIgnoreCase(v)) {
            throw new IllegalStateException(
                    "检测到已泄漏的默认令牌密钥（kb-dev-secret-...），拒绝启动。" +
                    "请通过环境变量 KB_TOKEN_SECRET 注入不少于 32 位的随机串后重启。");
        }
        if (v.isEmpty()) {
            v = loadOrCreateLocalSecret();
            log.warn("[安全] 未配置 KB_TOKEN_SECRET，已使用本地持久化的随机密钥（{}）：" +
                    "本地重启后登录态保持有效。生产环境务必显式注入 KB_TOKEN_SECRET。", localSecretPath());
        } else if (v.length() < 32) {
            log.warn("[安全] KB_TOKEN_SECRET 长度不足 32 位，建议更换为更长的随机串。");
        }
        this.secret = v;
    }

    public String get() {
        return secret;
    }

    /** 本地持久化密钥文件（固定在用户目录，避免与工作目录歧义；仅本地开发用，绝不入库） */
    private static File localSecretFile() {
        return new File(System.getProperty("user.home"), ".kb-platform" + File.separator + "token-secret");
    }
    private static String localSecretPath() {
        try { return localSecretFile().getAbsolutePath(); } catch (Exception e) { return "user.home/.kb-platform/token-secret"; }
    }

    /** 未配置 KB_TOKEN_SECRET 时：优先读本地持久化密钥（跨重启稳定，登录态/协同中继不受重启影响），没有则生成并写盘 */
    private static String loadOrCreateLocalSecret() {
        File f = localSecretFile();
        try {
            if (f.isFile()) {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (s.length() >= 32) return s;
            }
            byte[] rnd = new byte[32];
            new SecureRandom().nextBytes(rnd);
            String s = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
            java.nio.file.Files.createDirectories(f.getParentFile().toPath());
            java.nio.file.Files.writeString(f.toPath(), s);
            return s;
        } catch (Exception e) {
            log.warn("[安全] 本地持久化令牌密钥读写失败（{}），退回一次性随机密钥", e.getMessage());
            byte[] rnd = new byte[32];
            new SecureRandom().nextBytes(rnd);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
        }
    }
}
