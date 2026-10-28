/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.KvStore;
import com.kb.kbplatform.service.RuntimeFlags;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 系统状态（2026-10-26）。
 *
 * 目前只暴露 Redis 的运行状态：**是否连上、版本、内存、键数、各类键的分布**。
 * 起因是接入 Redis 后，运维层面完全看不到它到底有没有在起作用 ——
 * 只能翻日志找「Redis 已连接」那一行。现在后台「站点设置 → 系统状态」能直接看。
 *
 * 安全：只读，不返回密码/连接串；仅 CORE_MAINTAIN 权限可见。
 */
@RestController
public class AdminSystemController {

    private final StringRedisTemplate redis;
    private final KvStore kv;
    private final RuntimeFlags flags;

    public AdminSystemController(ObjectProvider<StringRedisTemplate> provider, KvStore kv, RuntimeFlags flags) {
        this.redis = provider.getIfAvailable();
        this.kv = kv;
        this.flags = flags;
    }

    /**
     * 系统状态页的权限口径：ADMIN 或「核心维护 CORE_MAINTAIN」。
     * 与 /api/admin/settings/** 保持一致 —— 页面路由就是按 CORE_MAINTAIN 放行的，
     * 只认 isAdmin() 会让持有该权限的非管理员「能进页面但接口 403」。
     */
    private void requireMaintain(TokenClaims c) {
        if (c == null || !(c.isAdmin() || c.hasPerm("CORE_MAINTAIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要「核心维护」权限");
        }
    }

    @GetMapping("/api/admin/system/redis")
    public Map<String, Object> redisStatus(@RequestAttribute("kbUser") TokenClaims c) {
        requireMaintain(c);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", flags.redisEnabled());  // 后台开关状态（关掉时不使用 Redis）
        out.put("backend", kv.backend());          // redis / memory（KvStore 实际在用哪个）
        out.put("connected", "redis".equals(kv.backend()));

        if (redis == null) {
            out.put("message", "未引入 spring-boot-starter-data-redis，键值存储走内存");
            return out;
        }

        try {
            Properties server = null;
            Properties memory = null;
            Long dbSize = null;
            RedisConnection conn = redis.getConnectionFactory().getConnection();
            try {
                server = conn.serverCommands().info("server");
                memory = conn.serverCommands().info("memory");
                dbSize = conn.serverCommands().dbSize();
            } finally {
                conn.close();
            }
            out.put("version", prop(server, "redis_version"));
            out.put("mode", prop(server, "redis_mode"));
            out.put("uptimeDays", prop(server, "uptime_in_days"));
            out.put("usedMemory", prop(memory, "used_memory_human"));
            out.put("peakMemory", prop(memory, "used_memory_peak_human"));
            out.put("maxMemory", prop(memory, "maxmemory_human"));
            out.put("dbSize", dbSize);

            // 键分布：分别数一下三类业务键。keyspace 只有几十~几百个键时才统计，
            // 避免 KEYS 在超大库上阻塞 Redis（那是会拖死线上的一类操作）。
            if (dbSize != null && dbSize < 20_000) {
                Map<String, Object> breakdown = new LinkedHashMap<>();
                breakdown.put("rateLimit", count("rl:*"));
                breakdown.put("loginState", count("sso:state:*"));
                breakdown.put("practiceSession", count("practice:session:*"));
                breakdown.put("other", Math.max(0, dbSize
                        - (Long) breakdown.get("rateLimit")
                        - (Long) breakdown.get("loginState")
                        - (Long) breakdown.get("practiceSession")));
                out.put("keys", breakdown);
            } else {
                out.put("keys", null);
                out.put("keysNote", "键数过多，跳过分类统计（避免 KEYS 阻塞）");
            }
        } catch (Exception e) {
            out.put("connected", false);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    private long count(String pattern) {
        try {
            Set<String> keys = redis.keys(pattern);
            return keys == null ? 0 : keys.size();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String prop(Properties p, String key) {
        return p == null ? null : p.getProperty(key);
    }

    /**
     * 开关 Redis（热生效，不用重启）。
     *
     * 关掉后：KvStore 立刻切内存（限流 / 登录 state / 练习会话不跨重启保留），
     * @Cacheable 全部退化为直查数据库。Redis 里的旧键不会被删，重新打开后还能用。
     */
    @PostMapping("/api/admin/system/redis/toggle")
    public Map<String, Object> toggleRedis(@RequestAttribute("kbUser") TokenClaims c,
                                           @RequestBody(required = false) Map<String, Object> body) {
        requireMaintain(c);
        Object raw = body == null ? null : body.get("enabled");
        if (!(raw instanceof Boolean b)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "enabled 必须是 true / false");
        }
        flags.setRedisEnabled(b);
        return Map.of("ok", true, "enabled", b);
    }
}
