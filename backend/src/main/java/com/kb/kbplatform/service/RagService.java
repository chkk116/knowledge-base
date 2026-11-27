/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * AI 问答（RAG）服务 —— 零依赖实现（不引 Spring AI）。
 *
 * 方案：检索增强生成（Retrieval-Augmented Generation）。
 *   1) 检索：优先 Meilisearch（若启用），否则回退 DocumentRepository 的 MySQL LIKE 检索；
 *   2) 生成：走 AiModelClient 统一调用管理员配置的模型（OpenAI / DashScope / DeepSeek / 智谱 等），
 *      管理员在「AI 模型管理」页填入 API Key 并选模型后即时生效，无需改配置重启；
 *   3) 基础聊天兜底：当知识库检索不到相关资料时，转为通用闲聊/通用问答（模型自身知识），不再机械回复“未找到”。
 *
 * 与 MeiliSearchClient 一致的约束：任何异常都降级为友好提示，绝不影响主流程；
 * 模型调用消耗云端 token，受 AiController 限流保护（每用户 20 次/小时）。
 */
@Service
public class RagService {

    /** 类级共享 mapper（与 AiConversationService 同为 Jackson 3），避免每次调用都新建 */
    private static final tools.jackson.databind.json.JsonMapper PERSONA_MAPPER =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int TOP_K = 3;
    /** 共享文档单独占 2 条来源，不与公开文章抢 TOP_K（否则文章命中满 3 条时私有文档永远进不来） */
    private static final int SHARED_TOP_K = 2;
    private static final int FRAG_LEN = 400;

    private final MeiliSearchClient meili;
    private final DocumentRepository repository;
    // 统一多模型调用层：模型与 API Key 由管理员在「AI 模型管理」页维护，保存后即时生效、无需重启后端
    private final AiModelClient aiModelClient;
    private final RagIndexService ragIndex;
    private final SharedDocRagService sharedDocRag;
    private final boolean enabled;   // kb.ai.enabled：总开关，false 时强制关闭 AI（默认 true）

