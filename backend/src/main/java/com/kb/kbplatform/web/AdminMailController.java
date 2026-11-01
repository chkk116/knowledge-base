/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.MailService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理员邮件（SMTP）配置：读写与测试发送。
 * 权限与「通知外发」一致（SITE_SETTINGS_MANAGE 或 CORE_MAINTAIN）。
 */
@RestController
@RequestMapping("/api/admin/mail")
public class AdminMailController {

    private final MailService mailService;

    public AdminMailController(MailService mailService) {
        this.mailService = mailService;
    }

    private void requirePerm(TokenClaims c) {
        if (!c.isAdmin() && !c.hasPerm("SITE_SETTINGS_MANAGE") && !c.hasPerm("CORE_MAINTAIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限");
        }
    }

    @GetMapping("/config")
    public Map<String, Object> config(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c);
        return mailService.publicView();
    }

    @PutMapping("/config")
    public Map<String, Object> save(@RequestAttribute("kbUser") TokenClaims c, @RequestBody MailConfigBody b) {
        requirePerm(c);
        MailService.Config cfg = new MailService.Config();
        cfg.host = b.host;
        cfg.port = b.port;
        cfg.username = b.username;
        cfg.password = b.password;
        cfg.fromName = b.fromName;
        cfg.fromAddress = b.fromAddress;
        cfg.encryption = b.encryption;
        cfg.enabled = Boolean.TRUE.equals(b.enabled);
        mailService.save(cfg);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    @PostMapping("/test")
    public Map<String, Object> test(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, String> body) {
        requirePerm(c);
        String to = body == null ? null : body.get("to");
        if (to == null || to.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写接收测试邮件的邮箱");
        }
        try {
            mailService.sendTest(to.trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发送失败：" + e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    public static class MailConfigBody {
        public String host;
        public int port;
        public String username;
        public String password;   // 空字符串表示保留原密码
        public String fromName;
        public String fromAddress;
        public String encryption;  // SSL / TLS / NONE
        public Boolean enabled;
    }
}
