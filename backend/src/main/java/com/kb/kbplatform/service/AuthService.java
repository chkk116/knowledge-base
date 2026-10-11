/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.PointsLog;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.PointsLogRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.security.PasswordUtil;
import com.kb.kbplatform.security.TokenUtil;
import com.kb.kbplatform.service.OrgService;
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
    private final OrgService orgService;

    private final com.kb.kbplatform.security.TokenSecretProvider secretProvider;
    private final NotificationService notificationService;

    @Value("${kb.security.token-ttl:86400000}")
    private long ttl;

    public AuthService(UserRepository userRepo, PointsLogRepository pointsLogRepo, OrgService orgService,
                        com.kb.kbplatform.security.TokenSecretProvider secretProvider,
                        NotificationService notificationService) {
        this.userRepo = userRepo;
        this.pointsLogRepo = pointsLogRepo;
        this.orgService = orgService;
        this.secretProvider = secretProvider;
        this.notificationService = notificationService;
    }

    /**
     * 给指定用户累加积分并落库（无明细记录，按系统调整处理）。
     * 必须标注事务：内部自调用 awardPoints 不会触发 AOP，事务不被继承；而 addPointsRaw 是
     * @Modifying 更新查询，无事务会抛 "No active transaction for update or delete query"。
     */
    @Transactional(rollbackFor = Exception.class)
    public void addPoints(String username, int delta) {
        userRepo.findByUsername(username).ifPresent(u -> awardPoints(u.getId(), delta, "SYSTEM", "系统调整"));
    }

    /** 按用户 id 累加积分（无明细记录，按系统调整处理） */
    @Transactional(rollbackFor = Exception.class)
    public void addPointsByUserId(Long userId, int delta) {
        awardPoints(userId, delta, "SYSTEM", "系统调整");
    }

    /** 管理员手动调整积分：写入 ADJUST 类型明细，便于追溯 */
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
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
                .orElseThrow(() -> new BizException("用户名或密码错误"));
        if (!u.isEnabled()) {
            throw new BizException("账号已被禁用，请联系管理员");
        }
        if (!PasswordUtil.matches(password, u.getPassword())) {
            throw new BizException("用户名或密码错误");
        }
        // 透明升级：存量用户仍为旧 salt:sha256 格式时，登录成功即重哈希为 PBKDF2（有工作因子，抗离线爆破）
        if (!PasswordUtil.isUpgraded(u.getPassword())) {
            u.setPassword(PasswordUtil.encode(password));
        }
        // 每日登录发分（当天已登录过则不再重复发，避免刷分）
        LocalDate today = LocalDate.now();
        int pointsNow = u.getPoints();
        if (!today.equals(u.getLastLoginDate())) {
            // awardPoints 返回原子增量后的新余额；u 里是进方法时的旧值（首登会少 5 分）
            pointsNow = awardPoints(u.getId(), LOGIN_POINTS, "LOGIN", "每日登录");
            u.setLastLoginDate(today);
        }
        userRepo.save(u);
        // 最终有效权限 = 直接权限 ∪ 所有角色权限；ADMIN 角色在 TokenClaims 中隐式全权，无需展开。
        // 展开进 token 的 permissions 快照，使 AuthFilter 无需回查数据库即可按角色鉴权。
        String effectivePerms = orgService.effectivePermissionsString(u);
        String token = TokenUtil.generate(secretProvider.get(), u.getId(), u.getUsername(), u.getRole(), effectivePerms, ttl, u.getTokenVersion());
        String displayName = u.getDisplayName() == null || u.getDisplayName().isBlank()
                ? u.getUsername() : u.getDisplayName();
        // 注意：Map.of 最多支持 10 个键值对，此处字段超过 10 个（含 id），改用 LinkedHashMap 保持顺序且可扩展
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("username", u.getUsername());
        m.put("role", u.getRole());
        m.put("permissions", effectivePerms);
        m.put("id", u.getId());
        m.put("displayName", displayName);
        m.put("avatar", u.getAvatar() == null ? "" : u.getAvatar());
        m.put("email", u.getEmail() == null ? "" : u.getEmail());
        m.put("bio", u.getBio() == null ? "" : u.getBio());
        m.put("points", pointsNow);
        m.put("createdAt", u.getCreatedAt() == null ? "" : u.getCreatedAt().toString());
        m.put("aiPersona", u.getAiPersona() == null ? "" : u.getAiPersona());
        m.put("departmentId", u.getDepartmentId());
        m.put("departmentName", orgService.departmentNameOf(u.getDepartmentId()));
        return m;
    }

    /** 取用户最终有效权限串（供 /auth/me、/auth/profile 回写前端权限快照，与登录令牌保持一致） */
    public String effectivePermissions(User u) {
        return orgService.effectivePermissionsString(u);
    }

    /** 部门名称（供 /auth/me、/auth/profile 回显）；无部门返回空串 */
    public String departmentNameOf(Long deptId) {
        return orgService.departmentNameOf(deptId);
    }

    public void register(String username, String password, String role, String displayName) {
        if (password == null || password.length() < 6) {
            throw new RuntimeException("用户名/密码不合法（密码至少 6 位）");
        }
        // 用户名含竖线会让令牌载荷分段错位 → 该账号每次请求都 401（永远登不进来），必须在建号时拦住
        String name = com.kb.kbplatform.security.UsernamePolicy.normalize(username);
        if (userRepo.findByUsername(name).isPresent()) {
            throw new RuntimeException("用户名已存在");
        }
        User u = new User();
        u.setUsername(name);
        u.setPassword(PasswordUtil.encode(password));
        // 安全：注册接口不允许自定角色，普通用户只能注册为 USER；管理员账号由管理员后台创建
        u.setRole("USER");
        String dn = displayName == null || displayName.isBlank() ? name : displayName.trim();
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
                if (avatar.isBlank()) {
                    // 重置为默认头像无需审核：直接生效并清掉待审
                    u.setAvatar("");
                    u.setAvatarPending(null);
                    u.setAvatarStatus("APPROVED");
                } else {
                    // 头像审核流（方案 A）：新头像进待审区，通过前展示区继续用旧头像
                    boolean isNewPending = !avatar.equals(u.getAvatarPending());
                    u.setAvatarPending(avatar);
                    u.setAvatarStatus("PENDING");
                    // 仅在「新的待审头像」时通知一次，同一张图反复点保存不刷屏
                    if (isNewPending) {
                        notificationService.notifyAvatar(u.getId(), "AVATAR_PENDING",
                                "您的新头像已提交审核，通过前对外仍展示原头像，请勿重复提交");
                        // 提审同步通知全体管理员（与文章提审同一范式），管理员本人提交则跳过自己
                        notificationService.notifyAvatarReviewSubmit(u.getId(), u.getUsername());
                    }
                }
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
            throw new BizException("原密码/新密码不合法（新密码至少 6 位）");
        }
        User u = userRepo.findByUsername(username)
                .orElseThrow(() -> new BizException("账号不存在"));
        if (!PasswordUtil.matches(oldPassword, u.getPassword())) {
            throw new BizException("原密码错误");
        }
        u.setPassword(PasswordUtil.encode(newPassword));
        u.setTokenVersion(u.getTokenVersion() + 1);   // 用户改密后，旧 token 立即失效
        userRepo.save(u);
    }
}
