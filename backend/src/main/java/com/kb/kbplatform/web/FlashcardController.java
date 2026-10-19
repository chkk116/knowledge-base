/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Flashcard;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.FlashcardService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 复习卡片接口。全部按「本人」隔离：所有查询/修改都带 userId 条件，
 * 越权访问别人的卡片一律 404（不泄露「存在但无权」）。
 */
@RestController
@RequestMapping("/api/flashcards")
public class FlashcardController {

    private final FlashcardService svc;

    public FlashcardController(FlashcardService svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<Flashcard> list(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.list(c.userId());
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.summary(c.userId());
    }

    @GetMapping("/due")
    public List<Flashcard> due(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.due(c.userId());
    }

    @PostMapping
    public Flashcard create(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        return svc.create(str(body.get("front")), str(body.get("back")), str(body.get("source")),
                asLong(body.get("shareId")), asLong(body.get("docId")), c.userId());
    }

    @PutMapping("/{id}")
    public Flashcard update(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                            @RequestBody Map<String, Object> body) {
        // 三个字段都可选：只改传了的（null 表示不改）
        return svc.update(id, str(body.get("front")), str(body.get("back")), str(body.get("source")), c.userId());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        svc.delete(id, c.userId());
        return Map.of("ok", true);
    }

    /** 复习一次：body { grade: again|hard|good|easy } */
    @PostMapping("/{id}/review")
    public Flashcard review(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                            @RequestBody Map<String, Object> body) {
        return svc.review(id, str(body.get("grade")), c.userId());
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static Long asLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }
}
