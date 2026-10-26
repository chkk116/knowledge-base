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
    private final AuthService auth;
    private final RateLimitService rateLimit;

    public SsoController(LdapAuthService ldap, AuthService auth, RateLimitService rateLimit) {
        this.ldap = ldap;
        this.auth = auth;
        this.rateLimit = rateLimit;
    }

    @GetMapping("/api/auth/sso/options")
    public Map<String, Object> options() {
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("ldap", ldap.enabled());
        ret.put("oidc", false);   // 预留：接入 OIDC 后按配置返回
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

    @PostMapping("/api/admin/sso/ldap/test")
    public Map<String, Object> test(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return ldap.test();
    }
}
