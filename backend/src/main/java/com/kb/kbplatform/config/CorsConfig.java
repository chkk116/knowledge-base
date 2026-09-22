/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * 跨域（CORS）配置。
 *
 * 背景：本系统前后端通常经 Nginx 同源反代（前端 /api 与后端同域），此时浏览器不会发起跨域请求，
 * CORS 规则不生效也无需生效。但在下列场景会真实跨域，必须放开：
 *   1) 本地开发：vite dev server(5173) 直连后端(8080)（虽然 vite.config.js 已配 proxy，
 *      但绕过 proxy / 独立前端部署时仍会跨域）；
 *   2) 前后端分离部署在不同域名（如前端 kb.xxx.com、后端 api.xxx.com）；
 *   3) 测试环境用独立子域联调。
 *
 * 设计：白名单从 kb.cors.allowed-origins 读取，**留空则不注册任何 CORS 规则**（零副作用，Nginx 同源场景）。
 * 注意：allowCredentials(true) 时严禁使用 allowedOrigins("*")，浏览器会直接拒绝响应，
 * 因此这里用 allowedOriginPatterns 逐个登记白名单域名。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 允许跨域的来源白名单，例如 https://www.example.com,https://test.example.com */
    @Value("${kb.cors.allowed-origins:}")
    private List<String> allowedOrigins;

    /** 是否允许携带 Cookie/Authorization；本项目用 Authorization 头，通常无需 Cookie，默认 false 更安全 */
    @Value("${kb.cors.allow-credentials:false}")
    private boolean allowCredentials;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        List<String> origins = allowedOrigins.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (origins.isEmpty()) {
            // 未配置白名单 → 不启用 CORS（同源反代场景，保持零开销、零攻击面）
            return;
        }
        registry.addMapping("/**")
                .allowedOriginPatterns(origins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // 前端需要读取的响应头：限流/导出等场景下可能用到
                .exposedHeaders("Content-Disposition", "X-Total-Count")
                .allowCredentials(allowCredentials)
                .maxAge(3600);
    }
}
