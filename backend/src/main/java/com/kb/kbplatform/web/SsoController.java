/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.security.RateLimitService;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuthService;
import com.kb.kbplatform.service.LdapAuthService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 企业身份源登录（当前支持 LDAP/AD，OIDC 预留）。
 *
 * 公开：
 *   GET  /api/auth/sso/options            登录页据此决定展示哪些入口
 *   POST /api/auth/sso/ldap/login         { username, password } → 与本地登录同样的会话结构
 *
 * 管理（仅完全管理员）：
 *   GET/PUT /api/admin/sso/ldap           读取/保存配置（bindPassword 空值 = 不改）
 *   POST    /api/admin/sso/ldap/test      用服务账号试连一次
 */
@RestController
public class SsoController {

    private final LdapAuthService ldap;
    private final com.kb.kbplatform.service.OidcAuthService oidc;
    private final com.kb.kbplatform.service.WeChatAuthService wechat;
    private final com.kb.kbplatform.service.QqAuthService qq;
    private final com.kb.kbplatform.service.GithubAuthService github;
    private final com.kb.kbplatform.service.GiteeAuthService gitee;
    private final com.kb.kbplatform.repository.UserIdentityRepository identities;
    private final AuthService auth;
    private final RateLimitService rateLimit;

    public SsoController(LdapAuthService ldap, com.kb.kbplatform.service.OidcAuthService oidc,
                         com.kb.kbplatform.service.WeChatAuthService wechat,
                         com.kb.kbplatform.service.QqAuthService qq,
                         com.kb.kbplatform.service.GithubAuthService github,
                         com.kb.kbplatform.service.GiteeAuthService gitee,
                         com.kb.kbplatform.repository.UserIdentityRepository identities,
                         AuthService auth, RateLimitService rateLimit) {
        this.ldap = ldap;
        this.oidc = oidc;
        this.wechat = wechat;
        this.qq = qq;
        this.github = github;
        this.gitee = gitee;
        this.identities = identities;
        this.auth = auth;
        this.rateLimit = rateLimit;
    }

