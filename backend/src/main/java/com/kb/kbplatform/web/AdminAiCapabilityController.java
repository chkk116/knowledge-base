/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AiCapabilityService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * AI 能力配置：文生图 / 文生视频 / OCR 各自一套端点与密钥。
 * 与「AI 模型管理」的对话模型池独立，互不干扰。
 */
@RestController
@RequestMapping("/api/admin/ai/capabilities")
public class AdminAiCapabilityController {

    private final AiCapabilityService svc;

    public AdminAiCapabilityController(AiCapabilityService svc) {
        this.svc = svc;
    }

    private void requirePerm(TokenClaims c) {
        if (c == null || !c.hasPerm("AI_MANAGE")) {
            throw new ResponseStatusException(FORBIDDEN, "需要「AI_MANAGE」权限");
        }
    }

    @GetMapping
    public Map<String, Object> get(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c);
        return svc.view();
    }

    /** body { image:{provider,baseUrl,apiKey,model,enabled,clearKey}, video:{...}, ocr:{...} } */
    @PutMapping
    public Map<String, Object> save(@RequestAttribute("kbUser") TokenClaims c,
                                    @RequestBody SaveReq r) {
        requirePerm(c);
        if (r == null) throw new ResponseStatusException(BAD_REQUEST, "缺少参数");
        return svc.save(r.image, r.video, r.ocr);
    }

    public static class SaveReq {
        public AiCapabilityService.CapReq image;
        public AiCapabilityService.CapReq video;
        public AiCapabilityService.CapReq ocr;
    }
}
