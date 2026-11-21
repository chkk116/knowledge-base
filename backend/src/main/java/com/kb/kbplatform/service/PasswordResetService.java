/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.PasswordReset;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.PasswordResetRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.PasswordUtil;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 密码重置链接。
 *
 * 没有 SMTP 也能安全找回：管理员在用户管理里生成一次性链接（30 分钟有效），
 * 通过任意渠道转交用户，用户自己设置新密码——管理员全程不接触新密码。
 * 数据库只存 token 的 SHA-256 哈希；生成新链接会作废该用户此前未使用的链接。
 */
@Service
public class PasswordResetService {

    private static final int TTL_MINUTES = 30;

    private final PasswordResetRepository repo;
    private final UserRepository userRepo;

    public PasswordResetService(PasswordResetRepository repo, UserRepository userRepo) {
        this.repo = repo;
        this.userRepo = userRepo;
    }

    /** 生成一次性重置 token（返回明文 token，仅此一次可见；库内只存哈希） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createLink(Long userId, Long operatorId) {
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (!u.isEnabled()) throw new IllegalArgumentException("账号已禁用，不能生成重置链接");

        LocalDateTime now = LocalDateTime.now();
        // 作废该用户此前所有未使用链接，避免多链接并存
        for (PasswordReset old : repo.findByUserIdAndUsedAtIsNull(userId)) {
            old.setUsedAt(now);
            repo.save(old);
        }

        byte[] rnd = new byte[32];
        new SecureRandom().nextBytes(rnd);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(rnd);

        PasswordReset pr = new PasswordReset();
        pr.setUserId(userId);
        pr.setTokenHash(sha256(token));
        pr.setExpiresAt(now.plusMinutes(TTL_MINUTES));
        pr.setCreatedBy(operatorId);
        repo.save(pr);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("username", u.getUsername());
        m.put("expiresAt", pr.getExpiresAt());
        m.put("ttlMinutes", TTL_MINUTES);
        return m;
    }

    /** 打开链接时校验：有效则返回用户名，便于页面提示「正在为 xxx 重置密码」 */
    @Transactional(readOnly = true)
    public Map<String, Object> check(String token) {
        PasswordReset pr = findValid(token);
        Map<String, Object> m = new LinkedHashMap<>();
        if (pr == null) {
            m.put("valid", false);
            return m;
        }
        String uname = userRepo.findById(pr.getUserId()).map(User::getUsername).orElse("");
        m.put("valid", true);
        m.put("username", uname);
        m.put("expiresAt", pr.getExpiresAt());
        return m;
    }

    /** 用链接设置新密码：校验 token → 改密 → 递增 tokenVersion（旧登录态全部失效）→ 标记已用 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reset(String token, String newPassword) {
        String weak = com.kb.kbplatform.security.PasswordPolicy.check(newPassword, null);
        if (weak != null) {
            throw new IllegalArgumentException(weak);
        }
        PasswordReset pr = findValid(token);
        if (pr == null) throw new IllegalArgumentException("链接已失效或已使用，请重新获取");
        // 抢占式消费：并发双击同一链接时只有一个请求能继续
        if (repo.claim(pr.getId(), LocalDateTime.now()) != 1) {
            throw new IllegalArgumentException("链接已失效或已使用，请重新获取");
        }

        User u = userRepo.findById(pr.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        u.setPassword(PasswordUtil.encode(newPassword));
        u.setTokenVersion(u.getTokenVersion() + 1);
        userRepo.save(u);

        return Map.of("ok", true, "username", u.getUsername());   // usedAt 已由 claim 原子写入
    }

    private PasswordReset findValid(String token) {
        if (token == null || token.isBlank()) return null;
        LocalDateTime now = LocalDateTime.now();
        return repo.findFirstByTokenHashAndUsedAtIsNullOrderByIdDesc(sha256(token.trim()))
                .filter(pr -> pr.getExpiresAt() != null && pr.getExpiresAt().isAfter(now))
                .orElse(null);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
