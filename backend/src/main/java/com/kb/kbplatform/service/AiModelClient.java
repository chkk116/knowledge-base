/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AiModel;
import com.kb.kbplatform.domain.AiUsageLog;
import com.kb.kbplatform.repository.AiModelRepository;
import com.kb.kbplatform.repository.AiUsageLogRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 统一多模型调用层（零依赖，不引 Spring AI）。
 *
 * OpenAI / 阿里 DashScope(百炼) / DeepSeek / 智谱 都实现了 OpenAI 兼容的 POST /chat/completions，
 * 差异仅在 base-url 与模型名，因此这里把配置行翻译成同一份请求体，各平台天然通用。
 *
 * - 选模型：优先用户这次指定的 modelId；否则取管理员设的「默认模型」；再否则取排序最前的启用项；
 * - 降级：任一模型 429/5xx/网络抖动/Key 失效时自动换下一个可用模型，全失败才返回友好提示；
 * - 缓存：RestClient 按配置行缓存，管理员保存/删除/启停后调用 invalidate() 失效。
 */
@Service
public class AiModelClient {

    private static final Logger log = LoggerFactory.getLogger(AiModelClient.class);

    private final AiModelRepository repo;
    private final AiKeyCipher cipher;
    private final AiUsageLogRepository usageRepo;
    private final Map<Long, RestClient> clients = new ConcurrentHashMap<>();

    public AiModelClient(AiModelRepository repo, AiKeyCipher cipher, AiUsageLogRepository usageRepo) {
        this.repo = repo;
        this.cipher = cipher;
        this.usageRepo = usageRepo;
    }

