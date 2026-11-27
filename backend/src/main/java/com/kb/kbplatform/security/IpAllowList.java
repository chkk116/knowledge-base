/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.security;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * API 密钥的 IP 白名单：支持精确 IP 与 IPv4 CIDR（{@code 10.0.0.0/8}），逗号分隔存放。
 *
 * <p>为什么不支持 IPv6 的 CIDR：IPv6 网段按前缀比较要处理压缩写法（{@code ::1} 与
 * {@code 0:0:0:0:0:0:0:1} 是同一地址），实现容易写错而误放行。IPv6 只做精确匹配，
 * 需要网段时用网关侧的防火墙更可靠。
 *
 * <p>白名单为空 = 不限制来源（保持旧密钥的既有行为）。
 */
public final class IpAllowList {

    private IpAllowList() {}

    /** 最多允许的规则条数（含重复项去重后） */
    public static final int MAX_RULES = 20;

    /** 规范化用户输入：去空白、去重；格式不合法或超过 20 条直接抛错，避免「配了但从不生效」 */
    public static String normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) return "";
        Set<String> out = new LinkedHashSet<>();
        for (String item : raw) {
            if (item == null) continue;
            String s = item.trim();
            if (s.isEmpty()) continue;
            int slash = s.indexOf('/');
            if (slash > 0) {
                String ip = s.substring(0, slash);
                int bits;
                try {
                    bits = Integer.parseInt(s.substring(slash + 1));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("IP 网段写法不对：" + s);
                }
                if (parseV4(ip) == null || bits < 0 || bits > 32) throw new IllegalArgumentException("IP 网段写法不对：" + s);
                out.add(ip + "/" + bits);
            } else {
                // 精确匹配：IPv4 或 IPv6 都行，但必须能解析
                if (parseV4(s) == null && !isLikelyV6(s)) throw new IllegalArgumentException("IP 写法不对：" + s);
                out.add(s);
            }
        }
        // 超上限要报错而不是截断：截断等于后几条静默失效，正是这个功能要避免的坑
        if (out.size() > MAX_RULES) throw new IllegalArgumentException("IP 白名单最多 " + MAX_RULES + " 条，当前 " + out.size() + " 条");
        return String.join(",", out);
    }

    /** 是否允许该来源 IP；白名单为空时一律允许 */
    public static boolean allows(String allowList, String candidate) {
        if (allowList == null || allowList.isBlank()) return true;
        if (candidate == null || candidate.isBlank()) return false;
        String ip = candidate.trim();
        for (String rule : allowList.split(",")) {
            String r = rule.trim();
            if (r.isEmpty()) continue;
            int slash = r.indexOf('/');
            if (slash > 0) {
                Long net = parseV4(r.substring(0, slash));
                Long addr = parseV4(ip);
                if (net == null || addr == null) continue;
                int bits;
                try {
                    bits = Integer.parseInt(r.substring(slash + 1));
                } catch (NumberFormatException e) {
                    continue;
                }
                // 位数越界必须跳过：Java 的移位会对位数取模，bits=33 会算出掩码 0，
                // 于是「所有 IPv4 都匹配」——安全判断直接失效（手工改库也可能塞进这种值）
                if (bits < 0 || bits > 32) continue;
                long mask = bits == 0 ? 0L : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
                if ((net & mask) == (addr & mask)) return true;
            } else {
                // 精确匹配统一按地址字节比较：IPv6 同一地址有 ::1 / 0:0:0:0:0:0:0:1、
                // 大小写等多种写法，字符串相等会漏判（ClientIp 里两种写法都真实出现过）
                if (sameAddress(r, ip)) return true;
            }
        }
        return false;
    }

    /** 精确比较两个地址（IPv4 走数值，IPv6 走 16 字节规范形式） */
    private static boolean sameAddress(String a, String b) {
        Long va = parseV4(a);
        Long vb = parseV4(b);
        if (va != null || vb != null) return va != null && va.equals(vb);
        try {
            return java.util.Arrays.equals(InetAddress.getByName(a).getAddress(),
                    InetAddress.getByName(b).getAddress());
        } catch (Exception e) {
            return false;
        }
    }

    private static Long parseV4(String s) {
        if (s == null) return null;
        String[] parts = s.split("\\.");
        if (parts.length != 4) return null;
        long v = 0;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return null;
            int n;
            try {
                n = Integer.parseInt(p);
            } catch (NumberFormatException e) {
                return null;
            }
            if (n < 0 || n > 255) return null;
            v = (v << 8) | n;
        }
        return v;
    }

    /** IPv6 粗略校验：含冒号且字符集合法即可（只用于精确匹配，不做数值比较） */
    private static boolean isLikelyV6(String s) {
        if (s.indexOf(':') < 0 || s.length() > 45) return false;
        for (char c : s.toCharArray()) {
            if (!(Character.digit(c, 16) >= 0 || c == ':' || c == '.')) return false;
        }
        return true;
    }
}
