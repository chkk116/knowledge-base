/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import com.kb.kbplatform.service.KvStore;
import org.springframework.stereotype.Service;

/**
 * 滑动窗口限流（2026-10-26 改用 KvStore，Redis 优先 / 内存兜底）。
 *
 * 改造前是纯进程内 Map：**后端一重启限流计数就清零**（重启即可绕过抗爆破），
 * 多实例部署时各算各的。现在走 Redis ZSET，重启不清零、多实例共享；
 * Redis 不可用时自动退回原来的内存实现，语义一致，不会让登录不可用。
 *
 * 用于登录/注册/验证码/LDAP/练习判分等敏感接口。
 */
@Service
public class RateLimitService {

    private final KvStore kv;

    public RateLimitService(KvStore kv) {
        this.kv = kv;
    }

    /**
     * 判断 key 在窗口内是否仍可放行一次。
     *
     * @param key          限流维度（如 "register:127.0.0.1"）
     * @param max          窗口内最大允许次数
     * @param windowMillis 滑动窗口大小（毫秒）
     * @return true=放行，false=已达上限
     */
    /** 清零某个计数（成功登录后释放用户名额度，避免正常多设备重登把自己锁住） */
    public void reset(String key) {
        kv.delete("rl:" + key);
    }

    public boolean allow(String key, int max, long windowMillis) {
        return kv.allowSliding("rl:" + key, max, windowMillis);
    }
}
