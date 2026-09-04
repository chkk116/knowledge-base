package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.PointsLog;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.PointsLogRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.security.PasswordUtil;
import com.kb.kbplatform.security.TokenUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
public class AuthService {

    // 积分规则
    public static final int LOGIN_POINTS = 5;          // 每日登录
    public static final int PUBLISH_POINTS = 20;       // 每发布一篇
    public static final int HOT_POINTS = 50;           // 帖子成为热帖
    public static final int COURSE_CHAPTER_POINTS = 10; // 每完成一门课程的某一章节
    public static final int EXAM_PASS_POINTS = 30;      // 每次考试及格

    private final UserRepository userRepo;
    private final PointsLogRepository pointsLogRepo;

    @Value("${kb.security.token-secret}")
    private String secret;

    @Value("${kb.security.token-ttl:86400000}")
    private long ttl;

    public AuthService(UserRepository userRepo, PointsLogRepository pointsLogRepo) {
        this.userRepo = userRepo;
        this.pointsLogRepo = pointsLogRepo;
    }

    /**
     * 给指定用户累加积分并落库（无明细记录，按系统调整处理）。
     * 必须标注事务：内部自调用 awardPoints 不会触发 AOP，事务不被继承；而 addPointsRaw 是
     * @Modifying 更新查询，无事务会抛 "No active transaction for update or delete query"。
     */
    @Transactional
    public void addPoints(String username, int delta) {
        userRepo.findByUsername(username).ifPresent(u -> awardPoints(u.getId(), delta, "SYSTEM", "系统调整"));
    }

    /** 按用户 id 累加积分（无明细记录，按系统调整处理） */
    @Transactional
    public void addPointsByUserId(Long userId, int delta) {
        awardPoints(userId, delta, "SYSTEM", "系统调整");
    }

    /** 管理员手动调整积分：写入 ADJUST 类型明细，便于追溯 */
    @Transactional
    public void adjustPoints(Long userId, int delta, String reason) {
        awardPoints(userId, delta, "ADJUST", reason == null || reason.isBlank() ? "管理员调整" : reason);
    }

    /**
     * 统一的积分变动入口：原子更新积分余额并写入积分明细（PointsLog）。
     * 余额通过数据库增量 UPDATE 完成，避免并发 read-modify-write 互相覆盖导致积分少计。
     * @param type  变动类型 LOGIN / PUBLISH / HOT / ADJUST / SYSTEM
     * @param reason 变动说明（如「发布《标题》」）
     * @return 变动后的积分余额
     */
    @Transactional
    public int awardPoints(Long userId, int delta, String type, String reason) {
        if (delta == 0) return 0;
        User u = userRepo.findById(userId).orElse(null);
        if (u == null) return 0;
        userRepo.addPointsRaw(userId, delta);              // 数据库原子增量
        int newBalance = userRepo.findPointsById(userId);   // 标量查询取最新余额（绕过一级缓存）
        PointsLog log = new PointsLog();
        log.setUserId(u.getId());
        log.setUsername(u.getUsername());
        log.setDelta(delta);
        log.setType(type);
        log.setReason(reason == null ? "" : reason);
        log.setBalance(newBalance);
        log.setCreatedAt(LocalDateTime.now());
        pointsLogRepo.save(log);
        return newBalance;
    }

