/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Flashcard;
import com.kb.kbplatform.domain.Question;
import com.kb.kbplatform.domain.QuestionOption;
import com.kb.kbplatform.domain.SharedDocument;
import com.kb.kbplatform.repository.FlashcardRepository;
import com.kb.kbplatform.repository.PracticeLogRepository;
import com.kb.kbplatform.repository.QuestionOptionRepository;
import com.kb.kbplatform.repository.QuestionRepository;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 「错题 → 文档 → 复习卡」闭环。
 *
 * 这是本系统区别于通用知识库的地方：把**考试数据**与**文档内容**连起来，
 * 用错误率回答一个问题——「哪段文档没讲清楚」。
 *
 * 匹配策略（刻意做成可解释的轻量启发式，不上向量库）：
 *   1. 从题干 + 分类 + 解析里抽关键词（中文 2~6 字片段 + 英文/数字词）
 *   2. 文档标题命中权重 3、正文命中权重 1
 *   3. 得分归一化后取前 N 条，低于阈值的直接不返回（宁缺毋滥，避免乱推荐）
 *
 * 之所以不用向量检索：错题量小（几十~几百），启发式足够且可解释——
 * 用户看到「因为你答错了『索引失效』，所以推荐《数据库索引》」能理解，向量相似度不能。
 */
@Service
public class WrongLoopService {

    private static final int MAX_DOCS = 5;
    private static final double MIN_SCORE = 0.35;   // 归一化后的展示分门槛
    private static final double MIN_RAW = 3;        // 原始分门槛：标题命中1次 or 正文命中3次

