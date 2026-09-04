package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.CaptchaService;
import com.kb.kbplatform.security.RateLimitService;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepo;
    private final CaptchaService captchaService;
    private final RateLimitService rateLimit;

    public AuthController(AuthService authService, UserRepository userRepo,
                          CaptchaService captchaService, RateLimitService rateLimit) {
        this.authService = authService;
        this.userRepo = userRepo;
        this.captchaService = captchaService;
        this.rateLimit = rateLimit;
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
        String ip = request.getRemoteAddr();
        if (!rateLimit.allow("login:ip:" + ip, 10, 10L * 60 * 1000)) {
            throw new RuntimeException("登录尝试过于频繁，请 10 分钟后再试");
        }
        String uname = r.username == null ? "" : r.username.trim().toLowerCase();
        if (!uname.isEmpty() && !rateLimit.allow("login:user:" + uname, 5, 10L * 60 * 1000)) {
            throw new RuntimeException("该账号登录尝试过于频繁，请 10 分钟后再试");
        }
        // 再校验滑动验证码票据（一次性 + 必须已完成滑动），失败直接拒绝，避免对密码做无谓校验
        if (!captchaService.consume(r.captchaToken)) {
            throw new RuntimeException("请先完成滑动验证或刷新后重试");
        }
        return authService.login(r.username, r.password);
    }

    /** 获取登录滑动拼图：返回 { token, bg, piece, w, h, pieceW, pieceH, pieceY }（正确缺口坐标仅存服务端） */
    @GetMapping("/captcha")
    public Map<String, Object> captcha(jakarta.servlet.http.HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if (!rateLimit.allow("captcha:ip:" + ip, 60, 60L * 1000)) {
            throw new RuntimeException("验证码请求过于频繁，请稍后再试");
        }
        return captchaService.generate();
    }

    /** 滑动成功后由前端回调：回传用户偏移 x，服务端校验对齐后才标记通过 */
    @PostMapping("/captcha/slide")
    public Map<String, Object> captchaSlide(@RequestBody Map<String, Object> body,
                                            jakarta.servlet.http.HttpServletRequest request) {
        // 真实客户端 IP：经 Nginx 等反代时 RemoteAddr 是代理地址（会让全站共享一个 IP 被锁），
        // 优先取 X-Forwarded-For 首个地址；环回地址由 CaptchaService 免锁定。
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) ip = request.getRemoteAddr();
        else ip = ip.split(",")[0].trim();
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
        m.put("permissions", u.isPresent() && u.get().getPermissions() != null ? u.get().getPermissions() : "");
        m.put("id", u.isPresent() ? u.get().getId() : claims.userId());
        m.put("displayName", displayName);
        m.put("avatar", avatar);
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
        m.put("permissions", u.getPermissions() == null ? "" : u.getPermissions());
        m.put("id", u.getId());
        m.put("displayName", displayName);
        m.put("avatar", u.getAvatar() == null ? "" : u.getAvatar());
        m.put("email", u.getEmail() == null ? "" : u.getEmail());
        m.put("bio", u.getBio() == null ? "" : u.getBio());
        m.put("gender", u.getGender() == null ? "" : u.getGender());
        m.put("hideGender", u.isHideGender());
        m.put("points", u.getPoints());
        m.put("createdAt", u.getCreatedAt() == null ? "" : u.getCreatedAt().toString());
        m.put("aiPersona", u.getAiPersona() == null ? "" : u.getAiPersona());
        m.put("displayBadge", u.getDisplayBadge() == null ? "" : u.getDisplayBadge());
        m.put("wornBadges", u.getWornList());
        return m;
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody RegisterRequest r,
                                        jakarta.servlet.http.HttpServletRequest request) {
        // 抗刷：同一 IP 每小时最多注册 10 次，缓解批量注册与用户名枚举
        String ip = request.getRemoteAddr();
        if (!rateLimit.allow("register:" + ip, 10, 60L * 60 * 1000)) {
            throw new RuntimeException("注册过于频繁，请稍后再试");
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
}
