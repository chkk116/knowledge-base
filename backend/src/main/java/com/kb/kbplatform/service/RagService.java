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
    private final List<String> modelChain;   // 文本模型降级链：免费 flash 模型常被挤爆(1305/429)，自动换下一个

    public RagService(MeiliSearchClient meili,
                      DocumentRepository repository,
                      @Value("${kb.ai.enabled:false}") boolean enabled,
                      @Value("${kb.ai.zhipu.base-url:https://open.bigmodel.cn/api/paas/v4}") String baseUrl,
                      @Value("${kb.ai.zhipu.api-key:}") String apiKey,
                      @Value("${kb.ai.zhipu.model:glm-4.7-flash}") String model,
                      @Value("${kb.ai.zhipu.models:}") String modelsCsv) {
        this.meili = meili;
        this.repository = repository;
        this.enabled = enabled && apiKey != null && !apiKey.isBlank();
        List<String> chain = new ArrayList<>();
        if (modelsCsv != null) {
            for (String m : modelsCsv.split(",")) {
                String t = m == null ? "" : m.trim();
                if (!t.isEmpty()) chain.add(t);
            }
        }
        if (chain.isEmpty()) chain.add(model);
        this.modelChain = chain;
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

        // 2. 组装 system prompt：身份 + 知识库概况 + 行为准则 + 对话条例 + 用户习惯
        StringBuilder system = new StringBuilder();
        system.append("你是企业知识库平台的内置 AI 助手「知识库 AI」，底层由智谱 GLM 驱动，服务于企业内部员工。");
        // 注入知识库概况，让「知识库里有多少篇文章 / 你能做什么」这类元问题可直接回答
        try {
            long published = repository.countByStatus("PUBLISHED");
            system.append("本知识库当前共有 ").append(published).append(" 篇已发布文章，内容涵盖运维手册、IT 操作文档、演练记录等企业资料。");
        } catch (Exception ignore) { /* 统计失败不影响问答 */ }
        system.append("\n【行为准则】\n")
                .append("1. 提供了【资料】时：优先且仅依据资料回答，操作步骤、命令、参数须如实引用，命令和代码一律放进 Markdown 代码块；资料与你的通用知识冲突时，以资料为准。\n")
                .append("2. 资料只覆盖部分问题：先依据资料作答，再补充通用知识，补充部分须标注「以下为通用做法」。\n")
                .append("3. 资料完全未覆盖：明确说「知识库中未找到相关内容」，可再给通用建议（同样标注为通用知识）；严禁编造不存在的文章、章节、数据或链接。\n")
                .append("4. 关于知识库本身的问题（有多少文章、你能做什么、你是谁）：直接依据上述概况和身份回答，不要说「无法访问后台」。\n")
                .append("5. 回答用简体中文，先结论后细节、要点分条、简洁专业；避免客套话和重复用户问题。\n")
                .append("6. 不讨论政治敏感内容，不提供违法或破坏系统的指导。");
        if (general) {
            system.append("\n7. 当前为「通用问答」模式：直接正常回答即可，不要提及知识库检索或资料。");
        }

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

    /** 调用智谱 chat/completions，传入完整 messages；429/5xx 自动降级到链内下一个模型（免费 flash 模型常被挤爆 1305） */
    private String callModel(List<Map<String, String>> messages) {
        for (String m : modelChain) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", m);
                body.put("temperature", 0.3);
                // GLM-4.7-Flash 支持动态思考（默认开）：思考内容也计入 max_tokens，
                // 实测简单问题思考就要消耗数百 token，上限给 4096 防止答案被思考挤掉截断
                body.put("max_tokens", 4096);
                body.put("messages", messages);

                Map<String, Object> resp = client.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                return extractContent(resp);
            } catch (org.springframework.web.client.RestClientResponseException re) {
                int sc = re.getStatusCode().value();
                log.warn("智谱 AI 调用失败(model={}, http={}): {}", m, sc, re.getMessage());
                if (sc == 429 || sc >= 500) continue;   // 模型过载/服务端错误：降级试链内下一个
                break;                                   // 其余 4xx（如 Key 失效）换模型也没用
            } catch (Exception e) {
                log.warn("智谱 AI 调用失败(model={}): {}", m, e.getMessage());
                continue;   // 网络抖动等：试下一个模型
            }
        }
        return "AI 服务暂时不可用，请稍后重试，或先用搜索框检索知识库。";
    }

    /** 检索 top-K 片段：优先 Meili，失败/未启用回退 MySQL；返回引用来源（id/title/snippet） */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> retrieve(String q) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (meili != null && meili.isEnabled()) {
            try {
                List<Map<String, Object>> hits = meili.search(q, null);
                // 可见性兜底（F10）：索引删除失败时，下架/封禁文档仍可能留在 Meili 里。
                // 送入模型前用数据库当前状态核对，避免 AI 引用已下架内容。
                Set<Long> hitIds = new HashSet<>();
                for (Map<String, Object> h : hits) {
                    if (h.get("id") == null) continue;
                    try { hitIds.add(Long.valueOf(String.valueOf(h.get("id")))); } catch (Exception e) { /* ignore */ }
                }
                Set<Long> visible = hitIds.isEmpty() ? Set.of()
                        : repository.findByStatusAndIdIn("PUBLISHED", new ArrayList<>(hitIds)).stream()
                            .map(Document::getId).collect(java.util.stream.Collectors.toSet());
                // 先按可见性过滤再取 TOP_K：否则不可见的过期索引条目会占坑，实际喂给模型的有效来源变少
                List<Map<String, Object>> picked = new ArrayList<>();
                for (Map<String, Object> h : hits) {
                    if (picked.size() >= TOP_K) break;
                    Long hid = null;
                    try { hid = h.get("id") == null ? null : Long.valueOf(String.valueOf(h.get("id"))); } catch (Exception e) { /* ignore */ }
                    if (hid != null && !visible.contains(hid)) continue;   // 索引过期条目：跳过
                    picked.add(h);
                }
                for (Map<String, Object> h : picked) {
                    String title = str(h.get("title"));
                    String snippet = str(h.get("snippet"));
                    if (title.isEmpty()) continue;
                    Long hid = null;
                    try { hid = h.get("id") == null ? null : Long.valueOf(String.valueOf(h.get("id"))); } catch (Exception e) { /* ignore */ }
                    if (hid != null && !visible.contains(hid)) continue;   // 索引过期条目：跳过
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
        // MySQL 兜底：整句 LIKE 几乎必然 0 命中（标题不会包含「找一下…」这类口语），
        // 先拆关键词（按空格/标点/常见疑问词），逐词检索后按命中词数排序合并
        List<String> keywords = splitKeywords(q);
        if (keywords.isEmpty()) keywords = List.of(q);
        Map<Long, Map<String, Object>> merged = new java.util.LinkedHashMap<>();
        Map<Long, Integer> score = new java.util.HashMap<>();
        try {
            for (String kw : keywords) {
                for (Document d : repository.search(kw)) {
                    if (!"PUBLISHED".equals(d.getStatus())) continue;
                    merged.putIfAbsent(d.getId(), toSource(d, keywords));
                    score.merge(d.getId(), 1, Integer::sum);
                }
            }
            merged.keySet().stream()
                    .sorted(java.util.Comparator.comparing((Long id) -> score.get(id)).reversed())
                    .limit(TOP_K)
                    .forEach(id -> out.add(merged.get(id)));
        } catch (Exception e) {
            log.warn("MySQL 检索失败: {}", e.getMessage());
        }
        return out;
    }

    /** 把 Document 转成引用来源：摘要取「覆盖关键词最多」的窗口（首次命中常落在文档开头的标题/目录，不含目标内容） */
    private Map<String, Object> toSource(Document d, List<String> keywords) {
        String text = HtmlSanitizer.toText(d.getContent());
        String snippet;
        List<String> kws = (keywords == null) ? List.of()
                : keywords.stream().filter(k -> k != null && k.length() >= 2).toList();
        int bestStart = -1, bestHits = 0;
        if (!kws.isEmpty() && text.length() > FRAG_LEN) {
            String lower = text.toLowerCase();
            // 候选窗口起点 = 每个关键词的每次出现位置（每词最多探测 8 处），选窗口内命中的不同关键词数最多者
            for (String kw : kws) {
                int from = 0, found = 0;
                while (found < 8) {
                    int i = lower.indexOf(kw.toLowerCase(), from);
                    if (i < 0) break;
                    from = i + 1; found++;
                    int start = Math.max(0, i - 80);
                    int end = Math.min(text.length(), start + FRAG_LEN);
                    String win = lower.substring(start, end);
                    int hits = 0;
                    for (String k2 : kws) if (win.contains(k2.toLowerCase())) hits++;
                    if (hits > bestHits) { bestHits = hits; bestStart = start; }
                }
            }
        }
        if (bestStart >= 0) {
            int end = Math.min(text.length(), bestStart + FRAG_LEN);
            snippet = (bestStart > 0 ? "…" : "") + text.substring(bestStart, end);
        } else {
            snippet = text.length() > FRAG_LEN ? text.substring(0, FRAG_LEN) : text;
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", d.getId());
        s.put("title", d.getTitle());
        s.put("snippet", snippet);
        return s;
    }

    /**
     * 拆检索关键词：按空白/标点切分，去掉疑问词与单字符噪声，保留 ≥2 字符的词。
     * 例：「找一下银河麒麟 V10 离线安装」→ [银河麒麟, V10, 离线安装]
     */
    private List<String> splitKeywords(String q) {
        if (q == null || q.isBlank()) return List.of();
        // 常见口语/疑问词：命中即整体剔除，避免它们污染 LIKE
        String cleaned = q.replaceAll("找一下|查找一下|搜索一下|帮我|请问|如何|怎么|怎样|什么|哪些|多少|为什么|一下|关于|知识库|文章|教程|的", " ");
        // 中文与英文/数字的边界补空格：「验证deb包安装状态」→「验证 deb 包安装状态」，否则整串成一个词无法 LIKE
        cleaned = cleaned.replaceAll("([\\u4e00-\\u9fa5])([a-zA-Z0-9])", "$1 $2")
                         .replaceAll("([a-zA-Z0-9])([\\u4e00-\\u9fa5])", "$1 $2");
        List<String> out = new ArrayList<>();
        for (String t : cleaned.split("[\\s\\p{Punct}，。！？、；：（）【】《》「」]+")) {
            if (t.length() >= 2 && !out.contains(t)) out.add(t);
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
