/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.StudyPlan;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.StudyPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 学习计划（2026-10-15）：计划与条目都只属于本人，无共享语义。
 * 提醒时间由前端传 ISO 字符串（yyyy-MM-dd 或 yyyy-MM-ddTHH:mm），按服务器本地时区解释。
 */
@RestController
@RequestMapping("/api/study-plans")
public class StudyPlanController {

    private final StudyPlanService svc;

    public StudyPlanController(StudyPlanService svc) { this.svc = svc; }

    @GetMapping
    public List<StudyPlan> list(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.listMine(uid(c));
    }

    /** 待复习汇总（首页提示 / 入口角标用） */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.summary(uid(c));
    }

    @GetMapping("/{id}")
    public StudyPlan detail(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.detail(uid(c), id);
    }

    @PostMapping
    public StudyPlan create(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        return svc.create(uid(c), str(body.get("title")), str(body.get("note")), parseRemind(body.get("remindAt")));
    }

    @PutMapping("/{id}")
    public StudyPlan update(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                            @RequestBody Map<String, Object> body) {
        boolean remindProvided = body.containsKey("remindAt");
        return svc.update(uid(c), id, str(body.get("title")), str(body.get("note")),
                parseRemind(body.get("remindAt")), remindProvided);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        svc.delete(uid(c), id);
        return Map.of("ok", true);
    }

    // ---- 条目 ----

    @PostMapping("/{id}/items")
    public StudyPlan addItem(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                             @RequestBody Map<String, Object> body) {
        return svc.addItem(uid(c), id, str(body.get("type")), lng(body.get("refId")),
                lng(body.get("refShareId")), str(body.get("title")));
    }

    @PutMapping("/{id}/items/{itemId}")
    public StudyPlan updateItem(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                @PathVariable Long itemId, @RequestBody Map<String, Object> body) {
        Boolean done = body.get("done") == null ? null : Boolean.valueOf(String.valueOf(body.get("done")));
        return svc.updateItem(uid(c), id, itemId, done, str(body.get("title")));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public StudyPlan deleteItem(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                @PathVariable Long itemId) {
        return svc.deleteItem(uid(c), id, itemId);
    }

    // ---- 帮助方法 ----

    private Long uid(TokenClaims c) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        return c.userId();
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }

    private Long lng(Object o) {
        if (o == null || String.valueOf(o).isBlank()) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }

    /** 支持 "2026-10-20"（当天 0 点）与 "2026-10-20T09:30"；空串/null 表示不提醒 */
    private LocalDateTime parseRemind(Object raw) {
        String s = str(raw);
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            if (v.length() == 10) return LocalDate.parse(v).atStartOfDay();
            if (v.length() == 16) return LocalDateTime.parse(v + ":00");
            return LocalDateTime.parse(v);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "提醒时间格式不正确（应为 yyyy-MM-dd 或 yyyy-MM-ddTHH:mm）");
        }
    }
}
