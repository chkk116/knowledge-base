/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户名策略 + 令牌载荷兼容性回归测试（F17）。
 * 令牌 payload = username|role|exp|userId|permissions|tokenVersion，用户名含竖线会让分段错位，
 * 解析时把 role 当成 exp → NumberFormatException → 该账号每次请求 401，等于永久无法登录。
 * 运行：mvn test -Dtest=UsernamePolicyTest
 */
class UsernamePolicyTest {

    @Test
    @DisplayName("含竖线的用户名被拒绝（否则令牌永远解析失败）")
    void rejectsPipe() {
        assertThrows(IllegalArgumentException.class, () -> UsernamePolicy.normalize("zhang|san"));
    }

    @Test
    @DisplayName("空 / 超长 / 控制字符用户名被拒绝")
    void rejectsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> UsernamePolicy.normalize("   "));
        assertThrows(IllegalArgumentException.class, () -> UsernamePolicy.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> UsernamePolicy.normalize("a".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> UsernamePolicy.normalize("zhang\nsan"));
    }

    @Test
    @DisplayName("合法用户名去空白后返回")
    void accepts() {
        assertEquals("zhangsan", UsernamePolicy.normalize("  zhangsan  "));
    }

    @Test
    @DisplayName("合规用户名的令牌可正常签发与解析")
    void tokenRoundTrip() {
        String secret = "test-secret";
        String token = TokenUtil.generate(secret, 7L, "zhangsan", "USER", "DOC_REVIEW", 3600_000L, 1L);
        TokenClaims c = TokenUtil.parse(secret, token);
        assertEquals("zhangsan", c.username());
        assertEquals("USER", c.role());
        assertEquals(7L, c.userId());
        assertTrue(c.hasPerm("DOC_REVIEW"));
    }

    @Test
    @DisplayName("反证：用户名带竖线时令牌解析失败（正是策略要拦下的情形）")
    void tokenWithPipeUnparsable() {
        String secret = "test-secret";
        String token = TokenUtil.generate(secret, 7L, "zhang|san", "USER", "", 3600_000L, 1L);
        assertThrows(IllegalArgumentException.class, () -> TokenUtil.parse(secret, token));
    }
}
