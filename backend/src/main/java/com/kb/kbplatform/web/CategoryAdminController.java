package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Category;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.CategoryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/categories")
public class CategoryAdminController {

    private final CategoryService svc;

    public CategoryAdminController(CategoryService svc) { this.svc = svc; }

    /** 管理员 / 分类标签管理权限：新建分类（可指定父级，形成分类树） */
    @PostMapping
    public Category create(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requirePerm(c);
        return svc.create(str(body.get("name")), lng(body.get("parentId")), str(body.get("slug")), intv(body.get("sortOrder")));
    }

    /** 管理员 / 分类标签管理权限：更新分类 */
    @PutMapping("/{id}")
    public Category update(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                           @RequestBody Map<String, Object> body) {
        requirePerm(c);
        return svc.update(id, str(body.get("name")), lng(body.get("parentId")), str(body.get("slug")), intv(body.get("sortOrder")));
    }

    /** 管理员 / 分类标签管理权限：删除分类（有子分类时拒绝） */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c);
        svc.delete(id);
        return Map.of("ok", true);
    }

    private void requirePerm(TokenClaims c) {
        if (c == null || (!"ADMIN".equals(c.role()) && !c.hasPerm("TAXONOMY_MANAGE"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要分类标签管理权限");
        }
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
    private Long lng(Object o) { return o == null ? null : Long.valueOf(String.valueOf(o)); }
    private Integer intv(Object o) { return o == null ? null : Integer.valueOf(String.valueOf(o)); }
}
