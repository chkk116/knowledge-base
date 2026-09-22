/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.*;
import com.kb.kbplatform.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;

@Service
public class ExamService {

    private final QuestionRepository questionRepo;
    private final QuestionOptionRepository optionRepo;
    private final ExamPaperRepository paperRepo;
    private final ExamPaperQuestionRepository pqRepo;
    private final ExamRecordRepository recordRepo;
    private final ExamAnswerRepository answerRepo;
    private final AuthService authService;
    private final CourseRepository courseRepo;
    private final CourseChapterRepository chapterRepo;
    private final LearningProgressRepository progressRepo;
    private final CourseService courseService;
    private final NotificationService notificationService;

    public ExamService(QuestionRepository questionRepo, QuestionOptionRepository optionRepo,
                       ExamPaperRepository paperRepo, ExamPaperQuestionRepository pqRepo,
                       ExamRecordRepository recordRepo, ExamAnswerRepository answerRepo,
                       AuthService authService, CourseRepository courseRepo,
                       CourseChapterRepository chapterRepo, LearningProgressRepository progressRepo,
                       CourseService courseService, NotificationService notificationService) {
        this.questionRepo = questionRepo;
        this.optionRepo = optionRepo;
        this.paperRepo = paperRepo;
        this.pqRepo = pqRepo;
        this.recordRepo = recordRepo;
        this.answerRepo = answerRepo;
        this.authService = authService;
        this.courseRepo = courseRepo;
        this.chapterRepo = chapterRepo;
        this.progressRepo = progressRepo;
        this.courseService = courseService;
        this.notificationService = notificationService;
    }

    // ===================== 学员侧 =====================

    /** 公开：已发布试卷列表（含题量） */
    public List<ExamPaper> listPapers() {
        List<ExamPaper> list = paperRepo.findAllByStatusOrderByCreatedAtDesc("PUBLISHED");
        for (ExamPaper p : list) p.setQuestionCount(pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(p.getId()).size());
        return list;
    }

    /** 限时宽限：网络抖动/提交在途的补偿时间，超过 deadline 后仍允许在这一小段时间内交卷 */
    private static final Duration SUBMIT_GRACE = Duration.ofSeconds(60);

    /** 服务端限时判定（F07）：不限时返回 false；已超时（含宽限）返回 true */
    private boolean expired(ExamRecord rec) {
        if (rec.getDeadlineAt() == null) return false;
        return LocalDateTime.now().isAfter(rec.getDeadlineAt().plus(SUBMIT_GRACE));
    }

    /** 学员考试视角——题目与选项，但不返回正确答案。
     *  防泄题闸门：必须先 POST /{id}/start 成功（存在该用户该试卷的进行中记录）才发题。 */
    public Map<String, Object> getPaperForStudent(Long userId, Long paperId) {
        ExamPaper paper = paperRepo.findById(paperId).orElseThrow(() -> new IllegalArgumentException("试卷不存在"));
        if (!"PUBLISHED".equals(paper.getStatus())) throw new ResponseStatusException(FORBIDDEN, "该试卷未发布");
        ExamRecord running = recordRepo.findByUserIdOrderByStartedAtDesc(userId).stream()
                .filter(r -> r.getPaperId().equals(paperId) && r.getSubmittedAt() == null)
                .findFirst().orElse(null);
        if (running == null) throw new ResponseStatusException(FORBIDDEN, "请先开始考试");
        // 已超时不再发题（否则可以「开考后关页面，几小时后再取题慢慢做」绕过限时）
        if (expired(running)) throw new ResponseStatusException(FORBIDDEN, "考试已超时结束，请重新开始");
        List<ExamPaperQuestion> pqs = pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(paperId);
        Map<Long, ExamPaperQuestion> pqMap = new LinkedHashMap<>();
        for (ExamPaperQuestion pq : pqs) pqMap.put(pq.getQuestionId(), pq);
        List<Question> qs = questionRepo.findByIdIn(new ArrayList<>(pqMap.keySet()));
        List<Map<String, Object>> questions = new ArrayList<>();
        for (Question q : qs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", q.getId());
            m.put("type", q.getType());
            m.put("content", q.getContent());
            m.put("score", pqMap.get(q.getId()).getScore());
            List<Map<String, Object>> opts = new ArrayList<>();
            for (QuestionOption o : optionRepo.findByQuestionIdOrderByLabelAsc(q.getId())) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("id", o.getId());
                om.put("label", o.getLabel());
                om.put("content", o.getContent());
                opts.add(om);
            }
            m.put("options", opts);
            questions.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("paper", paper);
        res.put("questions", questions);
        return res;
    }

