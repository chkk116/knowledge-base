/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.AiConversation;
import com.kb.kbplatform.domain.AiMessage;
import com.kb.kbplatform.security.RateLimitService;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AiConversationService;
import com.kb.kbplatform.service.AiModelClient;
import com.kb.kbplatform.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private static final Logger log = LoggerFactory.getLogger(AiController.class);

    private final ObjectProvider<RagService> ragProvider;
    private final RateLimitService rateLimit;
    private final AiConversationService convService;
    private final AiModelClient aiModelClient;

    public AiController(ObjectProvider<RagService> ragProvider,
                        RateLimitService rateLimit,
                        AiConversationService convService,
                        AiModelClient aiModelClient) {
        this.ragProvider = ragProvider;
        this.rateLimit = rateLimit;
        this.convService = convService;
        this.aiModelClient = aiModelClient;
    }

    /** 当前可用模型列表（不含 API Key），普通登录用户即可查询，用于对话页的模型下拉 */
    @GetMapping("/models")
    public List<Map<String, Object>> models() {
        return aiModelClient.publicModels();
    }

    @PostMapping("/ask")
    public Map<String, Object> ask(@RequestAttribute("kbUser") TokenClaims claims,
                                   @RequestBody Map<String, Object> body) {
        // 防刷：AI 调用消耗云端 token（真金白银），每用户 20 次/小时
        if (!rateLimit.allow("ai:ask:" + claims.userId(), 20, 60L * 60 * 1000)) {
            return Map.of("answer", "提问太频繁了，请稍后再试（每小时限 20 次）");
        }
        RagService svc = ragProvider.getIfAvailable();
        if (svc == null) {
            log.warn("AI 问答未启用：RagService bean 不可用（请确认 kb.ai.enabled=true 且 KB_ZHIPU_API_KEY 已注入）");
            return Map.of("answer", "AI 问答暂不可用，请稍后再试或联系管理员。");
        }
        String question = body.get("question") == null ? "" : body.get("question").toString();
        Long conversationId = parseLong(body.get("conversationId"));
        String mode = body.get("mode") == null ? "kb" : body.get("mode").toString();
        // modelId 省略时用管理员配置的默认模型
        Long modelId = parseLong(body.get("modelId"));
        return convService.ask(claims.userId(), conversationId, question, mode, modelId);
    }

    @GetMapping("/conversations")
    public List<AiConversation> conversations(@RequestAttribute("kbUser") TokenClaims claims) {
        return convService.list(claims.userId());
    }

    @PostMapping("/conversations")
    public AiConversation createConversation(@RequestAttribute("kbUser") TokenClaims claims,
                                             @RequestBody(required = false) Map<String, String> body) {
        String title = body == null ? null : body.get("title");
        return convService.create(claims.userId(), title);
    }

    @PutMapping("/conversations/{id}")
    public AiConversation updateConversation(@RequestAttribute("kbUser") TokenClaims claims,
                                             @PathVariable Long id,
                                             @RequestBody Map<String, String> body) {
        return convService.update(claims.userId(), id, body.get("title"), body.get("instructions"));
    }

    @DeleteMapping("/conversations/{id}")
    public Map<String, Object> deleteConversation(@RequestAttribute("kbUser") TokenClaims claims,
                                                  @PathVariable Long id) {
        convService.delete(claims.userId(), id);
        return Map.of("ok", true);
    }

    @GetMapping("/conversations/{id}/messages")
    public List<Map<String, Object>> messages(@RequestAttribute("kbUser") TokenClaims claims,
                                    @PathVariable Long id) {
        return convService.messages(claims.userId(), id);
    }

    @GetMapping("/preferences")
    public Map<String, Object> getPreference(@RequestAttribute("kbUser") TokenClaims claims) {
        return convService.getPreference(claims.userId());
    }

    @PutMapping("/preferences")
    public Map<String, Object> savePreference(@RequestAttribute("kbUser") TokenClaims claims,
                                              @RequestBody Map<String, Object> body) {
        return convService.savePreference(claims.userId(), body);
    }

    private Long parseLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            String s = o.toString().trim();
            if (s.isEmpty()) return null;
            return Long.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }
}
