/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import tools.jackson.databind.json.JsonMapper;
import com.kb.kbplatform.domain.SharedDocument;
import com.kb.kbplatform.util.YjsTextExtractor;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 出题：给定主题（可附资料），让模型产出结构化题目，**不落库**。
 *
 * 为什么不直接入库：AI 生成的内容必须过人的眼睛。这里只返回候选，
 * 前端预览勾选后再走 {@code POST /api/admin/exams/questions/batch} 保存。
 *
 * 三种资料源（sourceType）：
 *   - DOC：知识库空间文档（可信、有据可查，默认）
 *   - WEB：用户指定网页，复用 {@link WebClipService} 的安全抓取取正文
 *   - AI ：不附资料，凭模型通用知识出题（内容未经校验，前端需二次确认）
 *
 * ⚠️ 没有做「自动全网搜索」：实测服务器所在网络下 DuckDuckGo/Wikipedia 不可达，
 * 唯一可用的 Bing 对中文长查询分词极差（搜「SM-2 间隔重复算法」返回娱乐新闻），
 * 把这种结果喂给模型只会产出跑偏的题目。与其做一个看起来能用、实际会出错题的
 * 功能，不如让用户直接指定资料链接。
 */
@Service
public class AiQuestionService {

    private static final Logger log = LoggerFactory.getLogger(AiQuestionService.class);

    /** 送进 prompt 的资料总字数上限（再长模型也读不细，且白烧 token） */
    private static final int MAX_MATERIAL_CHARS = 24_000;
    /** 单篇资料上限 */
    private static final int MAX_PER_SOURCE_CHARS = 8_000;
    private static final int MAX_DOCS = 8;
    private static final int MAX_URLS = 5;
    private static final int MAX_COUNT = 20;
    /** 同一用户两次出题的间隔（AI 调用本身就要几十秒，这里只挡连点） */
    private static final long MIN_INTERVAL_MS = 20_000;

    private static final Set<String> TYPES = Set.of("SINGLE", "MULTI", "JUDGE", "FILL", "ESSAY");
    private static final Set<String> OBJECTIVE = Set.of("SINGLE", "MULTI", "JUDGE");

    private static final String REQUIREMENTS = """

            【出题要求】
            1. 紧扣【主题】。有参考资料时以资料为准，资料没覆盖的细节不要编造；
               无参考资料时按你掌握的通用知识出题，但不要杜撰具体数字、条款号、日期。
            2. 单选题 4 个选项且只有 1 个正确；多选题 4~5 个选项且至少 2 个正确；
               判断题 2 个选项（A=正确、B=错误）且只有 1 个正确；填空题、问答题 options 为空数组。
            3. 每题都要给 analysis（解析），说明依据或判断理由，不要只说「见资料第 N 段」。
            4. 题干与选项必须自包含，不得出现「根据资料」「上文提到」「如图」这类指代。
            5. 题目之间不要重复考点，覆盖主题的不同侧面。
            6. 只输出 JSON 数组，不要 markdown 代码块、不要任何前后解释。每个元素的字段：
               {"type":"SINGLE|MULTI|JUDGE|FILL|ESSAY","content":"题干","analysis":"解析",
                "difficulty":1到5的整数,"options":[{"label":"A","content":"选项内容","isCorrect":true}]}
            """;

    private final AiModelClient ai;
    private final WebClipService clip;
    private final DocShareService docShare;

    /** userId → 上次出题时间 */
    private final Map<Long, Long> lastCall = new ConcurrentHashMap<>();
    /** Jackson 3（Spring Boot 4）：坐标是 tools.jackson.*，线程安全可静态复用 */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public AiQuestionService(AiModelClient ai, WebClipService clip, DocShareService docShare) {
        this.ai = ai;
        this.clip = clip;
        this.docShare = docShare;
    }

    // ===================== 入口 =====================

