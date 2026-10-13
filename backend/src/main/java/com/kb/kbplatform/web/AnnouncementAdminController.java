/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Announcement;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AnnouncementService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 后台管理：公告增删改、置顶、启用/禁用。
 * 权限：ADMIN 角色，或拥有 ANNOUNCEMENT_MANAGE 细粒度权限。
 */
@RestController
@RequestMapping("/api/admin/announcements")
public class AnnouncementAdminController {

    private final AnnouncementService svc;

    public AnnouncementAdminController(AnnouncementService svc) {
        this.svc = svc;
    }

    @GetMapping
    public Page<Map<String, Object>> list(@RequestAttribute("kbUser") TokenClaims c,
                                          @RequestParam(required = false, defaultValue = "0") int page,
                                          @RequestParam(required = false, defaultValue = "15") int size,
                                          @RequestParam(required = false) Boolean enabled) {
        requirePerm(c);
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 15;
        return svc.pageAll(enabled, PageRequest.of(page, size)).map(svc::toMap);
    }

    @PostMapping
    public Map<String, Object> create(@RequestAttribute("kbUser") TokenClaims c,
                                      @RequestBody Map<String, Object> body) {
        requirePerm(c);
        Announcement a = svc.create(
                str(body.get("title")),
                str(body.get("content")),
                bool(body.get("pinned")),
                intv(body.get("sortOrder")),
                c.userId()
        );
        return svc.toMap(a);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        requirePerm(c);
        Announcement a = svc.update(id,
                str(body.get("title")),
                str(body.get("content")),
                boolOrNull(body.get("pinned")),
                intvOrNull(body.get("sortOrder")),
                boolOrNull(body.get("enabled")));
        return svc.toMap(a);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        svc.delete(id);
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/pin")
    public Map<String, Object> togglePin(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        return svc.toMap(svc.togglePin(id));
    }

    @PostMapping("/{id}/enable")
    public Map<String, Object> toggleEnabled(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        return svc.toMap(svc.toggleEnabled(id));
    }

    private void requirePerm(TokenClaims c) {
        if (c == null || (!"ADMIN".equals(c.role()) && !c.hasPerm("ANNOUNCEMENT_MANAGE"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要公告管理权限");
        }
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private boolean bool(Object o) {
        if (o == null) return false;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private Boolean boolOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private int intv(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    private Integer intvOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }
}
