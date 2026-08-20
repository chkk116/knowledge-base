package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.CaptchaService;
import com.kb.kbplatform.security.RateLimitService;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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

    /** 获取登录滑动拼图背景图：返回 { token, image(base64 PNG) } */
    @GetMapping("/captcha")
    public Map<String, String> captcha() {
        return captchaService.generate();
    }

    /** 滑动成功后由前端回调：标记票据为已通过 */
    @PostMapping("/captcha/slide")
    public Map<String, Object> captchaSlide(@RequestBody Map<String, String> body) {
        boolean ok = captchaService.markPassed(body.get("token"));
        return Map.of("ok", ok);
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
        return Map.of(
                "username", u.getUsername(),
                "role", u.getRole(),
                "displayName", displayName,
                "avatar", u.getAvatar() == null ? "" : u.getAvatar(),
                "email", u.getEmail() == null ? "" : u.getEmail(),
                "bio", u.getBio() == null ? "" : u.getBio(),
                "gender", u.getGender() == null ? "" : u.getGender(),
                "hideGender", u.isHideGender(),
                "points", u.getPoints(),
                "createdAt", u.getCreatedAt() == null ? "" : u.getCreatedAt().toString()
        );
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
