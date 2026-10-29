/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.service.AiFeedbackService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 反馈与知识缺口看板（只读）。
 *
 * 路径落在 /api/admin/ai 前缀下：AuthFilter 对该前缀的 GET 要求 AI_MANAGE
 * （完全管理员隐式拥有），与「AI 模型管理」保持同一套权限口径。
 */
@RestController
@RequestMapping("/api/admin/ai/feedback")
public class AdminAiFeedbackController {

    private final AiFeedbackService svc;

    public AdminAiFeedbackController(AiFeedbackService svc) {
        this.svc = svc;
    }

    @GetMapping
    public Map<String, Object> stats(@RequestParam(required = false, defaultValue = "7") int days) {
        return svc.stats(days);
    }
}
