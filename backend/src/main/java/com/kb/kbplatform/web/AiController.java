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
import com.kb.kbplatform.service.AiFeedbackService;
import com.kb.kbplatform.service.AiModelClient;
import com.kb.kbplatform.service.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
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
    private final AiFeedbackService feedbackService;

    public AiController(ObjectProvider<RagService> ragProvider,
                        RateLimitService rateLimit,
                        AiConversationService convService,
                        AiModelClient aiModelClient,
                        AiFeedbackService feedbackService) {
        this.ragProvider = ragProvider;
        this.rateLimit = rateLimit;
        this.convService = convService;
        this.aiModelClient = aiModelClient;
        this.feedbackService = feedbackService;
    }

    /** 当前可用模型列表（不含 API Key），普通登录用户即可查询，用于对话页的模型下拉 */
    @GetMapping("/models")
    public List<Map<String, Object>> models(@RequestAttribute("kbUser") TokenClaims claims) {
        return aiModelClient.publicModels(claims.userId());
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
            log.warn("AI 问答未启用：RagService bean 不可用（请确认 kb.ai.enabled=true）");
            return Map.of("answer", "AI 问答暂不可用，请稍后再试或联系管理员。");
        }
        String question = body.get("question") == null ? "" : body.get("question").toString();
        Long conversationId = parseLong(body.get("conversationId"));
        String mode = body.get("mode") == null ? "kb" : body.get("mode").toString();
        // modelId 省略时用管理员配置的默认模型；userModelId 非空时优先用用户自己的模型
        Long modelId = parseLong(body.get("modelId"));
        Long userModelId = parseLong(body.get("userModelId"));
        // 图片（dataURL 列表）：模型原生多模态，直接随消息下发。
        // 服务端必须兜底校验（前端压缩可绕过）：数量 ≤4、必须 data:image/*;base64、
        // 单张解码后 ≤2MB（base64 长度 ≈ 4/3 原始字节，用 3_000_000 字符近似）。
        // 缺校验会导致内存/带宽放大与 token 费用被刷（外部复审 2026-10-07）。
        List<String> images = parseImages(body.get("images"));
        return convService.ask(claims.userId(), conversationId, question, mode, modelId, userModelId, images);
    }

    /** 对某条回答点赞/踩（qaId 来自 /ask 或 /ask/stream 的 done 事件）；只能评价自己的记录。 */
    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestAttribute("kbUser") TokenClaims claims,
                                        @RequestBody Map<String, Object> body) {
        Long qaId = parseLong(body.get("qaId"));
        Integer rating = (body.get("rating") instanceof Number n) ? n.intValue() : null;
        String reason = body.get("reason") == null ? "" : body.get("reason").toString();
        return feedbackService.rate(claims.userId(), qaId, rating, reason);
    }

    /**
     * 流式问答（SSE）：逐段下发 {"delta":"..."} / {"reasoning":"..."}，结束发 done 事件（含 sources 等元信息）。
     * 前端用 fetch + ReadableStream 读取，abort 时后端从 sink 抛错退出并保留已生成内容。
     */
    @PostMapping(value = "/ask/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter askStream(
            @RequestAttribute("kbUser") TokenClaims claims, @RequestBody Map<String, Object> body) {
        var emitter = new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(180_000L);
        if (!rateLimit.allow("ai:ask:" + claims.userId(), 20, 60L * 60 * 1000)) {
            emitOnce(emitter, "error", Map.of("error", "提问太频繁了，请稍后再试（每小时限 20 次）"));
            return emitter;
        }
        String question = body.get("question") == null ? "" : body.get("question").toString();
        Long conversationId = parseLong(body.get("conversationId"));
        String mode = body.get("mode") == null ? "kb" : body.get("mode").toString();
        Long modelId = parseLong(body.get("modelId"));
        Long userModelId = parseLong(body.get("userModelId"));
        List<String> images = parseImages(body.get("images"));

        try {
        SSE_POOL.execute(() -> {
            try {
                Map<String, Object> result = convService.askStream(claims.userId(), conversationId, question, mode,
                        modelId, userModelId, images, (delta, reasoning) -> {
                            Map<String, Object> ev = new java.util.LinkedHashMap<>();
                            if (delta != null && !delta.isEmpty()) ev.put("delta", delta);
                            if (reasoning != null && !reasoning.isEmpty()) ev.put("reasoning", reasoning);
                            if (!ev.isEmpty()) emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                    .event().data(ev, org.springframework.http.MediaType.APPLICATION_JSON));
                        });
                emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("done")
                        .data(result, org.springframework.http.MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                log.warn("AI 流式问答中断: {}", e.getMessage());
                emitOnce(emitter, "error", Map.of("error", "AI 生成中断，请重试"));
                emitter.complete();
            }
        });
        } catch (java.util.concurrent.RejectedExecutionException busy) {
            emitOnce(emitter, "error", Map.of("error", "当前提问人数较多，请稍后再试"));
        }
        return emitter;
    }

    /** 流式下发放在独立线程池：不能占用 Tomcat 请求线程，也不能无限开线程 */
    /** 并发上限 8、队列上限 32：流式任务会长时间占线程，无界队列会让人无限排队等不到首字 */
    private static final int SSE_QUEUE = 32;
    private static final java.util.concurrent.ThreadPoolExecutor SSE_POOL =
            new java.util.concurrent.ThreadPoolExecutor(8, 8, 60, java.util.concurrent.TimeUnit.SECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(SSE_QUEUE),
                    (r) -> {
                        Thread t = new Thread(r, "ai-sse");
                        t.setDaemon(true);
                        return t;
                    },
                    new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    private void emitOnce(org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter,
                          String name, Object data) {
        try {
            emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event()
                    .name(name).data(data, org.springframework.http.MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception ignore) { /* 客户端已断开 */ }
    }

    /** 图片校验（流式与非流式共用，避免两条链路规则漂移） */
    private List<String> parseImages(Object imgsRaw) {
        List<String> images = new ArrayList<>();
        if (imgsRaw instanceof List<?> l) {
            for (Object o : l) {
                if (o == null) continue;
                if (!(o instanceof String s)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "images 元素必须是字符串 dataURL");
                }
                if (s.isBlank()) continue;
                if (!s.matches("^data:image/(png|jpeg|jpg|webp|gif);base64,[A-Za-z0-9+/=\\s]+$")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "图片仅支持 data:image/(png|jpeg|webp|gif) 的 base64 dataURL");
                }
                if (s.length() > 3_000_000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单张图片过大（解码后 ≤2MB）");
                }
                images.add(s);
            }
            if (images.size() > 4) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次最多附带 4 张图片");
            }
        }
        return images;
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

    /** 批量删除会话：body { ids: [1,2,3] }，返回实际删除条数 */
    @PostMapping("/conversations/batch-delete")
    public Map<String, Object> batchDelete(@RequestAttribute("kbUser") TokenClaims claims,
                                           @RequestBody Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        if (body.get("ids") instanceof List<?> l) {
            for (Object o : l) {
                Long id = parseLong(o);
                if (id != null) ids.add(id);
            }
        }
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要删除的会话");
        if (ids.size() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次最多删除 200 条");
        return Map.of("deleted", convService.deleteBatch(claims.userId(), ids));
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
