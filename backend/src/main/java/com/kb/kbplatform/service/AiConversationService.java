/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AiConversation;
import com.kb.kbplatform.domain.AiMessage;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.AiConversationRepository;
import com.kb.kbplatform.repository.AiMessageRepository;
import com.kb.kbplatform.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class AiConversationService {

    private static final int MAX_CONVERSATIONS = 100;   // 每个用户最多保留 100 条会话
    private static final int MAX_HISTORY = 20;          // 每次上下文携带最近 20 轮

    private final AiConversationRepository convRepo;
    private final AiMessageRepository msgRepo;
    private final UserRepository userRepo;
    private final RagService ragService;

    public AiConversationService(AiConversationRepository convRepo,
                                 AiMessageRepository msgRepo,
                                 UserRepository userRepo,
                                 RagService ragService) {
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.userRepo = userRepo;
        this.ragService = ragService;
    }

    public List<AiConversation> list(Long userId) {
        return convRepo.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiConversation create(Long userId, String title) {
        long count = convRepo.countByUserId(userId);
        if (count >= MAX_CONVERSATIONS) {
            // 删除最旧的一条
            List<AiConversation> all = convRepo.findByUserIdOrderByUpdatedAtDesc(userId);
            if (!all.isEmpty()) {
                AiConversation oldest = all.get(all.size() - 1);
                delete(userId, oldest.getId());
            }
        }
        AiConversation c = new AiConversation();
        c.setUserId(userId);
        c.setTitle(title == null || title.isBlank() ? "新对话" : title.trim());
        return convRepo.save(c);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiConversation update(Long userId, Long id, String title, String instructions) {
        AiConversation c = convRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (!c.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该会话");
        }
        if (title != null) c.setTitle(title.trim());
        if (instructions != null) c.setInstructions(instructions.trim());
        return convRepo.save(c);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long id) {
        AiConversation c = convRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (!c.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该会话");
        }
        msgRepo.deleteByConversationId(id);
        convRepo.delete(c);
    }

    public List<Map<String, Object>> messages(Long userId, Long id) {
        AiConversation c = convRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        if (!c.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该会话");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiMessage m : msgRepo.findByConversationIdOrderByCreatedAtAsc(id)) {
            Map<String, Object> mm = new LinkedHashMap<>();
            mm.put("id", m.getId());
            mm.put("role", m.getRole());
            mm.put("content", m.getContent());
            // 逐条回传本条消息的模式：同一会话里可能混用过 kb/general，
            // 前端若按「会话当前选中模式」统一渲染，历史标签会全错。null（旧数据）按 kb 兜底。
            mm.put("mode", m.getMode() == null ? "kb" : m.getMode());
            Object src = parseSources(m.getSources());
            if (src != null) mm.put("sources", src);
            mm.put("createdAt", m.getCreatedAt());
            out.add(mm);
        }
        return out;
    }

    /** 引用来源 JSON → List；脏数据/空值返回 null（前端按无引用处理） */
    private Object parseSources(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return tools.jackson.databind.json.JsonMapper.builder().build().readValue(json, List.class);
        } catch (Exception e) {
            return null;
        }
    }

    // 注意：此处不加 @Transactional —— ragService.ask() 是同步 HTTP 调用 LLM，
    // 若包在事务里会一直占用 DB 连接直到模型返回（数秒~数十秒）。
    // 下面各 msgRepo/convRepo.save 走 Spring Data JPA 自带的独立短事务，已各自原子。
    public Map<String, Object> ask(Long userId, Long conversationId, String question, String mode, Long modelId) {
        return ask(userId, conversationId, question, mode, modelId, null);
    }

    /** 多模态版本：images 为图片 dataURL 列表（可空；模型原生多模态直接看图，无需 OCR） */
    public Map<String, Object> ask(Long userId, Long conversationId, String question, String mode, Long modelId,
                                   List<String> images) {
        AiConversation c;
        if (conversationId == null) {
            c = create(userId, autoTitle(question));
        } else {
            c = convRepo.findById(conversationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
            if (!c.getUserId().equals(userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该会话");
            }
        }

        // 模式：kb（默认，走知识库检索）/ general（纯通用问答，不检索）
        boolean general = "general".equalsIgnoreCase(mode);

        // 保存用户消息
        AiMessage userMsg = new AiMessage();
        userMsg.setConversationId(c.getId());
        userMsg.setRole("user");
        userMsg.setContent(question);
        userMsg.setMode(mode);
        msgRepo.save(userMsg);

        // 构建历史上下文（不含当前问题，最多最近 MAX_HISTORY 轮）
        List<AiMessage> all = msgRepo.findByConversationIdOrderByCreatedAtAsc(c.getId());
        List<Map<String, String>> history = new ArrayList<>();
        int start = Math.max(0, all.size() - 1 - MAX_HISTORY);
        for (int i = start; i < all.size() - 1; i++) {
            AiMessage m = all.get(i);
            // 切到「通用」时，只带通用模式产生的历史。
            // 不隔离的话，模型会看到上一轮「知识库中未找到…」这类答案，一边被要求别提知识库、
            // 一边又在回答里顺着知识库语境走，思考过程会明显纠结（实测 confirmed）。
            // mode 为 null 的是本次改动前的历史数据，按 kb 处理。
            if (general && !"general".equals(m.getMode())) continue;
            history.add(Map.of("role", m.getRole(), "content", m.getContent()));
        }

        // 用户习惯
        String persona = userRepo.findById(userId)
                .map(User::getAiPersona)
                .orElse("");

        // 调用 RAG（mode 控制知识库/通用；返回 answer + sources 引用来源）
        Map<String, Object> rag = ragService.ask(question, history,
                c.getInstructions() == null ? "" : c.getInstructions(),
                persona == null ? "" : persona,
                mode == null ? "kb" : mode,
                modelId,
                images);
        String answer = rag.get("answer") == null ? "" : rag.get("answer").toString();
        Object sources = rag.get("sources");
        // 调用可视化字段透传：模型名 / 耗时 / token 用量 / 思考过程，前端在回答旁展示
        Object modelName = rag.get("modelName");
        Object latencyMs = rag.get("latencyMs");
        Object usage = rag.get("usage");
        Object reasoning = rag.get("reasoning");

        // 保存 AI 消息（引用来源一并落库为 JSON 快照，重开页面后仍能显示）
        AiMessage aiMsg = new AiMessage();
        aiMsg.setConversationId(c.getId());
        aiMsg.setRole("ai");
        aiMsg.setContent(answer);
        aiMsg.setMode(mode);
        if (sources instanceof List && !((List<?>) sources).isEmpty()) {
            try {
                aiMsg.setSources(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(sources));
            } catch (Exception ignore) { /* 序列化失败不影响回答 */ }
        }
        msgRepo.save(aiMsg);

        // 更新会话时间
        c = convRepo.save(c);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationId", c.getId());
        result.put("title", c.getTitle());
        result.put("answer", answer);
        if (sources instanceof List) result.put("sources", sources);
        if (modelName != null) result.put("modelName", modelName);
        if (latencyMs != null) result.put("latencyMs", latencyMs);
        if (usage != null) result.put("usage", usage);
        // 思考过程（思考型模型才有）：仅本次响应返回给前端做折叠展示，不落库 ——
        // 它体积可达数万字，且重开页面时没有「本次调用」的语义，存下来只会白白撑大消息表
        if (reasoning != null) result.put("reasoning", reasoning);
        return result;
    }

    /** 获取/保存用户 AI 偏好 */
    public Map<String, Object> getPreference(Long userId) {
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        String persona = u.getAiPersona();
        if (persona == null || persona.isBlank()) {
            return Map.of("tone", "", "length", "", "focus", "", "custom", "");
        }
        return parsePersona(persona);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> savePreference(Long userId, Map<String, Object> body) {
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tone", str(body.get("tone")));
        m.put("length", str(body.get("length")));
        m.put("focus", str(body.get("focus")));
        m.put("custom", str(body.get("custom")));
        u.setAiPersona(toJson(m));
        userRepo.save(u);
        return m;
    }

    private String autoTitle(String question) {
        if (question == null) return "新对话";
        String q = question.trim().replaceAll("\\s+", " ");
        if (q.isEmpty()) return "新对话";
        return q.length() <= 20 ? q : q.substring(0, 20) + "…";
    }

    private Map<String, Object> parsePersona(String persona) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tone", "");
        m.put("length", "");
        m.put("focus", "");
        m.put("custom", "");
        try {
            String[] parts = persona.replaceAll("[{}\\[\\]\"]", "").split(",");
            for (String p : parts) {
                String[] kv = p.split(":", 2);
                if (kv.length != 2) continue;
                String k = kv[0].trim();
                String v = kv[1].trim();
                if (m.containsKey(k)) m.put(k, v);
            }
        } catch (Exception ignored) {}
        return m;
    }

    private String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(e.getKey()).append("\":\"").append(escapeJson(str(e.getValue()))).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