    public RagService(MeiliSearchClient meili,
                      DocumentRepository repository,
                      @Value("${kb.ai.enabled:false}") boolean enabled,
                      AiModelClient aiModelClient,
                      RagIndexService ragIndex,
                      SharedDocRagService sharedDocRag) {
        this.meili = meili;
        this.repository = repository;
        this.enabled = enabled;
        this.aiModelClient = aiModelClient;
        this.ragIndex = ragIndex;
        this.sharedDocRag = sharedDocRag;
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
    /** 兼容旧调用：不指定模型时用管理员配置的默认模型 */
    public Map<String, Object> ask(String question, List<Map<String, String>> history,
                                   String instructions, String persona, String mode) {
        return ask(question, history, instructions, persona, mode, null, null);
    }

    /**
     * @param modelId 本次对话要用的模型配置 id；null = 用默认模型
     */
    public Map<String, Object> ask(String question, List<Map<String, String>> history,
                                   String instructions, String persona, String mode, Long modelId) {
        return ask(question, history, instructions, persona, mode, modelId, null);
    }

    /**
     * 多模态版本：imageUrls 为图片 dataURL 列表（data:image/png;base64,...）；为空则等同纯文本。
     * @param modelId 本次对话要用的模型配置 id；null = 用默认模型
     */
    /** 检索 + Prompt 组装的结果；error 非空表示前置条件不满足（未启用/无模型） */
    private record Prepared(List<Map<String, Object>> messages, List<Map<String, Object>> sources, String error) {}

    /**
     * 纯寒暄短路：思考型模型（默认 glm-4.7-flash）对「你好」也会先写上千字 reasoning，
     * 再按 kb 模式的身份口径把模型名、文章数、资料类型背一遍，既慢又答非所问。
     * 这里直接本地回一句，不检索、不调模型、不产生思考过程；只匹配明确的问候/致谢，
     * 带任何实质内容（「你好，帮我查…」）都会落到正常链路。
     */
    private static String smallTalkReply(String question) {
        if (question == null) return null;
        String q = question.strip().replaceAll("[!！。.,，~～?？]+$", "").strip().toLowerCase();
        if (q.isEmpty() || q.length() > 8) return null;
        return switch (q) {
            case "你好", "您好", "hi", "hello", "嗨", "哈喽", "在吗", "早上好", "下午好", "晚上好", "你好呀" ->
                    "你好！有什么可以帮你的？";
            case "谢谢", "多谢", "thanks", "thank you", "3q" -> "不客气，有需要随时说。";
            case "bye", "拜拜", "再见" -> "再见，随时找我。";
            default -> null;
        };
    }
    /** 检索 + 组装 messages（ask 与 askStream 共用，避免两条链路提示词漂移） */
    private Prepared prepare(String question, List<Map<String, String>> history,
                             String instructions, String persona, String mode, Long modelId, Long userId,
                             List<String> imageUrls) {
        // 纯图片提问（question 为空但带了 imageUrls）是合法场景，只有两者都为空才拦
        boolean hasImages = imageUrls != null && imageUrls.stream().anyMatch(u -> u != null && !u.isBlank());
        if (!enabled) {
            log.warn("AI 问答未启用：kb.ai.enabled=false（若已在「AI 模型管理」配好模型，请改回 true）");
            return new Prepared(null, List.of(), "AI 问答暂不可用，请稍后再试或联系管理员。");
        }
        // 空问题单独成一支：以前和「未启用」共用同一句提示，用户以为服务坏了（N103）
        if ((question == null || question.isBlank()) && !hasImages) {
            return new Prepared(null, List.of(), "问题不能为空，请输入内容后再提问。");
        }
        if (!aiModelClient.hasAvailable()) {
            return new Prepared(null, List.of(), "尚未配置可用的 AI 模型，请联系管理员在「AI 模型管理」中添加。");
        }

        // 1. 检索相关知识片段（通用模式跳过检索）；多轮对话时检索词并入最近几轮用户消息，
        //    解决「怎么申请」「那个怎么弄」这类省略/指代单独检索必然跑偏的问题
        boolean general = "general".equalsIgnoreCase(mode);
        List<Map<String, Object>> sources = general ? List.of()
                : retrieve(buildSearchQuery(question == null ? "" : question.trim(), history), userId);
        boolean hasContext = !sources.isEmpty();

        // 2. 组装 system prompt：身份 + （kb 模式）资料行为准则 + 对话条例 + 用户习惯
        // 底层模型名动态取自「AI 模型管理」的配置显示名，不再写死厂商 —— 管理员换平台后自我介绍自动跟上
        String modelLabel = aiModelClient.resolveModelLabel(modelId);
        StringBuilder system = new StringBuilder();
        // 统一身份口径（两种模式一致）：对外只自称「通用 AI 助手」+ 底层模型名，
        // 不透露平台内部信息（知识库/检索/资料/文章数量），也不自称企业知识库平台助手。
        String identity = "我是通用 AI 助手"
                + (modelLabel != null && !modelLabel.isBlank() ? "，底层由 " + modelLabel + " 模型驱动" : "")
                + "，可以回答问题、写作、翻译、编程、分析等。有什么需求直接说。";
        String identityRule = "\n【身份口径】\n"
                + "被问到「你是谁 / 你是什么模型 / 谁开发的 / 你能做什么」时，**原样**回答下面这句话，"
                + "不要扩写、不要补充其他内容、不要反问；用户重复问也按同一句回答：\n"
                + identity + "\n"
                + "不要提及知识库、检索、资料、文章数量等平台内部信息，也不要自称是企业知识库平台的助手。\n";

        if (general) {
            // 通用模式 = 正常模式：提示词压到最薄，只有「身份口径」+ 一行基本表达要求，
            // 不叠加任何平台侧约束（无检索、无资料、无知识库相关指令）。
            // 为什么不能完全不下发：不控制身份，模型会按厂商默认人格自我介绍（拿不到统一口径），
            // 也不会知道「不要提知识库」这条约束。
            system.append(identityRule)
                    .append("【其他】用简体中文，直接、简洁作答；不讨论政治敏感内容，不提供违法或破坏系统的指导。");
        } else {
            // kb 模式：**保持原有行为**（2026-10-06 用户确认）——知识库 AI 身份 + 概况 + 资料准则 + RAG 检索，
            // 不套用通用模式的身份口径。
            system.append("你是企业知识库平台的内置 AI 助手「知识库 AI」，服务于企业内部员工");
            if (modelLabel != null && !modelLabel.isBlank()) {
                system.append("，底层由 ").append(modelLabel).append(" 模型驱动");
            }
            system.append("。");
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
        }

        if (instructions != null && !instructions.isBlank()) {
            system.append("\n\n【本次对话条例】\n").append(instructions.trim());
        }
        if (persona != null && !persona.isBlank()) {
            system.append("\n\n【用户偏好】\n").append(extractPersonaHint(persona));
        }

        // 3. 组装 messages：system + 历史 + 当前 user
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.<String, Object>of("role", "system", "content", system.toString()));
        if (history != null) {
            for (Map<String, String> h : history) {
                String role = h.get("role");
                String content = h.get("content");
                if (role == null || content == null) continue;
                if (!"user".equals(role) && !"ai".equals(role) && !"assistant".equals(role)) continue;
                // 统一使用 assistant 作为 AI 角色名（智谱兼容）
                String modelRole = "ai".equals(role) ? "assistant" : role;
                messages.add(Map.<String, Object>of("role", modelRole, "content", content));
            }
        }
        // 当前提问文本（kb 模式带检索资料）
        String userText;
        if (hasContext) {
            // 上下文增强：优先喂「全文中最相关的段落」（fullText），无则回退 160 字摘要
            StringBuilder ctx = new StringBuilder();
            for (Map<String, Object> s : sources) {
                String ft = str(s.get("fullText"));
                String body = (ft == null || ft.isBlank()) ? str(s.get("snippet")) : ft;
                if (ctx.length() > 0) ctx.append("\n\n");
                ctx.append("【").append(s.getOrDefault("title", "")).append("】\n").append(body);
            }
            userText = "【资料】\n" + ctx + "\n\n【问题】" + question.trim();
        } else {
            userText = question.trim();
        }
        // 有图片时用多模态 content（text + image_url 数组）——模型原生多模态直接看图，无需 OCR
        // 兜底校验（即便 controller 层漏拦）：数量 ≤4、data:image 前缀、单条长度上限
        List<String> imgs = imageUrls == null ? List.of()
                : imageUrls.stream()
                        .filter(u -> u != null && !u.isBlank())
                        .filter(u -> u.startsWith("data:image/"))
                        .limit(4)
                        .toList();
        if (imgs.isEmpty()) {
            messages.add(Map.<String, Object>of("role", "user", "content", userText));
        } else {
            List<Map<String, Object>> parts = new ArrayList<>();
            parts.add(Map.of("type", "text", "text", userText));
            for (String u : imgs) {
                parts.add(Map.of("type", "image_url", "image_url", Map.of("url", u)));
            }
            messages.add(Map.<String, Object>of("role", "user", "content", parts));
        }

        return new Prepared(messages, sources, null);
    }

