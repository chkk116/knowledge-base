/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 一次性票据消费回归：getAndDelete 在内存兜底下也必须严格只成功一次。纯 Mockito，不依赖 Redis。 */
class KvStoreTest {

    private KvStore kv;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<StringRedisTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        RuntimeFlags flags = mock(RuntimeFlags.class);
        when(flags.redisEnabled()).thenReturn(false);   // 强制走内存兜底
        kv = new KvStore(provider, flags);
    }

    @Test
    @DisplayName("票据只能被消费一次：第二次返回 null")
    void ticketConsumedOnce() {
        kv.put("sso:ticket:abc", "jwt-token", Duration.ofSeconds(30));
        assertEquals("jwt-token", kv.getAndDelete("sso:ticket:abc"));
        assertNull(kv.getAndDelete("sso:ticket:abc"));
        assertNull(kv.get("sso:ticket:abc"));
    }

    @Test
    @DisplayName("过期票据不能被消费，未存放的 key 返回 null")
    void expiredOrMissingTicket() {
        kv.put("sso:ticket:old", "expired", Duration.ofMillis(-1));
        assertNull(kv.getAndDelete("sso:ticket:old"));
        assertNull(kv.getAndDelete("sso:ticket:missing"));
    }

    @Test
    @DisplayName("普通取值不影响后续消费")
    void plainGetDoesNotConsume() {
        kv.put("sso:ticket:abc", "jwt-token", Duration.ofSeconds(30));
        assertEquals("jwt-token", kv.get("sso:ticket:abc"));
        assertEquals("jwt-token", kv.getAndDelete("sso:ticket:abc"));
    }
}
