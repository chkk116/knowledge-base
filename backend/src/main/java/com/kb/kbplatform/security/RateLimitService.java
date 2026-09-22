/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 简单的内存滑动窗口限流（单实例足够；集群部署应换成 Redis 等共享存储）。
 * 用于登录/注册等敏感接口的抗爆破、抗批量注册。
 */
@Service
public class RateLimitService {

    private final ConcurrentHashMap<String, Deque<Long>> store = new ConcurrentHashMap<>();

    /** 空闲回收：超过该时长无任何活跃时间戳的 key 直接从内存清除，避免内存泄漏 */
    private static final long MAX_IDLE_MILLIS = 60 * 60 * 1000L;

    private final ScheduledExecutorService purger = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "rate-limit-purger");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        // 每 5 分钟清理一次已无活跃时间戳的限流 key（停止尝试后不再常驻内存）
        purger.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            store.entrySet().removeIf(e -> {
                Long last = e.getValue().peekLast();
                return last == null || (now - last) > MAX_IDLE_MILLIS;
            });
        }, 5, 5, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void destroy() {
        purger.shutdownNow();
    }

    /**
     * 判断 key 在窗口内是否仍可放行一次。
     * @param key        限流维度（如 "register:127.0.0.1"）
     * @param max        窗口内最大允许次数
     * @param windowMillis 滑动窗口大小（毫秒）
     * @return true=放行，false=已达上限
     */
    public synchronized boolean allow(String key, int max, long windowMillis) {
        long now = System.currentTimeMillis();
        Deque<Long> q = store.computeIfAbsent(key, k -> new ArrayDeque<>());
        q.removeIf(t -> now - t > windowMillis);
        if (q.size() >= max) return false;
        q.addLast(now);
        return true;
    }
}
