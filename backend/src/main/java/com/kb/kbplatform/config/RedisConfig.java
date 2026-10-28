/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.cache.Cache.ValueRetrievalException;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;

/**
 * Redis 缓存接入（可选）。
 *
 * 关键设计：整体受 {@code kb.redis.enabled} 控制。
 * - enabled=false（默认）：本配置类完全不加载，@EnableCaching 不开启，
 *   所有 @Cacheable/@CacheEvict 注解被 Spring 静默忽略，接口行为退化为直查数据库，
 *   与引入 Redis 之前完全一致（沿用项目里 Meilisearch 的"可选中间件"模式）。
 * - enabled=true：注册 Lettuce 连接工厂 + RedisCacheManager，缓存开始生效。
 *
 * 连接与 TTL 全部从 application.yml 的 kb.redis.* 读取，部署时无需改代码。
 */
@Configuration
@ConditionalOnProperty(name = "kb.redis.enabled", havingValue = "true")
@EnableCaching
public class RedisConfig {

    @Bean
    @Primary
    public LettuceConnectionFactory redisConnectionFactory(
            @Value("${kb.redis.host:127.0.0.1}") String host,
            @Value("${kb.redis.port:6379}") int port,
            @Value("${kb.redis.password:}") String password,
            @Value("${kb.redis.database:0}") int database) {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration();
        cfg.setHostName(host);
        cfg.setPort(port);
        if (password != null && !password.isBlank()) cfg.setPassword(password);
        cfg.setDatabase(database);
        return new LettuceConnectionFactory(cfg);
    }

    @Bean
    public CacheManager cacheManager(
            LettuceConnectionFactory factory,
            @Value("${kb.redis.ttl-minutes:30}") long ttlMinutes,
            com.kb.kbplatform.service.RuntimeFlags flags) {
        RedisCacheConfiguration cc = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(ttlMinutes))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.json()))
                .disableCachingNullValues();
        CacheManager redis = RedisCacheManager.builder(factory).cacheDefaults(cc).build();
        // 包一层：后台关掉 Redis 后，@Cacheable 立刻退化成「永远不命中、也不写入」
        return new ToggleCacheManager(redis, flags);
    }

    /**
     * 可按运行时开关旁路的缓存管理器。
     *
     * 关掉时返回一个空缓存：读永远 miss、写直接丢弃 —— 等价于 `@EnableCaching` 没开，
     * 也就是 `kb.redis.enabled=false` 启动时的行为，但**不需要重启**。
     */
    static final class ToggleCacheManager implements CacheManager {
        private final CacheManager delegate;
        private final com.kb.kbplatform.service.RuntimeFlags flags;
        private final Cache noop = new NoOpCache();

        ToggleCacheManager(CacheManager delegate, com.kb.kbplatform.service.RuntimeFlags flags) {
            this.delegate = delegate;
            this.flags = flags;
        }

        @Override
        public Cache getCache(String name) {
            return flags.redisEnabled() ? delegate.getCache(name) : noop;
        }

        @Override
        public java.util.Collection<String> getCacheNames() {
            return flags.redisEnabled() ? delegate.getCacheNames() : java.util.List.of();
        }
    }

    /** 什么都不存的缓存：读 miss、写丢弃、清空无操作 */
    static final class NoOpCache implements Cache {
        @Override public String getName() { return "noop"; }
        @Override public Object getNativeCache() { return this; }
        @Override public ValueWrapper get(Object key) { return null; }
        @Override public <T> T get(Object key, Class<T> type) { return null; }
        @Override public <T> T get(Object key, java.util.concurrent.Callable<T> loader) throws ValueRetrievalException {
            try {
                return loader.call();
            } catch (Exception e) {
                throw new ValueRetrievalException(key, loader, e);
            }
        }
        @Override public void put(Object key, Object value) { }
        @Override public void evict(Object key) { }
        @Override public void clear() { }
    }
}
