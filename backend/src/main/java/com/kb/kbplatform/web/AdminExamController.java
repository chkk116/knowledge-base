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
    private final com.kb.kbplatform.service.QuestionImportService questionImport;

    public AdminExamController(ExamService svc, AiQuestionService aiQuestions,
                               com.kb.kbplatform.service.QuestionImportService questionImport) {
        this.svc = svc;
        this.aiQuestions = aiQuestions;
        this.questionImport = questionImport;
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「" + code + "」权限");
        }
    }

    // ---------- 模拟卷（2026-10-21 改为试卷属性，在试卷列表的操作里设置） ----------
    /** 当前模拟卷（未配置 paperId 为 null） */
    @GetMapping("/mock")
    public Map<String, Object> getMock(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.mockPaper();
    }

    /** 把某份试卷设为/取消全站模拟卷；body { enabled: true|false } */
    @PostMapping("/papers/{id}/mock")
    public Map<String, Object> setPaperMock(@RequestAttribute("kbUser") TokenClaims c,
                                            @PathVariable Long id,
                                            @RequestBody(required = false) Map<String, Object> body) {
        requirePerm(c, "EXAM_MANAGE");
        Object raw = body == null ? null : body.get("enabled");
        // 缺省即生效太危险（空 body 会把试卷设为全站模拟卷，审查 r12）：必须显式传 enabled
        if (raw == null) throw new ResponseStatusException(BAD_REQUEST, "缺少 enabled 参数");
        return svc.setPaperMock(id, Boolean.parseBoolean(String.valueOf(raw)));
    }

    // ---------- 题库 ----------
    @GetMapping("/questions")
    public List<Question> questions(@RequestAttribute("kbUser") TokenClaims c,
                                    @RequestParam(required = false) String category) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.adminListQuestions(category);
    }

    /** 单题详情（含选项）：编辑弹窗要用。列表接口不返回 options，没这个端点时编辑等于重填。 */
    @GetMapping("/questions/{id}")
    public Question getQuestion(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.adminGetQuestionWithOptions(id);
    }

    @PostMapping("/questions")
    public Question createQuestion(@RequestAttribute("kbUser") TokenClaims c, @RequestBody QuestionReq req) {
        requirePerm(c, "EXAM_MANAGE");
        if (req == null || req.question == null)
            throw new ResponseStatusException(BAD_REQUEST, "缺少题目内容");
        req.question.setId(null);   // 同 H1：POST 强制新建，防 id 指定覆盖已有题目
        return svc.adminSaveQuestion(req.question, req.options);
    }

    @PutMapping("/questions/{id}")
    public Question updateQuestion(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id, @RequestBody QuestionReq req) {
        requirePerm(c, "EXAM_MANAGE");
        if (req == null || req.question == null)
            throw new ResponseStatusException(BAD_REQUEST, "缺少题目内容");
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

    /** 删除题目（批量）：逐条复用 adminDeleteQuestion，级联清理组卷关联与试卷总分不变 */
    @PostMapping("/questions/batch-delete")
    @Transactional(rollbackFor = Exception.class)   // 审查 r11：批量必须整体原子，不能删一半
    public Map<String, Object> deleteQuestions(@RequestAttribute("kbUser") TokenClaims c, @RequestBody IdsReq req) {
        requirePerm(c, "EXAM_MANAGE");
        List<Long> ids = req.ids == null ? List.of() : req.ids.stream().distinct().toList();
        if (ids.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "未选择要删除的题目");
        int n = 0;
        for (Long id : ids) {
            // 并发/重复点击时 id 可能已被删：跳过而不是整批 500（审查 r12）
            try { if (svc.adminDeleteQuestion(id)) n++; } catch (IllegalArgumentException ignore) { }
        }
        return Map.of("ok", true, "deleted", n);
    }

    /** 题库批量导入（Excel）。逐行导入、逐行报错，返回 { imported, failed:[{row,reason}] } */
    @PostMapping("/questions/import")
    public Map<String, Object> importQuestions(@RequestAttribute("kbUser") TokenClaims c,
                                               @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        requirePerm(c, "EXAM_MANAGE");
        if (file == null || file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "请选择要导入的 Excel 文件");
        if (file.getSize() > 5L * 1024 * 1024) throw new ResponseStatusException(BAD_REQUEST, "文件太大（≤5MB）");
        try (var in = file.getInputStream()) {
            return questionImport.importXlsx(in);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_REQUEST, "读取文件失败：" + e.getMessage());
        }
    }

    /** 下载导入模板（表头 + 四行示例） */
    @GetMapping("/questions/import-template")
    public org.springframework.http.ResponseEntity<byte[]> importTemplate(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "EXAM_MANAGE");
        byte[] bytes = questionImport.template();
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"question-import-template.xlsx\"")
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
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
            // 契约是「任一条不合法整批回滚」，不能静默跳过（审查 r9/r11）
            if (req == null || req.question == null)
                throw new ResponseStatusException(BAD_REQUEST, "第 " + (saved.size() + 1) + " 条数据为空，已整批取消保存");
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
        // 只认「传了且有意义的数值」：前端表单整体回传时，未填的字段是 0，
        // 按 0 落库会把 60 分钟静默改成 0（N100）
        if (paper.getPassScore() > 0) ex.setPassScore(paper.getPassScore());
        if (paper.getDurationMin() != null && paper.getDurationMin() > 0) ex.setDurationMin(paper.getDurationMin());
        // 状态没变时不要重设：否则「只改标题」也会走一遍发布校验，
        // 0 题草稿被误判成「要发布」直接 400（N101）
        if (paper.getStatus() != null && !paper.getStatus().equals(ex.getStatus())) ex.setStatus(paper.getStatus());
        // 「允许模拟」开关之前只写库不改值（PUT 里漏了），现在补上；null 表示前端没传，保持原值
        if (paper.getPracticeEnabled() != null) ex.setPracticeEnabled(paper.getPracticeEnabled());
        return svc.adminSavePaper(ex);
    }

    @DeleteMapping("/papers/{id}")
    public Map<String, Object> deletePaper(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "EXAM_MANAGE");
        svc.adminDeletePaper(id);
        return Map.of("ok", true);
    }

    /** 删除试卷（批量）：连带清理组卷关联与作答记录 */
    @PostMapping("/papers/batch-delete")
    @Transactional(rollbackFor = Exception.class)   // 审查 r11：批量整体原子
    public Map<String, Object> deletePapers(@RequestAttribute("kbUser") TokenClaims c, @RequestBody IdsReq req) {
        requirePerm(c, "EXAM_MANAGE");
        List<Long> ids = req.ids == null ? List.of() : req.ids.stream().distinct().toList();
        if (ids.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "未选择要删除的试卷");
        int n = 0;
        for (Long id : ids) {
            try { svc.adminDeletePaper(id); n++; } catch (IllegalArgumentException ignore) { }
        }
        return Map.of("ok", true, "deleted", n);
    }

    /** 批量删除入参 { ids: [..] } */
    public static class IdsReq {
        public List<Long> ids;
    }

    /** 组卷：替换题目与分值，回写总分/题量 */
    @PostMapping("/papers/{id}/assemble")
    public ExamPaper assemble(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id, @RequestBody AssembleReq req) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.assemble(id, req.items);
    }

    // ---- 主观题阅卷 ----
    /** 待阅卷列表：有主观题尚未给分的考试记录 */
    @GetMapping("/grading/pending")
    public List<Map<String, Object>> pendingGrading(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.adminPendingGrading();
    }

    /** 阅卷详情：一场考试的全部作答（含主观题参考答案与满分） */
    @GetMapping("/grading/{recordId}")
    public Map<String, Object> gradingDetail(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long recordId) {
        requirePerm(c, "EXAM_MANAGE");
        return svc.adminGradingDetail(recordId);
    }

    /** 提交阅卷：body { items: [{ questionId, score, comment? }] } */
    @PostMapping("/grading/{recordId}")
    public Map<String, Object> grade(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long recordId,
                                     @RequestBody GradeReq req) {
        requirePerm(c, "EXAM_MANAGE");
        if (req == null || req.items == null || req.items.isEmpty())
            throw new ResponseStatusException(BAD_REQUEST, "请至少给一道题打分");
        return svc.adminGrade(recordId, req.items);
    }

    /** 阅卷入参 */
    public static class GradeReq {
        public List<ExamService.GradeItem> items;
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
