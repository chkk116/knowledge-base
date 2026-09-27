/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AI 平台 API Key 的加密存储与脱敏。
 *
 * - 落库：Base64(iv || AES-GCM(plaintext))，解密失败可识别（主密钥轮换后旧数据不可解）；
 * - 主密钥：由环境变量 KB_AI_MASTER_KEY 提供（未设置时回落到开发默认并在启动时告警）；
 *   轮换该变量即失效全部已存 Key，管理员需重新填写，避免「偷换密钥静默解出旧 Key」。
 * - 对外只回显脱敏形式（首尾保留、中间打码），明文仅在服务端调用时使用。
 *
 * 用 javax.crypto（JDK 内置）而非 Spring Security Crypto，避免为一个工具类引入依赖。
 */
@Component
public class AiKeyCipher {

    private static final Logger log = LoggerFactory.getLogger(AiKeyCipher.class);

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;      // GCM 推荐 12 字节 IV
    private static final int TAG_BITS = 128;   // 认证标签长度

    private final SecretKeySpec key;

    public AiKeyCipher(@Value("${kb.ai.master-key:${KB_AI_MASTER_KEY:}}") String masterKey) {
        String mk = masterKey == null || masterKey.isBlank()
                ? "kb-dev-master-key-change-me-in-production"
                : masterKey.trim();
        if (masterKey == null || masterKey.isBlank()) {
            log.warn("未检测到 KB_AI_MASTER_KEY，AI 模型 API Key 使用开发默认密钥加密；"
                    + "生产环境请设置该环境变量（换值后需重新填写 API Key）");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(mk.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("AI Key 加密密钥派生失败", e);
        }
    }

    /** 明文 → 密文；空值原样返回 */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return "";
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LEN + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_LEN);
            System.arraycopy(sealed, 0, out, IV_LEN, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    /** 密文 → 明文；主密钥变更后解不开时返回空（由上层提示管理员重新填写），不影响页面可用性 */
    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) return "";
        try {
            byte[] in = Base64.getDecoder().decode(encrypted);
            if (in.length <= IV_LEN) return "";
            byte[] iv = Arrays.copyOfRange(in, 0, IV_LEN);
            byte[] sealed = Arrays.copyOfRange(in, IV_LEN, in.length);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("API Key 解密失败（主密钥已变更或数据损坏），请管理员重新填写");
            return "";
        }
    }

    /** 脱敏展示：便于管理员确认填的是哪个 Key，又不泄露完整内容 */
    public String mask(String plain) {
        if (plain == null || plain.isBlank()) return "";
        if (plain.length() <= 8) return plain.substring(0, 1) + "****";
        return plain.substring(0, 4) + "****" + plain.substring(plain.length() - 4);
    }
}