    /** 登录：开始考试，创建考试记录 */
    public ExamRecord start(Long userId, Long paperId) {
        ExamPaper paper = paperRepo.findById(paperId).orElseThrow(() -> new IllegalArgumentException("试卷不存在"));
        if (!"PUBLISHED".equals(paper.getStatus())) throw new ResponseStatusException(FORBIDDEN, "该试卷未发布");
        // #4 复用进行中记录：避免刷新/重连产生孤儿记录。
        // 但已超时的进行中记录不能再复用（否则等于把限时考试变成不限时）：
        // 就地按「超时未交」结算关闭，再新建一条记录（F07）。
        List<ExamRecord> inProgress = recordRepo.findByUserIdOrderByStartedAtDesc(userId).stream()
                .filter(r -> r.getPaperId().equals(paperId) && r.getSubmittedAt() == null)
                .toList();
        for (ExamRecord old : inProgress) {
            if (!expired(old)) return old;
            settleExpired(old);
        }
        // #2 结业考试前置校验：若该试卷是某课程的结业考试，须学完所有章节才能考
        // 结业考试可能被多门课程绑定：必须学完【每一门】绑定课程的全部章节（此前只校验第一门，
        // 会出现在 A 课已学完却被无关的 B 课卡住的情况）
        List<Course> bound = courseRepo.findByExamPaperId(paperId);
        for (Course course : bound) {
            List<CourseChapter> chapters = chapterRepo.findByCourseIdOrderBySortOrderAscIdAsc(course.getId());
            if (chapters.isEmpty()) continue;   // 无章节的课程不设卡
            List<Long> chapterIds = chapters.stream().map(CourseChapter::getId).toList();
            List<LearningProgress> progs = progressRepo.findByUserIdAndCourseId(userId, course.getId());
            boolean allDone = chapterIds.stream().allMatch(cid ->
                    progs.stream().anyMatch(p -> p.getChapterId().equals(cid) && p.isFinished()));
            if (!allDone) {
                throw new ResponseStatusException(BAD_REQUEST, "请先完成《" + course.getTitle() + "》的全部章节后再参加结业考试");
            }
        }
        ExamRecord rec = new ExamRecord();
        rec.setUserId(userId);
        rec.setPaperId(paperId);
        rec.setPaperTitle(paper.getTitle());
        rec.setQuestionCount(pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(paperId).size());
        rec.setStartedAt(LocalDateTime.now());
        // 服务端固化截止时间（不限时的试卷保持 null）
        Integer dur = paper.getDurationMin();
        if (dur != null && dur > 0) rec.setDeadlineAt(rec.getStartedAt().plusMinutes(dur));
        try {
            return recordRepo.save(rec);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 并发同时开考：数据库唯一约束（每个用户每份试卷最多一条进行中记录）拦下第二条，
            // 这里回查已存在的进行中记录返回，保证「开考」幂等（F08）
            return recordRepo.findByUserIdOrderByStartedAtDesc(userId).stream()
                    .filter(r -> r.getPaperId().equals(paperId) && r.getSubmittedAt() == null)
                    .findFirst()
                    .orElseThrow(() -> e);
        }
    }

    /** 超时未交的记录按 0 分关闭（不判分、不发分），避免永久占位导致无法重新考试 */
    @Transactional
    protected void settleExpired(ExamRecord rec) {
        rec.setScore(0);
        rec.setPassed(false);
        rec.setSubmittedAt(LocalDateTime.now());
        if (rec.getStartedAt() != null) {
            rec.setDurationSec((int) Math.max(0, Duration.between(rec.getStartedAt(), rec.getSubmittedAt()).getSeconds()));
        }
        recordRepo.save(rec);
    }