    @org.springframework.transaction.annotation.Transactional
    public Map<String, Object> login(String username, String password) {
        User u = userRepo.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("用户名或密码错误"));
        if (!u.isEnabled()) {
            throw new RuntimeException("账号已被禁用，请联系管理员");
        }
        if (!PasswordUtil.matches(password, u.getPassword())) {
            throw new RuntimeException("用户名或密码错误");
        }
        // 每日登录发分（当天已登录过则不再重复发，避免刷分）
        LocalDate today = LocalDate.now();
        if (!today.equals(u.getLastLoginDate())) {
            awardPoints(u.getId(), LOGIN_POINTS, "LOGIN", "每日登录");
            u.setLastLoginDate(today);
        }
        userRepo.save(u);
        String token = TokenUtil.generate(secret, u.getId(), u.getUsername(), u.getRole(), u.getPermissions(), ttl, u.getTokenVersion());
        String displayName = u.getDisplayName() == null || u.getDisplayName().isBlank()
                ? u.getUsername() : u.getDisplayName();
        // 注意：Map.of 最多支持 10 个键值对，此处字段超过 10 个（含 id），改用 LinkedHashMap 保持顺序且可扩展
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("username", u.getUsername());
        m.put("role", u.getRole());
        m.put("permissions", u.getPermissions() == null ? "" : u.getPermissions());
        m.put("id", u.getId());
        m.put("displayName", displayName);
        m.put("avatar", u.getAvatar() == null ? "" : u.getAvatar());
        m.put("email", u.getEmail() == null ? "" : u.getEmail());
        m.put("bio", u.getBio() == null ? "" : u.getBio());
        m.put("points", u.getPoints());
        m.put("createdAt", u.getCreatedAt() == null ? "" : u.getCreatedAt().toString());
        m.put("aiPersona", u.getAiPersona() == null ? "" : u.getAiPersona());
        return m;
    }

    public void register(String username, String password, String role, String displayName) {
        if (username == null || username.isBlank() || password == null || password.length() < 6) {
            throw new RuntimeException("用户名/密码不合法（密码至少 6 位）");
        }
        if (userRepo.findByUsername(username.trim()).isPresent()) {
            throw new RuntimeException("用户名已存在");
        }
        User u = new User();
        u.setUsername(username.trim());
        u.setPassword(PasswordUtil.encode(password));
        // 安全：注册接口不允许自定角色，普通用户只能注册为 USER；管理员账号由管理员后台创建
        u.setRole("USER");
        String dn = displayName == null || displayName.isBlank() ? username.trim() : displayName.trim();
        u.setDisplayName(HtmlSanitizer.toText(dn).trim());  // 昵称按纯文本存储，防 XSS
        userRepo.save(u);
    }

    public User updateProfile(String username, String displayName, String avatar, String email, String bio,
                              String gender, Boolean hideGender) {
        User u = userRepo.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("账号不存在"));
        // 昵称/简介按纯文本存储，去除一切 HTML 标签，防存储型 XSS
        if (displayName != null && !displayName.isBlank()) {
            u.setDisplayName(HtmlSanitizer.toText(displayName).trim());
        }
        if (avatar != null) {
            // 仅允许 http(s) 链接、data:image 或本地上传路径 /uploads/ 头像，杜绝 javascript: 等危险协议
            if (avatar.isBlank() || avatar.startsWith("http://")
                    || avatar.startsWith("https://") || avatar.startsWith("data:image/")
                    || avatar.startsWith("/uploads/")) {
                u.setAvatar(avatar);   // 允许为空串，用于重置为默认头像
            } else {
                u.setAvatar("");
            }
        }
        if (email != null) {
            u.setEmail(email.trim());
        }
        if (bio != null) {
            u.setBio(HtmlSanitizer.toText(bio));  // 纯文本存储，防 XSS
        }
        // 性别仅允许固定取值，杜绝任意串注入
        if (gender != null) {
            String g = gender.trim();
            u.setGender("男".equals(g) || "女".equals(g) || "保密".equals(g) ? g : "");
        }
        if (hideGender != null) {
            u.setHideGender(hideGender);
        }
        return userRepo.save(u);
    }

    /** 当前登录用户修改密码：校验原密码后更新（新密码至少 6 位） */
    public void changePassword(String username, String oldPassword, String newPassword) {
        if (oldPassword == null || oldPassword.isBlank()
                || newPassword == null || newPassword.length() < 6) {
            throw new RuntimeException("原密码/新密码不合法（新密码至少 6 位）");
        }
        User u = userRepo.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("账号不存在"));
        if (!PasswordUtil.matches(oldPassword, u.getPassword())) {
            throw new RuntimeException("原密码错误");
        }
        u.setPassword(PasswordUtil.encode(newPassword));
        u.setTokenVersion(u.getTokenVersion() + 1);   // 用户改密后，旧 token 立即失效
        userRepo.save(u);
    }
}
