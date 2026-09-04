package com.kb.kbplatform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 系统信息 · 查看者（客户端）主机信息。
 * 浏览器无法读取用户本机计算机名，故组合两种来源推导「查看者所在机器的名称」：
 *   1) 服务端对客户端真实 IP 做反向 DNS（内网 AD 域通常能解析出机器名，最适合防泄密溯源）；
 *   2) 前端把浏览器导航所用的主机名（window.location.hostname）随请求带上，
 *      本机按名访问开发服务器（如 http://jifang:5173）时即为本机计算机名。
 * 仅要求已登录（AuthFilter 已拦截匿名）；返回查看者自己的 IP / 主机名，用于「显示主机名」防泄密水印。
 */
@RestController
@RequestMapping("/api/system")
public class SystemInfoController {

    /** 提取客户端真实 IP（兼容反向代理 X-Forwarded-For） */
    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    /** 是否为「真实主机名」（排除空、localhost、纯 IPv4 / IPv6） */
    private boolean isRealName(String s) {
        if (s == null || s.isBlank()) return false;
        s = s.trim();
        if (s.equalsIgnoreCase("localhost")) return false;
        if (s.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) return false;        // IPv4
        if (s.matches("^[0-9a-fA-F:]+$") && s.contains(":")) return false; // IPv6
        return true;
    }

    /** 反向 DNS 解析主机名：带 1.5s 超时，解析不出（返回原 IP）则回退为 IP，绝不阻塞请求 */
    private String reverseDns(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            if (addr.isLoopbackAddress()) return "localhost";
            CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> {
                try {
                    String canonical = addr.getCanonicalHostName();
                    return (canonical == null || canonical.equals(ip)) ? ip : canonical;
                } catch (Exception e) {
                    return ip;
                }
            });
            String host = f.get(1500, TimeUnit.MILLISECONDS);
            return (host == null || host.isBlank()) ? ip : host;
        } catch (Exception e) {
            return ip;
        }
    }

    /** 浏览器导航所用的主机名是否就指向「当前客户端这台机器」（用于本机按名访问开发服务器的场景） */
    private boolean sameMachine(String browserHost, String clientIp) {
        if (!isRealName(browserHost)) return false;
        try {
            CompletableFuture<Boolean> f = CompletableFuture.supplyAsync(() -> {
                try {
                    InetAddress a = InetAddress.getByName(browserHost.trim());
                    String resolved = a.getHostAddress();
                    return resolved.equalsIgnoreCase(clientIp)
                        || resolved.replaceAll("^0+:0+:", "").equalsIgnoreCase(clientIp.replaceAll("^0+:0+:", ""));
                } catch (Exception e) {
                    return false;
                }
            });
            return Boolean.TRUE.equals(f.get(1500, TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 推导查看者计算机名：
     *   - 回环地址（localhost 本机访问）：直接用浏览器导航主机名（即本机计算机名，如 jifang）；非主机名则标 localhost
     *   - 非回环：先反向 DNS 客户端真实 IP（部署/局域网内可得机器名）；
     *             反向 DNS 无 PTR 记录时，若浏览器按主机名访问且该主机名正好解析到本机 IP，则用该主机名（本机按名访问开发服务器）
     *   - 兜底：返回客户端 IP（仍能定位到具体机器）
     */
    private String resolveHost(String ip, String browserHost) {
        if (isLoopback(ip)) {
            return isRealName(browserHost) ? browserHost.trim() : "localhost";
        }
        String reverse = reverseDns(ip);
        if (isRealName(reverse)) return reverse;
        if (isRealName(browserHost) && sameMachine(browserHost, ip)) return browserHost.trim();
        return ip;
    }

    private boolean isLoopback(String ip) {
        try {
            return InetAddress.getByName(ip).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/client-info")
    public Map<String, String> clientInfo(HttpServletRequest req,
                                          @RequestHeader(value = "X-Client-Host", required = false) String browserHost) {
        String ip = clientIp(req);
        String host = resolveHost(ip, browserHost);
        return Map.of("ip", ip, "host", host);
    }
}
