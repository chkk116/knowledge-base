/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AiMediaService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** AI 媒体生成接口（登录可用）：文生图 / 文生视频 */
@RestController
public class AiMediaController {

    private final AiMediaService svc;

    public AiMediaController(AiMediaService svc) {
        this.svc = svc;
    }

    private Long uid(TokenClaims c) {
        if (c == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return c.userId();
    }

    @PostMapping("/api/ai/image")
    public Map<String, Object> image(@RequestAttribute("kbUser") TokenClaims c,
                                     @RequestBody Map<String, Object> body) {
        uid(c);
        Long modelId = body.get("modelId") instanceof Number n ? n.longValue() : null;
        return svc.generateImage(modelId, String.valueOf(body.getOrDefault("prompt", "")));
    }

    @PostMapping("/api/ai/video")
    public Map<String, Object> createVideo(@RequestAttribute("kbUser") TokenClaims c,
                                           @RequestBody Map<String, Object> body) {
        uid(c);
        Long modelId = body.get("modelId") instanceof Number n ? n.longValue() : null;
        return svc.createVideo(modelId, String.valueOf(body.getOrDefault("prompt", "")));
    }

    @GetMapping("/api/ai/video/{taskId}")
    public Map<String, Object> videoStatus(@RequestAttribute("kbUser") TokenClaims c,
                                           @PathVariable String taskId) {
        uid(c);
        return svc.videoStatus(taskId);
    }
}