    /** 用户端可选模型（不含 Key，供对话页下拉使用） */
    public List<Map<String, Object>> publicModels() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiModel m : repo.findByEnabledTrueOrderBySortOrderAscIdAsc()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.getId());
            r.put("provider", m.getProvider());
            r.put("name", m.getName());
            r.put("model", m.getModel());
            r.put("isDefault", m.isDefaultModel());
            out.add(r);
        }
        return out;
    }

    /** 是否至少配置了一个可用模型（RagService 据此判断 AI 是否可用） */
    public boolean hasAvailable() {
        return !repo.findByEnabledTrueOrderBySortOrderAscIdAsc().isEmpty();
    }

    /**
     * 预告本次请求将优先使用的模型显示名（人设文案用，如「底层由 XX 驱动」）。
     * 若调用时降级到了其他模型，以回答下方展示的实际模型 meta 为准。
     */
    public String resolveModelLabel(Long modelId) {
        List<AiModel> chain = candidates(modelId);
        if (chain.isEmpty()) return "";
        AiModel m = chain.get(0);
        return m.getName() != null && !m.getName().isBlank() ? m.getName() : m.getModel();
    }

    /** 管理员保存/删除/启停/换 Key 后调用，丢弃旧 RestClient（内含旧 Key） */
    public void invalidate() {
        clients.clear();
    }

    /**
     * 对话：按请求优先使用指定模型，失败自动降级。
     * @param modelId 本次要用的模型配置 id；null = 用默认
     */
    public AiChatResult chat(Long modelId, List<Map<String, Object>> messages) {
        return chatChain(candidates(modelId), messages, "chat");
    }

    /** 沿给定候选链依次尝试（对话降级链与「测试连接」的单模型验证共用）；source 用于用量流水标记 */
    private AiChatResult chatChain(List<AiModel> chain, List<Map<String, Object>> messages, String source) {
        if (chain.isEmpty()) {
            return AiChatResult.fail("尚未配置可用的 AI 模型，请联系管理员在「AI 模型管理」中添加。");
        }
        List<String> errors = new ArrayList<>();
        for (AiModel m : chain) {
            long t0 = System.currentTimeMillis();
            try {
                RestClient c = clientFor(m);
                String key = cipher.decrypt(m.getApiKey());
                if (key == null || key.isBlank()) {
                    errors.add(m.getName() + ": API Key 为空或解密失败（主密钥变更）");
                    recordUsage(m, source, false, null, System.currentTimeMillis() - t0, "API Key 为空或解密失败");
                    continue;
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", m.getModel());
                body.put("temperature", 0.3);
                // 思考型模型（GLM-5.x、GPT-5.x、DeepSeek V4.1）会把思考过程计入 max_tokens，
                // 实测一轮思考+回答可达 15K+ tokens，8192 会把答案截断，给足上限
                body.put("max_tokens", 16384);
                body.put("messages", messages);

                Map<String, Object> resp = c.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                String text = extractContent(resp);
                long ms = System.currentTimeMillis() - t0;
                if (text == null || text.isBlank()) {
                    errors.add(m.getName() + ": 模型返回为空");
                    recordUsage(m, source, false, null, ms, "模型返回为空");
                    continue;
                }
                Map<String, Object> usage = usageOf(resp);
                recordUsage(m, source, true, usage, ms, null);
                return new AiChatResult(text, m.getModel(), m.getName(), ms, usage, extractReasoning(resp));
            } catch (org.springframework.web.client.RestClientResponseException re) {
                int sc = re.getStatusCode().value();
                log.warn("AI 模型调用失败(name={}, http={}): {}", m.getName(), sc, re.getMessage());
                errors.add(m.getName() + ": HTTP " + sc);
                recordUsage(m, source, false, null, System.currentTimeMillis() - t0, "HTTP " + sc);
                // 限流/服务端抖动/鉴权失败都换下一个模型再试
            } catch (Exception e) {
                log.warn("AI 模型调用异常(name={}): {}", m.getName(), e.getMessage());
                errors.add(m.getName() + ": " + e.getMessage());
                recordUsage(m, source, false, null, System.currentTimeMillis() - t0, e.getMessage());
            }
        }
        // 失败细节只进日志：这段文案会被当答案落库并展示给普通用户，
        // 一旦拼上异常原文，内部端点 / 主机名等就可能泄露出去
        if (!errors.isEmpty()) {
            log.warn("AI 全部候选模型均失败：{}", String.join("；", errors));
        }
        String hint = errors.isEmpty()
                ? "AI 服务暂时不可用，请稍后重试。"
                : "AI 服务暂时不可用：已依次尝试 " + errors.size() + " 个模型，均调用失败，请稍后重试。";
        return AiChatResult.fail(hint);
    }

    /** 记调用流水（用量统计的数据源）：绝不因记录失败影响正常调用 */
    private void recordUsage(AiModel m, String source, boolean success, Map<String, Object> usage,
                             long latencyMs, String error) {
        try {
            AiUsageLog row = new AiUsageLog();
            row.setModelId(m.getId());
            row.setModelName(m.getName());
            row.setModel(m.getModel());
            row.setSource(source);
            row.setSuccess(success);
            row.setPromptTokens(intOf(usage == null ? null : usage.get("prompt_tokens")));
            row.setCompletionTokens(intOf(usage == null ? null : usage.get("completion_tokens")));
            Object total = usage == null ? null : usage.get("total_tokens");
            row.setTotalTokens(total != null ? intOf(total)
                    : row.getPromptTokens() + row.getCompletionTokens());
            row.setLatencyMs(Math.max(0, latencyMs));
            if (error != null) {
                String e = error.length() > 280 ? error.substring(0, 280) : error;
                row.setError(e);
            }
            usageRepo.save(row);
        } catch (Exception ex) {
            log.warn("记录 AI 用量流水失败（不影响调用）: {}", ex.getMessage());
        }
    }

    private int intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return 0; }
    }

    /** 管理页「测试连接」：只测指定模型本身，不走降级链 —— 测 A 报 B 可用会误导排障 */
    public AiChatResult test(Long modelId) {
        AiModel m = repo.findById(modelId).orElse(null);
        if (m == null || !m.isEnabled()) return AiChatResult.fail("该模型配置不存在或已停用");
        List<Map<String, Object>> msgs = List.of(Map.<String, Object>of("role", "user", "content", "只回复两个字：正常"));
        return chatChain(List.of(m), msgs, "test");
    }

    /** 候选链：指定模型 → 默认模型 → 其余启用模型（去重） */
    private List<AiModel> candidates(Long modelId) {
        List<AiModel> all = repo.findByEnabledTrueOrderBySortOrderAscIdAsc();
        List<AiModel> chain = new ArrayList<>();
        if (modelId != null) {
            for (AiModel m : all) if (m.getId().equals(modelId)) chain.add(m);
        }
        if (chain.isEmpty()) {
            for (AiModel m : all) if (m.isDefaultModel()) { chain.add(m); break; }
        }
        if (chain.isEmpty() && !all.isEmpty()) chain.add(all.get(0));
        for (AiModel m : all) {
            if (chain.size() >= 3) break;               // 最多试 3 个，控制响应时长
            boolean dup = false;
            for (AiModel c : chain) if (c.getId().equals(m.getId())) dup = true;
            if (!dup) chain.add(m);
        }
        return chain;
    }

    private RestClient clientFor(AiModel m) {
        return clients.computeIfAbsent(m.getId(), id -> {
            String base = m.getBaseUrl() == null || m.getBaseUrl().isBlank()
                    ? defaultBaseUrl(m.getProvider())
                    : m.getBaseUrl().trim();
            RestClient.Builder b = RestClient.builder()
                    .baseUrl(base)
                    .requestFactory(timeoutRequestFactory());
            b = b.defaultHeader("Authorization", "Bearer " + cipher.decrypt(m.getApiKey()).trim());
            b = b.defaultHeader("Content-Type", "application/json");
            return b.build();
        });
    }

    /** 带连接/读取超时的请求工厂：不给超时，模型端 hang 住会把 Tomcat 线程一直钉着 */
    private static ClientHttpRequestFactory timeoutRequestFactory() {
        // JDK HttpClient 自身没有读超时，读超时由 JdkClientHttpRequestFactory 负责（注意 SF7 只有 setter，不能走构造参数）
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
        rf.setReadTimeout(Duration.ofSeconds(120));
        return rf;
    }

    /** 常见平台默认端点（管理员留空时兜底，减少填错） */
    private String defaultBaseUrl(String provider) {
        if (provider == null) return "https://api.openai.com/v1";
        return switch (provider) {
            case AiModel.PROVIDER_OPENAI -> "https://api.openai.com/v1";
            case AiModel.PROVIDER_DASHSCOPE -> "https://dashscope.aliyuncs.com/compatible-mode/v1";
            case AiModel.PROVIDER_DEEPSEEK -> "https://api.deepseek.com/v1";
            case AiModel.PROVIDER_ZHIPU -> "https://open.bigmodel.cn/api/paas/v4";
            default -> "https://api.openai.com/v1";
        };
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> resp) {
        if (resp == null) return null;
        Object choices = resp.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> m) {
                Object msg = m.get("message");
                if (msg instanceof Map<?, ?> mm) {
                    Object content = mm.get("content");
                    if (content != null) return String.valueOf(content).trim();
                }
            }
        }
        Object err = resp.get("error");
        if (err != null) log.warn("模型返回错误: {}", err);
        return null;
    }

    /** 思考型模型的思考过程（DeepSeek / GLM 都放在 message.reasoning_content），非思考模型无此字段返回 null */
    private String extractReasoning(Map<String, Object> resp) {
        if (resp == null) return null;
        Object choices = resp.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> m && m.get("message") instanceof Map<?, ?> mm) {
                Object r = mm.get("reasoning_content");
                if (r instanceof String s && !s.isBlank()) return s.trim();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> usageOf(Map<String, Object> resp) {
        if (resp == null) return Map.of();
        Object u = resp.get("usage");
        if (u instanceof Map) return (Map<String, Object>) u;
        return Map.of();
    }

    /** 一次调用的完整结果：文本 + 实际用的模型 + 耗时 + token 用量 + 思考过程（思考型模型才有） */
    public record AiChatResult(String text, String model, String modelName, long latencyMs,
                               Map<String, Object> usage, String reasoning) {

        static AiChatResult fail(String msg) {
            return new AiChatResult(msg, "", "", 0, Map.of(), null);
        }

        /** 成功 = 拿到了真实模型应答（model 非空）。失败文案也写在 text 里，所以不能用 text 判断 */
        public boolean ok() {
            return model != null && !model.isBlank();
        }
    }
}
