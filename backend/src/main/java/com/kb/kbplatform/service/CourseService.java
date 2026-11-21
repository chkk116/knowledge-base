/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Course;
import com.kb.kbplatform.domain.CourseChapter;
import com.kb.kbplatform.domain.ExamRecord;
import com.kb.kbplatform.domain.LearningProgress;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.CourseChapterRepository;
import com.kb.kbplatform.repository.CourseRepository;
import com.kb.kbplatform.repository.ExamRecordRepository;
import com.kb.kbplatform.repository.LearningProgressRepository;
import com.kb.kbplatform.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class CourseService {

    private final CourseRepository courseRepo;
    private final CourseChapterRepository chapterRepo;
    private final LearningProgressRepository progressRepo;
    private final UserRepository userRepo;
    private final AuthService authService;
    private final ExamRecordRepository recordRepo;

    public CourseService(CourseRepository courseRepo, CourseChapterRepository chapterRepo,
                         LearningProgressRepository progressRepo, UserRepository userRepo,
                         AuthService authService, ExamRecordRepository recordRepo) {
        this.courseRepo = courseRepo;
        this.chapterRepo = chapterRepo;
        this.progressRepo = progressRepo;
        this.userRepo = userRepo;
        this.authService = authService;
        this.recordRepo = recordRepo;
    }

    /** 公开：已发布课程列表（可按分类筛选） */
    public List<Course> listCourses(String category) {
        List<Course> list = (category == null || category.isBlank())
                ? courseRepo.findAllByStatusOrderBySortOrderAscIdAsc("PUBLISHED")
                : courseRepo.findAllByStatusAndCategoryOrderBySortOrderAscIdAsc("PUBLISHED", category);
        for (Course c : list) fillOwner(c);
        return list;
    }

    public List<String> categories() {
        Set<String> set = new LinkedHashSet<>();
        for (Course c : courseRepo.findAllByStatusOrderBySortOrderAscIdAsc("PUBLISHED")) {
            if (c.getCategory() != null && !c.getCategory().isBlank()) set.add(c.getCategory());
        }
        return new ArrayList<>(set);
    }

    /** 公开：课程详情 + 章节列表 */
    public Map<String, Object> getCourse(Long id) {
        Course course = courseRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("课程不存在"));
        fillOwner(course);
        List<CourseChapter> chapters = chapterRepo.findByCourseIdOrderBySortOrderAscIdAsc(id);
        // 历史数据读时净化（外部审查 2026-10-11）：写入侧净化只覆盖增量，存量章节仍带
        // 未清洗内容走前端 v-html。HtmlSanitizer 为白名单幂等清洗，读侧再过一遍即可
        // 兜住历史数据，无需迁移脚本；对已净化内容是恒等变换。
        for (CourseChapter ch : chapters) {
            ch.setContent(com.kb.kbplatform.security.HtmlSanitizer.sanitize(ch.getContent()));
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("course", course);
        res.put("chapters", chapters);
        return res;
    }

    /** 公开（登录）：某学员在某课程下各章节完成状态 */
    public List<Map<String, Object>> myProgress(Long userId, Long courseId) {
        List<CourseChapter> chapters = chapterRepo.findByCourseIdOrderBySortOrderAscIdAsc(courseId);
        List<LearningProgress> done = progressRepo.findByUserIdAndCourseId(userId, courseId);
        Set<Long> finished = new HashSet<>();
        for (LearningProgress p : done) if (p.isFinished()) finished.add(p.getChapterId());
        List<Map<String, Object>> res = new ArrayList<>();
        for (CourseChapter ch : chapters) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chapterId", ch.getId());
            m.put("title", ch.getTitle());
            m.put("finished", finished.contains(ch.getId()));
            res.add(m);
        }
        return res;
    }

    /** 登录：标记某章节完成（已存在则更新，幂等）；首次完成时奖励学习积分 */
    @Transactional(rollbackFor = Exception.class)
    public LearningProgress finishChapter(Long userId, Long courseId, Long chapterId) {
        if (!chapterRepo.findById(chapterId).map(c -> c.getCourseId().equals(courseId)).orElse(false)) {
            throw new IllegalArgumentException("章节不属于该课程");
        }
        LearningProgress p = progressRepo.findByUserIdAndChapterId(userId, chapterId)
                .orElseGet(() -> {
                    LearningProgress np = new LearningProgress();
                    np.setUserId(userId);
                    np.setCourseId(courseId);
                    np.setChapterId(chapterId);
                    return np;
                });
        // 首次完成判定交给条件更新：两个并发请求只有一条能把 finished=false 改成 true，
        // 另一条拿到 0 行就不加分（原来先读 isFinished 再保存，并发下会双领 +10，还可能写出重复行）
        boolean firstFinish;
        LearningProgress saved;
        if (p.getId() == null) {
            p.setFinished(true);
            p.setProgress(100);
            p.setLastAt(LocalDateTime.now());
            saved = progressRepo.save(p);
            firstFinish = true;
        } else {
            firstFinish = progressRepo.markFinished(userId, chapterId, LocalDateTime.now()) == 1;
            if (firstFinish) {
                p.setFinished(true);
                p.setProgress(100);
                p.setLastAt(LocalDateTime.now());
                saved = progressRepo.save(p);
            } else {
                saved = p;
            }
        }
        if (firstFinish) {
            CourseChapter ch = chapterRepo.findById(chapterId).orElse(null);
            String title = ch != null && ch.getTitle() != null ? ch.getTitle() : "章节";
            authService.awardPoints(userId, AuthService.COURSE_CHAPTER_POINTS, "COURSE",
                    "完成章节《" + title + "》");
        }
        return saved;
    }

    /**
     * 登录：重学某章节 —— 进度归零，允许重新走一遍阅读门槛。
     *
     * 同时**扣回**该章已发的学习积分：否则「重学 → 再完成」可以无限刷分。
     * 重学后再完成会重新发放，净额不变。
     */
    @Transactional(rollbackFor = Exception.class)
    public LearningProgress resetChapter(Long userId, Long courseId, Long chapterId) {
        if (!chapterRepo.findById(chapterId).map(c -> c.getCourseId().equals(courseId)).orElse(false)) {
            throw new IllegalArgumentException("章节不属于该课程");
        }
        LearningProgress p = progressRepo.findByUserIdAndChapterId(userId, chapterId).orElse(null);
        if (p == null) {
            LearningProgress np = new LearningProgress();
            np.setUserId(userId);
            np.setCourseId(courseId);
            np.setChapterId(chapterId);
            np.setFinished(false);
            np.setProgress(0);
            np.setLastAt(LocalDateTime.now());
            return progressRepo.save(np);
        }
        boolean wasFinished = p.isFinished();
        p.setFinished(false);
        p.setProgress(0);
        p.setLastAt(LocalDateTime.now());
        LearningProgress saved = progressRepo.save(p);
        if (wasFinished) {
            CourseChapter ch = chapterRepo.findById(chapterId).orElse(null);
            String title = ch != null && ch.getTitle() != null ? ch.getTitle() : "章节";
            authService.awardPoints(userId, -AuthService.COURSE_CHAPTER_POINTS, "COURSE",
                    "重学章节《" + title + "》扣回");
        }
        return saved;
    }

    /** 登录：我的学习概览（每门课的完成度） */
    public List<Map<String, Object>> myLearning(Long userId) {
        List<Course> courses = courseRepo.findAllByStatusOrderBySortOrderAscIdAsc("PUBLISHED");
        List<LearningProgress> all = progressRepo.findByUserId(userId);
        Map<Long, Integer> finishedByCourse = new HashMap<>();
        Set<Long> myCourses = new HashSet<>();
        for (LearningProgress p : all) {
            if (p.isFinished()) {
                finishedByCourse.merge(p.getCourseId(), 1, Integer::sum);
                myCourses.add(p.getCourseId());
            }
        }
        List<Map<String, Object>> res = new ArrayList<>();
        for (Course c : courses) {
            int total = (int) chapterRepo.countByCourseId(c.getId());
            int finished = finishedByCourse.getOrDefault(c.getId(), 0);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("course", c);
            m.put("totalChapters", total);
            m.put("finishedChapters", finished);
            m.put("percent", total == 0 ? 0 : (finished * 100 / total));
            m.put("started", myCourses.contains(c.getId()));
            res.add(m);
        }
        return res;
    }

    // ---- 结业证书（派生数据，不建表）----
    // 发证条件：课程绑定了结业试卷(examPaperId) + 全部章节完成 + 该试卷有及格记录。
    // 证书编号用「课程id + 及格记录id」确定性生成，可重复查询。

    /** 登录：我的全部结业证书 */
    public List<Map<String, Object>> myCertificates(Long userId) {
        List<Course> courses = courseRepo.findAllByStatusOrderBySortOrderAscIdAsc("PUBLISHED");
        List<LearningProgress> all = progressRepo.findByUserId(userId);
        List<ExamRecord> records = recordRepo.findByUserIdOrderByStartedAtDesc(userId);
        List<Map<String, Object>> res = new ArrayList<>();
        for (Course c : courses) {
            Map<String, Object> cert = buildCertificate(c, userId, all, records);
            if (cert != null) res.add(cert);
        }
        return res;
    }

    /** 登录：某课程的结业证书（未满足条件返回 null） */
    public Map<String, Object> certificateOf(Long userId, Long courseId) {
        Course c = courseRepo.findById(courseId).orElse(null);
        if (c == null) return null;
        return buildCertificate(c, userId, progressRepo.findByUserId(userId),
                recordRepo.findByUserIdOrderByStartedAtDesc(userId));
    }

    private Map<String, Object> buildCertificate(Course c, Long userId,
                                                 List<LearningProgress> all, List<ExamRecord> records) {
        if (c.getExamPaperId() == null) return null;
        List<CourseChapter> chapters = chapterRepo.findByCourseIdOrderBySortOrderAscIdAsc(c.getId());
        if (chapters.isEmpty()) return null;
        Set<Long> done = new HashSet<>();
        for (LearningProgress p : all) {
            if (c.getId().equals(p.getCourseId()) && p.isFinished()) done.add(p.getChapterId());
        }
        boolean allDone = chapters.stream().allMatch(ch -> done.contains(ch.getId()));
        if (!allDone) return null;
        ExamRecord pass = null;
        for (ExamRecord r : records) {
            if (r.getPaperId().equals(c.getExamPaperId()) && r.isPassed()) { pass = r; break; }
        }
        if (pass == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("courseId", c.getId());
        m.put("courseTitle", c.getTitle());
        m.put("paperTitle", pass.getPaperTitle());
        m.put("score", pass.getScore());
        m.put("issuedAt", pass.getSubmittedAt());
        m.put("certNo", "KB-" + c.getId() + "-" + pass.getId());
        return m;
    }

    // ---- 管理员维护 ----

    public Course save(Course c, Long operatorId) {
        if (c.getTitle() == null || c.getTitle().isBlank()) throw new IllegalArgumentException("课程标题不能为空");
        if (c.getStatus() == null || c.getStatus().isBlank()) c.setStatus("PUBLISHED");
        c.setUpdatedAt(LocalDateTime.now());
        if (c.getOwnerId() == null) c.setOwnerId(operatorId);
        return courseRepo.save(c);
    }

    /** 删除课程：先清章节（deleteByCourseId 为修改查询，必须包在事务里，否则报
     *  "No active transaction for update or delete query"） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        chapterRepo.deleteByCourseId(id);
        courseRepo.deleteById(id);
    }

    public CourseChapter saveChapter(CourseChapter ch) {
        if (ch.getCourseId() == null) throw new IllegalArgumentException("章节必须归属课程");
        if (ch.getTitle() == null || ch.getTitle().isBlank()) throw new IllegalArgumentException("章节标题不能为空");
        // [5] 章节正文净化：课程管理员写入 → 完整管理员查看（v-html）是提权级 XSS 链路，
        // 与评论/共享文档同用一套白名单清洗（外部复审 2026-10-09）
        ch.setContent(com.kb.kbplatform.security.HtmlSanitizer.sanitize(ch.getContent()));
        return chapterRepo.save(ch);
    }

    /** 删除章节：一并清掉该章节的学习进度，否则进度表残留指向已删章节的孤儿记录 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteChapter(Long id) {
        progressRepo.deleteByChapterId(id);
        chapterRepo.deleteById(id);
    }

    private void fillOwner(Course c) {
        if (c.getOwnerId() != null) {
            userRepo.findById(c.getOwnerId()).ifPresent(u ->
                    c.setOwnerName(u.getDisplayName() != null && !u.getDisplayName().isBlank()
                            ? u.getDisplayName() : u.getUsername()));
        }
    }
}
