/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.WebhookNotifier;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 通知外发（Webhook）配置：仅完全管理员可读写。
 *
 * GET  /api/admin/notify/webhook        读取（URL/密钥脱敏）
 * PUT  /api/admin/notify/webhook        保存（url/secret 传空 = 不改原值）
 * POST /api/admin/notify/webhook/test   发测试消息（同步返回结果，便于排错）
 */
@RestController
@RequestMapping("/api/admin/notify")
public class AdminNotifyController {

    private final WebhookNotifier webhook;

    public AdminNotifyController(WebhookNotifier webhook) {
        this.webhook = webhook;
    }

    private void requireAdmin(TokenClaims c) {
        if (c == null || !c.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可配置通知外发");
    }

    @GetMapping("/webhook")
    public Map<String, Object> get(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return webhook.config();
    }

    @PutMapping("/webhook")
    public Map<String, Object> save(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requireAdmin(c);
        return webhook.save(body);
    }

    @PostMapping("/webhook/test")
    public Map<String, Object> test(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return webhook.test();
    }
}
