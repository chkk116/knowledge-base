package com.kb.kbplatform.web;

import com.kb.kbplatform.service.TagService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagService svc;

    public TagController(TagService svc) { this.svc = svc; }

    /** 公开：标签列表（带文档计数） */
    @GetMapping
    public List<Map<String, Object>> list() { return svc.listWithCount(); }
}
