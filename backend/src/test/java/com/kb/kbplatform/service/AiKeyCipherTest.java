/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AI Key 加密器回归测试：加解密往返 + 生产环境主密钥 fail-fast。
 * 纯单元测试，不依赖数据库 / 外部服务。运行：mvn test -Dtest=AiKeyCipherTest
 */
class AiKeyCipherTest {

    private static final String DEV_KEY = "kb-dev-master-key-change-me-in-production";
    private static final String STRONG_KEY = "unit-test-master-key-0123456789-abcdefghij";

    private static StandardEnvironment env(String... profiles) {
        StandardEnvironment e = new StandardEnvironment();
        if (profiles.length > 0) e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    @DisplayName("加解密往返：明文可解回，且不同次加密的密文不同（IV 随机）")
    void roundTrip() {
        AiKeyCipher c = new AiKeyCipher(STRONG_KEY, env());
        String plain = "sk-abcdef1234567890";
        String c1 = c.encrypt(plain);
        String c2 = c.encrypt(plain);
        assertEquals(plain, c.decrypt(c1));
        assertEquals(plain, c.decrypt(c2));
        assertNotEquals(c1, c2, "同一明文两次加密应因随机 IV 而不同");
    }

    @Test
    @DisplayName("空值：加密返回空串，不抛异常")
    void blankPlain() {
        AiKeyCipher c = new AiKeyCipher(STRONG_KEY, env());
        assertEquals("", c.encrypt(null));
        assertEquals("", c.encrypt("   "));
    }

    @Test
    @DisplayName("prod：缺主密钥 → 拒绝启动")
    void prodRejectsMissingKey() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AiKeyCipher("", env("prod")));
        assertTrue(e.getMessage().contains("KB_AI_MASTER_KEY"));
    }

    @Test
    @DisplayName("prod：显式写成开发默认密钥 → 同样拒绝启动")
    void prodRejectsDevKey() {
        assertThrows(IllegalStateException.class, () -> new AiKeyCipher(DEV_KEY, env("prod")));
    }

    @Test
    @DisplayName("prod：注入了足够强的主密钥 → 正常启动")
    void prodAcceptsStrongKey() {
        assertDoesNotThrow(() -> new AiKeyCipher(STRONG_KEY, env("prod")));
    }

    @Test
    @DisplayName("dev：缺主密钥 → 回落开发默认，不拒绝启动")
    void devFallsBack() {
        AiKeyCipher c = assertDoesNotThrow(() -> new AiKeyCipher("", env()));
        assertEquals("x", c.decrypt(c.encrypt("x")));
    }
}
