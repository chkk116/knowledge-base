/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Course;
import com.kb.kbplatform.domain.LearningProgress;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.CourseService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/courses")
public class CourseController {

    private final CourseService svc;
    private final UserRepository userRepo;

    public CourseController(CourseService svc, UserRepository userRepo) {
        this.svc = svc;
        this.userRepo = userRepo;
    }

    /** 公开：课程列表（可按分类筛选） */
    @GetMapping
    public List<Course> list(@RequestParam(required = false) String category) {
        return svc.listCourses(category);
    }

    /** 公开：课程分类 */
    @GetMapping("/categories")
    public List<String> categories() {
        return svc.categories();
    }

    /** 公开：课程详情 + 章节 */
    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        return svc.getCourse(id);
    }

    /** 登录：某课程下我的章节完成状态 */
    @GetMapping("/{id}/progress")
    public List<Map<String, Object>> progress(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.myProgress(resolveOwner(c), id);
    }

    /** 登录：标记章节完成 */
    @PostMapping("/{id}/chapters/{cid}/finish")
    public LearningProgress finish(@RequestAttribute("kbUser") TokenClaims c,
                                   @PathVariable Long id, @PathVariable Long cid) {
        return svc.finishChapter(resolveOwner(c), id, cid);
    }

    /** 登录：我的学习概览 */
    @GetMapping("/mine")
    public List<Map<String, Object>> mine(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.myLearning(resolveOwner(c));
    }

    /** 登录：我的全部结业证书 */
    @GetMapping("/certificates")
    public List<Map<String, Object>> certificates(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.myCertificates(resolveOwner(c));
    }

    /** 登录：某课程的结业证书（未满足条件返回 null） */
    @GetMapping("/{id}/certificate")
    public Map<String, Object> certificate(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.certificateOf(resolveOwner(c), id);
    }

    private Long resolveOwner(TokenClaims c) {
        if (c == null || c.username() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        }
        return userRepo.findByUsername(c.username())
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "请先登录"));
    }
}
