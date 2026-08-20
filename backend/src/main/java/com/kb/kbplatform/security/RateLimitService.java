package com.kb.kbplatform.security;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单的内存滑动窗口限流（单实例足够；集群部署应换成 Redis 等共享存储）。
 * 用于登录/注册等敏感接口的抗爆破、抗批量注册。
 */
@Service
public class RateLimitService {

    private final ConcurrentHashMap<String, Deque<Long>> store = new ConcurrentHashMap<>();

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
