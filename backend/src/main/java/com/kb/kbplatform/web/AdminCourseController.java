/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Course;
import com.kb.kbplatform.domain.CourseChapter;
import com.kb.kbplatform.repository.CourseChapterRepository;
import com.kb.kbplatform.repository.CourseRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.CourseService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/admin/courses")
public class AdminCourseController {

    private final CourseService svc;
    private final CourseRepository courseRepo;
    private final CourseChapterRepository chapterRepo;

    public AdminCourseController(CourseService svc, CourseRepository courseRepo, CourseChapterRepository chapterRepo) {
        this.svc = svc;
        this.courseRepo = courseRepo;
        this.chapterRepo = chapterRepo;
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「" + code + "」权限");
        }
    }

    /** 管理员：全部课程（含草稿） */
    @GetMapping
    public List<Course> list(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "COURSE_MANAGE");
        return courseRepo.findAll();
    }

    @PostMapping
    public Course create(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Course course) {
        requirePerm(c, "COURSE_MANAGE");
        return svc.save(course, c.userId());
    }

    @PutMapping("/{id}")
    public Course update(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id, @RequestBody Course course) {
        requirePerm(c, "COURSE_MANAGE");
        Course ex = courseRepo.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "课程不存在"));
        if (course.getTitle() != null) ex.setTitle(course.getTitle());
        if (course.getCover() != null) ex.setCover(course.getCover());
        if (course.getSummary() != null) ex.setSummary(course.getSummary());
        if (course.getCategory() != null) ex.setCategory(course.getCategory());
        if (course.getLevel() != 0) ex.setLevel(course.getLevel());
        if (course.getStatus() != null) ex.setStatus(course.getStatus());
        if (course.getSortOrder() != null) ex.setSortOrder(course.getSortOrder());
        if (course.getLearningMode() != null) ex.setLearningMode(course.getLearningMode());
        ex.setFreeLearn(course.isFreeLearn());
        ex.setProgressBar(course.isProgressBar());
        if (course.getTags() != null) ex.setTags(course.getTags());
        if (course.getContent() != null) ex.setContent(course.getContent());
        if (course.getExamPaperId() != null) ex.setExamPaperId(course.getExamPaperId());
        return svc.save(ex, c.userId());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "COURSE_MANAGE");
        svc.delete(id);
        return Map.of("ok", true);
    }

    @GetMapping("/{id}/chapters")
    public List<CourseChapter> chapters(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "COURSE_MANAGE");
        return chapterRepo.findByCourseIdOrderBySortOrderAscIdAsc(id);
    }

    @PostMapping("/chapters")
    public CourseChapter createChapter(@RequestAttribute("kbUser") TokenClaims c, @RequestBody CourseChapter ch) {
        requirePerm(c, "COURSE_MANAGE");
        return svc.saveChapter(ch);
    }

    @PutMapping("/chapters/{id}")
    public CourseChapter updateChapter(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id, @RequestBody CourseChapter ch) {
        requirePerm(c, "COURSE_MANAGE");
        CourseChapter ex = chapterRepo.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "章节不存在"));
        if (ch.getTitle() != null) ex.setTitle(ch.getTitle());
        if (ch.getContent() != null) ex.setContent(ch.getContent());
        if (ch.getArticleId() != null) ex.setArticleId(ch.getArticleId());
        if (ch.getSortOrder() != null) ex.setSortOrder(ch.getSortOrder());
        if (ch.getDurationMin() != null) ex.setDurationMin(ch.getDurationMin());
        return svc.saveChapter(ex);
    }

    @DeleteMapping("/chapters/{id}")
    public Map<String, Object> deleteChapter(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "COURSE_MANAGE");
        svc.deleteChapter(id);
        return Map.of("ok", true);
    }
}