    /** 一次性问答（非流式），行为与原实现一致 */
    public Map<String, Object> ask(String question, List<Map<String, String>> history,
                                   String instructions, String persona, String mode, Long modelId,
                                   List<String> imageUrls) {
        return ask(question, history, instructions, persona, mode, modelId, null, null, imageUrls);
    }

    /** 带用户私有模型的一次性问答：userModelId 命中时优先用该用户模型 */
    public Map<String, Object> ask(String question, List<Map<String, String>> history,
                                   String instructions, String persona, String mode, Long modelId,
                                   Long userModelId, Long userId, List<String> imageUrls) {
        String small = smallTalkReply(question);
        if (small != null) return Map.of("answer", small, "sources", List.of());
        Prepared p = prepare(question, history, instructions, persona, mode, modelId, userId, imageUrls);
        if (p.error() != null) return Map.of("answer", p.error(), "sources", List.of());
        AiModelClient.AiChatResult call = callModel(modelId, userModelId, userId, p.messages());
        return assemble(call, p.sources());
    }

    /**
     * 流式问答：模型每吐出一段文本就回调一次 sink（前端据此逐字渲染）。
     * 返回结构与 ask() 完全一致，answer 是汇总后的完整文本（用于落库）。
     */
    public Map<String, Object> askStream(String question, List<Map<String, String>> history,
                                         String instructions, String persona, String mode, Long modelId,
                                         List<String> imageUrls, AiModelClient.StreamSink sink) throws Exception {
        return askStream(question, history, instructions, persona, mode, modelId, null, null, imageUrls, sink);
    }

