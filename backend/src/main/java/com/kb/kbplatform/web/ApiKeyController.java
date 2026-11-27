/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.ApiKey;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人 API 密钥（对标 GitHub 的 Personal Access Token）。
 *
 * <pre>
 * GET    /api/me/api-keys        列表（只回显前缀，不含明文）
 * POST   /api/me/api-keys        新建 {name, scopes[], expiresInDays} → {key}（明文只此一次）
 * DELETE /api/me/api-keys/{id}   吊销
 * </pre>
 *
 * 这些接口不在公开放行清单里，AuthFilter 强制登录；密钥本身也要能查/吊销自己名下的，
 * 所以改的是「当前登录用户」，与密钥鉴权（X-API-Key）是两条独立通道。
 */
@RestController
@RequestMapping("/api/me/api-keys")
public class ApiKeyController {

    private final ApiKeyService svc;

    public ApiKeyController(ApiKeyService svc) {
        this.svc = svc;
    }

    /**
     * 用密钥调进来时禁止再管密钥。
     *
     * <p>否则一把只带 DOC_VIEW 的密钥可以 POST 一把「不带作用域」的新密钥——新密钥继承账号全部权限，
     * 作用域机制等于被自己绕过去了。管理密钥只能用人登录后的令牌（或网页端）操作。
     */
    private void rejectApiKeyChannel(HttpServletRequest req) {
        if (req != null && req.getAttribute("kbApiKey") != null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "API 密钥不能用于管理密钥，请用登录令牌");
        }
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestAttribute("kbUser") TokenClaims c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiKey k : svc.list(c.userId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", k.getId());
            m.put("name", k.getName());
            m.put("prefix", k.getKeyPrefix());
            m.put("scopes", k.getScopes());
            m.put("allowedIps", k.getAllowedIps());
            m.put("expiresAt", k.getExpiresAt());
            m.put("lastUsedAt", k.getLastUsedAt());
            m.put("revoked", k.isRevoked());
            m.put("createdAt", k.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    @PostMapping
    public Map<String, Object> create(@RequestAttribute("kbUser") TokenClaims c,
                                      HttpServletRequest req,
                                      @RequestBody Map<String, Object> body) {
        rejectApiKeyChannel(req);
        String name = body.get("name") instanceof String s ? s : "";
        Integer days = body.get("expiresInDays") instanceof Number n ? n.intValue() : null;
        List<String> scopes = new ArrayList<>();
        if (body.get("scopes") instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof String s && !s.isBlank()) {
                    // 只能授予自己已有的权限码，防止越权创建（服务层还会再取一次交集）
                    if (c.hasPerm(s)) scopes.add(s);
                }
            }
        }
        List<String> allowedIps = new ArrayList<>();
        if (body.get("allowedIps") instanceof List<?> l) {
            for (Object o : l) if (o instanceof String s && !s.isBlank()) allowedIps.add(s);
        }
        String plain = svc.create(c.userId(), name, scopes, days, allowedIps);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ok", true);
        r.put("key", plain);   // 只在此刻返回，刷新后拿不回来
        return r;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> revoke(@RequestAttribute("kbUser") TokenClaims c, HttpServletRequest req,
                                      @PathVariable Long id) {
        rejectApiKeyChannel(req);
        svc.revoke(c.userId(), id);
        return Map.of("ok", true);
    }
}
