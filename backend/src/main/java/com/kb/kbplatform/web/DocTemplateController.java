/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.DocTemplate;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.DocTemplateService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 文档模板。
 * GET 需要登录（存草稿/新建文档都要用），增删改仅管理员。
 */
@RestController
@RequestMapping("/api/doc-templates")
public class DocTemplateController {

    private final DocTemplateService svc;

    public DocTemplateController(DocTemplateService svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<DocTemplate> list(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.list();
    }

    private void requireAdmin(TokenClaims c) {
        if (c == null || !c.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅管理员可维护模板");
    }

    @PostMapping
    public DocTemplate create(@RequestAttribute("kbUser") TokenClaims c, @RequestBody DocTemplate t) {
        requireAdmin(c);
        return svc.create(t, c.userId());
    }

    @PutMapping("/{id}")
    public DocTemplate update(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id, @RequestBody DocTemplate t) {
        requireAdmin(c);
        return svc.update(id, t);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requireAdmin(c);
        svc.delete(id);
        return Map.of("ok", true);
    }
}
