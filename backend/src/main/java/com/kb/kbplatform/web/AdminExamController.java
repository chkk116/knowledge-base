/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.ExamPaper;
import com.kb.kbplatform.domain.Question;
import com.kb.kbplatform.domain.QuestionOption;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AiQuestionService;
import com.kb.kbplatform.service.ExamService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/admin/exams")
public class AdminExamController {

    private final ExamService svc;
    private final AiQuestionService aiQuestions;

    public AdminExamController(ExamService svc, AiQuestionService aiQuestions) {
        this.svc = svc;
        this.aiQuestions = aiQuestions;
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「" + code + "」权限");
        }
    }

    // ---------- 题库 ----------
    @GetMapping("/questions")
    public List<Question> questions(@RequestAttribute("kbUser") TokenClaims c,
                                    @RequestParam(required = false) String category) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.adminListQuestions(category);
    }

    @PostMapping("/questions")
    public Question createQuestion(@RequestAttribute("kbUser") TokenClaims c, @RequestBody QuestionReq req) {
        requirePerm(c, "EXAM_MANAGE");
        req.question.setId(null);   // 同 H1：POST 强制新建，防 id 指定覆盖已有题目
        return svc.adminSaveQuestion(req.question, req.options);
    }

    @PutMapping("/questions/{id}")
    public Question updateQuestion(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id, @RequestBody QuestionReq req) {
        requirePerm(c, "EXAM_MANAGE");
        // 定向 findById 替代「全量拉题库再 stream 过滤」（2026-10-13 审查 M9）
        Question ex = svc.adminGetQuestion(id);
        if (req.question.getContent() != null) ex.setContent(req.question.getContent());
        if (req.question.getType() != null) ex.setType(req.question.getType());
        if (req.question.getAnalysis() != null) ex.setAnalysis(req.question.getAnalysis());
        if (req.question.getDifficulty() != 0) ex.setDifficulty(req.question.getDifficulty());
        if (req.question.getCategory() != null) ex.setCategory(req.question.getCategory());
        return svc.adminSaveQuestion(ex, req.options);
    }

    @DeleteMapping("/questions/{id}")
    public Map<String, Object> deleteQuestion(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "EXAM_MANAGE");
        svc.adminDeleteQuestion(id);
        return Map.of("ok", true);
    }

    // ---------- AI 出题 ----------

    /**
     * AI 生成候选题目：只返回，不落库。前端预览勾选后再调 /questions/batch 保存。
     *
     * sourceType：DOC=知识库文档 / WEB=指定网页 / AI=模型通用知识。
     * WEB 与 AI 都要前端先弹确认——前者会真的去访问外部网站，后者内容未经知识库校验。
     */
    @PostMapping("/ai/generate")
    public Map<String, Object> aiGenerate(@RequestAttribute("kbUser") TokenClaims c,
                                          @RequestBody AiQuestionService.GenReq req) {
        requirePerm(c, "EXAM_MANAGE");
        if (c.isDemo()) throw new ResponseStatusException(FORBIDDEN, "演示账号不能使用 AI 出题");
        return aiQuestions.generate(req, c.userId(), c.isAdmin());
    }

    /** 批量入库：一次事务，任一条不合法整批回滚（避免只存进去一半） */
    @PostMapping("/questions/batch")
    @Transactional(rollbackFor = Exception.class)
    public List<Question> batchCreate(@RequestAttribute("kbUser") TokenClaims c, @RequestBody List<QuestionReq> reqs) {
        requirePerm(c, "EXAM_MANAGE");
        if (reqs == null || reqs.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "没有要保存的题目");
        if (reqs.size() > 50) throw new ResponseStatusException(BAD_REQUEST, "一次最多保存 50 道题");
        List<Question> saved = new ArrayList<>();
        for (QuestionReq req : reqs) {
            if (req == null || req.question == null) continue;
            req.question.setId(null);
            saved.add(svc.adminSaveQuestion(req.question, req.options));
        }
        return saved;
    }

    // ---------- 试卷 ----------
    @GetMapping("/papers")
    public List<ExamPaper> papers(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.adminListPapers();
    }

    @PostMapping("/papers")
    public ExamPaper createPaper(@RequestAttribute("kbUser") TokenClaims c, @RequestBody ExamPaper paper) {
        requirePerm(c, "EXAM_MANAGE");
        paper.setId(null);   // 同 H1：POST 强制新建，防 id 指定覆盖已有试卷
        return svc.adminSavePaper(paper);
    }

    @PutMapping("/papers/{id}")
    public ExamPaper updatePaper(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id, @RequestBody ExamPaper paper) {
        requirePerm(c, "EXAM_MANAGE");
        ExamPaper ex = svc.adminGetPaper(id);   // 定向 findById 替代全量列表过滤（M9）
        if (paper.getTitle() != null) ex.setTitle(paper.getTitle());
        if (paper.getDescription() != null) ex.setDescription(paper.getDescription());
        if (paper.getPassScore() != 0) ex.setPassScore(paper.getPassScore());
        if (paper.getDurationMin() != null) ex.setDurationMin(paper.getDurationMin());
        if (paper.getStatus() != null) ex.setStatus(paper.getStatus());
        return svc.adminSavePaper(ex);
    }

    @DeleteMapping("/papers/{id}")
    public Map<String, Object> deletePaper(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "EXAM_MANAGE");
        svc.adminDeletePaper(id);
        return Map.of("ok", true);
    }

    /** 组卷：替换题目与分值，回写总分/题量 */
    @PostMapping("/papers/{id}/assemble")
    public ExamPaper assemble(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id, @RequestBody AssembleReq req) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.assemble(id, req.items);
    }

    // ---- DTO ----
    public static class QuestionReq {
        public Question question;
        public List<QuestionOption> options;
    }

    public static class AssembleReq {
        public List<ExamService.PaperItem> items;
    }
}