    @GetMapping("/api/auth/sso/options")
    public Map<String, Object> options() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("ldap", ldap.enabled());
        ret.put("oidc", oidc.enabled());
        ret.put("oidcLabel", oidc.buttonLabel());
        ret.put("wechat", wechat.enabled());
        ret.put("wechatLabel", wechat.buttonLabel());
        ret.put("qq", qq.enabled());
        ret.put("qqLabel", qq.buttonLabel());
        ret.put("github", github.enabled());
        ret.put("githubLabel", github.buttonLabel());
        ret.put("gitee", gitee.enabled());
        ret.put("giteeLabel", gitee.buttonLabel());
        return ret;
    }

    @PostMapping("/api/auth/sso/ldap/login")
    public Map<String, Object> ldapLogin(@RequestBody Map<String, Object> body) {
        String username = body.get("username") == null ? "" : String.valueOf(body.get("username")).trim();
        String password = body.get("password") == null ? "" : String.valueOf(body.get("password"));
        // 防爆破：同一账号每分钟 10 次；LDAP 侧被锁号比这边限流更严重，必须前置拦截
        if (!rateLimit.allow("sso-ldap:" + username, 10, 60_000L)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "尝试太频繁，请稍后再试");
        }
        Map<String, String> who = ldap.authenticate(username, password);
        return auth.loginExternal(who.get("username"), who.get("displayName"), who.get("email"));
    }

    /** 发起 OIDC 登录：302 到身份源授权页。origin 由前端传入，用于回调后跳回本站 */
    @GetMapping("/api/auth/sso/oidc/start")
    public org.springframework.http.ResponseEntity<Void> oidcStart(@RequestParam(required = false) String origin) {
        String url = oidc.authorizeUrl(publicBase());
        return org.springframework.http.ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", url).build();
    }

    /** OIDC 回调：换 token → 取用户 → 签发本站会话 → 302 回前端带上 token */
    @GetMapping("/api/auth/sso/oidc/callback")
    public org.springframework.http.ResponseEntity<Void> oidcCallback(@RequestParam(required = false) String code,
                                                                     @RequestParam(required = false) String state,
                                                                     @RequestParam(required = false) String origin) {
        String base = publicBase();   // 不信任客户端传入的 origin（否则可把 token 送到攻击者域名）
        try {
            Map<String, String> who = oidc.callback(code, state, base);
            Map<String, Object> session = auth.loginExternal(who.get("username"), who.get("displayName"), who.get("email"));
            String token = String.valueOf(session.get("token"));
            return org.springframework.http.ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", base + "/sso?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "单点登录失败" : e.getMessage();
            return org.springframework.http.ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", base + "/sso?error=" + java.net.URLEncoder.encode(msg, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
        }
    }

    /**
     * 本站基址：一律由请求本身推导（代理场景优先 X-Forwarded-*）。
     * 绝不使用回调参数里的 origin —— 那是攻击者可控的开放重定向/令牌外带入口。
     */
    private String publicBase() {
        var attrs = (org.springframework.web.context.request.ServletRequestAttributes)
                org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (attrs == null) return "";
        var r = attrs.getRequest();
        String proto = firstNonBlank(r.getHeader("X-Forwarded-Proto"), r.getScheme(), "http");
        String host = firstNonBlank(r.getHeader("X-Forwarded-Host"), r.getHeader("Host"), r.getServerName());
        // Host 头同样可被伪造：只接受主机名/端口形态，出现路径、空格、@ 一律回落 serverName
        if (host == null || !host.matches("[A-Za-z0-9.\\-:\\[\\]]+")) host = r.getServerName();
        return proto + "://" + host;
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private void requireAdmin(TokenClaims c) {
        if (c == null || (!c.isAdmin() && !c.hasPerm("SSO_MANAGE")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要企业登录配置权限");
    }

    @GetMapping("/api/admin/sso/ldap")
    public Map<String, Object> getConfig(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return ldap.config();
    }

    @PutMapping("/api/admin/sso/ldap")
    public Map<String, Object> saveConfig(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requireAdmin(c);
        return ldap.save(body);
    }

    @GetMapping("/api/admin/sso/oidc")
    public Map<String, Object> getOidc(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return oidc.config();
    }

    @PutMapping("/api/admin/sso/oidc")
    public Map<String, Object> saveOidc(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requireAdmin(c);
        return oidc.save(body);
    }

    @PostMapping("/api/admin/sso/ldap/test")
    public Map<String, Object> test(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return ldap.test();
    }

    // ==================== 第三方扫码登录（微信 / QQ / GitHub） ====================
    //
    // 三家流程一致：start 302 到对方授权页 → 对方回调带 code → 换 token 取 openid →
    // 去 kb_user_identity 找绑定 → 签发本站会话。
    // **绑定与登录的区别挂在 state 上**（bindUserId），不从回调参数读，否则可伪造成给任意账号绑定。

    @GetMapping("/api/auth/sso/wechat/start")
    public org.springframework.http.ResponseEntity<Void> wechatStart(@RequestParam(required = false) String origin) {
        return found(wechat.authorizeUrl(base(origin), null));
    }

    @GetMapping("/api/auth/sso/wechat/callback")
    public org.springframework.http.ResponseEntity<Void> wechatCallback(@RequestParam(required = false) String code,
                                                                       @RequestParam(required = false) String state,
                                                                       @RequestParam(required = false) String origin) {
        String b = base(origin);
        try {
            var u = wechat.callback(code, state);
            String ext = notBlank(u.unionId()) ? u.unionId() : u.openId();
            return finish(b, "wechat", ext, u.openId(), u.unionId(), u.nickname(), u.avatar(),
                    u.bindUserId(), wechat.autoRegister(), "微信");
        } catch (Exception e) {
            return fail(b, e.getMessage());
        }
    }

    @GetMapping("/api/auth/sso/qq/start")
    public org.springframework.http.ResponseEntity<Void> qqStart(@RequestParam(required = false) String origin) {
        return found(qq.authorizeUrl(base(origin), null));
    }

    @GetMapping("/api/auth/sso/qq/callback")
    public org.springframework.http.ResponseEntity<Void> qqCallback(@RequestParam(required = false) String code,
                                                                   @RequestParam(required = false) String state,
                                                                   @RequestParam(required = false) String origin) {
        String b = base(origin);
        try {
            var u = qq.callback(code, state);
            return finish(b, "qq", u.openId(), u.openId(), null, u.nickname(), u.avatar(),
                    u.bindUserId(), qq.autoRegister(), "QQ");
        } catch (Exception e) {
            return fail(b, e.getMessage());
        }
    }

    @GetMapping("/api/auth/sso/github/start")
    public org.springframework.http.ResponseEntity<Void> githubStart(@RequestParam(required = false) String origin) {
        return found(github.authorizeUrl(base(origin), null));
    }

    @GetMapping("/api/auth/sso/github/callback")
    public org.springframework.http.ResponseEntity<Void> githubCallback(@RequestParam(required = false) String code,
                                                                       @RequestParam(required = false) String state,
                                                                       @RequestParam(required = false) String origin) {
        String b = base(origin);
        try {
            var u = github.callback(code, state);
            return finish(b, "github", u.externalId(), u.login(), null, u.nickname(), u.avatar(),
                    u.bindUserId(), github.autoRegister(), "GitHub");
        } catch (Exception e) {
            return fail(b, e.getMessage());
        }
    }

    @GetMapping("/api/auth/sso/gitee/start")
    public org.springframework.http.ResponseEntity<Void> giteeStart(@RequestParam(required = false) String origin) {
        return found(gitee.authorizeUrl(base(origin), null));
    }

    @GetMapping("/api/auth/sso/gitee/callback")
    public org.springframework.http.ResponseEntity<Void> giteeCallback(@RequestParam(required = false) String code,
                                                                      @RequestParam(required = false) String state,
                                                                      @RequestParam(required = false) String origin) {
        String b = base(origin);
        try {
            var u = gitee.callback(code, state);
            return finish(b, "gitee", u.externalId(), u.login(), null, u.nickname(), u.avatar(),
                    u.bindUserId(), gitee.autoRegister(), "Gitee");
        } catch (Exception e) {
            return fail(b, e.getMessage());
        }
    }

    /** 已登录用户在「个人中心」点「绑定微信/QQ/GitHub/Gitee」：返回一次性授权地址（state 里带 userId） */
    @GetMapping("/api/auth/sso/bind/start")
    public Map<String, Object> bindStart(@RequestAttribute("kbUser") TokenClaims c,
                                        @RequestParam String provider,
                                        @RequestParam(required = false) String origin) {
        Long uid = c == null ? null : c.userId();
        if (uid == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录再绑定");
        String b = base(origin);
        String url = switch (provider) {
            case "wechat" -> wechat.authorizeUrl(b, uid);
            case "qq" -> qq.authorizeUrl(b, uid);
            case "github" -> github.authorizeUrl(b, uid);
            case "gitee" -> gitee.authorizeUrl(b, uid);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的身份源");
        };
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("url", url);
        return ret;
    }

    /** 登录页/个人中心据此展示哪些第三方入口 */
    @GetMapping("/api/auth/sso/bind/list")
    public Map<String, Object> bindList(@RequestAttribute("kbUser") TokenClaims c) {
        Map<String, Object> ret = new LinkedHashMap<>();
        if (c == null) return ret;
        for (var id : identities.findAll()) {
            if (!id.getUserId().equals(c.userId())) continue;
            ret.put(id.getProvider(), notBlank(id.getNickname()) ? id.getNickname() : id.getExternalId());
        }
        return ret;
    }

    @DeleteMapping("/api/auth/sso/bind/{provider}")
    public Map<String, Object> unbind(@RequestAttribute("kbUser") TokenClaims c, @PathVariable String provider) {
        Long uid = c == null ? null : c.userId();
        if (uid == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        identities.deleteByUserIdAndProvider(uid, provider);
        return bindList(c);
    }

    /**
     * 绑定 / 登录的公共收尾。
     *
     * @param bindUserId 非空 = 绑定流程（把 extId 绑到该用户），空 = 登录流程
     */
    private org.springframework.http.ResponseEntity<Void> finish(String base, String provider, String extId,
                                                                String openId, String unionId,
                                                                String nickname, String avatar,
                                                                Long bindUserId, boolean autoReg, String label) {
        // —— 绑定流程 ——
        if (bindUserId != null) {
            var exists = identities.findByProviderAndExternalId(provider, extId);
            if (exists.isPresent() && !exists.get().getUserId().equals(bindUserId)) {
                throw new IllegalArgumentException("这个" + label + "已绑定其它账号，请先解绑");
            }
            var id = exists.orElseGet(com.kb.kbplatform.domain.UserIdentity::new);
            id.setUserId(bindUserId);
            id.setProvider(provider);
            id.setExternalId(extId);
            id.setOpenId(openId);
            id.setUnionId(unionId);
            id.setNickname(nickname);
            id.setAvatar(avatar);
            id.setUpdatedAt(java.time.LocalDateTime.now());
            identities.save(id);
            return found(base + "/sso?bound=" + java.net.URLEncoder.encode(label, java.nio.charset.StandardCharsets.UTF_8));
        }

        // —— 登录流程 ——
        var bound = identities.findByProviderAndExternalId(provider, extId);
        Map<String, Object> session;
        if (bound.isPresent()) {
            session = auth.sessionForUser(bound.get().getUserId());
            // 老用户第一次扫码：把第三方头像补上（用户自己设过的不会被覆盖）
            auth.fillAvatarIfEmpty(bound.get().getUserId(), avatar);
            // 昵称/头像跟着身份源更新（用户改了微信昵称这里能同步上）
            var id = bound.get();
            if (notBlank(nickname) && !nickname.equals(id.getNickname())) {
                id.setNickname(nickname);
                id.setAvatar(avatar);
                id.setUpdatedAt(java.time.LocalDateTime.now());
                identities.save(id);
            }
        } else if (autoReg) {
            // 只去非法字符会撞名（如 a.b 与 ab、超长截断后相同）→ 统一追加原值哈希，保证稳定且唯一
            String raw = provider + "_" + extId;
            String safe = raw.replaceAll("[^A-Za-z0-9_]", "");
            String suffix = Integer.toHexString(raw.hashCode());
            if (safe.length() > 30) safe = safe.substring(0, 30);
            String uname = safe + "_" + suffix;
            session = auth.loginExternal(uname, nickname, "", avatar);
            var id = new com.kb.kbplatform.domain.UserIdentity();
            id.setUserId(((Number) session.get("id")).longValue());
            id.setProvider(provider);
            id.setExternalId(extId);
            id.setOpenId(openId);
            id.setUnionId(unionId);
            id.setNickname(nickname);
            id.setAvatar(avatar);
            identities.save(id);
        } else {
            throw new IllegalArgumentException("该" + label + "账号还没绑定本站账号：请先用账号密码登录，"
                    + "在「个人中心」里绑定" + label + "后再扫码登录");
        }

        String token = String.valueOf(session.get("token"));
        return found(base + "/sso?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8));
    }

    @GetMapping("/api/admin/sso/wechat")
    public Map<String, Object> getWechat(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return wechat.config();
    }

    @PutMapping("/api/admin/sso/wechat")
    public Map<String, Object> saveWechat(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requireAdmin(c);
        return wechat.save(body);
    }

    @GetMapping("/api/admin/sso/qq")
    public Map<String, Object> getQq(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return qq.config();
    }

    @PutMapping("/api/admin/sso/qq")
    public Map<String, Object> saveQq(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requireAdmin(c);
        return qq.save(body);
    }

    @GetMapping("/api/admin/sso/gitee")
    public Map<String, Object> getGitee(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return gitee.config();
    }

    @PutMapping("/api/admin/sso/gitee")
    public Map<String, Object> saveGitee(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requireAdmin(c);
        return gitee.save(body);
    }

    @GetMapping("/api/admin/sso/github")
    public Map<String, Object> getGithub(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return github.config();
    }

    @PutMapping("/api/admin/sso/github")
    public Map<String, Object> saveGithub(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requireAdmin(c);
        return github.save(body);
    }

    /**
     * 第三方连通性诊断（管理员）。
     *
     * 生产上出现过「curl 能通、应用里每次 request timed out」，光看错误信息没法定位，
     * 这里用**应用自己的 HTTP 栈**去探测各家接口，直接返回耗时/状态码/异常。
     * 排查「登录报超时」时先打这个接口。
     */
    @GetMapping("/api/admin/sso/diag")
    public Map<String, Object> diag(@RequestAttribute("kbUser") TokenClaims c,
                                    @RequestParam(required = false) String target) {
        requireAdmin(c);
        String t = target == null ? "all" : target;
        Map<String, Object> out = new LinkedHashMap<>();
        if ("all".equals(t) || "github".equals(t)) {
            out.put("github_api", com.kb.kbplatform.service.HttpFetch.probe("https://api.github.com/user"));
            out.put("github_oauth", com.kb.kbplatform.service.HttpFetch.probe("https://github.com/login/oauth/access_token"));
        }
        if ("all".equals(t) || "wechat".equals(t)) {
            out.put("wechat_api", com.kb.kbplatform.service.HttpFetch.probe("https://api.weixin.qq.com/sns/oauth2/access_token"));
            out.put("wechat_open", com.kb.kbplatform.service.HttpFetch.probe("https://open.weixin.qq.com/connect/qrconnect"));
        }
        if ("all".equals(t) || "gitee".equals(t)) {
            out.put("gitee_api", com.kb.kbplatform.service.HttpFetch.probe("https://gitee.com/api/v5/user"));
            out.put("gitee_oauth", com.kb.kbplatform.service.HttpFetch.probe("https://gitee.com/oauth/token"));
        }
        if ("all".equals(t) || "qq".equals(t)) {
            out.put("qq_graph", com.kb.kbplatform.service.HttpFetch.probe("https://graph.qq.com/oauth2.0/me"));
        }
        return out;
    }

    private static String base(String origin) {
        return origin == null ? "" : origin.replaceAll("/+$", "");
    }

    private static org.springframework.http.ResponseEntity<Void> found(String url) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.FOUND).header("Location", url).build();
    }

    private static org.springframework.http.ResponseEntity<Void> fail(String base, String msg) {
        String m = msg == null ? "登录失败" : msg;
        return found(base + "/sso?error="
                + java.net.URLEncoder.encode(m, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean notBlank(String v) {
        return v != null && !v.trim().isEmpty();
    }
}
