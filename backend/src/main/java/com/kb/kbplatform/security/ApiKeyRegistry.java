/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 静态 API 密钥表：给外部系统调用的最简通道。
 *
 * <p>密钥配在环境变量 {@code KB_API_KEYS} 里，格式 {@code 密钥:用户ID}，多个用逗号分隔：
 * <pre>KB_API_KEYS=kb_live_9f3a...:15,kb_robot_2c81...:22</pre>
 *
 * <p>密钥对应的就是那个用户账号——权限完全按该账号的 role / 权限码走，不再另建一套 scope。
 * 好处是：不用建表、不用管理页，收回只要删环境变量（或禁用账号）重启即可。
 *
 * <p>为什么不做成「密钥放 URL」：令牌进 URL 会落进反代/网关的 access log、浏览器历史、
 * Referer，等于把密钥公开。所以只认 {@code X-API-Key} 请求头。
 *
 * <p>比较用 {@link MessageDigest#isEqual} 做常量时间比对，避免按前缀逐字节试探的时序侧信道。
 */
@Component
public class ApiKeyRegistry {

    /** 密钥 -> 绑定账号 ID（保持插入顺序，便于日志排查时逐个比对） */
    private final Map<String, Long> keys = new LinkedHashMap<>();

    public ApiKeyRegistry(@Value("${kb.api.keys:${KB_API_KEYS:}}") String raw) {
        if (raw == null || raw.isBlank()) return;
        for (String pair : raw.split(",")) {
            String p = pair.trim();
            if (p.isEmpty()) continue;
            int idx = p.lastIndexOf(':');
            if (idx <= 0) continue;
            String key = p.substring(0, idx).trim();
            String uid = p.substring(idx + 1).trim();
            if (key.isEmpty() || uid.isEmpty()) continue;
            try {
                keys.put(key, Long.parseLong(uid));
            } catch (NumberFormatException ignored) {
                // 配错的项直接跳过：宁可这一条不生效，也不让整个服务起不来
            }
        }
    }

    public boolean enabled() {
        return !keys.isEmpty();
    }

    /** 命中返回绑定账号 ID，未命中返回 null */
    public Long resolve(String presented) {
        if (presented == null || presented.isEmpty() || keys.isEmpty()) return null;
        byte[] given = presented.getBytes(StandardCharsets.UTF_8);
        Long hit = null;
        for (Map.Entry<String, Long> e : keys.entrySet()) {
            // 有意不使用提前 return：无论命中哪一条，比对次数都相同
            if (MessageDigest.isEqual(given, e.getKey().getBytes(StandardCharsets.UTF_8))) {
                hit = e.getValue();
            }
        }
        return hit;
    }

    /** 命中密钥的「指纹」（前 8 位），用于日志/审计时标识是哪把钥匙，不泄露完整密钥 */
    public String label(String presented) {
        if (presented == null || presented.length() < 8) return "";
        for (String k : keys.keySet()) {
            if (MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), k.getBytes(StandardCharsets.UTF_8))) {
                return "key:" + k.substring(0, 8) + "…";
            }
        }
        return "";
    }
}
