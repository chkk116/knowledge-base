/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AiFeedbackService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * AI 反馈工作台接口。
 *
 * 路径落在 /api/admin/ai 前缀下：AuthFilter 对该前缀的 GET 要求 AI_MANAGE；
 * 写操作（/actions）在 AuthFilter 里有针对本精确路径的例外，同样按 AI_MANAGE 放行
 * （只写反馈处理状态，不碰模型凭证，无 SSRF 面）。
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

    /** 处理状态全量下发：前端按 action key 查，跨设备同步 */
    @GetMapping("/actions")
    public Map<String, Object> actions() {
        return svc.actions();
    }

    /** 写入 / 删除一条处理状态；status 为空表示重新打开（回到待处理） */
    @PostMapping("/actions")
    public Map<String, Object> saveAction(@RequestAttribute("kbUser") TokenClaims c,
                                          @RequestBody Map<String, Object> body) {
        if (c == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        Object key = body == null ? null : body.get("key");
        Object status = body == null ? null : body.get("status");
        Object note = body == null ? null : body.get("note");
        return svc.saveAction(c.userId(),
            key == null ? null : String.valueOf(key),
            status == null ? null : String.valueOf(status),
            note == null ? null : String.valueOf(note));
    }
}