    public Map<String, Object> generate(GenReq req, Long userId, boolean sysAdmin) {
        String topic = req.topic == null ? "" : req.topic.trim();
        if (topic.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写出题主题");
        if (topic.length() > 200) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "主题请控制在 200 字以内");

        int count = req.count <= 0 ? 5 : Math.min(req.count, MAX_COUNT);
        String sourceType = normalizeSource(req.sourceType);

        // 纯参数校验全部排在限流之前：填错表单不该消耗配额，也不该让用户等 20 秒
        if ("DOC".equals(sourceType) && req.shareId == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择知识库空间");
        List<String> urls = cleanUrls(req.urls);
        if ("WEB".equals(sourceType) && urls.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少填写一个参考网页链接");

        throttle(userId);

        List<Map<String, Object>> materials = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        StringBuilder ctx = new StringBuilder();

        switch (sourceType) {
            case "DOC" -> collectDocs(req, userId, sysAdmin, ctx, materials, warnings);
            case "WEB" -> collectWeb(urls, ctx, materials, warnings);
            default -> { /* AI：不附资料 */ }
        }

        String prompt = buildPrompt(topic, count, req, sourceType, ctx);
        AiModelClient.AiChatResult r = ai.chat(null, List.of(
                Map.of("role", "system", "content", "你是资深企业培训出题专家。严格遵守格式要求，只输出 JSON 数组。"),
                Map.of("role", "user", "content", prompt)
        ));
        if (!r.ok())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    r.text() + "（若持续失败，请到「AI 模型管理」检查余额或换用其他模型）");

        List<Map<String, Object>> questions = parseQuestions(r.text(), warnings);
        if (questions.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "AI 这次没有产出可用的题目，换个主题或补充资料后重试");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceType", sourceType);
        out.put("materials", materials);
        out.put("warnings", warnings);
        out.put("questions", questions);
        out.put("model", r.modelName());
        return out;
    }

    // ===================== 资料收集 =====================

    private void collectDocs(GenReq req, Long userId, boolean sysAdmin,
                             StringBuilder ctx, List<Map<String, Object>> materials, List<String> warnings) {
        if (req.shareId == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择知识库空间");

        // 显式传入的 docIds 也要受 MAX_DOCS 约束（审查 r9 High）：否则可一次拉几十篇 × 8k 字
        List<Long> ids = req.docIds == null ? List.of()
                : req.docIds.stream().filter(java.util.Objects::nonNull).distinct().limit(MAX_DOCS).toList();
        if (req.docIds != null && req.docIds.size() > MAX_DOCS)
            warnings.add("指定文档超出上限，已只取前 " + MAX_DOCS + " 篇");
        if (ids.isEmpty()) {
            // 没指定就用该空间最近的若干篇，省得用户逐篇勾
            ids = docShare.listDocs(req.shareId, userId, sysAdmin).stream()
                    .filter(d -> !d.isInbox())
                    .limit(MAX_DOCS)
                    .map(SharedDocument::getId)
                    .toList();
            if (ids.isEmpty()) {
                warnings.add("该空间没有可用文档，已改为仅按主题出题");
                return;
            }
        }

        for (Long docId : ids) {
            if (ctx.length() >= MAX_MATERIAL_CHARS) break;
            try {
                SharedDocument d = docShare.getDoc(req.shareId, docId, userId, sysAdmin);
                String text = plain(d.getContent());
                // 协同文档的正文只在 yjs_state 里（content 要「保存快照」才有），
                // 直接从二进制里抽文本，否则这一档永远是空的
                if (text.isEmpty()) text = YjsTextExtractor.extract(d.getYjsState());
                if (text.isEmpty()) {
                    warnings.add("文档《" + safeTitle(d) + "》没有正文，已跳过");
                    continue;
                }
                appendMaterial(ctx, "文档《" + safeTitle(d) + "》", text);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", safeTitle(d));
                m.put("url", null);
                m.put("chars", Math.min(text.length(), MAX_PER_SOURCE_CHARS));
                materials.add(m);
            } catch (ResponseStatusException e) {
                warnings.add("文档 " + docId + " 读取失败：" + e.getReason());
            }
        }
    }

    private void collectWeb(List<String> urls, StringBuilder ctx,
                            List<Map<String, Object>> materials, List<String> warnings) {
        for (String url : urls) {
            if (ctx.length() >= MAX_MATERIAL_CHARS) break;
            try {
                Map<String, Object> page = clip.fetchForAi(url);
                String text = plain(str(page.get("content")));
                if (text.isEmpty()) {
                    warnings.add(url + " 没抓到正文，已跳过");
                    continue;
                }
                String title = str(page.get("title"));
                if (title.isEmpty()) title = url;
                appendMaterial(ctx, "网页《" + title + "》（" + url + "）", text);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("title", title);
                m.put("url", url);
                m.put("chars", Math.min(text.length(), MAX_PER_SOURCE_CHARS));
                materials.add(m);
            } catch (ResponseStatusException e) {
                warnings.add(url + " 抓取失败：" + e.getReason());
            } catch (Exception e) {
                warnings.add(url + " 抓取失败：" + e.getMessage());
            }
        }
        if (materials.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参考网页全部抓取失败，请检查链接后重试");
    }

    private void appendMaterial(StringBuilder ctx, String label, String text) {
        String cut = text.length() > MAX_PER_SOURCE_CHARS ? text.substring(0, MAX_PER_SOURCE_CHARS) + "…" : text;
        ctx.append("\n--- ").append(label).append(" ---\n").append(cut).append('\n');
    }

    // ===================== Prompt =====================

    private String buildPrompt(String topic, int count, GenReq req, String sourceType, StringBuilder ctx) {
        StringBuilder p = new StringBuilder();
        p.append("【主题】").append(topic).append('\n');
        p.append("【题量】").append(count).append(" 道\n");

        List<String> types = req.types == null ? List.of()
                : req.types.stream().filter(t -> t != null && TYPES.contains(t.trim().toUpperCase(Locale.ROOT)))
                .map(t -> t.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        p.append("【题型】").append(types.isEmpty() ? "不限（建议以单选为主，搭配多选、判断）" : String.join("、", types)).append('\n');

        p.append("【难度】").append(req.difficulty <= 0 ? "不限" : req.difficulty + "（1 最易，5 最难）").append('\n');
        if (req.category != null && !req.category.isBlank())
            p.append("【分类】").append(req.category.trim()).append('\n');
        if (req.extra != null && !req.extra.isBlank())
            p.append("【额外要求】").append(req.extra.trim()).append('\n');

        if (ctx.length() > 0) {
            p.append("\n【参考资料】\n").append(ctx);
        } else if ("AI".equals(sourceType)) {
            p.append("\n【参考资料】无。请基于你掌握的通用知识出题。\n");
        } else {
            p.append("\n【参考资料】无（未能取到资料）。请基于你掌握的通用知识出题，并在解析里说明依据来源。\n");
        }
        p.append(REQUIREMENTS);
        return p.toString();
    }

    // ===================== 解析与校验 =====================

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseQuestions(String raw, List<String> warnings) {
        List<Map<String, Object>> list;
        try {
            list = JSON.readValue(extractJsonArray(raw), List.class);
        } catch (Exception e) {
            log.warn("AI 出题返回无法解析：{}", raw == null ? "null" : raw.substring(0, Math.min(300, raw.length())));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI 返回的内容不是有效 JSON，请重试");
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> q : list) {
            if (q == null) continue;
            String type = str(q.get("type")).toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) continue;
            String content = str(q.get("content")).trim();
            if (content.isEmpty()) continue;

            List<Map<String, Object>> opts = normalizeOptions(q.get("options"));
            if (OBJECTIVE.contains(type)) {
                // 客观题没有选项 / 没有正确答案 = 交卷必 0 分，直接丢弃
                if (opts.isEmpty()) {
                    warnings.add("丢弃一道无选项的" + typeName(type) + "：" + brief(content));
                    continue;
                }
                long correct = opts.stream().filter(o -> Boolean.TRUE.equals(o.get("isCorrect"))).count();
                if (correct == 0) {
                    warnings.add("丢弃一道没有正确答案的" + typeName(type) + "：" + brief(content));
                    continue;
                }
                if ("MULTI".equals(type) && correct < 2)
                    warnings.add("有一道多选题只有 1 个正确答案，建议人工改成单选：" + brief(content));
            } else {
                opts = List.of();
            }

            int diff = intOf(q.get("difficulty"), 3);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", type);
            item.put("content", content);
            item.put("analysis", str(q.get("analysis")));
            item.put("difficulty", Math.max(1, Math.min(5, diff)));
            item.put("options", opts);
            out.add(item);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> normalizeOptions(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<Map<String, Object>> opts = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String content = str(m.get("content")).trim();
            if (content.isEmpty()) continue;
            Map<String, Object> opt = new LinkedHashMap<>();
            opt.put("label", String.valueOf((char) ('A' + opts.size())));
            opt.put("content", content);
            opt.put("isCorrect", Boolean.TRUE.equals(m.get("isCorrect")));
            opts.add(opt);
        }
        return opts;
    }

    /** 模型常把 JSON 包在 ```json 里，或前后带解释；截取最外层数组 */
    private String extractJsonArray(String raw) {
        String s = raw == null ? "" : raw.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int nl = s.indexOf('\n', fence);
            int end = s.indexOf("```", nl < 0 ? fence + 3 : nl);
            if (nl > 0 && end > nl) s = s.substring(nl + 1, end).trim();
        }
        int start = s.indexOf('[');
        int last = s.lastIndexOf(']');
        if (start < 0 || last <= start) throw new IllegalArgumentException("no json array");
        return s.substring(start, last + 1);
    }

    // ===================== 工具 =====================

    private void throttle(Long userId) {
        long now = System.currentTimeMillis();
        Long prev = lastCall.get(userId);
        if (prev != null && now - prev < MIN_INTERVAL_MS)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "出题请求太频繁，请稍后再试");
        lastCall.put(userId, now);
        if (lastCall.size() > 5000) lastCall.clear();
    }

    private static List<String> cleanUrls(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(u -> u != null && !u.trim().isEmpty())
                .map(String::trim).distinct().limit(MAX_URLS).toList();
    }

    private static String normalizeSource(String s) {        String v = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
        return switch (v) {
            case "WEB" -> "WEB";
            case "AI" -> "AI";
            default -> "DOC";
        };
    }

    private static String plain(String html) {
        if (html == null || html.isEmpty()) return "";
        return Jsoup.parse(html).text().replaceAll("\\s+", " ").trim();
    }

    private static String safeTitle(SharedDocument d) {
        String t = d.getTitle();
        return t == null || t.isBlank() ? "未命名文档" : t;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(str(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String brief(String s) {
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    private static String typeName(String type) {
        return switch (type) {
            case "SINGLE" -> "单选题";
            case "MULTI" -> "多选题";
            case "JUDGE" -> "判断题";
            case "FILL" -> "填空题";
            default -> "问答题";
        };
    }

    // ===================== DTO =====================

    public static class GenReq {
        public String topic;
        public int count = 5;
        public List<String> types;
        public int difficulty = 0;
        public String category;
        public String sourceType = "DOC";
        public Long shareId;
        public List<Long> docIds;
        public List<String> urls;
        public String extra;
    }
}
