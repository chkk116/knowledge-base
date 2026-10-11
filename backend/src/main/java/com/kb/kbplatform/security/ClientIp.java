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
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int i = xff.indexOf(',');
            return (i > 0 ? xff.substring(0, i) : xff).trim();
        }
        return req.getRemoteAddr();
    }
}
