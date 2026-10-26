/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.security.RateLimitService;
import com.kb.kbplatform.service.DocShareService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档外部分享（免登录只读）。
 *
 * - GET  /api/public/shared/{token}        元信息（标题 / 是否需要密码），不返回正文
 * - POST /api/public/shared/{token}/view   取正文；有密码时校验，失败按令牌限流
 *
 * 安全边界：
 * 1. 令牌 40 位随机串，未开启/已过期一律 404，不区分原因（避免探测）；
 * 2. 正文服务端 HtmlSanitizer 消毒后再下发；
 * 3. 密码尝试按令牌限流，避免暴力猜解；
 * 4. 「禁止复制」只是前端限制，服务端无法阻止拿去存档——响应里明确 noCopy 仅作声明。
 */
@RestController
@RequestMapping("/api/public/shared")
public class PublicShareController {

    private final DocShareService svc;
    private final RateLimitService rateLimit;

    public PublicShareController(DocShareService svc, RateLimitService rateLimit) {
        this.svc = svc;
        this.rateLimit = rateLimit;
    }

    @GetMapping("/{token}")
    public Map<String, Object> meta(@PathVariable String token) {
        return svc.publicMeta(token);
    }

    @PostMapping("/{token}/view")
    public Map<String, Object> view(@PathVariable String token, @RequestBody(required = false) Map<String, Object> body) {
        // 同一令牌每分钟最多 20 次：密码猜解与爬取都挡住
        if (!rateLimit.allow("public-share:" + token, 20, 60_000L)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "访问太频繁，请稍后再试");
        }
        Object pwd = body == null ? null : body.get("password");
        return svc.publicView(token, pwd == null ? null : String.valueOf(pwd));
    }
}
