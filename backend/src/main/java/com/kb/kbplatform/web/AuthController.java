/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.CaptchaService;
import com.kb.kbplatform.security.RateLimitService;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuthService;
import com.kb.kbplatform.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.kb.kbplatform.service.BizException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final UserRepository userRepo;
    private final CaptchaService captchaService;
    private final RateLimitService rateLimit;
    private final com.kb.kbplatform.service.LoginLogService loginLogService;
    private final NotificationService notificationService;
    private final com.kb.kbplatform.service.AccountDeletionService deletionService;

    public AuthController(AuthService authService, UserRepository userRepo,
                          CaptchaService captchaService, RateLimitService rateLimit,
                          com.kb.kbplatform.service.LoginLogService loginLogService,
                          NotificationService notificationService,
                          com.kb.kbplatform.service.AccountDeletionService deletionService) {
        this.authService = authService;
        this.userRepo = userRepo;
        this.captchaService = captchaService;
        this.rateLimit = rateLimit;
        this.loginLogService = loginLogService;
        this.notificationService = notificationService;
        this.deletionService = deletionService;
    }

    public static class LoginRequest {
        public String username;
        public String password;
        public String captchaToken;   // 图形验证码令牌
        public String captcha;        // 用户输入的验证码
    }

    public static class RegisterRequest {
        public String username;
        public String password;
        public String role;
        public String displayName;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest r,
                                     jakarta.servlet.http.HttpServletRequest request) {
        // 抗爆破第一道：IP + 用户名双维滑动窗口限流（滑动验证码为前端判定，脚本可绕过，
        // 服务端限流才是真闸门）。IP 10 次/10 分钟；单用户名 5 次/10 分钟。
        String ip = com.kb.kbplatform.security.ClientIp.resolve(request);
        if (!rateLimit.allow("login:ip:" + ip, 10, 10L * 60 * 1000)) {
            throw new BizException("登录尝试过于频繁，请 10 分钟后再试");
        }
        // 再校验滑动验证码票据（一次性 + 必须已完成滑动），失败直接拒绝，避免对密码做无谓校验
        if (!captchaService.consume(r.captchaToken)) {
            throw new BizException("请先完成滑动验证或刷新后重试");
        }
        // 用户名维度限流：放在验证码通过之后（外部审查 2026-10-11：原先在验证码前计数，
        // 攻击者刷验证码失败就能烧掉目标账号的登录窗口 = 变相远程锁号）。IP 维度仍在最前。
        String uname = r.username == null ? "" : r.username.trim().toLowerCase();
        if (!uname.isEmpty() && !rateLimit.allow("login:user:" + uname, 5, 10L * 60 * 1000)) {
            throw new BizException("该账号登录尝试过于频繁，请 10 分钟后再试");
        }
        // 登录日志：成功/失败都记（失败含原因）。用户名原样记录，便于排查爆破来源。
        String logName = r.username == null ? "" : r.username.trim();
        try {
            Map<String, Object> result = authService.login(r.username, r.password);
            Object uid = result.get("id");
            loginLogService.record(uid instanceof Number ? ((Number) uid).longValue() : null,
                    logName, true, null, request);
            // 高危登录告警（2026-09-12 新增）：管理员账号经非浏览器客户端（python-urllib/curl/java 等
            // 脚本 UA）成功登录 → 立即通知全体管理员。判定：UA 为空或不含 Mozilla（所有主流浏览器
            // UA 都以 Mozilla/ 开头）。告警失败不影响登录本身。
            try {
                User lu = (uid instanceof Number)
                        ? userRepo.findById(((Number) uid).longValue()).orElse(null) : null;
                String ua = request.getHeader("User-Agent");
                boolean suspiciousClient = ua == null || !ua.contains("Mozilla");
                // 与 TokenClaims.isAdmin() 语义对齐（2026-09-21 ocr 评审）：真实 ADMIN 或权限覆盖全集的
                // 账号都按管理员告警——否则全权限角色拿到完整管理 API 却绕过告警，留下覆盖缺口。
                // effectivePerms 是逗号分隔字符串（AuthService 直接透传）；经 OrgService.effectivePermissions 的 Perm.valid 过滤，
                // 实际只含 WHITELIST 内的码，"*" 分支仅为防御（与 TokenClaims.hasPerm 的通配检查保持一致）。
                java.util.Set<String> have = new java.util.HashSet<>();
                for (String p : String.valueOf(result.get("permissions")).split(",")) {
                    String t = p.trim().toUpperCase();
                    if (!t.isEmpty()) have.add(t);
                }
                boolean coversAll = have.contains("*")
                        || have.containsAll(com.kb.kbplatform.security.Perm.WHITELIST);
                boolean adminLike = lu != null && ("ADMIN".equals(lu.getRole()) || coversAll);
                if (adminLike && suspiciousClient) {
                    String xff = request.getHeader("X-Forwarded-For");
                    // nginx $proxy_add_x_forwarded_for 会把真实地址附加在尾部，首段可被客户端伪造——
                    // 两段都带上，事件响应时可甄别（第二段为 TCP 层来源，不可伪造）
                    String realIp = (xff == null || xff.isBlank()) ? request.getRemoteAddr()
                            : xff.split(",")[0].trim() + " (via " + request.getRemoteAddr() + ")";
                    notificationService.notifySecurityAlert(logName, realIp, ua);
                }
            } catch (Exception alertEx) {
                // 告警只是旁路，登录主流程不受影响；但留痕便于排查"为什么没收到告警"
                log.warn("发送 SECURITY_ALERT 告警失败（不影响登录）: {}", alertEx.getMessage());
            }
            return result;
        } catch (RuntimeException e) {
            // 找得到账号则带上 userId；用户名不存在则记 null
            Long uid = userRepo.findByUsername(logName.toLowerCase()).map(User::getId).orElse(null);
            loginLogService.record(uid, logName, false, e.getMessage(), request);
            throw e;
        }
    }

    /** 获取登录滑动拼图：返回 { token, bg, piece, w, h, pieceW, pieceH, pieceY }（正确缺口坐标仅存服务端） */
    @GetMapping("/captcha")
    public Map<String, Object> captcha(jakarta.servlet.http.HttpServletRequest request) {
        String ip = com.kb.kbplatform.security.ClientIp.resolve(request);
        if (!rateLimit.allow("captcha:ip:" + ip, 60, 60L * 1000)) {
            throw new BizException("验证码请求过于频繁，请稍后再试");
        }
        return captchaService.generate();
    }

    /** 滑动成功后由前端回调：回传用户偏移 x，服务端校验对齐后才标记通过 */
    @PostMapping("/captcha/slide")
    public Map<String, Object> captchaSlide(@RequestBody Map<String, Object> body,
                                            jakarta.servlet.http.HttpServletRequest request) {
        // 统一客户端 IP 解析（X-Real-IP → XFF 首段 → RemoteAddr）；环回地址由 CaptchaService 免锁定。
        String ip = com.kb.kbplatform.security.ClientIp.resolve(request);
        if (!rateLimit.allow("slide:ip:" + ip, 30, 60L * 1000)) {
            return slideFail("验证过于频繁，请稍后再试");
        }
        String token = body.get("token") == null ? null : String.valueOf(body.get("token"));
        Object xObj = body.get("x");
        Integer x = (xObj instanceof Number) ? ((Number) xObj).intValue() : null;
        List<Map<String, Object>> traj = null;
        Object trajObj = body.get("trajectory");
        if (trajObj instanceof List) {
            try { traj = (List<Map<String, Object>>) trajObj; } catch (Exception ignore) {}
        }
        // IP 级锁定优先提示，避免脚本高频试探时只收到笼统的 false
        if (captchaService.isBlocked(ip)) {
            return slideFail("验证失败次数过多，账号已被临时锁定 10 分钟，请稍后再试");
        }
        boolean ok = captchaService.markPassed(token, x, traj, ip);
        if (!ok) {
            // 对齐失败 / 轨迹异常 / 票据超次：明确告知需重新拖动。返回 200 + ok:false（业务结果，非 HTTP 错误），
            // 避免浏览器把正常"没拼对"标红成 400 影响观感；前端按 data.ok 判定，提示文案见 error。
            return slideFail("滑动验证未通过，请重新拖动拼图完成验证");
        }
        return Map.of("ok", true);
    }

    /** 滑动验证失败的统一回包：HTTP 200 + { ok:false, error }，让前端按业务结果处理，不产生控制台 4xx 红字 */
    private Map<String, Object> slideFail(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return m;
    }

    @GetMapping("/me")
    public Map<String, Object> me(@RequestAttribute("kbUser") TokenClaims claims) {
        String displayName = claims.username();
        String avatar = "";
        var u = userRepo.findByUsername(claims.username());
        if (u.isPresent()) {
            if (u.get().getDisplayName() != null && !u.get().getDisplayName().isBlank()) {
                displayName = u.get().getDisplayName();
            }
            avatar = u.get().getAvatar() == null ? "" : u.get().getAvatar();
        }
        String email = u.isPresent() && u.get().getEmail() != null ? u.get().getEmail() : "";
        String bio = u.isPresent() && u.get().getBio() != null ? u.get().getBio() : "";
        String gender = u.isPresent() && u.get().getGender() != null ? u.get().getGender() : "";
        boolean hideGender = u.isPresent() && u.get().isHideGender();
        String aiPersona = u.isPresent() && u.get().getAiPersona() != null ? u.get().getAiPersona() : "";
        String displayBadge = u.isPresent() && u.get().getDisplayBadge() != null ? u.get().getDisplayBadge() : "";
        List<String> wornBadges = u.isPresent() ? u.get().getWornList() : new ArrayList<>();
        // 注意：Map.of 最多支持 10 个键值对，此处字段超过 10 个，改用 LinkedHashMap 保持顺序且可扩展
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", claims.username());
        m.put("role", claims.role());
        m.put("permissions", u.isPresent() ? authService.effectivePermissions(u.get()) : "");
        m.put("id", u.isPresent() ? u.get().getId() : claims.userId());
        m.put("displayName", displayName);
        m.put("avatar", avatar);
        m.put("avatarPending", u.map(x -> x.getAvatarPending()).orElse(null));   // 头像审核中标记（null=无待审）
        m.put("email", email);
        m.put("bio", bio);
        m.put("gender", gender);
        m.put("hideGender", hideGender);
        m.put("points", u.isPresent() ? u.get().getPoints() : 0);
        m.put("createdAt", u.isPresent() && u.get().getCreatedAt() != null
                ? u.get().getCreatedAt().toString() : "");
        m.put("aiPersona", aiPersona);
        m.put("displayBadge", displayBadge);
        m.put("wornBadges", wornBadges);
        m.put("departmentId", u.isPresent() ? u.get().getDepartmentId() : null);
        m.put("departmentName", u.isPresent() ? authService.departmentNameOf(u.get().getDepartmentId()) : "");
        return m;
    }

    public static class ProfileRequest {
        public String displayName;
        public String avatar;
        public String email;
        public String bio;
        public String gender;
        public Boolean hideGender;
    }

    /** 当前登录用户修改个人资料（昵称 / 头像 / 邮箱 / 简介 / 性别 / 隐藏性别） */
    @PutMapping("/profile")
    public Map<String, Object> profile(@RequestAttribute("kbUser") TokenClaims claims,
                                       @RequestBody ProfileRequest r) {
        User u = authService.updateProfile(claims.username(), r.displayName, r.avatar, r.email, r.bio,
                r.gender, r.hideGender);
        String displayName = u.getDisplayName() == null || u.getDisplayName().isBlank()
                ? u.getUsername() : u.getDisplayName();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("username", u.getUsername());
        m.put("role", u.getRole());
        // 必须与 /auth/me 返回同样的身份字段：前端 setProfile() 会用这份响应整体覆盖
        // auth.user，缺 id/permissions 会导致「保存资料后后台权限全部丢失」（hasPerm 恒 false）。
        m.put("permissions", authService.effectivePermissions(u));
        m.put("id", u.getId());
        m.put("displayName", displayName);
        m.put("avatar", u.getAvatar() == null ? "" : u.getAvatar());
        m.put("avatarPending", u.getAvatarPending());   // 头像审核中标记
        m.put("avatarStatus", u.getAvatarStatus() == null ? "APPROVED" : u.getAvatarStatus());
        m.put("email", u.getEmail() == null ? "" : u.getEmail());
        m.put("bio", u.getBio() == null ? "" : u.getBio());
        m.put("gender", u.getGender() == null ? "" : u.getGender());
        m.put("hideGender", u.isHideGender());
        m.put("points", u.getPoints());
        m.put("createdAt", u.getCreatedAt() == null ? "" : u.getCreatedAt().toString());
        m.put("aiPersona", u.getAiPersona() == null ? "" : u.getAiPersona());
        m.put("displayBadge", u.getDisplayBadge() == null ? "" : u.getDisplayBadge());
        m.put("wornBadges", u.getWornList());
        m.put("departmentId", u.getDepartmentId());
        m.put("departmentName", authService.departmentNameOf(u.getDepartmentId()));
        return m;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest r,
                                        jakarta.servlet.http.HttpServletRequest request) {
        // 抗刷：同一 IP 每小时最多注册 10 次，缓解批量注册与用户名枚举
        String ip = com.kb.kbplatform.security.ClientIp.resolve(request);
        if (!rateLimit.allow("register:" + ip, 10, 60L * 60 * 1000)) {
            throw new BizException("注册过于频繁，请稍后再试");
        }
        authService.register(r.username, r.password, r.role, r.displayName);
        return Map.of("ok", true);
    }

    public static class ChangePasswordRequest {
        public String oldPassword;
        public String newPassword;
    }

    /** 当前登录用户修改密码（需校验原密码） */
    @PostMapping("/change-password")
    public Map<String, Object> changePassword(@RequestAttribute("kbUser") TokenClaims claims,
                                              @RequestBody ChangePasswordRequest r) {
        authService.changePassword(claims.username(), r.oldPassword, r.newPassword);
        return Map.of("ok", true);
    }

    /**
     * 自助注销当前账号（满足 Google Play「应用内账户删除」要求）。
     * 密码二次确认 + 软删除账号（PII 清除、公共内容匿名显示）+ 个人数据硬删，
     * 详见 AccountDeletionService（2026-10-07 外部审查：原实现仅删 user 行，关联数据残留）。
     */
    @DeleteMapping("/me")
    public Map<String, Object> deleteMe(@RequestAttribute("kbUser") TokenClaims claims,
                                        @RequestBody(required = false) Map<String, String> body) {
        User u = userRepo.findByUsername(claims.username()).orElse(null);
        if (u == null) return Map.of("ok", true);
        deletionService.deleteAccount(u, body == null ? null : body.get("password"));
        return Map.of("ok", true);
    }
}
