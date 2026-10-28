/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.WrongLoopService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 「错题 → 文档 → 复习卡」闭环接口。
 *
 * GET  /api/exams/wrong/{questionId}/related-docs   这道错题对应哪篇文档（附命中片段）
 * POST /api/exams/wrong/to-cards                    一键把选中的错题变成复习卡
 * GET  /api/doc-shares/{shareId}/docs/{docId}/weak-points  这篇文档相关题目的全站错误率
 *
 * 全部需要登录；文档相关接口走 DocShareService 的权限校验（不能借此探测无权限文档）。
 */
@RestController
public class WrongLoopController {

    private final WrongLoopService svc;

    public WrongLoopController(WrongLoopService svc) {
        this.svc = svc;
    }

    private Long uid(TokenClaims c) {
        if (c == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return c.userId();
    }

    @GetMapping("/api/exams/wrong/{questionId}/related-docs")
    public List<Map<String, Object>> relatedDocs(@RequestAttribute("kbUser") TokenClaims c,
                                                 @PathVariable Long questionId) {
        return svc.relatedDocs(uid(c), c.isAdmin(), questionId);
    }

    @PostMapping("/api/exams/wrong/to-cards")
    public Map<String, Object> toCards(@RequestAttribute("kbUser") TokenClaims c,
                                       @RequestBody Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        if (body.get("ids") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) ids.add(n.longValue());
                else if (o != null) {
                    try { ids.add(Long.parseLong(String.valueOf(o))); } catch (NumberFormatException ignore) { }
                }
            }
        }
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要加入复习卡的错题");
        if (ids.size() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次最多 100 道");
        return svc.toCards(uid(c), c.isAdmin(), ids);
    }

    @GetMapping("/api/doc-shares/{shareId}/docs/{docId}/weak-points")
    public Map<String, Object> weakPoints(@RequestAttribute("kbUser") TokenClaims c,
                                          @PathVariable Long shareId, @PathVariable Long docId) {
        return svc.weakPoints(uid(c), c.isAdmin(), shareId, docId);
    }
}
