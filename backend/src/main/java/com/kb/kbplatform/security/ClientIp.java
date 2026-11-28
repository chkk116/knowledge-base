/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 统一客户端 IP 解析。
 *
 * 外部审查 2026-10-11：原先两套口径并存——login/register/captcha 用 getRemoteAddr()（反代下
 * 是代理地址，全站共享一个 IP 会被整批锁死），captchaSlide/审计/登录日志又各写各的 XFF 解析。
 * 统一口径：
 *   1. X-Real-IP：nginx 配 $remote_addr（TCP 层来源，受信代理覆写，客户端伪造不了）——首选；
 *   2. X-Forwarded-For 首段：兼容未升级的旧 nginx 配置（多级代理时可被伪造，仅作回退）；
 *   3. RemoteAddr：无反代直连时的 TCP 层地址。
 */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(HttpServletRequest req) {
        if (req == null) return null;
        String remote = req.getRemoteAddr();
        // 只有直连来自本机（生产 nginx 与后端同机）或显式配置的可信代理时才接受 IP 头；
        // 直连后端时 XFF / X-Real-IP 是客户端可伪造的，无条件信任会让 IP 锁定形同虚设。
        if (!isTrustedProxy(remote)) return remote;
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            // 取最后一段：nginx 的 $proxy_add_x_forwarded_for 会把客户端自带的头原样保留、
            // 再把真实来源追加到末尾，取首段等于让客户端自己填 IP，限流与锁定都会被绕过。
            int i = xff.lastIndexOf(',');
            String last = (i >= 0 ? xff.substring(i + 1) : xff).trim();
            if (!last.isEmpty()) return last;
        }
        return remote;
    }

    /**
     * 安全判定用的来源 IP：只认「受信代理覆写的 X-Real-IP」，**不**回退 X-Forwarded-For。
     *
     * <p>XFF 在多级代理下是可被客户端塞首段的（`$proxy_add_x_forwarded_for` 会把客户端自带的
     * 头原样带上）。用于限流之类还可以接受，但用来做「密钥来源白名单」就等于让人自带一个允许的 IP。
     */
    public static String resolveStrict(HttpServletRequest req) {
        if (req == null) return null;
        String remote = req.getRemoteAddr();
        if (!isTrustedProxy(remote)) return remote;
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return remote;
    }

    private static boolean isTrustedProxy(String remote) {
        if (remote == null || remote.isBlank()) return false;
        if (remote.equals("127.0.0.1") || remote.equals("0:0:0:0:0:0:0:1")
                || remote.equals("::1") || remote.startsWith("127.")) return true;
        String configured = System.getenv("KB_TRUSTED_PROXIES");   // 逗号分隔的代理 IP 白名单
        if (configured == null || configured.isBlank()) return false;
        for (String ip : configured.split(",")) {
            if (remote.equals(ip.trim())) return true;
        }
        return false;
    }
}
