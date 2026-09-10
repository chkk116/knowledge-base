package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.PasswordUtil;
import com.kb.kbplatform.security.Perm;
import com.kb.kbplatform.service.OrgService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.security.SecureRandom;

/**
 * 账号管理（仅管理员调用）：增删改、启用/禁用、角色（权限）分配。
 * 角色校验集中在此处，控制器只负责“是否为管理员”的门禁。
 */
@Service
public class AdminService {

    private final UserRepository userRepo;
    private final OrgService orgService;

    public AdminService(UserRepository userRepo, OrgService orgService) {
        this.userRepo = userRepo;
        this.orgService = orgService;
    }

    /** 按 id 取单个用户（不存在抛 404）。
     *  旧实现是 listUsers() = findAll() 全表加载后 stream 过滤取一条，改用主键直查。 */
    public User getUser(Long id) {
        return userRepo.findById(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "用户不存在"));
    }

    /** 审计用：按 id 取用户名（不存在返回 null） */
    public String usernameOf(Long id) {
        return userRepo.findById(id).map(User::getUsername).orElse(null);
    }

    /** 取用户详情（安全视图，不含密码），含已分配角色 id 列表，供管理后台编辑表单预填 */
    public java.util.Map<String, Object> getUserDetail(Long id) {
        User u = getUser(id);
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("role", u.getRole());
        m.put("enabled", u.isEnabled());
        m.put("permissions", u.getPermissions() == null ? "" : u.getPermissions());
        m.put("storageQuota", u.getStorageQuota());
        m.put("departmentId", u.getDepartmentId());
        m.put("roleIds", orgService.getUserRoleIds(id));
        m.put("email", u.getEmail());
        m.put("createdAt", u.getCreatedAt() == null ? "" : u.getCreatedAt().toString());
        return m;
    }

    /** 管理员用户检索 + 排序 + 分页。
     *  sort=newest 按注册时间倒序；sort=points 按积分倒序；sort=role 按角色升序。
     *  role 为空表示不按角色过滤；enabled 为 null 表示不按启用态过滤（false=仅禁用）。
     *  其余/缺省按注册时间倒序。 */
    public Page<User> searchUsers(String keyword, String role, Boolean enabled, String sort, int page, int size) {
        Sort s;
        if ("points".equals(sort)) {
            s = Sort.by(Sort.Direction.DESC, "points");
        } else if ("role".equals(sort)) {
            s = Sort.by(Sort.Direction.ASC, "role").and(Sort.by(Sort.Direction.DESC, "createdAt"));
        } else {
            s = Sort.by(Sort.Direction.DESC, "createdAt");
        }
        if (size <= 0 || size > 100) size = 15;
        if (page < 0) page = 0;
        return userRepo.search(keyword == null ? "" : keyword.trim(),
                role == null || role.isBlank() ? null : role.trim().toUpperCase(),
                enabled, PageRequest.of(page, size, s));
    }

    @Transactional
    public User createUser(CreateReq r) {
        if (r.username == null || r.username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (r.password == null || r.password.length() < 6) {
            throw new IllegalArgumentException("密码至少 6 位");
        }
        if (userRepo.findByUsername(r.username.trim()).isPresent()) {
            throw new IllegalArgumentException("用户名已存在");
        }
        User u = new User();
        u.setUsername(r.username.trim());
        u.setPassword(PasswordUtil.encode(r.password));
        u.setRole(normalizeRole(r.role));
        u.setPermissions(normalizePerms(r.permissions));
        u.setDisplayName(r.displayName == null || r.displayName.isBlank()
                ? r.username.trim() : r.displayName.trim());
        u.setEnabled(r.enabled == null || r.enabled);
        User saved = userRepo.save(u);
        // 新用户尚未登录、无旧 token；分配角色/部门不触发 tokenVersion 自增
        if (r.roleIds != null) orgService.setUserRoles(saved.getId(), r.roleIds);
        if (r.departmentId != null) orgService.setUserDepartment(saved.getId(), r.departmentId);
        return saved;
    }

    /** 审计/控制器用：按 id 取用户（不存在抛异常） */
    public User requireUser(Long id) {
        return userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    @Transactional
    public User updateUser(Long id, UpdateReq r) {
        User u = userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        // 身份类字段（角色 / 权限 / 启用状态）一旦变更，必须让该用户的旧 token 立即失效。
        // 因为 AuthFilter 的鉴权完全读取 token 内的 role/permissions 快照，不回查数据库；
        // 若不递增 tokenVersion，被降权/撤销权限的用户在 token 剩余有效期内仍保有原权限。
        boolean identityChanged = false;

        if (r.displayName != null && !r.displayName.isBlank()) {
            u.setDisplayName(r.displayName.trim());
        }
        if (r.role != null && !r.role.isBlank()) {
            String oldRole = u.getRole();
            u.setRole(normalizeRole(r.role));
            // 角色改为 ADMIN 时清空细粒度权限（ADMIN 隐式拥有全部，无需冗余存储）
            if ("ADMIN".equals(u.getRole())) u.setPermissions("");
            if (!java.util.Objects.equals(oldRole, u.getRole())) identityChanged = true;
        }
        if (r.permissions != null) {
            String oldPerms = u.getPermissions() == null ? "" : u.getPermissions();
            String newPerms = normalizePerms(r.permissions);
            u.setPermissions(newPerms);
            if (!oldPerms.equals(newPerms)) identityChanged = true;
        }
        if (r.password != null && !r.password.isBlank()) {
            if (r.password.length() < 6) throw new IllegalArgumentException("密码至少 6 位");
            u.setPassword(PasswordUtil.encode(r.password));
            u.setTokenVersion(u.getTokenVersion() + 1);   // 管理员改用户密码后，旧 token 立即失效
        }
        if (r.enabled != null) {
            boolean oldEnabled = u.isEnabled();
            u.setEnabled(r.enabled);
            if (oldEnabled != u.isEnabled()) identityChanged = true;
        }
        if (r.storageQuota != null) {
            u.setStorageQuota(r.storageQuota == 0L ? null : r.storageQuota);  // 0 表示恢复系统默认 5GB
        }
        // 部门 / 角色变更同样影响有效权限，需令旧 token 失效
        if (r.departmentId != null) {
            Long oldDept = u.getDepartmentId();
            if (!java.util.Objects.equals(oldDept, r.departmentId)) {
                orgService.setUserDepartment(u.getId(), r.departmentId);   // 同事务内即更新托管实体 u
                identityChanged = true;
            }
        }
        if (r.roleIds != null) {
            java.util.List<Long> old = orgService.getUserRoleIds(u.getId());
            if (!new java.util.HashSet<>(old).equals(new java.util.HashSet<>(r.roleIds))) {
                orgService.setUserRoles(u.getId(), r.roleIds);
                identityChanged = true;
            }
        }
        if (identityChanged) {
            u.setTokenVersion(u.getTokenVersion() + 1);   // 角色/权限/启用状态变更 → 旧 token 立即失效
        }
        return userRepo.save(u);
    }

    @Transactional
    public void deleteUser(Long id) {
        userRepo.deleteById(id);
    }

    /** 管理员重置密码：生成 10 位随机密码并写回（原密码失效），返回明文供管理员转交用户（无 SMTP 故不发邮件） */
    @Transactional
    public String resetPassword(Long id) {
        User u = userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        String raw = genRandomPassword();
        u.setPassword(PasswordUtil.encode(raw));
        u.setTokenVersion(u.getTokenVersion() + 1);   // 管理员重置密码后，旧 token 立即失效
        userRepo.save(u);
        return raw;
    }

    /** 生成 10 位不含易混淆字符的随机密码 */
    private static String genRandomPassword() {
        final String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    @Transactional
    public User toggleUser(Long id, boolean enabled) {
        User u = userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        u.setEnabled(enabled);
        return userRepo.save(u);
    }

    private String normalizeRole(String role) {
        if (role == null) return "USER";
        String r = role.trim().toUpperCase();
        return "ADMIN".equals(r) ? "ADMIN" : "USER";
    }

    /** 规范化权限码串：仅保留白名单内的码，去重、去空、逗号分隔；非法内容丢弃 */
    private String normalizePerms(String perms) {
        if (perms == null || perms.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : perms.split(",")) {
            String t = p.trim().toUpperCase();
            if (Perm.WHITELIST.contains(t) && sb.indexOf(t) < 0) {
                if (sb.length() > 0) sb.append(",");
                sb.append(t);
            }
        }
        return sb.toString();
    }

    // ---- 请求 DTO ----
    public static class CreateReq {
        public String username;
        public String password;
        public String role;
        public String displayName;
        public Boolean enabled;
        public String permissions;   // 细粒度权限码（逗号分隔），仅 ADMIN 操作者可下发
        public Long storageQuota;    // 个人存储总容量（字节）；null = 系统默认 5GB
        public java.util.List<Long> roleIds;   // 分配的角色 id 列表（kb_role.id）
        public Long departmentId;                // 所属部门 id（kb_department.id）
    }

    public static class UpdateReq {
        public String displayName;
        public String role;
        public String password;
        public Boolean enabled;
        public String permissions;   // 细粒度权限码（逗号分隔）
        public Long storageQuota;    // 个人存储总容量（字节）；0 = 恢复系统默认 5GB
        public java.util.List<Long> roleIds;   // 角色 id 列表（整体替换）
        public Long departmentId;                // 所属部门 id（置 null 表示清除部门）
    }
}