    /** 提交试卷并自动阅卷（客观题判分，主观题记 0 待人工）；首次提交且及格时奖励考试积分。
     *  幂等 + 限时（F07/F08）：已提交直接返回首次结果；超时（含宽限）拒绝；并发提交只有一条能判分发分。 */
    @Transactional
    public ExamRecord submit(Long userId, Long recordId, List<AnswerItem> items) {
        ExamRecord rec = recordRepo.findById(recordId).orElseThrow(() -> new IllegalArgumentException("考试记录不存在"));
        if (!rec.getUserId().equals(userId)) throw new ResponseStatusException(FORBIDDEN, "无权提交该考试");
        // 已经是已提交状态：重复提交（用户狂点/网络重试）直接回放首次结果，不重复判分、不重复发分
        if (rec.getSubmittedAt() != null) return rec;
        // 服务端限时：超过 deadline + 宽限一律拒绝；未交的记录会在下次 start 时按超时结算关闭
        if (expired(rec)) {
            throw new ResponseStatusException(CONFLICT, "考试已超时，本次提交未生效；可重新开始考试");
        }
        // 原子抢占提交权：两个并发请求只有一个能把 submitted_at 从 NULL 改掉，另一个回放首次结果
        if (recordRepo.claimSubmit(recordId, LocalDateTime.now()) == 0) {
            return recordRepo.findById(recordId).orElse(rec);
        }

        List<ExamPaperQuestion> pqs = pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(rec.getPaperId());
        Map<Long, ExamPaperQuestion> pqMap = new LinkedHashMap<>();
        for (ExamPaperQuestion pq : pqs) pqMap.put(pq.getQuestionId(), pq);
        List<Question> qs = questionRepo.findByIdIn(new ArrayList<>(pqMap.keySet()));
        Map<Long, Question> qMap = new LinkedHashMap<>();
        for (Question q : qs) qMap.put(q.getId(), q);
        Map<Long, List<QuestionOption>> optMap = new LinkedHashMap<>();
        for (Question q : qs) optMap.put(q.getId(), optionRepo.findByQuestionIdOrderByLabelAsc(q.getId()));

        Map<Long, List<String>> ansMap = new LinkedHashMap<>();
        for (AnswerItem it : items) ansMap.put(it.questionId, it.answer == null ? List.of() : it.answer);

        int total = 0;
        for (ExamPaperQuestion pq : pqs) {
            Question q = qMap.get(pq.getQuestionId());
            List<String> correct = new ArrayList<>();
            for (QuestionOption o : optMap.get(q.getId())) if (o.isCorrect()) correct.add(o.getLabel());
            List<String> userAns = ansMap.getOrDefault(q.getId(), List.of());
            boolean ok = judge(q.getType(), userAns, correct);
            int qScore = ok ? pq.getScore() : 0;
            total += qScore;

            ExamAnswer ea = new ExamAnswer();
            ea.setRecordId(recordId);
            ea.setQuestionId(q.getId());
            ea.setAnswer(String.join(",", userAns));
            ea.setCorrect(ok);
            ea.setScore(qScore);
            answerRepo.save(ea);
        }
        rec.setScore(total);
        boolean passed = total >= paperRepo.findById(rec.getPaperId()).map(ExamPaper::getPassScore).orElse(60);
        rec.setPassed(passed);
        // 是否首次通过：用于结业证书通知去重（此时 rec 尚未持久化，exists 不含本次）
        boolean firstPass = passed && !recordRepo.existsByUserIdAndPaperIdAndPassedTrue(userId, rec.getPaperId());
        rec.setSubmittedAt(LocalDateTime.now());
        rec.setDurationSec((int) Duration.between(rec.getStartedAt(), rec.getSubmittedAt()).getSeconds());
        ExamRecord saved = recordRepo.save(rec);
        if (passed) {
            authService.awardPoints(userId, AuthService.EXAM_PASS_POINTS, "EXAM",
                    "通过考试《" + (rec.getPaperTitle() != null ? rec.getPaperTitle() : "试卷") + "》");
            // 首次通过且该试卷绑定的课程已学完章节 → 颁发结业证书并通知学员
            if (firstPass) {
                Course course = courseRepo.findByExamPaperId(rec.getPaperId()).stream().findFirst().orElse(null);
                if (course != null && courseService.certificateOf(userId, course.getId()) != null) {
                    notificationService.notifyCertificate(userId, "系统",
                            "恭喜！您已完成课程《" + course.getTitle() + "》并获得结业证书，可在个人中心「学习档案 → 我的结业证书」中查看与下载");
                }
            }
        }
        return saved;
    }

