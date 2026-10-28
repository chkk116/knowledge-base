/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Question;
import com.kb.kbplatform.repository.QuestionRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AiQuestionService;
import com.kb.kbplatform.service.ExamService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 个人自测：任何登录用户都能给自己出题（不需要 EXAM_MANAGE）。
 *
 * 为什么单独一套接口而不放开 /api/admin/exams：管理端是"管全站题库"，放开等于人人都能
 * 改别人的题。个人自测只需要"我的题只属于我"——靠**分类隔离**实现：
 *   category 强制写成 `个人-{userId}`，入库时 creatorId 也强制为自己。
 * 练习直接用 /api/exams/practice/session 按 category 抽题（不依赖试卷），错题本/复习卡天然按用户隔离。
 *
 * 配额：个人题库上限 300 题（AI 出题有成本，且个人场景不需要更大）。超了提示删旧题。
 */
@RestController
@RequestMapping("/api/exams/self")
public class SelfExamController {

    private static final String CAT_PREFIX = "个人-";
    private static final int MAX_PER_USER = 300;
    private static final int MAX_BATCH = 50;

    private final AiQuestionService aiQuestions;
    private final ExamService svc;
    private final QuestionRepository qRepo;

    public SelfExamController(AiQuestionService aiQuestions, ExamService svc, QuestionRepository qRepo) {
        this.aiQuestions = aiQuestions;
        this.svc = svc;
        this.qRepo = qRepo;
    }

    public static String personalCategory(Long userId) {
        return CAT_PREFIX + userId;
    }

    private Long uid(TokenClaims c) {
        if (c == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        if (c.isDemo()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "演示账号不能 AI 出题");
        return c.userId();
    }

    private void checkQuota(Long userId) {
        if (qRepo.countByCreatorIdAndCategory(userId, personalCategory(userId)) >= MAX_PER_USER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "个人题库已满（上限 " + MAX_PER_USER + " 题），请先删除一些旧题");
        }
    }

    /** AI 生成题目草稿：category 强制为个人分类，题源仍可 DOC/WEB/AI */
    @PostMapping("/ai/generate")
    public Map<String, Object> generate(@RequestAttribute("kbUser") TokenClaims c,
                                        @RequestBody AiQuestionService.GenReq req) {
        Long userId = uid(c);
        checkQuota(userId);
        if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "参数为空");
        req.category = personalCategory(userId);   // 隔离：不写进公共题库
        return aiQuestions.generate(req, userId, false);
    }

    /** 批量入库：强制归属与分类，任一条不合法整批回滚 */
    @PostMapping("/questions/batch")
    @Transactional(rollbackFor = Exception.class)
    public List<Question> batchCreate(@RequestAttribute("kbUser") TokenClaims c,
                                      @RequestBody List<AdminExamController.QuestionReq> reqs) {
        Long userId = uid(c);
        if (reqs == null || reqs.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有要保存的题目");
        if (reqs.size() > MAX_BATCH) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次最多保存 " + MAX_BATCH + " 道题");
        checkQuota(userId);
        if (qRepo.countByCreatorIdAndCategory(userId, personalCategory(userId)) + reqs.size() > MAX_PER_USER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "保存后将超过个人题库上限 " + MAX_PER_USER + " 题");
        }
        List<Question> saved = new ArrayList<>();
        for (AdminExamController.QuestionReq req : reqs) {
            if (req == null || req.question == null)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "第 " + (saved.size() + 1) + " 条数据为空，已整批取消保存");
            req.question.setId(null);
            req.question.setCategory(personalCategory(userId));
            req.question.setCreatorId(userId);
            saved.add(svc.adminSaveQuestion(req.question, req.options));
        }
        return saved;
    }


    /** 我的测验：列出自己的个人题库（按分类隔离，别人看不到） */
    @GetMapping("/questions")
    public List<Map<String, Object>> myQuestions(@RequestAttribute("kbUser") TokenClaims c) {
        Long userId = uid(c);
        String cat = personalCategory(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Question q : qRepo.findByCategoryOrderByCreatedAtDesc(cat)) {
            if (q.getCreatorId() == null || !q.getCreatorId().equals(userId)) continue;  // 双保险
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("id", q.getId());
            row.put("type", q.getType());
            row.put("content", q.getContent());
            row.put("analysis", q.getAnalysis());
            row.put("createdAt", q.getCreatedAt());
            out.add(row);
        }
        return out;
    }

    /** 删除自己的一道题（连同选项）；他人题目一律 404，不泄露存在性 */
    @DeleteMapping("/questions/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteQuestion(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        Long userId = uid(c);
        Question q = qRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "题目不存在"));
        if (q.getCreatorId() == null || !q.getCreatorId().equals(userId)
                || !personalCategory(userId).equals(q.getCategory())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "题目不存在");
        }
        svc.adminDeleteQuestion(id);
        return Map.of("deleted", true);
    }

    /** 个人题库概览：给「刷题」入口显示数量 + 分类 */
    @GetMapping("/stats")
    public Map<String, Object> stats(@RequestAttribute("kbUser") TokenClaims c) {
        Long userId = uid(c);
        long n = qRepo.countByCreatorIdAndCategory(userId, personalCategory(userId));
        return Map.of("category", personalCategory(userId), "questions", n, "limit", MAX_PER_USER);
    }
}
