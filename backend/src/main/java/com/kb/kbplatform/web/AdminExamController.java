package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.ExamPaper;
import com.kb.kbplatform.domain.Question;
import com.kb.kbplatform.domain.QuestionOption;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.ExamService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/admin/exams")
public class AdminExamController {

    private final ExamService svc;

    public AdminExamController(ExamService svc) {
        this.svc = svc;
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
        return svc.adminSaveQuestion(req.question, req.options);
    }

    @PutMapping("/questions/{id}")
    public Question updateQuestion(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id, @RequestBody QuestionReq req) {
        requirePerm(c, "EXAM_MANAGE");
        Question ex = svc.adminListQuestions(null).stream()
                .filter(q -> q.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "题目不存在"));
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

    // ---------- 试卷 ----------
    @GetMapping("/papers")
    public List<ExamPaper> papers(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.adminListPapers();
    }

    @PostMapping("/papers")
    public ExamPaper createPaper(@RequestAttribute("kbUser") TokenClaims c, @RequestBody ExamPaper paper) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.adminSavePaper(paper);
    }

    @PutMapping("/papers/{id}")
    public ExamPaper updatePaper(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id, @RequestBody ExamPaper paper) {
        requirePerm(c, "EXAM_MANAGE");
        ExamPaper ex = svc.adminListPapers().stream()
                .filter(p -> p.getId().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "试卷不存在"));
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
