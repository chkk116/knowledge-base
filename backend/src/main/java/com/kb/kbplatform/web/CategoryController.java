package com.kb.kbplatform.web;

import com.kb.kbplatform.service.CategoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryService svc;

    public CategoryController(CategoryService svc) { this.svc = svc; }

    /** 公开：分类树（层级） */
    @GetMapping
    public List<Map<String, Object>> tree() { return svc.tree(); }
}
