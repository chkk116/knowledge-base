/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.SpaceAccessService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 团队空间邀请链接。
 *
 * 全部端点都要求登录（AuthFilter 对 /api/space-invites/** 无白名单放行）——
 * 包括预览与兑换，避免匿名探测 token 有效性，也保证加进去的一定是真实账号。
 * 生成/撤销另需空间管理员权限，由 SpaceAccessService 判定。
 */
@RestController
@RequestMapping("/api/space-invites")
public class SpaceInviteController {

    private final SpaceAccessService svc;
    private final UserRepository userRepo;

    public SpaceInviteController(SpaceAccessService svc, UserRepository userRepo) {
        this.svc = svc;
        this.userRepo = userRepo;
    }

    private Long uid(TokenClaims c) {
        if (c == null || c.username() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return userRepo.findByUsername(c.username()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
    }

    private boolean sysAdmin(TokenClaims c) {
        return c != null && "ADMIN".equals(c.role());
    }

    private Integer intOf(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) return null;
        try {
            return Integer.valueOf(String.valueOf(body.get(key)).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 当前有效邀请（管理员在空间成员弹窗里回显） */
    @GetMapping("/space/{spaceId}")
    public Map<String, Object> info(@RequestAttribute("kbUser") TokenClaims c,
                                    @PathVariable Long spaceId) {
        return svc.inviteInfo(spaceId, uid(c), sysAdmin(c));
    }

    /** 生成 / 重生成邀请链接；旧的立即失效 */
    @PostMapping("/space/{spaceId}")
    public Map<String, Object> create(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long spaceId,
                                      @RequestBody(required = false) Map<String, Object> body) {
        return svc.createInvite(spaceId, uid(c), c.username(), sysAdmin(c),
                intOf(body, "expireDays"), intOf(body, "maxUses"));
    }

    /** 撤销：所有未撤销邀请置为 revoked */
    @DeleteMapping("/space/{spaceId}")
    public Map<String, Object> revoke(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long spaceId) {
        svc.revokeInvite(spaceId, uid(c), c.username(), sysAdmin(c));
        return Map.of("ok", true);
    }

    /** 链接预览：空间名 / 邀请人 / 有效期 / 是否已是成员 */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestAttribute("kbUser") TokenClaims c,
                                       @RequestParam String token) {
        return svc.preview(token, uid(c));
    }

    /** 兑换：加入空间（只读），已是成员则幂等 */
    @PostMapping("/redeem")
    public Map<String, Object> redeem(@RequestAttribute("kbUser") TokenClaims c,
                                      @RequestBody Map<String, Object> body) {
        String token = body == null || body.get("token") == null ? null : String.valueOf(body.get("token"));
        return svc.redeem(token, uid(c), c.username());
    }
}