    private static final Pattern CJK = Pattern.compile("[\u4e00-\u9fa5]{2,6}");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]{3,}");
    /**
     * 停用词：题干里的「提问措辞」本身没有检索价值。
     * 实测教训：只去掉「下列/关于」时，"TCP 三次握手的主要作用是？" 会靠「主要」「作用」
     * 误命中无关文档——这类泛词必须一起过滤，否则精度很差。
     */
    private static final Set<String> STOP = Set.of(
            "下列", "以下", "哪个", "哪些", "关于", "说法", "正确", "错误", "的是", "不是", "可以", "应该",
            "如何", "什么", "简述", "说明", "请", "以及", "并且", "或者", "一个", "我们", "他们",
            "主要", "作用", "属于", "常见", "解决", "方案", "目的", "意义", "特点", "优点", "缺点",
            "描述", "陈述", "判断", "选择", "填空", "问答", "问题", "内容", "情况", "方式", "方法"
    );

    private final QuestionRepository qRepo;
    private final QuestionOptionRepository optRepo;
    private final PracticeLogRepository logRepo;
    private final FlashcardRepository cardRepo;
    private final DocShareService docShare;

    public WrongLoopService(QuestionRepository qRepo, QuestionOptionRepository optRepo,
                            PracticeLogRepository logRepo, FlashcardRepository cardRepo,
                            DocShareService docShare) {
        this.qRepo = qRepo;
        this.optRepo = optRepo;
        this.logRepo = logRepo;
        this.cardRepo = cardRepo;
        this.docShare = docShare;
    }

    /** 从文本抽关键词（按出现次数取前 8，去停用词） */
    static List<String> keywords(String... texts) {
        Map<String, Integer> freq = new LinkedHashMap<>();
        for (String t : texts) {
            if (t == null || t.isBlank()) continue;
            Matcher m = CJK.matcher(t);
            while (m.find()) {
                String w = m.group();
                if (STOP.contains(w)) continue;
                freq.merge(w, 1, Integer::sum);
            }
            Matcher w2 = WORD.matcher(t);
            while (w2.find()) freq.merge(w2.group().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        return freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(8)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 错题 → 相关文档（只在该用户可读范围内找） */
    public List<Map<String, Object>> relatedDocs(Long userId, boolean sysAdmin, Long questionId) {
        Question q = qRepo.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "题目不存在"));
        // 只能在「自己做过的题」上查关联文档：否则可以拿题号挨个探测题库
        if (!logRepo.existsByUserIdAndQuestionId(userId, questionId)) return List.of();
        return matchDocs(userId, sysAdmin, q, docShare.listAllDocs(userId, sysAdmin, true));
    }

    /** 纯匹配：文档列表由调用方一次取好，批量场景（转卡）复用，避免每题重扫全库 */
    private List<Map<String, Object>> matchDocs(Long userId, boolean sysAdmin, Question q, List<SharedDocument> docs) {
        List<String> kws = keywords(q.getContent(), q.getCategory(), q.getAnalysis());
        if (kws.isEmpty()) return List.of();
        List<Map<String, Object>> scored = new ArrayList<>();
        double max = 0;
        for (SharedDocument d : docs) {
            String title = d.getTitle() == null ? "" : d.getTitle();
            String body = d.getContent() == null ? "" : d.getContent();
            double score = 0;
            for (String kw : kws) {
                if (title.contains(kw)) score += 3;
                else if (body.contains(kw)) score += 1;
            }
            if (score <= 0) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shareId", d.getShareId());
            row.put("docId", d.getId());
            row.put("title", title);
            row.put("score", score);
            row.put("raw", score);   // 原始分：用于绝对门槛判断（归一化会把弱命中抬成 1.0）
            row.put("snippet", snippet(body, kws));
            scored.add(row);
            max = Math.max(max, score);
        }
        final double denom = max <= 0 ? 1 : max;
        return scored.stream()
                // 绝对门槛优先：标题命中(3分)或正文命中≥3次才够格，避免「一处弱命中」被归一化抬到 1.0
                .filter(r -> (double) r.get("raw") >= MIN_RAW)
                .peek(r -> r.put("score", Math.round(((double) r.get("score") / denom) * 100) / 100.0))
                .filter(r -> (double) r.get("score") >= MIN_SCORE)
                .sorted((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")))
                .limit(MAX_DOCS)
                .toList();
    }

    /** 命中的关键词前后取一段纯文本，给前端做「为什么推荐这段」 */
    private String snippet(String html, List<String> kws) {
        String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        int at = -1;
        for (String kw : kws) {
            at = text.indexOf(kw);
            if (at >= 0) break;
        }
        if (at < 0) return text.length() > 80 ? text.substring(0, 80) + "…" : text;
        int from = Math.max(0, at - 30);
        int to = Math.min(text.length(), at + 70);
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
    }

    /** 错题一键转复习卡：正=题干，背=正确答案 + 解析；同题只建一张（source 去重） */
    @Transactional
    public Map<String, Object> toCards(Long userId, boolean sysAdmin, List<Long> questionIds) {
        int created = 0, skipped = 0;
        // 文档列表只取一次（原来每题都全量扫一遍文档，100 题就是 100 次全表扫）
        List<SharedDocument> docs = docShare.listAllDocs(userId, sysAdmin, true);
        for (Long qid : questionIds) {
            if (qid == null) continue;
            String source = "wrong:" + qid;
            if (cardRepo.existsByUserIdAndSource(userId, source)) { skipped++; continue; }
            Question q = qRepo.findById(qid).orElse(null);
            if (q == null) { skipped++; continue; }
            // 只允许把「自己做过的题」转成卡：否则任何人可凭题号把整库答案套成复习卡
            if (!logRepo.existsByUserIdAndQuestionId(userId, qid)) { skipped++; continue; }

            String front = plain(q.getContent());
            StringBuilder back = new StringBuilder();
            List<QuestionOption> opts = optRepo.findByQuestionIdOrderByLabelAsc(qid);
            List<String> correct = opts.stream().filter(QuestionOption::isCorrect)
                    .map(o -> o.getLabel() + ". " + plain(o.getContent())).toList();
            if (!correct.isEmpty()) back.append("正确答案：").append(String.join("；", correct));
            if (q.getAnalysis() != null && !q.getAnalysis().isBlank()) {
                if (back.length() > 0) back.append("\n");
                back.append("解析：").append(plain(q.getAnalysis()));
            }
            if (back.length() == 0) back.append("（本题无解析）");

            // 顺带把关联文档记在卡片上，复习时能一键跳回去看原文
            Long shareId = null, docId = null;
            List<Map<String, Object>> hit = matchDocs(userId, sysAdmin, q, docs);
            if (!hit.isEmpty()) {
                shareId = ((Number) hit.get(0).get("shareId")).longValue();
                docId = ((Number) hit.get(0).get("docId")).longValue();
            }
            Flashcard c = new Flashcard();
            c.setUserId(userId);
            c.setShareId(shareId);
            c.setDocId(docId);
            c.setFront(front.length() > 200 ? front.substring(0, 200) : front);
            c.setBack(back.toString());
            c.setSource(source);
            cardRepo.save(c);
            created++;
        }
        return Map.of("created", created, "skipped", skipped);
    }

    /**
     * 文档易错点：这篇文档相关的题目，全站答错了多少次。
     * 给作者看的——「这段是不是没写清楚」。
     */
    public Map<String, Object> weakPoints(Long userId, boolean sysAdmin, Long shareId, Long docId) {
        SharedDocument doc = docShare.getDoc(shareId, docId, userId, sysAdmin);
        String title = doc.getTitle() == null ? "" : doc.getTitle();
        String tags = doc.getTags() == null ? "" : doc.getTags();
        List<String> kws = keywords(title, tags);
        if (kws.isEmpty()) return Map.of("relatedQuestions", 0, "wrongRate", 0.0, "samples", List.of());

        // 文档没有分类字段，用标题/标签抽出的关键词在题库里反查（题目量级小，全量扫可接受）
        List<Question> pool = qRepo.findAllByOrderByCreatedAtDesc();

        // 题目统计一次查完（原来每题两次 count，题库一大就是几百次查询）
        Map<Long, long[]> stats = new HashMap<>();
        for (Object[] row : logRepo.statsByQuestion()) {
            if (row[0] == null) continue;
            long total0 = row[1] == null ? 0 : ((Number) row[1]).longValue();
            long ok0 = row[2] == null ? 0 : ((Number) row[2]).longValue();
            stats.put(((Number) row[0]).longValue(), new long[]{total0, ok0});
        }
        List<Map<String, Object>> samples = new ArrayList<>();
        int matched = 0, total = 0, wrong = 0;
        for (Question q : pool) {
            String text = (q.getContent() == null ? "" : q.getContent());
            boolean hit = kws.stream().anyMatch(text::contains);
            if (!hit) continue;
            long[] st = stats.get(q.getId());
            if (st == null || st[0] <= 0) continue;
            long all = st[0];
            long ok = st[1];
            matched++;
            total += (int) all;
            wrong += (int) (all - ok);
            if (samples.size() < 8) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("questionId", q.getId());
                row.put("content", plain(text).length() > 60 ? plain(text).substring(0, 60) + "…" : plain(text));
                row.put("attempts", all);
                row.put("wrongRate", Math.round((all - ok) * 100.0 / all) / 100.0);
                samples.add(row);
            }
        }
        double rate = total == 0 ? 0 : Math.round(wrong * 100.0 / total) / 100.0;
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("relatedQuestions", matched);
        ret.put("attempts", total);
        ret.put("wrongRate", rate);
        ret.put("samples", samples);
        return ret;
    }

    private String plain(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
    }
}
