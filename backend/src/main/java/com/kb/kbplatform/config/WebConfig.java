package com.kb.kbplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 将 /uploads/** 映射到本地上传目录，使上传的图片可通过 HTTP 直接访问。
 * 目录由 kb.upload.dir 指定（默认 ./uploads，运行时解析为绝对路径）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${kb.upload.dir:./uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        String loc = root.toString().replace("\\", "/");
        if (!loc.endsWith("/")) loc += "/";
        // 上传文件名是 UUID（内容不可变），安全长缓存 30 天，显著减少重复请求
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + loc)
                .setCachePeriod(30 * 24 * 3600);
    }
}
