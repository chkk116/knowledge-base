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
import com.kb.kbplatform.service.MailService;
import com.kb.kbplatform.service.OrgService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminService svc;
    private final AuthService authService;
    private final AuditService audit;
    private final DocShareService docShareSvc;
    private final OrgService orgService;
    private final com.kb.kbplatform.service.PasswordResetService passwordResetService;
    private final MailService mailService;
    private final com.kb.kbplatform.service.SpaceAccessService spaceAccess;
    private final String siteBaseUrl;

    public AdminUserController(AdminService svc, AuthService authService, AuditService audit, DocShareService docShareSvc,
                               OrgService orgService,
                               com.kb.kbplatform.service.PasswordResetService passwordResetService,
                               MailService mailService,
                               com.kb.kbplatform.service.SpaceAccessService spaceAccess,
                               @Value("${KB_PUBLIC_BASE_URL:https://kb.example.com}") String siteBaseUrl) {
        this.svc = svc;
        this.authService = authService;
        this.audit = audit;
        this.docShareSvc = docShareSvc;
        this.orgService = orgService;
        this.passwordResetService = passwordResetService;
        this.mailService = mailService;
        this.spaceAccess = spaceAccess;
        this.siteBaseUrl = siteBaseUrl.endsWith("/") ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1) : siteBaseUrl;
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
        // 防提权（审查 NC1）：create 与 update 同口径——非 ADMIN 分配的细粒度权限 / 角色
        // 必须是自身权限的子集，否则子管理员可借新建账号一键提权
        if (!"ADMIN".equals(c.role())) {
            if (r.permissions != null && !r.permissions.isBlank()) {
                List<String> codes = permCodes(r.permissions);
                if (!codes.isEmpty() && !c.hasAllPerms(codes))
                    throw new ResponseStatusException(FORBIDDEN, "不能授予自身未拥有的权限码");
            }
            if (r.roleIds != null) {
                for (Long rid : r.roleIds) {
                    Role role = orgService.getRole(rid);
                    List<String> rcodes = permCodes(role.getPermissions());
                    if (!rcodes.isEmpty() && !c.hasAllPerms(rcodes))
                        throw new ResponseStatusException(FORBIDDEN, "不能授予自身未拥有的角色权限");
                }
            }
        }
        User u = svc.createUser(r);
        audit.log(c.userId(), c.username(), "CREATE_USER", "USER", u.getId(), u.getUsername(), "创建用户 " + u.getUsername());
        spaceAccess.joinDefaultSpace(u.getId(), u.getUsername());
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
        // 防提权（审查 r21 critical）：非 ADMIN 不得修改 ADMIN 账号的任何信息
        // （与 delete/toggle/reset-password 同口径，否则可改 ADMIN 的昵称/邮箱等字段）
        if (!"ADMIN".equals(c.role()) && "ADMIN".equals(svc.requireUser(id).getRole())) {
            throw new ResponseStatusException(FORBIDDEN, "无权限修改管理员账号");
        }
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

    /** 非 ADMIN 操作者不得触碰「权限高于自己」的账号（改密/重置链接等，审查 r22 High） */
    private void guardTargetNotHigher(TokenClaims c, User target) {
        if ("ADMIN".equals(c.role())) return;
        if ("ADMIN".equals(target.getRole())) {
            throw new ResponseStatusException(FORBIDDEN, "无权限操作管理员账号");
        }
        List<String> tCodes = permCodes(target.getPermissions());
        if (!tCodes.isEmpty() && !c.hasAllPerms(tCodes)) {
            throw new ResponseStatusException(FORBIDDEN, "无权限操作权限更高的账号");
        }
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
        guardTargetNotHigher(c, target);
        String tname = svc.usernameOf(id);
        String raw = svc.resetPassword(id);
        audit.log(c.userId(), c.username(), "RESET_PWD", "USER", id, tname, "重置用户 " + (tname != null ? tname : "id=" + id) + " 的密码");
        return Map.of("ok", true, "newPassword", raw);
    }

    /** 生成一次性密码重置链接（30 分钟有效）；管理员不接触新密码，由用户自己设置 */
    @PostMapping("/{id}/reset-link")
    public Map<String, Object> resetLink(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "USER_MANAGE");
        User target = svc.requireUser(id);
        guardTargetNotHigher(c, target);
        Map<String, Object> m = passwordResetService.createLink(id, c.userId());
        // 已验证邮箱 + SMTP 已配置 → 自动把重置链接发到用户邮箱（不再只靠手动转交；token 仍返回供兜底）
        boolean emailed = false;
        if (target.getEmail() != null && target.isEmailVerified() && mailService.isConfigured()) {
            try {
                String link = siteBaseUrl + "/reset-password?token=" + m.get("token");
                mailService.sendResetLink(target, link);
                emailed = true;
            } catch (Exception e) {
                // 邮件失败不影响链接生成，token 仍返回供管理员手动转交
            }
        }
        m.put("emailed", emailed);
        audit.log(c.userId(), c.username(), "RESET_LINK", "USER", id, target.getUsername(),
                "生成密码重置链接：" + target.getUsername() + (emailed ? "（已邮件发送）" : "（未邮件发送）"));
        return m;
    }

    /**
     * 批量导入用户（企业部署第一步：导组织架构与账号）。
     *
     * 前端把 CSV/XLSX 解析成行数组再提交（后端不引 Excel 依赖）；逐行处理、互不影响，
     * 返回每行结果。dryRun=true 只校验不落库，供导入前预览。
     */
    @PostMapping("/import")
    public Map<String, Object> importUsers(@RequestAttribute("kbUser") TokenClaims c,
                                           @RequestBody ImportReq req) {
        requirePerm(c, "USER_MANAGE");
        if (req == null || req.rows == null || req.rows.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "没有可导入的数据");
        }
        if (req.rows.size() > 1000) {
            throw new ResponseStatusException(BAD_REQUEST, "单次最多导入 1000 行，请拆分文件");
        }
        boolean dry = Boolean.TRUE.equals(req.dryRun);
        String defaultPwd = req.defaultPassword == null ? "" : req.defaultPassword.trim();
        boolean operatorAdmin = "ADMIN".equals(c.role());

        List<Map<String, Object>> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        // 部门索引只查一次库：批量导入每行都可能有部门，逐行查会放大成 N 次查询
        Map<String, Long> deptIndex = orgService.departmentLookup();
        int created = 0, skipped = 0, failed = 0;

        for (int i = 0; i < req.rows.size(); i++) {
            ImportRow r = req.rows.get(i);
            int line = i + 2;   // 表头占第 1 行，便于用户对照 Excel 行号
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("line", line);
            String uname = r == null || r.username == null ? "" : r.username.trim();
            res.put("username", uname);
            try {
                if (uname.isEmpty()) throw new IllegalArgumentException("用户名为空");
                if (!seen.add(uname)) throw new IllegalArgumentException("文件内用户名重复：" + uname);
                // 查重放在 dryRun 之前：否则「校验」会告诉用户可创建，实际导入却被跳过
                if (svc.usernameExists(uname)) throw new IllegalArgumentException("用户名已存在：" + uname);
                String pwd = (r.password == null || r.password.isBlank()) ? defaultPwd : r.password;
                if (pwd.isEmpty()) throw new IllegalArgumentException("缺少密码（未填且未设置默认密码）");
                String role = (r.role == null || r.role.isBlank()) ? "USER" : r.role.trim().toUpperCase();
                if (!"ADMIN".equals(role) && !"USER".equals(role)) {
                    throw new IllegalArgumentException("角色只能是 USER 或 ADMIN");
                }
                if (!operatorAdmin && "ADMIN".equals(role)) {
                    throw new IllegalArgumentException("无权限创建管理员");
                }
                // 组织架构同步（手动一键通路）：导入行可填部门名称或部门编码，
                // dryRun 也校验，避免「校验通过、实际导入却因部门找不到失败」
                String dept = r.department == null ? "" : r.department.trim();
                Long deptId = null;
                if (!dept.isEmpty()) {
                    deptId = deptIndex.get(OrgService.lookupKey(dept));
                    if (deptId == null) {
                        throw new IllegalArgumentException("部门不存在或已停用：" + dept + "（请填部门编码或已建好的部门名称）");
                    }
                    res.put("department", dept);
                }

                if (!dry) {
                    AdminService.CreateReq cr = new AdminService.CreateReq();
                    cr.username = uname;
                    cr.password = pwd;
                    cr.displayName = r.displayName;
                    cr.email = r.email;
                    cr.role = role;
                    cr.departmentId = deptId;
                    User u = svc.createUser(cr);
                    res.put("userId", u.getId());
                    spaceAccess.joinDefaultSpace(u.getId(), u.getUsername());
                }
                res.put("status", dry ? "ok" : "created");
                created++;
            } catch (IllegalArgumentException e) {
                String msg = e.getMessage() == null ? "导入失败" : e.getMessage();
                if (msg.contains("已存在")) { res.put("status", "skipped"); skipped++; }
                else { res.put("status", "failed"); failed++; }
                res.put("message", msg);
            } catch (Exception e) {
                res.put("status", "failed");
                failed++;
                res.put("message", "系统异常：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            }
            results.add(res);
        }

        if (!dry) {
            audit.log(c.userId(), c.username(), "IMPORT_USERS", "USER", null, null,
                    "批量导入用户：成功 " + created + "，跳过 " + skipped + "，失败 " + failed);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dryRun", dry);
        out.put("total", req.rows.size());
        out.put("created", created);
        out.put("skipped", skipped);
        out.put("failed", failed);
        out.put("results", results);
        return out;
    }

    /** 批量导入入参 */
    public static class ImportReq {
        public List<ImportRow> rows;
        public String defaultPassword;
        public Boolean dryRun;
    }

    /** 一行用户数据（前端已按表头映射好） */
    public static class ImportRow {
        public String username;
        public String displayName;
        public String email;
        public String password;
        public String role;
        public String department;   // 部门名称或编码（可选，手动组织架构同步用）
    }

    private static String formatQuota(Long bytes) {
        if (bytes == null) return "系统默认";
        if (bytes >= 1024L * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        if (bytes >= 1024L * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        if (bytes >= 1024L) return String.format("%.2f KB", bytes / 1024.0);
        return bytes + " B";
    }
}
