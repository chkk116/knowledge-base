/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
        if (COMPROMISED.equalsIgnoreCase(v)) {
            throw new IllegalStateException(
                    "检测到已泄漏的默认令牌密钥（kb-dev-secret-...），拒绝启动。" +
                    "请通过环境变量 KB_TOKEN_SECRET 注入不少于 32 位的随机串后重启。");
        }
        if (v.isEmpty()) {
            byte[] rnd = new byte[32];
            new SecureRandom().nextBytes(rnd);
            v = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);
            log.warn("[安全] 未配置 KB_TOKEN_SECRET，已生成临时随机令牌密钥（仅适用于本地开发）：" +
                    "重启后所有登录态失效。生产环境务必显式注入 KB_TOKEN_SECRET。");
        } else if (v.length() < 32) {
            log.warn("[安全] KB_TOKEN_SECRET 长度不足 32 位，建议更换为更长的随机串。");
        }
        this.secret = v;
    }

    public String get() {
        return secret;
    }
}
