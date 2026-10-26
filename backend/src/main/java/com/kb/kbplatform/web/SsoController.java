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
    private final AuthService auth;
    private final RateLimitService rateLimit;

    public SsoController(LdapAuthService ldap, com.kb.kbplatform.service.OidcAuthService oidc,
                         AuthService auth, RateLimitService rateLimit) {
        this.ldap = ldap;
        this.oidc = oidc;
        this.auth = auth;
        this.rateLimit = rateLimit;
    }

    @GetMapping("/api/auth/sso/options")
    public Map<String, Object> options() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("ldap", ldap.enabled());
        ret.put("oidc", oidc.enabled());
        ret.put("oidcLabel", oidc.buttonLabel());
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
        String url = oidc.authorizeUrl(origin);
        return org.springframework.http.ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", url).build();
    }

    /** OIDC 回调：换 token → 取用户 → 签发本站会话 → 302 回前端带上 token */
    @GetMapping("/api/auth/sso/oidc/callback")
    public org.springframework.http.ResponseEntity<Void> oidcCallback(@RequestParam(required = false) String code,
                                                                     @RequestParam(required = false) String state,
                                                                     @RequestParam(required = false) String origin) {
        String base = origin == null ? "" : origin.replaceAll("/+$", "");
        try {
            Map<String, String> who = oidc.callback(code, state, base);
            Map<String, Object> session = auth.loginExternal(who.get("username"), who.get("displayName"), who.get("email"));
            String token = String.valueOf(session.get("token"));
            return org.springframework.http.ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", base + "/#/sso?token=" + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "单点登录失败" : e.getMessage();
            return org.springframework.http.ResponseEntity.status(HttpStatus.FOUND)
                    .header("Location", base + "/#/sso?error=" + java.net.URLEncoder.encode(msg, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
        }
    }

    private void requireAdmin(TokenClaims c) {
        if (c == null || !c.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可配置企业登录");
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
}
