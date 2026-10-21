/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.ExamRecord;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.ExamService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/exams")
public class ExamController {

    private final ExamService svc;
    private final UserRepository userRepo;

    public ExamController(ExamService svc, UserRepository userRepo) {
        this.svc = svc;
        this.userRepo = userRepo;
    }

    /** 公开：已发布试卷列表 */
    @GetMapping
    public List<?> list() {
        return svc.listPapers();
    }

    /** 公开：试卷详情（学员视角，不含答案） */
    @GetMapping("/{id}")
    public Map<String, Object> paper(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.getPaperForStudent(c.userId(), id);
    }

    /** 登录：开始考试 */
    @PostMapping("/{id}/start")
    public ExamRecord start(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.start(resolveOwner(c), id);
    }

    /** 登录：提交并阅卷 */
    @PostMapping("/{id}/submit")
    public ExamRecord submit(@RequestAttribute("kbUser") TokenClaims c,
                             @PathVariable Long id, @RequestBody SubmitReq req) {
        return svc.submit(resolveOwner(c), req.recordId, req.items);
    }

    /** 练习：按试卷取题（无需 start、不落成绩，不含答案） */
    @GetMapping("/{id}/practice")
    public Map<String, Object> practicePaper(@PathVariable Long id) {
        return svc.practicePaper(id);
    }

    /** 练习：题库随机抽题（可按分类） */
    @GetMapping("/practice/random")
    public Map<String, Object> practiceRandom(@RequestParam(required = false) String category,
                                              @RequestParam(required = false) Integer count) {
        return svc.practiceRandom(category, count);
    }

    /** 练习：逐题判分，返回正确答案与解析 */
    @PostMapping("/practice/check")
    public Map<String, Object> practiceCheck(@RequestBody PracticeCheckReq req) {
        return svc.practiceCheck(req.questionId, req.answer);
    }

    public static class PracticeCheckReq {
        public Long questionId;
        public List<String> answer;
    }

    /** 登录：我的成绩 */
    @GetMapping("/mine")
    public List<ExamRecord> mine(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.myRecords(resolveOwner(c));
    }

    /** 登录：成绩详情 */
    @GetMapping("/records/{rid}")
    public Map<String, Object> detail(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long rid) {
        return svc.recordDetail(resolveOwner(c), rid);
    }

    public static class SubmitReq {
        public Long recordId;
        public List<ExamService.AnswerItem> items;
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
