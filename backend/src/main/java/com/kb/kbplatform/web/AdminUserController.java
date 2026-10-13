/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Role;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AdminService;
import com.kb.kbplatform.service.AuthService;
import com.kb.kbplatform.service.AuditService;
import com.kb.kbplatform.service.DocShareService;
import com.kb.kbplatform.service.OrgService;
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
    private final DocShareService docShareSvc;
    private final OrgService orgService;

    public AdminUserController(AdminService svc, AuthService authService, AuditService audit, DocShareService docShareSvc,
                               OrgService orgService) {
        this.svc = svc;
        this.authService = authService;
        this.audit = audit;
        this.docShareSvc = docShareSvc;
        this.orgService = orgService;
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「" + code + "」权限");
        }
    }

    /** 权限串 → 码表（逗号分隔） */
    private static List<String> permCodes(String s) {
        if (s == null || s.isBlank()) return List.of();
        return java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
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

    /** 用户详情（安全视图，不含密码），含已分配角色 id 列表，供编辑表单预填 */
    @GetMapping("/{id}")
    public Map<String, Object> detail(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "USER_MANAGE");
        return svc.getUserDetail(id);
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
        // 防提权（2026-10-13 审查 C3）：非 ADMIN 操作者给他人分配的细粒度权限 / 角色，必须是自身权限的子集。
        // 否则持 USER_MANAGE 者可把全权限角色分配给任意普通账号（或新建账号）间接提权。
        if (!"ADMIN".equals(c.role())) {
            if (r.permissions != null && !r.permissions.isBlank()) {
                List<String> codes = permCodes(r.permissions);
                if (!codes.isEmpty() && !c.hasAllPerms(codes))
                    throw new ResponseStatusException(FORBIDDEN, "不能授予自身未拥有的权限码");
            }
            if (r.roleIds != null) {
                for (Long rid : r.roleIds) {
                    Role role = orgService.getRole(rid);
                    List<String> codes = permCodes(role.getPermissions());
                    if (!codes.isEmpty() && !c.hasAllPerms(codes))
                        throw new ResponseStatusException(FORBIDDEN, "不能授予他人自身未拥有的角色「" + role.getName() + "」");
                }
            }
        }
        // 防自助提权：非 ADMIN 角色不得给自己改角色 / 细粒度权限 / 启用状态。
        // 否则持有 USER_MANAGE 的子管理员可直接给自己写满权限码（含 CORE_MAINTAIN、DOC_REVIEW）完成越权升级。
        if (id.equals(c.userId()) && !"ADMIN".equals(c.role())) {
            if (r.role != null || r.permissions != null || r.enabled != null
                    || r.roleIds != null || r.departmentId != null) {
                throw new ResponseStatusException(FORBIDDEN, "不能修改自身的角色、权限、部门或启用状态");
            }
        }
        User before = svc.requireUser(id);
        Long oldQuota = before.getStorageQuota();
        User u = svc.updateUser(id, r);
        Long newQuota = u.getStorageQuota();
        if (r.storageQuota != null) {
            audit.log(c.userId(), c.username(), "UPDATE_USER_QUOTA", "USER", id, u.getUsername(),
                    "调整用户「" + u.getUsername() + "」存储配额：" + formatQuota(oldQuota) + " → " + formatQuota(newQuota));
        } else {
            audit.log(c.userId(), c.username(), "UPDATE_USER", "USER", id, u.getUsername(), "更新用户 " + u.getUsername());
        }
        return u;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c,
                                       @PathVariable Long id) {
        requirePerm(c, "USER_MANAGE");
        if (id.equals(c.userId())) {
            throw new ResponseStatusException(FORBIDDEN, "不能删除自身账号");
        }
        // 防权限提升：非 ADMIN 角色不得删除管理员用户（避免普通管理员删光管理员接管）
        User target = svc.requireUser(id);
        if (!"ADMIN".equals(c.role()) && "ADMIN".equals(target.getRole())) {
            throw new ResponseStatusException(FORBIDDEN, "无权限删除管理员账号");
        }
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
        if (id.equals(c.userId())) {
            throw new ResponseStatusException(FORBIDDEN, "不能禁用/启用自身账号");
        }
        User target = svc.requireUser(id);
        if (!"ADMIN".equals(c.role()) && "ADMIN".equals(target.getRole())) {
            throw new ResponseStatusException(FORBIDDEN, "无权限操作管理员账号");
        }
        User u = svc.toggleUser(id, r.enabled != null && r.enabled);
        audit.log(c.userId(), c.username(), "TOGGLE_USER", "USER", id, u.getUsername(),
                (r.enabled != null && r.enabled ? "启用" : "禁用") + "用户 " + u.getUsername());
        return u;
    }

    public static class ToggleReq {
        public Boolean enabled;
    }

    /** 管理员查看任意用户的存储用量（已用 / 有效配额），用于用户管理页容量列 */
    @GetMapping("/{id}/storage")
    public Map<String, Object> userStorage(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "USER_MANAGE");
        long used = docShareSvc.storageUsage(id);
        long quota = docShareSvc.effectiveUserQuota(id);
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("used", used);
        m.put("quota", quota);
        m.put("username", svc.usernameOf(id));
        return m;
    }

    /** 管理员调整用户积分：delta 可正可负，reason 写入积分明细（类型 ADJUST） */
    @PostMapping("/{id}/points")
    public User adjustPoints(@RequestAttribute("kbUser") TokenClaims c,
                              @PathVariable Long id,
                              @RequestBody PointsReq r) {
        requirePerm(c, "USER_MANAGE");
        if (r.delta == null) throw new ResponseStatusException(FORBIDDEN, "delta 不能为空");
        authService.adjustPoints(id, r.delta, r.reason == null ? "管理员调整" : r.reason);
        User u = svc.getUser(id);   // 旧实现：listUsers() 全表加载后 stream 过滤取一条
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
        if (id.equals(c.userId())) {
            throw new ResponseStatusException(FORBIDDEN, "不能重置自身密码");
        }
        // 防权限提升：非 ADMIN 角色不得重置管理员密码（拿到明文即接管）
        User target = svc.requireUser(id);
        if (!"ADMIN".equals(c.role()) && "ADMIN".equals(target.getRole())) {
            throw new ResponseStatusException(FORBIDDEN, "无权限重置管理员密码");
        }
        String tname = svc.usernameOf(id);
        String raw = svc.resetPassword(id);
        audit.log(c.userId(), c.username(), "RESET_PWD", "USER", id, tname, "重置用户 " + (tname != null ? tname : "id=" + id) + " 的密码");
        return Map.of("ok", true, "newPassword", raw);
    }

    private static String formatQuota(Long bytes) {
        if (bytes == null) return "系统默认";
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024L) return String.format("%.2f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
