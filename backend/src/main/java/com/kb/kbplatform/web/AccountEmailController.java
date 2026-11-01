/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.KvStore;
import com.kb.kbplatform.service.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 个人邮箱绑定（验证码验证所有权）。
 *
 * 流程：用户填邮箱 → send-code 发 6 位验证码（10 分钟有效）→ verify 回填正确即标记「已绑定」。
 * 验证码存于 KvStore（Redis 优先、内存兜底），附带限流防刷；邮箱若已被其他已验证账号占用则拒绝。
 */
@RestController
@RequestMapping("/api/account/email")
public class AccountEmailController {

    private static final Logger log = LoggerFactory.getLogger(AccountEmailController.class);
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");

    private final UserRepository userRepo;
    private final MailService mailService;
    private final KvStore kv;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AccountEmailController(UserRepository userRepo, MailService mailService, KvStore kv) {
        this.userRepo = userRepo;
        this.mailService = mailService;
        this.kv = kv;
    }

    /** 当前绑定状态 */
    @GetMapping("/status")
    public Map<String, Object> status(@RequestAttribute("kbUser") TokenClaims c) {
        User u = userRepo.findById(c.userId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.getEmail() == null ? "" : u.getEmail());
        m.put("verified", u.isEmailVerified());
        m.put("notifyEnabled", u.isEmailNotifyEnabled());
        m.put("mailConfigured", mailService.isConfigured());
        return m;
    }

    /** 发送绑定验证码 */
    @PostMapping("/send-code")
    public Map<String, Object> sendCode(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, String> body) {
        if (!mailService.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮件服务未配置，无法发送验证码");
        }
        String email = body == null ? null : body.get("email");
        if (email == null || !EMAIL.matcher(email.trim()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱格式不正确");
        }
        email = email.trim();
        // 限流：每 60 秒最多 1 次，每 10 分钟最多 10 次
        if (!kv.allowSliding("email_code_rate:" + c.userId(), 1, 60_000L)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "发送过于频繁，请 1 分钟后再试");
        }
        if (!kv.allowSliding("email_code_rate10:" + c.userId(), 10, 10 * 60_000L)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "短时间内发送次数过多，请稍后再试");
        }
        // 占用校验：该邮箱若已被其他已验证账号绑定，拒绝
        Optional<User> owner = userRepo.findByEmail(email);
        if (owner.isPresent() && !owner.get().getId().equals(c.userId()) && owner.get().isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该邮箱已被其他账号绑定");
        }
        String code = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
        String payload = "{\"code\":\"" + code + "\",\"email\":\"" + email.replace("\"", "") + "\"}";
        kv.put("email_bind:" + c.userId(), payload, Duration.ofMinutes(10));
        try {
            mailService.sendVerifyCode(email, code);
        } catch (Exception e) {
            log.warn("发送绑定验证码失败 {}: {}", email, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码发送失败：" + e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    /** 校验验证码并绑定 */
    @PostMapping("/verify")
    public Map<String, Object> verify(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, String> body) {
        String email = body == null ? null : body.get("email");
        String code = body == null ? null : body.get("code");
        if (email == null || code == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱和验证码不能为空");
        }
        email = email.trim();
        code = code.trim();
        String stored = kv.get("email_bind:" + c.userId());
        if (stored == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码已过期，请重新发送");
        }
        JsonNode n;
        try {
            n = objectMapper.readTree(stored);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码数据异常");
        }
        String sc = n.has("code") ? n.get("code").asText() : "";
        String se = n.has("email") ? n.get("email").asText() : "";
        if (!se.equals(email) || !sc.equalsIgnoreCase(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码不正确");
        }
        Optional<User> owner = userRepo.findByEmail(email);
        if (owner.isPresent() && !owner.get().getId().equals(c.userId()) && owner.get().isEmailVerified()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该邮箱已被其他账号绑定");
        }
        User u = userRepo.findById(c.userId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
        u.setEmail(email);
        u.setEmailVerified(true);
        userRepo.save(u);
        kv.delete("email_bind:" + c.userId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("email", email);
        return m;
    }

    /** 解绑邮箱 */
    @PostMapping("/unbind")
    public Map<String, Object> unbind(@RequestAttribute("kbUser") TokenClaims c) {
        User u = userRepo.findById(c.userId()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
        u.setEmail(null);
        u.setEmailVerified(false);
        userRepo.save(u);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }
}