    /** 登录：我的考试成绩 */
    public List<ExamRecord> myRecords(Long userId) {
        return recordRepo.findByUserIdOrderByStartedAtDesc(userId);
    }

    /** 登录：成绩详情（逐题回顾，含正确答案） */
    public Map<String, Object> recordDetail(Long userId, Long recordId) {
        ExamRecord rec = recordRepo.findById(recordId).orElseThrow(() -> new IllegalArgumentException("考试记录不存在"));
        if (!rec.getUserId().equals(userId)) throw new ResponseStatusException(FORBIDDEN, "无权查看该成绩");
        List<ExamAnswer> eas = answerRepo.findByRecordId(recordId);
        List<Long> qids = new ArrayList<>();
        for (ExamAnswer ea : eas) qids.add(ea.getQuestionId());
        List<Question> qs = questionRepo.findByIdIn(qids);
        Map<Long, Question> qMap = new LinkedHashMap<>();
        for (Question q : qs) qMap.put(q.getId(), q);
        Map<Long, List<QuestionOption>> optMap = new LinkedHashMap<>();
        for (Question q : qs) optMap.put(q.getId(), optionRepo.findByQuestionIdOrderByLabelAsc(q.getId()));

        List<Map<String, Object>> items = new ArrayList<>();
        for (ExamAnswer ea : eas) {
            Question q = qMap.get(ea.getQuestionId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionId", q.getId());
            m.put("type", q.getType());
            m.put("content", q.getContent());
            m.put("userAnswer", ea.getAnswer());
            m.put("correct", ea.isCorrect());
            m.put("score", ea.getScore());
            m.put("analysis", q.getAnalysis());
            List<Map<String, Object>> opts = new ArrayList<>();
            for (QuestionOption o : optMap.get(q.getId())) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("label", o.getLabel());
                om.put("content", o.getContent());
                om.put("isCorrect", o.isCorrect());
                opts.add(om);
            }
            m.put("options", opts);
            items.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("record", rec);
        res.put("items", items);
        return res;
    }

    // ===================== 管理员侧 =====================

    public List<Question> adminListQuestions(String category) {
        return (category == null || category.isBlank())
                ? questionRepo.findAllByOrderByCreatedAtDesc()
                : questionRepo.findByCategoryOrderByCreatedAtDesc(category);
    }

    /** 保存题目：选项清理为派生删除（需事务），且多步写入需原子 */
    @Transactional
    public Question adminSaveQuestion(Question q, List<QuestionOption> options) {
        if (q.getContent() == null || q.getContent().isBlank()) throw new IllegalArgumentException("题干不能为空");
        if (q.getType() == null || q.getType().isBlank()) q.setType("SINGLE");
        // #7 客观题必须有选项且至少 1 个正确，避免"无选项题"永远 0 分
        boolean objective = "SINGLE".equals(q.getType()) || "MULTI".equals(q.getType()) || "JUDGE".equals(q.getType());
        if (objective) {
            if (options == null || options.isEmpty()) throw new IllegalArgumentException("客观题必须至少包含一个选项");
            boolean hasCorrect = options.stream().anyMatch(QuestionOption::isCorrect);
            if (!hasCorrect) throw new IllegalArgumentException("客观题必须至少有一个正确选项");
        }
        Question saved = questionRepo.save(q);
        if (options != null) {
            optionRepo.deleteByQuestionId(saved.getId());
            int i = 0;
            for (QuestionOption o : options) {
                o.setId(null);
                o.setQuestionId(saved.getId());
                if (o.getLabel() == null || o.getLabel().isBlank()) o.setLabel(String.valueOf((char) ('A' + i)));
                optionRepo.save(o);
                i++;
            }
        }
        return saved;
    }

    /** 删除题目：选项清理为修改查询，需事务 */
    @Transactional
    public void adminDeleteQuestion(Long id) {
        optionRepo.deleteByQuestionId(id);
        questionRepo.deleteById(id);
    }

    /** 保存试卷：与 adminDeletePaper / assemble 一致，需要事务保证多步写入原子 */
    @Transactional
    public ExamPaper adminSavePaper(ExamPaper p) {
        if (p.getTitle() == null || p.getTitle().isBlank()) throw new IllegalArgumentException("试卷标题不能为空");
        if (p.getStatus() == null || p.getStatus().isBlank()) p.setStatus("PUBLISHED");
        // #7 及格线合法性：0 <= passScore <= totalScore（totalScore 为 0 时跳过比较，交由 assemble 回写后校验）
        if (p.getPassScore() < 0) {
            throw new IllegalArgumentException("及格线不能为负数");
        }
        if (p.getTotalScore() > 0 && p.getPassScore() > p.getTotalScore()) {
            throw new IllegalArgumentException("及格线不能高于试卷总分");
        }
        return paperRepo.save(p);
    }

    public List<ExamPaper> adminListPapers() {
        return paperRepo.findAllByOrderByCreatedAtDesc();
    }

    /** 删除试卷：连带清理试卷题目与作答记录，整体需事务保证原子性 */
    @Transactional
    public void adminDeletePaper(Long id) {
        pqRepo.deleteByPaperId(id);
        // 按试卷批量清理作答明细与考试记录（单条 DELETE），替代原「全表扫描 + 逐条 N+1 删除」
        answerRepo.deleteByPaperId(id);
        recordRepo.deleteByPaperId(id);
        paperRepo.deleteById(id);
    }

    /** 组卷：替换试卷题目与分值，并回写总分/题量 */
    /** 组卷：先清空旧题目再写入新题目，需事务保证不出现半套卷 */
    @Transactional
    public ExamPaper assemble(Long paperId, List<PaperItem> items) {
        ExamPaper paper = paperRepo.findById(paperId).orElseThrow(() -> new IllegalArgumentException("试卷不存在"));
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("试卷至少包含一道题");
        pqRepo.deleteByPaperId(paperId);
        int total = 0, sort = 0;
        for (PaperItem it : items) {
            // #7 校验题目存在且分值合法
            if (it.questionId == null || !questionRepo.existsById(it.questionId)) {
                throw new IllegalArgumentException("题目不存在：id=" + it.questionId);
            }
            if (it.score < 0) throw new IllegalArgumentException("题目分值不能为负");
            ExamPaperQuestion pq = new ExamPaperQuestion();
            pq.setPaperId(paperId);
            pq.setQuestionId(it.questionId);
            pq.setScore(it.score);
            pq.setSortOrder(sort++);
            pqRepo.save(pq);
            total += it.score;
        }
        paper.setTotalScore(total);
        paper.setQuestionCount(items.size());
        return paperRepo.save(paper);
    }

    // ===================== 内部 =====================

    private boolean judge(String type, List<String> userAns, List<String> correct) {
        if (userAns == null || userAns.isEmpty()) return false;
        if ("SINGLE".equals(type) || "JUDGE".equals(type)) {
            return userAns.size() == 1 && correct.contains(userAns.get(0));
        }
        if ("MULTI".equals(type)) {
            if (userAns.size() != correct.size()) return false;
            return new HashSet<>(userAns).equals(new HashSet<>(correct));
        }
        return false; // FILL / ESSAY 主观题不自动判分
    }

    // ---- 提交 / 组卷 DTO ----
    public static class AnswerItem {
        public Long questionId;
        public List<String> answer;
    }

    public static class PaperItem {
        public Long questionId;
        public int score;
    }
}
