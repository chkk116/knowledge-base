/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/**
 * 内网地址判定（SSRF 防护共用口径）。
 *
 * 各处在用的判定零散，且 IPv4 映射 IPv6 字面量（`[::ffff:127.0.0.1]`、`[::ffff:10.0.0.1]`）容易漏：
 * 这类地址在 Java 里通常被转成 Inet4Address、走回环/私有判定没问题，但只要有一层没转，
 * 就会以「公网 IPv6」的身份被放行。这里统一做一次归一化再判。
 */
public final class NetAddressSafety {

    private NetAddressSafety() { }

    /** 是否属于「不该被外部访问」的地址：回环 / 私网 / 链路本地 / 组播 / 未指定 / ULA / IPv4 映射内网 */
    public static boolean isInternal(InetAddress a) {
        if (a == null) return true;
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] raw = a.getAddress();
        if (a instanceof Inet6Address) {
            // fc00::/7（唯一本地地址）
            if ((raw[0] & 0xFE) == 0xFC) return true;
            // IPv4 映射：::ffff:a.b.c.d —— 取尾部 4 字节按 IPv4 再判一次
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) { mapped = false; break; }
            }
            if (mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                try {
                    InetAddress v4 = InetAddress.getByAddress(new byte[]{raw[12], raw[13], raw[14], raw[15]});
                    return isInternal(v4);
                } catch (Exception e) {
                    return true;
                }
            }
            // IPv4 兼容地址（::a.b.c.d，已废弃但仍可能被用来绕过）
            boolean compat = true;
            for (int i = 0; i < 12; i++) {
                if (raw[i] != 0) { compat = false; break; }
            }
            if (compat) return true;
        }
        if (a instanceof Inet4Address) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            if (b0 == 0 || b0 == 10 || b0 == 127) return true;
            if (b0 == 169 && b1 == 254) return true;          // 链路本地（含云元数据 169.254.169.254）
            if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
            if (b0 == 192 && b1 == 168) return true;
            if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;  // CGNAT 100.64/10
        }
        return false;
    }
}
