package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AdminService;
import com.kb.kbplatform.service.AuthService;
import com.kb.kbplatform.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminService svc;
    private final AuthService authService;
    private final AuditService audit;

    public AdminUserController(AdminService svc, AuthService authService, AuditService audit) {
        this.svc = svc;
        this.authService = authService;
        this.audit = audit;
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「" + code + "」权限");
        }
    }

    @GetMapping
    public Page<User> list(@RequestAttribute("kbUser") TokenClaims c,
                           @RequestParam(required = false, defaultValue = "") String keyword,
                           @RequestParam(required = false) String role,
                           @RequestParam(required = false) Boolean enabled,
                           @RequestParam(required = false, defaultValue = "newest") String sort,
                           @RequestParam(required = false, defaultValue = "0") int page,
                           @RequestParam(required = false, defaultValue = "15") int size) {
        requirePerm(c, "USER_MANAGE");
        return svc.searchUsers(keyword, role, enabled, sort, page, size);
    }

    @PostMapping
    public User create(@RequestAttribute("kbUser") TokenClaims c,
                       @RequestBody AdminService.CreateReq r) {
        // 防权限提升：普通权限管理员（非 ADMIN）不得创建/赋予管理员角色
        if (!"ADMIN".equals(c.role()) && r.role != null && "ADMIN".equalsIgnoreCase(r.role.trim())) {
            throw new ResponseStatusException(FORBIDDEN, "无权限分配管理员角色");
        }
        requirePerm(c, "USER_MANAGE");
        User u = svc.createUser(r);
        audit.log(c.userId(), c.username(), "CREATE_USER", "USER", u.getId(), u.getUsername(), "创建用户 " + u.getUsername());
        return u;
    }

    @PutMapping("/{id}")
    public User update(@RequestAttribute("kbUser") TokenClaims c,
                       @PathVariable Long id,
                       @RequestBody AdminService.UpdateReq r) {
        if (!"ADMIN".equals(c.role()) && r.role != null && "ADMIN".equalsIgnoreCase(r.role.trim())) {
            throw new ResponseStatusException(FORBIDDEN, "无权限分配管理员角色");
        }
        requirePerm(c, "USER_MANAGE");
        User u = svc.updateUser(id, r);
        audit.log(c.userId(), c.username(), "UPDATE_USER", "USER", id, u.getUsername(), "更新用户 " + u.getUsername());
        return u;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c,
                                       @PathVariable Long id) {
        requirePerm(c, "USER_MANAGE");
        String tname = svc.usernameOf(id);
        svc.deleteUser(id);
        audit.log(c.userId(), c.username(), "DELETE_USER", "USER", id, tname, "删除用户 " + (tname != null ? tname : "id=" + id));
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/toggle")
    public User toggle(@RequestAttribute("kbUser") TokenClaims c,
                       @PathVariable Long id,
                       @RequestBody ToggleReq r) {
        requirePerm(c, "USER_MANAGE");
        User u = svc.toggleUser(id, r.enabled != null && r.enabled);
        audit.log(c.userId(), c.username(), "TOGGLE_USER", "USER", id, u.getUsername(),
                (r.enabled != null && r.enabled ? "启用" : "禁用") + "用户 " + u.getUsername());
        return u;
    }

    public static class ToggleReq {
        public Boolean enabled;
    }

    /** 管理员调整用户积分：delta 可正可负，reason 写入积分明细（类型 ADJUST） */
    @PostMapping("/{id}/points")
    public User adjustPoints(@RequestAttribute("kbUser") TokenClaims c,
                              @PathVariable Long id,
                              @RequestBody PointsReq r) {
        requirePerm(c, "USER_MANAGE");
        if (r.delta == null) throw new ResponseStatusException(FORBIDDEN, "delta 不能为空");
        authService.adjustPoints(id, r.delta, r.reason == null ? "管理员调整" : r.reason);
        User u = svc.listUsers().stream().filter(x -> x.getId().equals(id)).findFirst().orElseThrow();
        audit.log(c.userId(), c.username(), "ADJUST_POINTS", "USER", id, u.getUsername(),
                "调整积分 " + r.delta + "（" + (r.reason == null ? "管理员调整" : r.reason) + "）");
        return u;
    }

    public static class PointsReq {
        public Integer delta;
        public String reason;
    }

    /** 管理员重置用户密码：生成随机密码返回明文，供管理员转交（无 SMTP 故不发邮件） */
    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@RequestAttribute("kbUser") TokenClaims c,
                                              @PathVariable Long id) {
        requirePerm(c, "USER_MANAGE");
        String tname = svc.usernameOf(id);
        String raw = svc.resetPassword(id);
        audit.log(c.userId(), c.username(), "RESET_PWD", "USER", id, tname, "重置用户 " + (tname != null ? tname : "id=" + id) + " 的密码");
        return Map.of("ok", true, "newPassword", raw);
    }
}
