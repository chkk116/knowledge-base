/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Tag;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.TagService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/tags")
public class TagAdminController {

    private final TagService svc;

    public TagAdminController(TagService svc) { this.svc = svc; }

    /** 管理员 / 分类标签管理权限：创建标签 */
    @PostMapping
    public Map<String, Object> create(@RequestAttribute("kbUser") TokenClaims c,
                                      @RequestBody Map<String, String> body) {
        requirePerm(c);
        Tag t = svc.create(body.get("name"));
        return Map.of("id", t.getId(), "name", t.getName());
    }

    /** 管理员 / 分类标签管理权限：删除标签 */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        svc.delete(id);
        return Map.of("ok", true);
    }

    private void requirePerm(TokenClaims c) {
        if (c == null || (!c.isAdmin() && !c.hasPerm("TAXONOMY_MANAGE"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要分类标签管理权限");
        }
    }
}