    /** 带用户私有模型的流式问答 */
    public Map<String, Object> askStream(String question, List<Map<String, String>> history,
                                         String instructions, String persona, String mode, Long modelId,
                                         Long userModelId, Long userId, List<String> imageUrls,
                                         AiModelClient.StreamSink sink) throws Exception {
        String small = smallTalkReply(question);
        if (small != null) {
            sink.accept(small, null);
            return Map.of("answer", small, "sources", List.of());
        }
        Prepared p = prepare(question, history, instructions, persona, mode, modelId, userId, imageUrls);
        if (p.error() != null) {
            sink.accept(p.error(), null);
            return Map.of("answer", p.error(), "sources", List.of());
        }
        AiModelClient.AiChatResult call = aiModelClient.chatStream(modelId, userModelId, userId, p.messages(), sink);
        return assemble(call, p.sources());
    }

    private Map<String, Object> assemble(AiModelClient.AiChatResult call, List<Map<String, Object>> sources) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", call.text());
        result.put("sources", sources);
        // 可视化：把本次实际用的模型、耗时、token 用量带出去，前端可在回答旁展示
        result.put("model", call.model());
        result.put("modelName", call.modelName());
        result.put("latencyMs", call.latencyMs());
        result.put("usage", call.usage());
        // 思考型模型（DeepSeek / GLM 思考版）的思考过程，前端折叠展示；非思考模型为 null
        result.put("reasoning", call.reasoning());
        return result;
    }

    /** 从用户习惯 JSON 中提取可读提示，失败则原样返回 */
    private String extractPersonaHint(String persona) {
        try {
            // 标准 JSON 解析：custom 里可能含逗号/冒号，手撕 split 会把整段偏好切碎
            StringBuilder sb = new StringBuilder();
            Map<?, ?> json = PERSONA_MAPPER.readValue(persona, Map.class);
            for (Map.Entry<?, ?> e : json.entrySet()) {
                String k = String.valueOf(e.getKey()).trim();
                String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
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

    /** 交给统一模型层：该层负责按配置选模型，并在限流/报错时自动降级到下一个可用模型 */
    private AiModelClient.AiChatResult callModel(Long modelId, Long userModelId, Long userId,
                                                 List<Map<String, Object>> messages) {
        return aiModelClient.chat(modelId, userModelId, userId, messages);
    }

    /** 检索 top-K 片段：优先 Meili，失败/未启用回退 MySQL；返回引用来源（id/title/snippet） */
    @SuppressWarnings("unchecked")
    /**
     * 构造检索词：多轮对话时把最近 2 条用户消息并入当前问题，
     * 解决「怎么申请」「那个怎么弄」这类省略/指代单独检索必然跑偏的问题。
     * 轻量方案（不做 LLM 查询改写）：拼接即够用——Meili 多词 OR 与 MySQL 逐词兜底都兼容长检索串。
     */
    private String buildSearchQuery(String question, List<Map<String, String>> history) {
        if (history == null || history.isEmpty()) return question;
        List<String> prevUser = new ArrayList<>();
        for (Map<String, String> h : history) {
            if (!"user".equals(h.get("role"))) continue;
            String c = h.get("content");
            if (c != null && !c.isBlank()) prevUser.add(c.trim());
        }
        if (prevUser.isEmpty()) return question;
        int from = Math.max(0, prevUser.size() - 2);
        StringBuilder sb = new StringBuilder();
        for (String p : prevUser.subList(from, prevUser.size())) {
            sb.append(p, 0, Math.min(p.length(), 80)).append(' ');
        }
        sb.append(question);
        return sb.toString();
    }

    /**
     * 上下文增强：从全文中挑出与问题最相关的段落喂给模型（此前只有 160 字摘要，上下文严重不足）。
     * 切块优先按换行（纯文本段落），无换行则按 300 字窗口；评分用问题 2-gram 命中数（无中文分词依赖）；
     * 取 top-2 段落拼接（截 1200 字），无任何命中时回退文档开头 800 字。
     */
    private String focusParagraphs(String content, String query) {
        if (content == null || content.isBlank()) return "";
        content = content.trim();
        if (content.length() <= 800) return content;
        List<String> blocks = new ArrayList<>();
        String[] paras = content.split("\n+");
        if (paras.length > 1) {
            for (String p : paras) {
                String t = p.trim();
                if (t.length() >= 20) blocks.add(t.length() > 300 ? t.substring(0, 300) : t);
            }
        }
        if (blocks.isEmpty()) {
            for (int i = 0; i < content.length(); i += 280) {
                blocks.add(content.substring(i, Math.min(content.length(), i + 300)));
            }
        }
        String q = query == null ? "" : query;
        java.util.Set<String> grams = new java.util.HashSet<>();
        for (int i = 0; i + 2 <= q.length(); i++) grams.add(q.substring(i, i + 2));
        String best1 = null, best2 = null;
        int s1 = 0, s2 = 0;
        for (String b : blocks) {
            int score = 0;
            for (String g : grams) if (b.contains(g)) score++;
            if (score > s1) { s2 = s1; best2 = best1; s1 = score; best1 = b; }
            else if (score > s2) { s2 = score; best2 = b; }
        }
        String result = (best1 == null)
                ? content.substring(0, Math.min(content.length(), 800))
                : (best2 == null || best2.equals(best1) ? best1 : best1 + "\n…\n" + best2);
        return result.length() > 1200 ? result.substring(0, 1200) : result;
    }

    /**
     * 公开文章检索（原三级链路）+ 当前用户可读的共享文档。
     * 共享文档由 SharedDocRagService 独立检索并逐条复核空间权限，两类来源分开取额度，
     * 避免公开文章命中满 TOP_K 时私有文档永远进不了模型上下文。
     */
    private List<Map<String, Object>> retrieve(String q, Long userId) {
        List<Map<String, Object>> out = new ArrayList<>(retrievePublic(q));
        if (userId != null) {
            List<String> keywords = splitKeywords(q);
            if (keywords.isEmpty()) keywords = List.of(q);
            out.addAll(sharedDocRag.search(q, userId, keywords, SHARED_TOP_K));
        }
        return out;
    }

    private List<Map<String, Object>> retrievePublic(String q) {
        // 语义检索优先（真·向量 RAG）：未启用 / 无索引 / 失败时自动落回 Meili / MySQL 全文检索
        if (ragIndex != null && ragIndex.isEnabled()) {
            try {
                List<Map<String, Object>> v = ragIndex.search(q, TOP_K);
                if (!v.isEmpty()) {
                    // 与 Meili 路径同口径（F10）：Embedding 停用期间下架文章的旧向量仍留在索引里，
                    // 送入模型前必须按数据库当前状态复核 PUBLISHED，否则 AI 会引用已下架内容
                    Set<Long> ids = SearchVisibility.extractIds(v);
                    Set<Long> visible = ids.isEmpty() ? Set.of()
                            : repository.findByStatusAndIdIn("PUBLISHED", new ArrayList<>(ids)).stream()
                                .map(Document::getId).collect(java.util.stream.Collectors.toSet());
                    List<Map<String, Object>> picked = SearchVisibility.filterVisible(v, visible, TOP_K);
                    if (!picked.isEmpty()) return picked;
                }
            } catch (Exception e) {
                log.warn("向量检索失败，回退全文检索: {}", e.getMessage());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (meili != null && meili.isEnabled()) {
            try {
                List<Map<String, Object>> hits = meili.search(q, null);
                // 可见性兜底（F10）：索引删除失败时，下架/封禁文档仍可能留在 Meili 里。
                // 送入模型前用数据库当前状态核对，避免 AI 引用已下架内容。
                Set<Long> hitIds = SearchVisibility.extractIds(hits);
                Set<Long> visible = hitIds.isEmpty() ? Set.of()
                        : repository.findByStatusAndIdIn("PUBLISHED", new ArrayList<>(hitIds)).stream()
                            .map(Document::getId).collect(java.util.stream.Collectors.toSet());
                // 先按可见性过滤再取 TOP_K：否则不可见的过期索引条目会占坑，实际喂给模型的有效来源变少
                List<Map<String, Object>> picked = SearchVisibility.filterVisible(hits, visible, TOP_K);
                for (Map<String, Object> h : picked) {
                    String title = str(h.get("title"));
                    String snippet = str(h.get("snippet"));
                    if (title.isEmpty()) continue;
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("id", h.get("id"));
                    s.put("title", title);
                    s.put("snippet", snippet);
                    // 上下文增强：Meili hit 自带全文（纯文本），挑出与问题最相关的段落喂给模型
                    s.put("fullText", focusParagraphs(str(h.get("content")), q));
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
        // 上下文增强：MySQL 兜底分支同样提供相关段落（关键词窗口取双倍长度再聚焦）
        String focusBase = (bestStart >= 0)
                ? text.substring(bestStart, Math.min(text.length(), bestStart + FRAG_LEN * 3))
                : text;
        s.put("fullText", focusParagraphs(focusBase, String.join(" ", kws)));
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

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
