package com.kb.kbplatform.web;

import com.kb.kbplatform.security.RateLimitService;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.RagService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ai")
public class AiController {

    private final ObjectProvider<RagService> ragProvider;
    private final RateLimitService rateLimit;

    public AiController(ObjectProvider<RagService> ragProvider, RateLimitService rateLimit) {
        this.ragProvider = ragProvider;
        this.rateLimit = rateLimit;
    }

    @PostMapping("/ask")
    public Map<String, String> ask(@RequestAttribute("kbUser") TokenClaims claims,
                                   @RequestBody Map<String, String> body) {
        // 防刷：AI 调用消耗云端 token（真金白银），每用户 20 次/小时
        if (!rateLimit.allow("ai:ask:" + claims.userId(), 20, 60L * 60 * 1000)) {
            return Map.of("answer", "提问太频繁了，请稍后再试（每小时限 20 次）");
        }
        RagService svc = ragProvider.getIfAvailable();
        if (svc == null) {
            return Map.of("answer",
                    "AI 问答尚未启用。请先接入模型服务（Ollama / OpenAI 等），并在 application.yml 中将 kb.ai.enabled 改为 true、按所选向量库打开对应 spring.ai.* 开关。");
        }
        return Map.of("answer", svc.ask(body.get("question")));
    }
}
