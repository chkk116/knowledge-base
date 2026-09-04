package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * AI 问答（RAG）服务 —— 零依赖实现（不引 Spring AI）。
 *
 * 方案：检索增强生成（Retrieval-Augmented Generation）。
 *   1) 检索：优先 Meilisearch（若启用），否则回退 DocumentRepository 的 MySQL LIKE 检索；
 *   2) 生成：调用智谱大模型（OpenAI 兼容端点，默认 glm-4-flash 永久免费）基于检索片段作答；
 *   3) 基础聊天兜底：当知识库检索不到相关资料时，转为通用闲聊/通用问答（模型自身知识），不再机械回复“未找到”。
 *
 * 与 MeiliSearchClient 一致的约束：任何异常都降级为友好提示，绝不影响主流程；
 * 模型调用消耗云端 token，受 AiController 限流保护（每用户 20 次/小时）。
 * API Key 通过环境变量 KB_ZHIPU_API_KEY 注入（原明文 Key 已泄露并轮换）。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int TOP_K = 3;
    private static final int FRAG_LEN = 400;

    private final MeiliSearchClient meili;
    private final DocumentRepository repository;
    private final RestClient client;
    private final boolean enabled;
    private final String model;

    public RagService(MeiliSearchClient meili,
                      DocumentRepository repository,
                      @Value("${kb.ai.enabled:false}") boolean enabled,
                      @Value("${kb.ai.zhipu.base-url:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
                      @Value("${kb.ai.zhipu.api-key:}") String apiKey,
                      @Value("${kb.ai.zhipu.model:glm-4-flash}") String model) {
        this.meili = meili;
        this.repository = repository;
        this.enabled = enabled && apiKey != null && !apiKey.isBlank();
        this.model = model;
        if (this.enabled) {
            // 静态工厂构建 RestClient，不依赖 Spring 自动配置的 RestClient.Builder bean（SB 4.1.0 下该 bean 未注入）
            RestClient.Builder b = RestClient.builder().baseUrl(baseUrl.trim());
            b = b.defaultHeader("Authorization", "Bearer " + apiKey.trim());
            b = b.defaultHeader("Content-Type", "application/json");
            this.client = b.build();
        } else {
            this.client = null;
        }
    }

    public String ask(String question) {
        Map<String, Object> r = ask(question, null, null, null, "kb");
        Object a = r.get("answer");
        return a == null ? "" : a.toString();
    }

    /**
     * 支持历史上下文、对话条例、用户习惯的增强问答。
     * @param question      当前问题
     * @param history       历史消息（role/content），按时间正序；可为空
     * @param instructions  当前会话的对话条例/系统提示；可为空
     * @param persona       用户全局习惯/偏好（JSON 字符串）；可为空
     * @param mode          回答模式：kb=基于知识库检索（默认），general=纯通用问答（不检索）
     * @return { answer, sources }，sources 为引用来源列表（id/title/snippet），通用模式下为空
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> ask(String question, List<Map<String, String>> history,
                                   String instructions, String persona, String mode) {
        if (!enabled || question == null || question.isBlank()) {
            if (!enabled) {
                log.warn("AI 问答未启用：kb.ai.enabled=false 或 kb.ai.zhipu.api-key 未注入（请在 application.yml 设 enabled=true 并通过环境变量 KB_ZHIPU_API_KEY 注入 Key）");
            }
            return Map.of("answer", "AI 问答暂不可用，请稍后再试或联系管理员。", "sources", List.of());
        }

        // 1. 检索相关知识片段（通用模式跳过检索）
        boolean general = "general".equalsIgnoreCase(mode);
        List<Map<String, Object>> sources = general ? List.of() : retrieve(question.trim());
        boolean hasContext = !sources.isEmpty();

        // 2. 组装 system prompt：默认身份 + 对话条例 + 用户习惯
        StringBuilder system = new StringBuilder();
        system.append("你是知识库的智能助手。");
        if (hasContext) {
            system.append("优先依据提供的【资料】回答；若资料中没有相关信息，明确说明“知识库中未找到相关内容”，不要编造。");
        } else {
            system.append("如果没有相关资料，也可以进行通用问答和日常闲聊。");
        }
        system.append("请用中文、简洁友好地回答。");

        if (instructions != null && !instructions.isBlank()) {
            system.append("\n\n【本次对话条例】\n").append(instructions.trim());
        }
        if (persona != null && !persona.isBlank()) {
            system.append("\n\n【用户偏好】\n").append(extractPersonaHint(persona));
        }

        // 3. 组装 messages：system + 历史 + 当前 user
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system.toString()));
        if (history != null) {
            for (Map<String, String> h : history) {
                String role = h.get("role");
                String content = h.get("content");
                if (role == null || content == null) continue;
                if (!"user".equals(role) && !"ai".equals(role) && !"assistant".equals(role)) continue;
                // 统一使用 assistant 作为 AI 角色名（智谱兼容）
                String modelRole = "ai".equals(role) ? "assistant" : role;
                messages.add(Map.of("role", modelRole, "content", content));
            }
        }
        if (hasContext) {
            String ctx = sources.stream()
                    .map(s -> "【" + s.getOrDefault("title", "") + "】\n" + s.getOrDefault("snippet", ""))
                    .collect(java.util.stream.Collectors.joining("\n\n"));
            messages.add(Map.of("role", "user", "content",
                    "【资料】\n" + ctx + "\n\n【问题】" + question.trim()));
        } else {
            messages.add(Map.of("role", "user", "content", question.trim()));
        }

        String answer = callModel(messages);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("sources", sources);
        return result;
    }

    /** 从用户习惯 JSON 中提取可读提示，失败则原样返回 */
    private String extractPersonaHint(String persona) {
        try {
            // 极简解析：不需要引入 JSON 库，逐行提取 key-value
            StringBuilder sb = new StringBuilder();
            String[] parts = persona.replaceAll("[{}\\[\\]\"]", "").split(",");
            for (String p : parts) {
                String[] kv = p.split(":", 2);
                if (kv.length != 2) continue;
                String k = kv[0].trim();
                String v = kv[1].trim();
                if (v.isEmpty() || "null".equals(v)) continue;
                switch (k) {
                    case "tone" -> sb.append("回答语气：").append(v).append("。");
                    case "length" -> sb.append("回复长度偏好：").append(v).append("。");
                    case "focus" -> sb.append("关注领域：").append(v).append("。");
                    case "custom" -> sb.append("附加要求：").append(v).append("。");
                    default -> {}
                }
            }
            return sb.isEmpty() ? persona : sb.toString();
        } catch (Exception e) {
            return persona;
        }
    }

    /** 调用智谱 chat/completions，传入完整 messages */
    private String callModel(List<Map<String, String>> messages) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("temperature", 0.3);
            body.put("max_tokens", 800);
            body.put("messages", messages);

            Map<String, Object> resp = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return extractContent(resp);
        } catch (Exception e) {
            log.warn("智谱 AI 调用失败: {}", e.getMessage());
            return "AI 服务暂时不可用，请稍后重试，或先用搜索框检索知识库。";
        }
    }

    /** 检索 top-K 片段：优先 Meili，失败/未启用回退 MySQL；返回引用来源（id/title/snippet） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> retrieve(String q) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (meili != null && meili.isEnabled()) {
            try {
                List<Map<String, Object>> hits = meili.search(q, null);
                for (Map<String, Object> h : hits.stream().limit(TOP_K).toList()) {
                    String title = str(h.get("title"));
                    String snippet = str(h.get("snippet"));
                    if (title.isEmpty()) continue;
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("id", h.get("id"));
                    s.put("title", title);
                    s.put("snippet", snippet);
                    out.add(s);
                }
                if (!out.isEmpty()) return out;
            } catch (Exception e) {
                log.warn("Meili 检索失败，回退 MySQL: {}", e.getMessage());
            }
        }
        // MySQL 兜底
        try {
            for (Document d : repository.search(q)) {
                if (!"PUBLISHED".equals(d.getStatus())) continue;
                String text = HtmlSanitizer.toText(d.getContent());
                if (text.length() > FRAG_LEN) text = text.substring(0, FRAG_LEN);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", d.getId());
                s.put("title", d.getTitle());
                s.put("snippet", text);
                out.add(s);
                if (out.size() >= TOP_K) break;
            }
        } catch (Exception e) {
            log.warn("MySQL 检索失败: {}", e.getMessage());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> resp) {
        if (resp == null) return "AI 返回为空。";
        Object choicesObj = resp.get("choices");
        if (choicesObj instanceof List<?> choices && !choices.isEmpty()) {
            Object first = choices.get(0);
            if (first instanceof Map<?, ?> m) {
                Object message = m.get("message");
                if (message instanceof Map<?, ?> msg) {
                    Object content = msg.get("content");
                    if (content != null) return String.valueOf(content).trim();
                }
            }
        }
        Object err = resp.get("error");
        if (err != null) return "AI 返回错误：" + err;
        return "AI 返回为空。";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
