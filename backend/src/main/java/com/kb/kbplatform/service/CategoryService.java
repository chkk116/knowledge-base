/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Category;
import com.kb.kbplatform.repository.CategoryRepository;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class CategoryService {

    private final CategoryRepository repo;

    public CategoryService(CategoryRepository repo) {
        this.repo = repo;
    }

    /** 返回层级树（children 嵌套），用于分类树展示与选择 */
    public List<Map<String, Object>> tree() {
        List<Category> all = repo.findAllByOrderBySortOrderAscIdAsc();
        Map<Long, List<Category>> byParent = new LinkedHashMap<>();
        for (Category c : all) {
            long pid = c.getParentId() == null ? 0L : c.getParentId();
            byParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(c);
        }
        return build(byParent, 0L);
    }

    private List<Map<String, Object>> build(Map<Long, List<Category>> byParent, Long pid) {
        List<Category> nodes = byParent.get(pid);
        if (nodes == null) return Collections.emptyList();
        List<Map<String, Object>> res = new ArrayList<>();
        for (Category c : nodes) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("parentId", c.getParentId());
            m.put("sortOrder", c.getSortOrder());
            m.put("children", build(byParent, c.getId()));
            res.add(m);
        }
        return res;
    }

    public Category create(String name, Long parentId, String slug, Integer sortOrder) {
        name = (name == null ? "" : name.trim());
        if (name.isEmpty()) throw new IllegalArgumentException("分类名不能为空");
        Category c = new Category();
        c.setName(name);
        c.setParentId(parentId == null ? 0L : parentId);
        c.setSlug(slug);
        c.setSortOrder(sortOrder == null ? 0 : sortOrder);
        return repo.save(c);
    }

    public Category update(Long id, String name, Long parentId, String slug, Integer sortOrder) {
        Category c = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("分类不存在"));
        if (name != null && !name.trim().isEmpty()) c.setName(name.trim());
        if (parentId != null) {
            if (parentId.equals(id)) throw new IllegalArgumentException("不能将自己设为父级");
            c.setParentId(parentId);
        }
        if (slug != null) c.setSlug(slug);
        if (sortOrder != null) c.setSortOrder(sortOrder);
        return repo.save(c);
    }

    public void delete(Long id) {
        if (repo.findByParentIdOrderBySortOrderAscIdAsc(id).isEmpty()) {
            repo.deleteById(id);
        } else {
            throw new IllegalStateException("该分类下还有子分类，请先处理子分类");
        }
    }
}
