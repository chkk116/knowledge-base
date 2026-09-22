/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuditService;
import com.kb.kbplatform.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * 头像审核（方案 A 人工审核流）：
 *  用户在 PUT /auth/profile 提交非空头像 → avatarPending + avatarStatus=PENDING，
 *  展示侧（评论/排行/作者卡/侧栏）只读 avatar 字段，未过审图零暴露；
 *  管理员在此 通过（avatar=avatarPending）或 驳回（仅清 pending，原头像不动）。
 */
@RestController
@RequestMapping("/api/admin/avatars")
public class AdminAvatarController {

    private final UserRepository userRepo;
    private final AuditService audit;
    private final NotificationService notificationService;

    public AdminAvatarController(UserRepository userRepo, AuditService audit, NotificationService notificationService) {
        this.userRepo = userRepo;
        this.audit = audit;
        this.notificationService = notificationService;
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「" + code + "」权限");
        }
    }

    private String clip(String s) { return s == null ? "" : (s.length() > 60 ? s.substring(0, 60) : s); }

    /** 待审头像列表：含当前生效头像，便于对比新旧 */
    @GetMapping("/pending")
    public List<Map<String, Object>> pending(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "AVATAR_REVIEW");
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : userRepo.findByAvatarPendingIsNotNull()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName() == null || u.getDisplayName().isBlank()
                    ? u.getUsername() : u.getDisplayName());
            m.put("avatar", u.getAvatar() == null ? "" : u.getAvatar());           // 当前生效（可能为空=默认头像）
            m.put("avatarPending", u.getAvatarPending());                          // 待审新头像
            m.put("status", u.getAvatarStatus());
            out.add(m);
        }
        return out;
    }

    /** 通过：待审头像转正 */
    @PostMapping("/{id}/approve")
    public Map<String, Object> approve(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "AVATAR_REVIEW");
        User u = userRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (u.getAvatarPending() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该用户没有待审头像");
        u.setAvatar(u.getAvatarPending());
        u.setAvatarPending(null);
        u.setAvatarStatus("APPROVED");
        userRepo.save(u);
        notificationService.notifyAvatar(u.getId(), "AVATAR_APPROVED", "您的新头像已通过审核并生效");
        audit.log(c.userId(), c.username(), "AVATAR_APPROVE", "USER", u.getId(), u.getUsername(),
                "通过头像审核：" + clip(u.getAvatar()));
        return Map.of("ok", true);
    }

    /** 驳回：清掉待审，原头像保持不变（用户重新提交即再次进入待审） */
    @PostMapping("/{id}/reject")
    public Map<String, Object> reject(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "AVATAR_REVIEW");
        User u = userRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        if (u.getAvatarPending() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该用户没有待审头像");
        u.setAvatarPending(null);
        u.setAvatarStatus("REJECTED");
        userRepo.save(u);
        notificationService.notifyAvatar(u.getId(), "AVATAR_REJECTED",
                "您提交的新头像未通过审核，已保留原头像；如有疑问请联系管理员");
        audit.log(c.userId(), c.username(), "AVATAR_REJECT", "USER", u.getId(), u.getUsername(),
                "驳回头像审核，沿用原头像");
        return Map.of("ok", true);
    }
}
