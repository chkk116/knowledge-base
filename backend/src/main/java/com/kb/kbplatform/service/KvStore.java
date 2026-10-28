/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

/**
 * 键值存储（Redis 优先，内存兜底）—— 2026-10-26。
 *
 * 为什么要有这一层：限流、第三方登录 state、练习判分会话原来都是**进程内 Map**，
 * 后端一重启就全没了（正在做的练习会判不了分），多实例也各自为政。
 *
 * 设计原则：**Redis 只是加速与共享，不是必需品**。
 * - 启动时探测一次，连不上就打一条 warn 然后全程走内存，行为与改造前一致；
 * - 运行中任何一次 Redis 异常 → 立刻切内存并记日志，30 秒后自动重试恢复；
 * - 所以「Redis 挂了」不会导致登录/练习不可用，只会退化成单实例语义。
 *
 * 内存兜底带 TTL（每次读时判过期），不会无界增长。
 */
@Service
public class KvStore {

    private static final Logger log = LoggerFactory.getLogger(KvStore.class);

    private final StringRedisTemplate redis;
    /** 运行时开关（后台可关 Redis） */
    private final RuntimeFlags flags;
    /** 内存兜底：key → {值, 过期毫秒} */
    private final Map<String, String[]> mem = new ConcurrentHashMap<>();
    /** 滑动窗口用的内存队列（限流） */
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    private volatile boolean redisUp = false;
    private volatile long nextProbeAt = 0L;

    private final ScheduledExecutorService purger = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kv-purger");
        t.setDaemon(true);
        return t;
    });

    public KvStore(ObjectProvider<StringRedisTemplate> provider, RuntimeFlags flags) {
        this.redis = provider.getIfAvailable();
        this.flags = flags;
    }

    @PostConstruct
    public void init() {
        probe();
        purger.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            // 内存兜底的过期时间存成字符串，比较前要转数字
            mem.entrySet().removeIf(e -> Long.parseLong(e.getValue()[1]) < now);
            windows.entrySet().removeIf(e -> {
                Long last = e.getValue().peekLast();
                return last == null || now - last > 60 * 60 * 1000L;
            });
        }, 5, 5, TimeUnit.MINUTES);
    }

    /** 启动时探测；连不上就永久走内存（除非后面自动恢复） */
    private void probe() {
        // 后台「系统状态」里关掉 Redis 时，即使连得上也不用它 —— 热切换，不用重启
        if (!flags.redisEnabled()) {
            if (redisUp) log.info("Redis 已被后台关闭，键值存储切换到内存");
            redisUp = false;
            nextProbeAt = System.currentTimeMillis() + 5_000L;   // 5 秒后复查开关
            return;
        }
        if (redis == null) {
            log.info("未配置 Redis（或未引入依赖），键值存储使用内存兜底");
            redisUp = false;
            return;
        }
        try {
            // 一次读操作即可验证连通性（GET 不存在的 key 返回 null，不写数据）
            redis.opsForValue().get("__kb_probe__");
            redisUp = true;
            log.info("Redis 已连接，限流 / 登录 state / 练习会话使用共享存储");
        } catch (Exception e) {
            redisUp = false;
            log.warn("Redis 连接失败，自动降级为内存存储：{}", e.getMessage());
        }
    }

    public boolean redisReady() {
        maybeRecover();   // 后台刚打开开关时，这里顺手探一次，状态页才不会一直显示内存
        return flags.redisEnabled() && redisUp;
    }

    /** 当前实际后端，用于健康检查 / 后台展示 */
    public String backend() {
        maybeRecover();
        return (flags.redisEnabled() && redisUp) ? "redis" : "memory";
    }

    private void markDown(Exception e) {
        if (redisUp) log.warn("Redis 操作失败，临时降级为内存存储：{}", e.getMessage());
        redisUp = false;
        // 30 秒后再试一次，避免一直卡在内存
        nextProbeAt = System.currentTimeMillis() + 30_000L;
    }

    private void maybeRecover() {
        long now = System.currentTimeMillis();
        // 后台关掉 Redis：下一次操作立刻切内存（不能等 probe —— 连着的时候根本不会重探）
        if (!flags.redisEnabled()) {
            if (redisUp) log.info("Redis 已在后台关闭，键值存储改用内存");
            redisUp = false;
            nextProbeAt = now + 5_000L;   // 重新打开后最多 5 秒恢复
            return;
        }
        if (!redisUp && redis != null && now > nextProbeAt) {
            probe();
        }
    }

    // ---------------- 基础 KV ----------------

    public void put(String key, String value, Duration ttl) {
        maybeRecover();
        if (redisUp) {
            try {
                redis.opsForValue().set(key, value, ttl);
                return;
            } catch (Exception e) {
                markDown(e);
            }
        }
        mem.put(key, new String[]{value, String.valueOf(System.currentTimeMillis() + ttl.toMillis())});
    }

    /** 取值；不存在或已过期返回 null */
    public String get(String key) {
        maybeRecover();
        if (redisUp) {
            try {
                return redis.opsForValue().get(key);
            } catch (Exception e) {
                markDown(e);
            }
        }
        String[] e = mem.get(key);
        if (e == null) return null;
        if (Long.parseLong(e[1]) < System.currentTimeMillis()) {
            mem.remove(key);
            return null;
        }
        return e[0];
    }

    public void delete(String key) {
        maybeRecover();
        if (redisUp) {
            try {
                redis.delete(key);
                return;
            } catch (Exception ex) {
                markDown(ex);
            }
        }
        mem.remove(key);
    }

    /**
     * 滑动窗口限流：窗口内第 max+1 次返回 false。
     *
     * Redis 用 ZSET（member 唯一，score=时间戳）实现精确滑动窗口；
     * 内存兜底用双端队列，语义一致。
     */
    public boolean allowSliding(String key, int max, long windowMillis) {
        maybeRecover();
        long now = System.currentTimeMillis();
        if (redisUp) {
            try {
                ZSetOperations<String, String> z = redis.opsForZSet();
                z.removeRangeByScore(key, 0, now - windowMillis);
                Long n = z.zCard(key);
                if (n != null && n >= max) return false;
                z.add(key, now + ":" + UUID.randomUUID(), now);
                // TTL 略大于窗口，避免 key 永久驻留
                redis.expire(key, Duration.ofMillis(windowMillis + 1000L));
                return true;
            } catch (Exception e) {
                markDown(e);
            }
        }
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            q.removeIf(t -> now - t > windowMillis);
            if (q.size() >= max) return false;
            q.addLast(now);
            return true;
        }
    }
}
