/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.MailService;
import com.kb.kbplatform.service.PasswordResetService;
import org.springframework.beans.factory.annotation.Value;
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
 * 管理员邮件（SMTP）配置：读写、测试、手动发送、邮件模板、密码重置邮件。
 * 权限与「通知外发」一致（SITE_SETTINGS_MANAGE 或 CORE_MAINTAIN）。
 */
@RestController
@RequestMapping("/api/admin/mail")
public class AdminMailController {

    private final MailService mailService;
    private final PasswordResetService passwordResetService;
    private final UserRepository userRepository;
    private final String siteBaseUrl;

    public AdminMailController(MailService mailService,
                               PasswordResetService passwordResetService,
                               UserRepository userRepository,
                               @Value("${KB_PUBLIC_BASE_URL:https://chkk116.cn}") String siteBaseUrl) {
        this.mailService = mailService;
        this.passwordResetService = passwordResetService;
        this.userRepository = userRepository;
        this.siteBaseUrl = siteBaseUrl.endsWith("/") ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1) : siteBaseUrl;
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

    /** 管理员手动发送一封邮件（收件人 / 主题 / 正文） */
    @PostMapping("/send")
    public Map<String, Object> send(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, String> body) {
        requirePerm(c);
        String to = body == null ? null : body.get("to");
        String subject = body == null ? null : body.get("subject");
        String text = body == null ? null : body.get("body");
        try {
            mailService.sendCustom(to, subject, text);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发送失败：" + e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    /** 邮件模板：当前值 + 默认值 + 变量说明 */
    @GetMapping("/templates")
    public Map<String, Object> templates(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("templates", mailService.templates());
        m.put("defaults", mailService.defaultTemplates());
        m.put("vars", MailService.TPL_VARS);
        return m;
    }

    @PutMapping("/templates")
    public Map<String, Object> saveTemplates(@RequestAttribute("kbUser") TokenClaims c,
                                             @RequestBody Map<String, Map<String, String>> body) {
        requirePerm(c);
        mailService.saveTemplates(body);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    /** 给指定用户发送密码重置邮件（按用户名或邮箱匹配） */
    @PostMapping("/reset-mail")
    public Map<String, Object> resetMail(@RequestAttribute("kbUser") TokenClaims c,
                                         @RequestBody Map<String, String> body) {
        requirePerm(c);
        String account = body == null ? null : body.get("account");
        if (account == null || account.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写用户名或邮箱");
        }
        String a = account.trim();
        User u = userRepository.findByUsername(a)
                .or(() -> userRepository.findByEmail(a))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!u.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号已禁用，不能发送重置邮件");
        }
        // 防权限提升：与 AdminUserController.reset-link 一致，非 ADMIN 不得给管理员账号生成重置链接
        if (!c.isAdmin() && "ADMIN".equals(u.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权限操作管理员账号");
        }
        if (u.getEmail() == null || u.getEmail().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该用户未绑定邮箱");
        }
        Map<String, Object> link = passwordResetService.createLink(u.getId(), c.userId());
        String url = siteBaseUrl + "/reset-password?token=" + link.get("token");
        // 邮件失败不影响链接生成：token 仍返回，管理员可手动转交给用户
        boolean emailed = true;
        String sendErr = null;
        try {
            mailService.sendResetLink(u, url);
        } catch (Exception e) {
            emailed = false;
            sendErr = e.getMessage();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("emailed", emailed);
        m.put("sendError", sendErr);
        m.put("email", u.getEmail());
        m.put("link", url);
        m.put("expiresAt", link.get("expiresAt"));
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
