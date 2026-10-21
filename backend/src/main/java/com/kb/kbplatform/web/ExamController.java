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

    /** 学员端：当前配置的固定模拟卷（未配置返回 paperId=null） */
    @GetMapping("/mock")
    public Map<String, Object> mock() {
        return svc.mockPaper();
    }

    /** 练习：逐题判分，返回正确答案与解析；顺带写练习流水（错题本用） */
    @PostMapping("/practice/check")
    public Map<String, Object> practiceCheck(@RequestAttribute("kbUser") TokenClaims c,
                                             @RequestBody PracticeCheckReq req) {
        return svc.practiceCheck(resolveOwner(c), req.questionId, req.answer);
    }

    /** 练习统计：累计答题 / 正确率 / 待复习错题数 */
    @GetMapping("/practice/stats")
    public Map<String, Object> practiceStats(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.practiceStats(resolveOwner(c));
    }

    /** 错题本：最近一次答错的题（含答案与解析），答对自动移出 */
    @GetMapping("/practice/wrong")
    public List<Map<String, Object>> practiceWrong(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.wrongQuestions(resolveOwner(c));
    }

    /** 错题本：标记已掌握（删除该题练习记录） */
    @DeleteMapping("/practice/wrong/{qid}")
    public Map<String, Object> clearWrong(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long qid) {
        return svc.clearWrong(resolveOwner(c), qid);
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
