package com.kb.kbplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
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
            @Value("${kb.redis.ttl-minutes:30}") long ttlMinutes) {
        RedisCacheConfiguration cc = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(ttlMinutes))
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.string()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(RedisSerializer.json()))
                .disableCachingNullValues();
        return RedisCacheManager.builder(factory).cacheDefaults(cc).build();
    }
}
