/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.*;
import com.kb.kbplatform.repository.*;
import com.kb.kbplatform.security.HtmlSanitizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

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
    private final SysSettingRepository settingRepo;
    private final PracticeLogRepository practiceLogRepo;
    private final UserRepository userRepo;

    public ExamService(QuestionRepository questionRepo, QuestionOptionRepository optionRepo,
                       ExamPaperRepository paperRepo, ExamPaperQuestionRepository pqRepo,
                       ExamRecordRepository recordRepo, ExamAnswerRepository answerRepo,
                       AuthService authService, CourseRepository courseRepo,
                       CourseChapterRepository chapterRepo, LearningProgressRepository progressRepo,
                       CourseService courseService, NotificationService notificationService,
                       SysSettingRepository settingRepo,
                       PracticeLogRepository practiceLogRepo,
                       UserRepository userRepo,
                       org.springframework.transaction.support.TransactionTemplate txTemplate,
                       com.kb.kbplatform.service.KvStore kvStore) {
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
        this.settingRepo = settingRepo;
        this.notificationService = notificationService;
        this.practiceLogRepo = practiceLogRepo;
        this.userRepo = userRepo;
        this.txTemplate = txTemplate;
        this.kvStore = kvStore;
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
        // 0 题试卷允许保存但不允许开考：开始只会得到一条 0 分记录（简介与实际不符也是它引起的）
        if (pqRepo.countByPaperId(paperId) == 0) {
            throw new ResponseStatusException(BAD_REQUEST, "该试卷还没有题目，请联系管理员组卷");
        }
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
            m.put("content", HtmlSanitizer.sanitize(q.getContent()));
            m.put("score", pqMap.get(q.getId()).getScore());
            List<Map<String, Object>> opts = new ArrayList<>();
            for (QuestionOption o : optionRepo.findByQuestionIdOrderByLabelAsc(q.getId())) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("id", o.getId());
                om.put("label", o.getLabel());
                om.put("content", HtmlSanitizer.sanitize(o.getContent()));
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
            // 答案只在交卷时落库，进行中的记录必然一题未答：直接作废，不要结算成 0 分。
            // 结算会在「重新开始同一份试卷」时凭空多出一条 0 分未及格记录（N2/N57），
            // 还白占十次上限里的一次；作废后用户可以正常重考。
            // 真有作答痕迹（历史版本可能边答边存）才走超时结算，保证不丢已判分的成绩。
            if (answerRepo.findByRecordId(old.getId()).isEmpty()) {
                recordRepo.delete(old);
                continue;
            }
            settleExpired(old);
        }
        // 考试次数上限（外部审查 2026-10-11）：同一试卷不限次重考可反复刷通过记录/积分，
        // 上限 10 次。**必须在复用/结算之后**判断（审查 r12）：否则 9 次已完成 + 1 条进行中
        // 会被直接拒绝，而那条进行中本可以继续作答。
        if (recordRepo.countByUserIdAndPaperId(userId, paperId) >= 10) {
            throw new ResponseStatusException(BAD_REQUEST, "该试卷考试次数已达上限（10 次），如需重考请联系管理员");
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
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    protected void settleExpired(ExamRecord rec) {
        // 外部审查 2026-10-11：protected + 同类自调用，@Transactional 注解不被 AOP 拦截——
        // 改用 TransactionTemplate 显式包裹，保证结算原子性（将来内部加多步写也不裸奔）
        txTemplate.executeWithoutResult(st -> {
            rec.setScore(0);
            rec.setPassed(false);
            rec.setSubmittedAt(LocalDateTime.now());
            if (rec.getStartedAt() != null) {
                long sec = Math.max(0, Duration.between(rec.getStartedAt(), rec.getSubmittedAt()).getSeconds());
                // 用时按试卷时长封顶（+1 分钟宽限）：超时记录下次 start 才结算，
                // 直接算「现在 - 开始时间」会写出 983 分钟这种明显没意义的数字
                Integer limitMin = paperRepo.findById(rec.getPaperId()).map(ExamPaper::getDurationMin).orElse(null);
                long cap = (limitMin != null && limitMin > 0) ? limitMin * 60L + 60 : sec;
                rec.setDurationSec((int) Math.min(sec, cap));
            }
            recordRepo.save(rec);
        });
    }

    /** 提交试卷并自动阅卷（客观题判分，主观题记 0 待人工）；首次提交且及格时奖励考试积分。
     *  幂等 + 限时（F07/F08）：已提交直接返回首次结果；超时（含宽限）拒绝；并发提交只有一条能判分发分。 */
    @Transactional(rollbackFor = Exception.class)
    public ExamRecord submit(Long userId, Long recordId, List<AnswerItem> items) {
        ExamRecord rec = recordRepo.findById(recordId).orElseThrow(() -> new IllegalArgumentException("考试记录不存在"));
        if (!rec.getUserId().equals(userId)) throw new ResponseStatusException(FORBIDDEN, "无权提交该考试");
        fillPaperFields(rec);   // 标题/题数不落库，交卷响应与积分文案都要用（N61）
        // 已经是已提交状态：重复提交（用户狂点/网络重试）直接回放首次结果，不重复判分、不重复发分
        if (rec.getSubmittedAt() != null) return rec;
        // 服务端限时：超过 deadline + 宽限一律拒绝；未交的记录会在下次 start 时按超时结算关闭
        if (expired(rec)) {
            throw new ResponseStatusException(CONFLICT, "考试已超时，本次提交未生效；可重新开始考试");
        }
        // 原子抢占提交权：两个并发请求只有一个能把 submitted_at 从 NULL 改掉，另一个回放首次结果
        if (recordRepo.claimSubmit(recordId, LocalDateTime.now()) == 0) {
            ExamRecord again = recordRepo.findById(recordId).orElse(rec);
            fillPaperFields(again);
            return again;
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
        int pendingCount = 0;
        for (ExamPaperQuestion pq : pqs) {
            Question q = qMap.get(pq.getQuestionId());
            // 题目在组卷后被删除（管理员清理题库）时不能让整次交卷 500，该题按 0 分跳过
            if (q == null) continue;
            // 主观题（填空/简答）交卷时不判分：标记 pending，等管理员在「主观题阅卷」给分
            boolean manual = "FILL".equals(q.getType()) || "ESSAY".equals(q.getType());
            List<String> correct = new ArrayList<>();
            for (QuestionOption o : optMap.get(q.getId())) if (o.isCorrect()) correct.add(o.getLabel());
            List<String> userAns = ansMap.getOrDefault(q.getId(), List.of());
            boolean ok = !manual && judge(q.getType(), userAns, correct);
            int qScore = ok ? pq.getScore() : 0;
            total += qScore;
            if (manual) pendingCount++;

            ExamAnswer ea = new ExamAnswer();
            ea.setRecordId(recordId);
            ea.setQuestionId(q.getId());
            ea.setAnswer(String.join(",", userAns));
            ea.setCorrect(ok);
            ea.setScore(qScore);
            ea.setPending(manual);
            answerRepo.save(ea);
        }
        rec.setScore(total);
        rec.setPendingCount(pendingCount);
        // 有待阅卷主观题时成绩为「暂定」：不判通过、不发积分/证书，等阅卷完成后重算
        boolean passed = pendingCount == 0
                && total >= paperRepo.findById(rec.getPaperId()).map(ExamPaper::getPassScore).orElse(60);
        rec.setPassed(passed);
        // 是否首次通过：用于结业证书通知去重（此时 rec 尚未持久化，exists 不含本次）
        boolean firstPass = passed && !recordRepo.existsByUserIdAndPaperIdAndPassedTrue(userId, rec.getPaperId());
        rec.setSubmittedAt(LocalDateTime.now());
        rec.setDurationSec((int) Duration.between(rec.getStartedAt(), rec.getSubmittedAt()).getSeconds());
        ExamRecord saved = recordRepo.save(rec);
        // 只在「首次通过」时发分与颁证：补考/重考反复通过会无限刷考试积分（F15）。
        // 补考（此前未通过）首次及格时 firstPass 仍为 true，不影响正常激励。
        if (passed && firstPass) {
            authService.awardPoints(userId, AuthService.EXAM_PASS_POINTS, "EXAM",
                    "通过考试《" + (rec.getPaperTitle() != null ? rec.getPaperTitle() : "试卷") + "》");
            // 该试卷绑定的课程已学完章节 → 颁发结业证书并通知学员
            Course course = courseRepo.findByExamPaperId(rec.getPaperId()).stream().findFirst().orElse(null);
            if (course != null && courseService.certificateOf(userId, course.getId()) != null) {
                notificationService.notifyCertificate(userId, "系统",
                        "恭喜！您已完成课程《" + course.getTitle() + "》并获得结业证书，可在个人中心「学习档案 → 我的结业证书」中查看与下载");
            }
        }
        return saved;
    }

    /** 登录：我的考试成绩 */
    /**
     * 放弃进行中的考试：直接删掉这条记录。
     * 没有这个入口时，用户点开就退出会留下一条 0 分未及格记录，还占掉 10 次上限里的一次。
     * 已交卷的记录不允许放弃（那会变成刷分/抹成绩）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void abandon(Long userId, Long recordId) {
        ExamRecord rec = recordRepo.findById(recordId)
                .orElseThrow(() -> new IllegalArgumentException("考试记录不存在"));
        if (!java.util.Objects.equals(rec.getUserId(), userId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能放弃自己的考试");
        }
        if (rec.getSubmittedAt() != null) {
            throw new ResponseStatusException(BAD_REQUEST, "已交卷的考试不能放弃");
        }
        recordRepo.delete(rec);
    }

    public List<ExamRecord> myRecords(Long userId) {
        // 只展示已交卷的记录：进行中的记录还没作答，列表里按 0 分/未及格展示会让人以为「考砸了」，
        // 实际只是没交卷（下次点开始会复用同一条进行中记录）
        List<ExamRecord> list = recordRepo.findByUserIdOrderByStartedAtDesc(userId).stream()
                .filter(r -> r.getSubmittedAt() != null)
                .toList();
        if (list.isEmpty()) return list;
        // paperTitle / questionCount 在实体里是 @Transient（不落库），列表页要显示试卷名，
        // 这里一次性批量补齐，避免逐条查试卷的 N+1。缺了就退化成前端显示的「考试 #id」。
        Set<Long> pids = new LinkedHashSet<>();
        for (ExamRecord r : list) if (r.getPaperId() != null) pids.add(r.getPaperId());
        Map<Long, ExamPaper> papers = new LinkedHashMap<>();
        for (ExamPaper p : paperRepo.findAllById(pids)) papers.put(p.getId(), p);
        // pendingCount 同样是 @Transient：一次性批量统计，避免逐条 count
        Map<Long, Integer> pendingMap = new HashMap<>();
        List<Long> rids = new ArrayList<>();
        for (ExamRecord r : list) rids.add(r.getId());
        for (Object[] row : answerRepo.countPendingByRecordIds(rids)) {
            pendingMap.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        for (ExamRecord r : list) {
            r.setPendingCount(pendingMap.getOrDefault(r.getId(), 0));
            ExamPaper p = papers.get(r.getPaperId());
            if (p == null) continue;
            if (r.getPaperTitle() == null || r.getPaperTitle().trim().isEmpty()) r.setPaperTitle(p.getTitle());
            if (r.getQuestionCount() <= 0) r.setQuestionCount(p.getQuestionCount());
        }
        return list;
    }

    /** paperTitle / questionCount 是 @Transient（不落库），单条读取时必须从试卷补回，
     *  否则成绩详情、交卷响应会返回 paperTitle=null / questionCount=0（N61）。 */
    private void fillPaperFields(ExamRecord rec) {
        if (rec == null || rec.getPaperId() == null) return;
        ExamPaper p = paperRepo.findById(rec.getPaperId()).orElse(null);
        if (p == null) return;
        if (rec.getPaperTitle() == null || rec.getPaperTitle().trim().isEmpty()) rec.setPaperTitle(p.getTitle());
        if (rec.getQuestionCount() <= 0) rec.setQuestionCount(p.getQuestionCount());
    }

    /** 登录：成绩详情（逐题回顾，含正确答案） */
    public Map<String, Object> recordDetail(Long userId, Long recordId) {
        ExamRecord rec = recordRepo.findById(recordId).orElseThrow(() -> new IllegalArgumentException("考试记录不存在"));
        if (!rec.getUserId().equals(userId)) throw new ResponseStatusException(FORBIDDEN, "无权查看该成绩");
        fillPaperFields(rec);
        List<ExamAnswer> eas = answerRepo.findByRecordId(recordId);
        List<Long> qids = new ArrayList<>();
        for (ExamAnswer ea : eas) qids.add(ea.getQuestionId());
        List<Question> qs = questionRepo.findByIdIn(qids);
        Map<Long, Question> qMap = new LinkedHashMap<>();
        for (Question q : qs) qMap.put(q.getId(), q);
        Map<Long, List<QuestionOption>> optMap = new LinkedHashMap<>();
        for (Question q : qs) optMap.put(q.getId(), optionRepo.findByQuestionIdOrderByLabelAsc(q.getId()));
        // 每题满分：主观题要显示「得分 / 满分」，阅卷页也用同一份
        Map<Long, Integer> maxScoreMap = new LinkedHashMap<>();
        for (ExamPaperQuestion pq : pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(rec.getPaperId())) {
            maxScoreMap.put(pq.getQuestionId(), pq.getScore());
        }
        rec.setPendingCount((int) answerRepo.countByRecordIdAndPendingTrue(recordId));

        // 成绩回顾的答案披露策略（外部审查 2026-10-11）：未交卷的进行中记录不下发
        // 正确答案/解析/判定，防止「边考边拉成绩接口对题」。
        boolean reveal = rec.getSubmittedAt() != null;
        List<Map<String, Object>> items = new ArrayList<>();
        for (ExamAnswer ea : eas) {
            Question q = qMap.get(ea.getQuestionId());
            // 题目已被删除时跳过该条作答，避免整页成绩回顾 500（历史记录仍应可看）
            if (q == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionId", q.getId());
            m.put("type", q.getType());
            m.put("content", HtmlSanitizer.sanitize(q.getContent()));
            m.put("userAnswer", ea.getAnswer());
            m.put("pending", ea.isPending());
            m.put("maxScore", maxScoreMap.getOrDefault(q.getId(), 0));
            if (reveal) {
                m.put("correct", ea.isCorrect());
                m.put("score", ea.getScore());
                m.put("analysis", HtmlSanitizer.sanitize(q.getAnalysis()));
            }
            List<Map<String, Object>> opts = new ArrayList<>();
            for (QuestionOption o : optMap.get(q.getId())) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("label", o.getLabel());
                om.put("content", HtmlSanitizer.sanitize(o.getContent()));
                if (reveal) om.put("isCorrect", o.isCorrect());
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

    // ===================== 主观题阅卷（管理员） =====================

    /** 待阅卷列表：有 pending 作答的考试记录（按提交时间倒序） */
    public List<Map<String, Object>> adminPendingGrading() {
        List<Long> rids = answerRepo.findRecordIdsWithPending();
        if (rids.isEmpty()) return List.of();
        List<ExamRecord> recs = new ArrayList<>(recordRepo.findAllById(rids));
        recs.sort(Comparator.comparing(
                (ExamRecord r) -> r.getSubmittedAt() == null ? LocalDateTime.MIN : r.getSubmittedAt()).reversed());

        Set<Long> pids = new LinkedHashSet<>();
        for (ExamRecord r : recs) if (r.getPaperId() != null) pids.add(r.getPaperId());
        Map<Long, ExamPaper> papers = new LinkedHashMap<>();
        for (ExamPaper p : paperRepo.findAllById(pids)) papers.put(p.getId(), p);
        Map<Long, String> names = userNameMap(recs);
        Map<Long, Integer> pendingMap = new HashMap<>();
        for (Object[] row : answerRepo.countPendingByRecordIds(rids)) {
            pendingMap.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (ExamRecord r : recs) {
            ExamPaper p = papers.get(r.getPaperId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("recordId", r.getId());
            m.put("userId", r.getUserId());
            m.put("user", names.getOrDefault(r.getUserId(), "用户#" + r.getUserId()));
            m.put("paperId", r.getPaperId());
            m.put("paperTitle", p == null ? ("试卷 #" + r.getPaperId()) : p.getTitle());
            m.put("score", r.getScore());
            m.put("pendingCount", pendingMap.getOrDefault(r.getId(), 0));
            m.put("submittedAt", r.getSubmittedAt());
            out.add(m);
        }
        return out;
    }

    /** 阅卷详情：记录 + 全部作答（题干 / 学员答案 / 参考答案 / 解析 / 满分） */
    public Map<String, Object> adminGradingDetail(Long recordId) {
        ExamRecord rec = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "考试记录不存在"));
        List<ExamAnswer> eas = answerRepo.findByRecordId(recordId);
        List<Long> qids = new ArrayList<>();
        for (ExamAnswer ea : eas) qids.add(ea.getQuestionId());
        Map<Long, Question> qMap = new LinkedHashMap<>();
        for (Question q : questionRepo.findByIdIn(qids)) qMap.put(q.getId(), q);
        Map<Long, Integer> maxScoreMap = new LinkedHashMap<>();
        for (ExamPaperQuestion pq : pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(rec.getPaperId())) {
            maxScoreMap.put(pq.getQuestionId(), pq.getScore());
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (ExamAnswer ea : eas) {
            Question q = qMap.get(ea.getQuestionId());
            if (q == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionId", q.getId());
            m.put("type", q.getType());
            m.put("content", HtmlSanitizer.sanitize(q.getContent()));
            m.put("userAnswer", ea.getAnswer());
            m.put("pending", ea.isPending());
            m.put("score", ea.getScore());
            m.put("maxScore", maxScoreMap.getOrDefault(q.getId(), 0));
            m.put("comment", ea.getComment());
            m.put("analysis", HtmlSanitizer.sanitize(q.getAnalysis()));
            List<Map<String, Object>> refs = new ArrayList<>();
            for (QuestionOption o : optionRepo.findByQuestionIdOrderByLabelAsc(q.getId())) {
                if (!o.isCorrect()) continue;
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("label", o.getLabel());
                om.put("content", HtmlSanitizer.sanitize(o.getContent()));
                refs.add(om);
            }
            m.put("correctAnswer", refs);
            items.add(m);
        }
        rec.setPendingCount((int) answerRepo.countByRecordIdAndPendingTrue(recordId));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("record", rec);
        res.put("user", userNameMap(List.of(rec)).getOrDefault(rec.getUserId(), "用户#" + rec.getUserId()));
        res.put("items", items);
        return res;
    }

    /** 提交阅卷：给主观题打分（0 ~ 该题满分），全部阅完后重算总分 / 通过状态 / 首次通过发分 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adminGrade(Long recordId, List<GradeItem> items) {
        ExamRecord rec = recordRepo.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "考试记录不存在"));
        if (rec.getSubmittedAt() == null)
            throw new ResponseStatusException(BAD_REQUEST, "该考试尚未交卷，不能阅卷");
        if (items == null || items.isEmpty())
            throw new ResponseStatusException(BAD_REQUEST, "请至少给一道题打分");

        Map<Long, ExamAnswer> byQ = new LinkedHashMap<>();
        for (ExamAnswer ea : answerRepo.findByRecordId(recordId)) byQ.put(ea.getQuestionId(), ea);
        Map<Long, Integer> maxScoreMap = new LinkedHashMap<>();
        for (ExamPaperQuestion pq : pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(rec.getPaperId())) {
            maxScoreMap.put(pq.getQuestionId(), pq.getScore());
        }

        int graded = 0;
        for (GradeItem it : items) {
            if (it == null || it.questionId == null) continue;
            ExamAnswer ea = byQ.get(it.questionId);
            if (ea == null) throw new ResponseStatusException(BAD_REQUEST, "题目不在本次考试中：" + it.questionId);
            if (!ea.isPending()) continue;   // 已阅过：忽略重复提交，避免覆盖首次给分
            int max = maxScoreMap.getOrDefault(it.questionId, 0);
            int sc = Math.max(0, Math.min(it.score == null ? 0 : it.score, max));
            ea.setScore(sc);
            ea.setPending(false);
            ea.setCorrect(sc > 0);
            ea.setComment(it.comment == null || it.comment.isBlank() ? null
                    : (it.comment.length() > 500 ? it.comment.substring(0, 500) : it.comment.trim()));
            answerRepo.save(ea);
            graded++;
        }

        // 重算：总分 = 所有作答得分之和；只有全部主观题都阅完才判定通过
        int total = 0;
        for (ExamAnswer ea : answerRepo.findByRecordId(recordId)) total += ea.getScore();
        rec.setScore(total);
        long pending = answerRepo.countByRecordIdAndPendingTrue(recordId);
        rec.setPendingCount((int) pending);
        ExamPaper paper = paperRepo.findById(rec.getPaperId()).orElse(null);
        int passScore = paper == null ? 60 : paper.getPassScore();
        boolean passed = pending == 0 && total >= passScore;
        boolean firstPass = passed && !recordRepo.existsByUserIdAndPaperIdAndPassedTrue(rec.getUserId(), rec.getPaperId());
        rec.setPassed(passed);
        recordRepo.save(rec);

        if (passed && firstPass) {
            String title = paper != null ? paper.getTitle() : "试卷";
            authService.awardPoints(rec.getUserId(), AuthService.EXAM_PASS_POINTS, "EXAM", "通过考试《" + title + "》", "paper:" + rec.getPaperId());
            Course course = courseRepo.findByExamPaperId(rec.getPaperId()).stream().findFirst().orElse(null);
            if (course != null && courseService.certificateOf(rec.getUserId(), course.getId()) != null) {
                notificationService.notifyCertificate(rec.getUserId(), "系统",
                        "恭喜！您已完成课程《" + course.getTitle() + "》并获得结业证书，可在个人中心「学习档案 → 我的结业证书」中查看与下载");
            }
        }

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("recordId", recordId);
        res.put("graded", graded);
        res.put("score", total);
        res.put("passed", passed);
        res.put("pendingCount", (int) pending);
        return res;
    }

    /** 阅卷提交项 */
    public static class GradeItem {
        public Long questionId;
        public Integer score;
        public String comment;
    }

    private Map<Long, String> userNameMap(Collection<ExamRecord> recs) {
        Set<Long> ids = new LinkedHashSet<>();
        for (ExamRecord r : recs) ids.add(r.getUserId());
        Map<Long, String> out = new HashMap<>();
        for (User u : userRepo.findAllById(ids)) {
            String n = u.getDisplayName();
            out.put(u.getId(), (n == null || n.isBlank()) ? u.getUsername() : n);
        }
        return out;
    }

    // ===================== 管理员侧 =====================

    public List<Question> adminListQuestions(String category) {
        return (category == null || category.isBlank())
                ? questionRepo.findAllByOrderByCreatedAtDesc()
                : questionRepo.findByCategoryOrderByCreatedAtDesc(category);
    }

    /** 定向取单题（2026-10-13 审查 M9：单题更新不再全量拉题库） */
    public Question adminGetQuestion(Long id) {
        return questionRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "题目不存在"));
    }

    /** 单题 + 选项：编辑页要用（列表接口为了省流量不返回 options，以前编辑时选项是空的） */
    public Question adminGetQuestionWithOptions(Long id) {
        Question q = adminGetQuestion(id);
        q.setOptions(optionRepo.findByQuestionIdOrderByLabelAsc(id));
        return q;
    }

    /** 定向取单份试卷（同 M9） */
    public ExamPaper adminGetPaper(Long id) {
        ExamPaper p = paperRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "试卷不存在"));
        p.setQuestionCount((int) pqRepo.countByPaperId(id));   // @Transient，不补就是 0
        return p;
    }

    /** 保存题目：选项清理为派生删除（需事务），且多步写入需原子 */
    @Transactional(rollbackFor = Exception.class)
    public Question adminSaveQuestion(Question q, List<QuestionOption> options) {
        if (q.getContent() == null || q.getContent().isBlank()) throw new IllegalArgumentException("题干不能为空");
        // 题型统一规范成大写：导入的 CSV/人工填的可能是单/小写，前端按 SINGLE/MULTI/JUDGE/FILL 分支渲染，
        // 小写会落到默认分支渲染成文本框（外部测试遇到的「单选却给输入框」就是这么来的）
        q.setType(q.getType() == null || q.getType().isBlank() ? "SINGLE" : q.getType().trim().toUpperCase());
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
    @Transactional(rollbackFor = Exception.class)
    public void adminDeleteQuestion(Long id) {
        // 级联清理组卷关联并重算受影响试卷总分（2026-10-13 审查 H4）：
        // 此前只删题目与选项，ExamPaperQuestion 悬空——有外键时报 500，无外键时留脏数据且试卷总分虚高。
        List<ExamPaperQuestion> links = pqRepo.findByQuestionId(id);
        if (!links.isEmpty()) {
            java.util.Set<Long> paperIds = links.stream()
                    .map(ExamPaperQuestion::getPaperId).collect(java.util.stream.Collectors.toSet());
            pqRepo.deleteByQuestionId(id);
            for (Long pid : paperIds) {
                int total = pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(pid).stream()
                        .mapToInt(ExamPaperQuestion::getScore).sum();
                paperRepo.findById(pid).ifPresent(p -> {
                    p.setTotalScore(total);
                    paperRepo.save(p);
                });
            }
        }
        optionRepo.deleteByQuestionId(id);
        questionRepo.deleteById(id);
    }

    /** 保存试卷：与 adminDeletePaper / assemble 一致，需要事务保证多步写入原子 */
    @Transactional(rollbackFor = Exception.class)
    public ExamPaper adminSavePaper(ExamPaper p) {
        if (p.getTitle() == null || p.getTitle().isBlank()) throw new IllegalArgumentException("试卷标题不能为空");
        if (p.getStatus() == null || p.getStatus().isBlank()) p.setStatus("PUBLISHED");
        // 新建的试卷必然还没题目：直接按 PUBLISHED 落库就会变成「学生看到、点开始即 0 分」的空卷
        // （E2/N4 里那两份 0 题卷就是这么来的）。先落草稿，组完卷再发布。
        if (p.getId() == null && "PUBLISHED".equals(p.getStatus())) {
            p.setStatus("DRAFT");
        }
        // 0 题试卷发布出去就是「点开始即 0 分、结业闭环断裂」，实测已经踩过两次
        if ("PUBLISHED".equals(p.getStatus()) && p.getId() != null
                && pqRepo.countByPaperId(p.getId()) == 0) {
            throw new IllegalArgumentException("试卷还没有题目，不能发布；请先在「组卷」里选题");
        }
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
        List<ExamPaper> list = paperRepo.findAllByOrderByCreatedAtDesc();
        // ⚠️ questionCount 在实体里是 @Transient（不落库），这里不补就永远是 0 ——
        // 后台试卷列表此前一直显示「0 题」，即使组卷成功（2026-10-20 用户反馈）。
        for (ExamPaper p : list) {
            p.setQuestionCount((int) pqRepo.countByPaperId(p.getId()));
        }
        return list;
    }

    /** 删除试卷：连带清理试卷题目与作答记录，整体需事务保证原子性 */
    @Transactional(rollbackFor = Exception.class)
    public void adminDeletePaper(Long id) {
        pqRepo.deleteByPaperId(id);
        // 按试卷批量清理作答明细与考试记录（单条 DELETE），替代原「全表扫描 + 逐条 N+1 删除」
        answerRepo.deleteByPaperId(id);
        recordRepo.deleteByPaperId(id);
        paperRepo.deleteById(id);
    }

    /** 组卷：替换试卷题目与分值，并回写总分/题量 */
    /** 组卷：先清空旧题目再写入新题目，需事务保证不出现半套卷 */
    @Transactional(rollbackFor = Exception.class)
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

    // ===================== 练习模式（2026-10-21） =====================
    // 与考试的区别：不创建考试记录、不限时、不落库成绩；逐题判分，答案在判分时才下发，
    // 避免「一次性拿到整卷答案」（练习也需要防泄题的场景：同一份试卷还要考试用）。

    /** 练习题目 DTO：不含正确答案（答案只在 practiceCheck 返回） */
    private Map<String, Object> practiceQuestion(Question q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.getId());
        m.put("type", q.getType());
        m.put("content", HtmlSanitizer.sanitize(q.getContent()));
        m.put("category", q.getCategory());
        List<Map<String, Object>> opts = new ArrayList<>();
        for (QuestionOption o : optionRepo.findByQuestionIdOrderByLabelAsc(q.getId())) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("id", o.getId());
            om.put("label", o.getLabel());
            om.put("content", HtmlSanitizer.sanitize(o.getContent()));
            opts.add(om);
        }
        m.put("options", opts);
        return m;
    }

    // ---- 练习会话（2026-10-21 加固）：防止练习判分接口被当成「答案查询器」 ----
    private static final class PracticeSession {
        final Long userId;
        final Set<Long> qids;
        final long expireAt;
        PracticeSession(Long userId, Set<Long> qids, long expireAt) {
            this.userId = userId; this.qids = qids; this.expireAt = expireAt;
        }
    }
    /**
     * 练习会话改存 KvStore（2026-10-26）。
     *
     * 原来放进程内 Map：**后端一重启，正在做的练习就判不了分**（提示「会话已失效」），
     * 多实例部署也会因为会话落在别的实例而失效。现在 Redis 优先、内存兜底。
     * 序列化格式：{"u":userId,"q":[题号...],"e":过期毫秒}
     */
    private final com.kb.kbplatform.service.KvStore kvStore;
    private static final String SESSION_KEY = "practice:session:";
    private static final Map<Long, long[]> CHECK_RATE = new ConcurrentHashMap<>();
    /** 限流表清理计数器（每 200 次判分扫一次过期条目，避免每次请求都 O(n)） */
    private final java.util.concurrent.atomic.AtomicInteger rateSweep = new java.util.concurrent.atomic.AtomicInteger();
    private static final long SESSION_TTL_MS = 2 * 60 * 60 * 1000L;


    // ---------- 练习会话持久化（KvStore） ----------

    private void saveSession(String sid, PracticeSession s) {
        long ttlMs = Math.max(60_000L, s.expireAt - System.currentTimeMillis());
        StringBuilder qs = new StringBuilder();
        for (Long q : s.qids) {
            if (qs.length() > 0) qs.append(',');
            qs.append(q);
        }
        String json = "{\"u\":" + s.userId + ",\"q\":[" + qs + "],\"e\":" + s.expireAt + "}";
        kvStore.put(SESSION_KEY + sid, json, java.time.Duration.ofMillis(ttlMs));
    }

    private PracticeSession loadSession(String sid) {
        String json = kvStore.get(SESSION_KEY + sid);
        if (json == null || json.isBlank()) return null;
        try {
            // 后端用 Jackson 3（tools.jackson.*），没有 org.json（那是 Android 侧的库）
            Map<String, Object> o = tools.jackson.databind.json.JsonMapper.builder()
                    .build().readValue(json, Map.class);
            Set<Long> qids = new HashSet<>();
            Object arr = o.get("q");
            if (arr instanceof List<?> list) {
                for (Object v : list) {
                    if (v instanceof Number n) qids.add(n.longValue());
                }
            }
            return new PracticeSession(
                    ((Number) o.get("u")).longValue(),
                    qids,
                    ((Number) o.get("e")).longValue()
            );
        } catch (Exception e) {
            // 脏数据（例如 Redis 里残留了旧格式）直接当失效处理，不要抛给用户
            kvStore.delete(SESSION_KEY + sid);
            return null;
        }
    }

    /** 用户「进行中考试」涉及的所有题目：这些题在交卷前不允许通过练习接口取答案 */
    private Set<Long> runningExamQuestions(Long userId) {
        Set<Long> qids = new HashSet<>();
        for (ExamRecord r : recordRepo.findByUserIdOrderByStartedAtDesc(userId)) {
            if (r.getSubmittedAt() != null || expired(r)) continue;
            for (ExamPaperQuestion pq : pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(r.getPaperId()))
                qids.add(pq.getQuestionId());
        }
        return qids;
    }

    /** 判分限流：每用户每分钟最多 120 次（同时防止刷流水写爆 kb_practice_log） */
    private void checkPracticeRate(Long userId) {
        // 走 KvStore（Redis 可用时多实例共享，未配 Redis 时自动落到进程内存并发安全）：
        // 原来固定用进程内 Map，多实例部署时每个实例各算各的，等于把上限放大 N 倍（白盒 T10）。
        if (!kvStore.allowSliding("practice:rate:" + userId, 120, 60_000L)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "练习判分太频繁，请稍后再试");
        }
    }

    /**
     * 开启练习会话：返回 sessionId + 题目（不含答案）。
     * 判分时必须带上 sessionId，且题目必须在本次会话内——否则练习接口就是「按 id 查答案」。
     */
    public Map<String, Object> startPractice(Long userId, Long paperId, String category, Integer count) {
        Set<Long> running = runningExamQuestions(userId);
        List<Map<String, Object>> qs = new ArrayList<>();
        ExamPaper paper = null;

        if (paperId != null) {
            paper = paperRepo.findById(paperId).orElseThrow(() -> new IllegalArgumentException("试卷不存在"));
            if (!"PUBLISHED".equals(paper.getStatus()))
                throw new ResponseStatusException(FORBIDDEN, "该试卷未发布");
            if (Boolean.FALSE.equals(paper.getPracticeEnabled()))
                throw new ResponseStatusException(FORBIDDEN, "该试卷未开启模拟练习");
            boolean inExam = recordRepo.findByUserIdOrderByStartedAtDesc(userId).stream()
                    .anyMatch((r) -> r.getSubmittedAt() == null && !expired(r) && paperId.equals(r.getPaperId()));
            if (inExam) throw new ResponseStatusException(FORBIDDEN, "该试卷正在考试中，交卷后再练习");
            List<ExamPaperQuestion> pqs = pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(paperId);
            List<Long> ids = new ArrayList<>();
            for (ExamPaperQuestion pq : pqs) ids.add(pq.getQuestionId());
            Map<Long, Question> qMap = new LinkedHashMap<>();
            for (Question q : questionRepo.findByIdIn(ids)) qMap.put(q.getId(), q);
            for (ExamPaperQuestion pq : pqs) {
                Question q = qMap.get(pq.getQuestionId());
                if (q != null) qs.add(practiceQuestion(q));
            }
        } else {
            int n = Math.min(Math.max(count == null ? 10 : count, 1), 50);
            String cat = category == null ? "" : category.trim();
            List<Question> pool = cat.isEmpty()
                    ? questionRepo.findAllByOrderByCreatedAtDesc()
                    : questionRepo.findByCategoryOrderByCreatedAtDesc(cat);
            if (pool.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "题库里没有可用题目");
            // 跳过正在考的题：随机练习也不能把考试答案喂出来
            List<Question> usable = new ArrayList<>();
            for (Question q : pool) if (!running.contains(q.getId())) usable.add(q);
            if (usable.isEmpty())
                throw new ResponseStatusException(FORBIDDEN, "题库中的题目正在考试中使用，请交卷后再练习");
            Collections.shuffle(usable, new Random());
            for (Question q : usable.subList(0, Math.min(n, usable.size()))) qs.add(practiceQuestion(q));
        }

        long now = System.currentTimeMillis();
        // 过期由 TTL 负责（Redis 到期自动清、内存兜底也按 TTL 判），不再需要手工扫表与上限淘汰
        Set<Long> qids = new HashSet<>();
        for (Map<String, Object> m : qs) qids.add(((Number) m.get("id")).longValue());
        String sid = UUID.randomUUID().toString().replace("-", "");
        saveSession(sid, new PracticeSession(userId, qids, now + SESSION_TTL_MS));

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("sessionId", sid);
        res.put("paper", paper);
        res.put("questions", qs);
        return res;
    }

    /** 练习：按试卷取题（无需 start，不落记录） */
    public Map<String, Object> practicePaper(Long paperId) {
        ExamPaper paper = paperRepo.findById(paperId).orElseThrow(() -> new IllegalArgumentException("试卷不存在"));
        if (!"PUBLISHED".equals(paper.getStatus()))
            throw new ResponseStatusException(FORBIDDEN, "该试卷未发布");
        // 管理员可以关闭某份试卷的模拟练习（null 视为允许，兼容老数据）
        if (Boolean.FALSE.equals(paper.getPracticeEnabled()))
            throw new ResponseStatusException(FORBIDDEN, "该试卷未开启模拟练习");
        List<ExamPaperQuestion> pqs = pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(paperId);
        List<Long> ids = new ArrayList<>();
        for (ExamPaperQuestion pq : pqs) ids.add(pq.getQuestionId());
        Map<Long, Question> qMap = new LinkedHashMap<>();
        for (Question q : questionRepo.findByIdIn(ids)) qMap.put(q.getId(), q);
        List<Map<String, Object>> qs = new ArrayList<>();
        for (ExamPaperQuestion pq : pqs) {
            Question q = qMap.get(pq.getQuestionId());
            if (q == null) continue;   // 组卷后题目被删，跳过而不是 500
            qs.add(practiceQuestion(q));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("paper", paper);
        res.put("questions", qs);
        return res;
    }

    /** 练习：题库随机抽题（可按分类），不落记录 */
    public Map<String, Object> practiceRandom(String category, Integer count) {
        int n = Math.min(Math.max(count == null ? 10 : count, 1), 50);
        String cat = category == null ? "" : category.trim();
        List<Question> pool = cat.isEmpty()
                ? questionRepo.findAllByOrderByCreatedAtDesc()
                : questionRepo.findByCategoryOrderByCreatedAtDesc(cat);
        if (pool.isEmpty())
            throw new ResponseStatusException(BAD_REQUEST, "题库里没有可用题目");
        List<Question> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, new Random());
        List<Map<String, Object>> qs = new ArrayList<>();
        for (Question q : shuffled.subList(0, Math.min(n, shuffled.size()))) qs.add(practiceQuestion(q));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("questions", qs);
        res.put("total", qs.size());
        return res;
    }

    /** 练习：单题判分，返回正确答案与解析（主观题不自动判分，只给参考答案）
     *  userId 非空时落一条练习流水，用于错题本与正确率统计（2026-10-21）。 */
    public Map<String, Object> practiceCheck(Long userId, String sessionId, Long questionId, List<String> answer) {
        checkPracticeRate(userId);
        // 会话校验（审查 r11 High）：没有会话就只能按「非考试题」兜底，
        // 否则任意 questionId 都能换出答案，考试防泄题形同虚设
        if (sessionId != null && !sessionId.isBlank()) {
            PracticeSession s = loadSession(sessionId);
            if (s == null) throw new ResponseStatusException(BAD_REQUEST, "练习会话已失效，请重新开始练习");
            if (s.expireAt < System.currentTimeMillis()) {
                kvStore.delete(SESSION_KEY + sessionId);
                throw new ResponseStatusException(BAD_REQUEST, "练习会话已过期，请重新开始练习");
            }
            if (!s.userId.equals(userId))
                throw new ResponseStatusException(FORBIDDEN, "无权使用该练习会话");
            if (!s.qids.contains(questionId))
                throw new ResponseStatusException(BAD_REQUEST, "该题不在本次练习范围内");
        } else if (runningExamQuestions(userId).contains(questionId)) {
            throw new ResponseStatusException(FORBIDDEN, "考试进行中，暂不能查看该题答案");
        }
        Question q = questionRepo.findById(questionId).orElseThrow(() -> new IllegalArgumentException("题目不存在"));
        List<QuestionOption> opts = optionRepo.findByQuestionIdOrderByLabelAsc(questionId);
        List<String> correct = new ArrayList<>();
        List<Map<String, Object>> correctList = new ArrayList<>();
        for (QuestionOption o : opts) {
            if (!o.isCorrect()) continue;
            correct.add(o.getLabel());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("label", o.getLabel());
            m.put("content", HtmlSanitizer.sanitize(o.getContent()));
            correctList.add(m);
        }
        List<String> ans = answer == null ? List.of() : answer;
        boolean manual = "FILL".equals(q.getType()) || "ESSAY".equals(q.getType());
        boolean ok = !manual && judge(q.getType(), ans, correct);
        if (userId != null) recordPractice(userId, questionId, ok, manual, ans);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("correct", ok);
        res.put("manual", manual);
        res.put("type", q.getType());
        res.put("correctAnswer", correctList);
        res.put("analysis", HtmlSanitizer.sanitize(q.getAnalysis()));
        return res;
    }

    /** 写练习流水；主观题不判分，也就没有对错，不记（否则错题本会被主观题塞满） */
    private void recordPractice(Long userId, Long questionId, boolean correct, boolean manual, List<String> ans) {
        if (manual) return;
        try {
            PracticeLog log = new PracticeLog();
            log.setUserId(userId);
            log.setQuestionId(questionId);
            log.setCorrect(correct);
            String joined = String.join(",", ans);
            log.setAnswer(joined.length() > 500 ? joined.substring(0, 500) : joined);
            practiceLogRepo.save(log);
        } catch (Exception ignore) {
            // 练习流水只是辅助数据，写失败不能影响判分
        }
    }

    /** 练习统计：累计答题 / 正确 / 正确率 / 待复习错题数 */
    public Map<String, Object> practiceStats(Long userId) {
        long total = practiceLogRepo.countByUserId(userId);
        long correct = practiceLogRepo.countByUserIdAndCorrectTrue(userId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total", total);
        res.put("correct", correct);
        res.put("rate", total > 0 ? Math.round(correct * 100.0 / total) : 0);
        res.put("wrong", wrongCount(userId));
        return res;
    }

    /** 只数「最近一次判错」的题，不组装题干/选项（审查 r11 Medium：统计接口原来把整份错题都查出来） */
    private int wrongCount(Long userId) {
        Set<Long> handled = new HashSet<>();
        int n = 0;
        for (PracticeLog l : practiceLogRepo.findTop300ByUserIdOrderByIdDesc(userId)) {
            if (!handled.add(l.getQuestionId())) continue;
            if (!l.isCorrect()) n++;
            if (n >= 100) break;
        }
        return n;
    }

    /**
     * 错题本：每道题只看最近一次作答，最近一次答错的才算「待复习」。
     * 答对即自动移出（不需要学员手动清理），点「已掌握」也可强制移除。
     */
    public List<Map<String, Object>> wrongQuestions(Long userId) {
        List<PracticeLog> logs = practiceLogRepo.findTop300ByUserIdOrderByIdDesc(userId);
        Set<Long> seen = new LinkedHashSet<>();
        Set<Long> handled = new HashSet<>();
        Map<Long, String> lastAnswer = new HashMap<>();
        for (PracticeLog l : logs) {
            if (!handled.add(l.getQuestionId())) continue;   // 只认每题最近一次作答
            if (l.isCorrect()) continue;
            seen.add(l.getQuestionId());
            lastAnswer.put(l.getQuestionId(), l.getAnswer());
            if (seen.size() >= 100) break;
        }
        if (seen.isEmpty()) return List.of();
        Map<Long, Question> qMap = new LinkedHashMap<>();
        for (Question q : questionRepo.findByIdIn(new ArrayList<>(seen))) qMap.put(q.getId(), q);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long qid : seen) {
            Question q = qMap.get(qid);
            if (q == null) continue;   // 题目已被删
            Map<String, Object> m = practiceQuestion(q);
            List<Map<String, Object>> correctList = new ArrayList<>();
            for (QuestionOption o : optionRepo.findByQuestionIdOrderByLabelAsc(qid)) {
                if (!o.isCorrect()) continue;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("label", o.getLabel());
                c.put("content", HtmlSanitizer.sanitize(o.getContent()));
                correctList.add(c);
            }
            m.put("correctAnswer", correctList);
            m.put("analysis", HtmlSanitizer.sanitize(q.getAnalysis()));
            m.put("lastAnswer", lastAnswer.get(qid));
            out.add(m);
        }
        return out;
    }

    /** 移除某题错题记录（学员自认已掌握） */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> clearWrong(Long userId, Long questionId) {
        long n = practiceLogRepo.deleteByUserIdAndQuestionId(userId, questionId);
        return Map.of("ok", true, "removed", n);
    }

    // ===================== 固定模拟卷（2026-10-21） =====================
    // 改为试卷自身属性 mockEnabled：管理员在试卷列表的操作里直接「设为模拟卷」，
    // 全站最多一份（设置时自动清掉其它试卷的标记）。
    // 兼容旧版：老配置存在 kb_sys_setting(exam_mock_paper)，读到后自动迁移到列上。
    private static final String MOCK_KEY = "exam_mock_paper";

    /** 当前模拟卷（未配置或试卷已删 → paperId 为 null） */
    public Map<String, Object> mockPaper() {
        Map<String, Object> res = new LinkedHashMap<>();
        ExamPaper p = currentMock();
        res.put("paperId", p == null ? null : p.getId());
        res.put("title", p == null ? null : p.getTitle());
        res.put("questionCount", p == null ? 0 : (int) pqRepo.countByPaperId(p.getId()));
        return res;
    }

    private ExamPaper currentMock() {
        List<ExamPaper> list = paperRepo.findByMockEnabledTrueOrderByIdAsc();
        if (!list.isEmpty()) return list.get(0);
        // 旧版配置迁移：读到就写回列，然后删掉旧 key
        Long legacy = settingRepo.findByKey(MOCK_KEY).map((s) -> {
            try { return Long.valueOf(s.getValue().trim()); } catch (Exception e) { return null; }
        }).orElse(null);
        if (legacy == null) return null;
        ExamPaper p = paperRepo.findById(legacy).orElse(null);
        if (p == null) return null;
        try {
            p.setMockEnabled(Boolean.TRUE);
            paperRepo.save(p);
            settingRepo.findByKey(MOCK_KEY).ifPresent(settingRepo::delete);
        } catch (Exception ignore) {
            // 迁移失败不影响返回（GET 里写库，可能撞只读/并发）
        }
        return p;
    }

    /** 把某份试卷设为/取消全站模拟卷；设为 true 时清掉其它试卷的标记 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setPaperMock(Long paperId, boolean enabled) {
        ExamPaper target = paperRepo.findById(paperId).orElseThrow(() -> new IllegalArgumentException("试卷不存在"));
        if (enabled && !"PUBLISHED".equals(target.getStatus()))
            throw new IllegalArgumentException("只能把「已发布」的试卷设为模拟卷");
        if (enabled) {
            for (ExamPaper p : paperRepo.findByMockEnabledTrueOrderByIdAsc()) {
                if (!p.getId().equals(paperId)) { p.setMockEnabled(Boolean.FALSE); paperRepo.save(p); }
            }
        }
        target.setMockEnabled(enabled);
        paperRepo.save(target);
        settingRepo.findByKey(MOCK_KEY).ifPresent(settingRepo::delete);   // 清掉旧配置，避免两份来源打架
        return mockPaper();
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
