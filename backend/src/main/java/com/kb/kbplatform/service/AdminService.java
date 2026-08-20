package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.PasswordUtil;
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

    public AdminService(UserRepository userRepo) {
        this.userRepo = userRepo;
    }

    public List<User> listUsers() {
        return userRepo.findAll();
    }

    /** 审计用：按 id 取用户名（不存在返回 null） */
    public String usernameOf(Long id) {
        return userRepo.findById(id).map(User::getUsername).orElse(null);
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
        if (r.username == null || r.username.isBlank() || r.username.length() < 3) {
            throw new IllegalArgumentException("用户名至少 3 个字符");
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
        return userRepo.save(u);
    }

    @Transactional
    public User updateUser(Long id, UpdateReq r) {
        User u = userRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (r.displayName != null && !r.displayName.isBlank()) {
            u.setDisplayName(r.displayName.trim());
        }
        if (r.role != null && !r.role.isBlank()) {
            u.setRole(normalizeRole(r.role));
            // 角色改为 ADMIN 时清空细粒度权限（ADMIN 隐式拥有全部，无需冗余存储）
            if ("ADMIN".equals(u.getRole())) u.setPermissions("");
        }
        if (r.permissions != null) {
            u.setPermissions(normalizePerms(r.permissions));
        }
        if (r.password != null && !r.password.isBlank()) {
            if (r.password.length() < 6) throw new IllegalArgumentException("密码至少 6 位");
            u.setPassword(PasswordUtil.encode(r.password));
            u.setTokenVersion(u.getTokenVersion() + 1);   // 管理员改用户密码后，旧 token 立即失效
        }
        if (r.enabled != null) {
            u.setEnabled(r.enabled);
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
    private static final java.util.Set<String> PERM_WHITELIST =
            java.util.Set.of("USER_MANAGE", "DOC_REVIEW", "STATS_VIEW", "BLACKLIST_MANAGE", "TAXONOMY_MANAGE", "ANNOUNCEMENT_MANAGE");
    private String normalizePerms(String perms) {
        if (perms == null || perms.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : perms.split(",")) {
            String t = p.trim().toUpperCase();
            if (PERM_WHITELIST.contains(t) && sb.indexOf(t) < 0) {
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
    }

    public static class UpdateReq {
        public String displayName;
        public String role;
        public String password;
        public Boolean enabled;
        public String permissions;   // 细粒度权限码（逗号分隔）
    }
}
