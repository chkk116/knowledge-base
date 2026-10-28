/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.repository.SysSettingRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 运行时开关（存 `kb_sys_setting`，改完**不用重启**就生效）。
 *
 * 目前只有 Redis 一个：后台「站点设置 → 系统状态」里可以直接关掉。
 * 关掉后 {@link KvStore} 走内存、{@code @Cacheable} 全部失效直查数据库 ——
 * 和 `kb.redis.enabled=false` 启动的效果一致，区别是这里能热切换。
 *
 * 读库带 10 秒缓存：这些开关每几秒被查很多次，没必要每次都打数据库。
 */
@Component
public class RuntimeFlags {

    public static final String KEY_REDIS_ENABLED = "redis_enabled";
    private static final long TTL_MS = 10_000L;

    private final SysSettingRepository repo;

    private volatile boolean redisEnabled = true;
    private volatile long loadedAt = 0L;

    public RuntimeFlags(SysSettingRepository repo) {
        this.repo = repo;
    }

    /** 是否允许使用 Redis（默认允许；只有明确写成 "false" 才算关） */
    public boolean redisEnabled() {
        long now = System.currentTimeMillis();
        if (now - loadedAt > TTL_MS) {
            synchronized (this) {
                if (now - loadedAt > TTL_MS) {
                    redisEnabled = readRedisEnabled();
                    loadedAt = now;
                }
            }
        }
        return redisEnabled;
    }

    public void setRedisEnabled(boolean enabled) {
        SysSetting s = repo.findByKey(KEY_REDIS_ENABLED)
                .orElseGet(() -> new SysSetting(KEY_REDIS_ENABLED, "true"));
        s.setValue(enabled ? "true" : "false");
        s.setUpdatedAt(LocalDateTime.now());
        repo.save(s);
        redisEnabled = enabled;
        loadedAt = System.currentTimeMillis();
    }

    private boolean readRedisEnabled() {
        try {
            return repo.findByKey(KEY_REDIS_ENABLED)
                    .map(s -> !"false".equalsIgnoreCase(String.valueOf(s.getValue()).trim()))
                    .orElse(true);
        } catch (Exception e) {
            // 读不到就当开着（保持历史行为），别因为一个开关把缓存整体打挂
            return true;
        }
    }
}
