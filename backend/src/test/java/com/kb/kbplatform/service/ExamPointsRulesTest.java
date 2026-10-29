/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.*;
import com.kb.kbplatform.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 考试积分回归测试（F15）：考试积分只在「首次通过」时发放。
 * 旧实现每次及格都发 EXAM_PASS_POINTS，反复重考同一份试卷即可无限刷分。
 * 纯 Mockito 单元测试：不依赖数据库。运行：mvn test -Dtest=ExamPointsRulesTest
 */
class ExamPointsRulesTest {

    private QuestionRepository questionRepo;
    private QuestionOptionRepository optionRepo;
    private ExamPaperRepository paperRepo;
    private ExamPaperQuestionRepository pqRepo;
    private ExamRecordRepository recordRepo;
    private ExamAnswerRepository answerRepo;
    private AuthService authService;
    private CourseRepository courseRepo;
    private ExamService svc;

    private static final Long USER = 5L, PAPER = 9L, RECORD = 100L;

    @BeforeEach
    void setUp() {
        questionRepo = Mockito.mock(QuestionRepository.class);
        optionRepo = Mockito.mock(QuestionOptionRepository.class);
        paperRepo = Mockito.mock(ExamPaperRepository.class);
        pqRepo = Mockito.mock(ExamPaperQuestionRepository.class);
        recordRepo = Mockito.mock(ExamRecordRepository.class);
        answerRepo = Mockito.mock(ExamAnswerRepository.class);
        authService = Mockito.mock(AuthService.class);
        courseRepo = Mockito.mock(CourseRepository.class);
        svc = new ExamService(questionRepo, optionRepo, paperRepo, pqRepo, recordRepo, answerRepo, authService,
                courseRepo, Mockito.mock(CourseChapterRepository.class), Mockito.mock(LearningProgressRepository.class),
                Mockito.mock(CourseService.class), Mockito.mock(NotificationService.class),
                Mockito.mock(com.kb.kbplatform.repository.SysSettingRepository.class),
                Mockito.mock(com.kb.kbplatform.repository.PracticeLogRepository.class),
                // 阅卷列表要回填用户名，本测试不走阅卷，mock 即可
                Mockito.mock(com.kb.kbplatform.repository.UserRepository.class),
                // settleExpired 用到的 TransactionTemplate：本测试只走 submit()，给个 mock 即可
                Mockito.mock(org.springframework.transaction.support.TransactionTemplate.class),
                // 练习会话存 KvStore（Redis/内存），本测试不走会话逻辑，mock 即可
                Mockito.mock(KvStore.class));

        // 一道 10 分的单选题，正确选项 A
        Question q = new Question();
        q.setId(1L);
        q.setType("SINGLE");
        q.setContent("1+1=?");
        QuestionOption opt = new QuestionOption();
        opt.setId(11L);
        opt.setQuestionId(1L);
        opt.setLabel("A");
        opt.setCorrect(true);
        ExamPaperQuestion pq = new ExamPaperQuestion();
        pq.setPaperId(PAPER);
        pq.setQuestionId(1L);
        pq.setScore(10);

        ExamPaper paper = new ExamPaper();
        paper.setId(PAPER);
        paper.setPassScore(10);

        when(pqRepo.findByPaperIdOrderBySortOrderAscIdAsc(PAPER)).thenReturn(List.of(pq));
        when(questionRepo.findByIdIn(anyList())).thenReturn(List.of(q));
        when(optionRepo.findByQuestionIdOrderByLabelAsc(1L)).thenReturn(List.of(opt));
        when(paperRepo.findById(PAPER)).thenReturn(Optional.of(paper));
        when(answerRepo.save(any(ExamAnswer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(recordRepo.save(any(ExamRecord.class))).thenAnswer(inv -> inv.getArgument(0));
        when(courseRepo.findByExamPaperId(PAPER)).thenReturn(List.of());
    }

    private ExamRecord pendingRecord() {
        ExamRecord rec = new ExamRecord();
        rec.setId(RECORD);
        rec.setUserId(USER);
        rec.setPaperId(PAPER);
        rec.setPaperTitle("入门测验");
        rec.setStartedAt(LocalDateTime.now().minusMinutes(3));
        rec.setSubmittedAt(null);   // 未交卷
        return rec;
    }

    private List<ExamService.AnswerItem> rightAnswer() {
        ExamService.AnswerItem it = new ExamService.AnswerItem();
        it.questionId = 1L;
        it.answer = List.of("A");
        return List.of(it);
    }

    @Test
    @DisplayName("F15：首次及格发放考试积分")
    void submit_firstPass_awardsPoints() {
        when(recordRepo.findById(RECORD)).thenReturn(Optional.of(pendingRecord()));
        when(recordRepo.claimSubmit(eq(RECORD), any())).thenReturn(1);
        when(recordRepo.existsByUserIdAndPaperIdAndPassedTrue(USER, PAPER)).thenReturn(false);

        ExamRecord saved = svc.submit(USER, RECORD, rightAnswer());

        assertTrue(saved.isPassed());
        verify(authService).awardPoints(eq(USER), eq(AuthService.EXAM_PASS_POINTS), eq("EXAM"), contains("入门测验"));
    }

    @Test
    @DisplayName("F15：已经通过过该试卷，重考再次及格不再重复发分（防止刷分）")
    void submit_repeatPass_noPoints() {
        when(recordRepo.findById(RECORD)).thenReturn(Optional.of(pendingRecord()));
        when(recordRepo.claimSubmit(eq(RECORD), any())).thenReturn(1);
        when(recordRepo.existsByUserIdAndPaperIdAndPassedTrue(USER, PAPER)).thenReturn(true);

        ExamRecord saved = svc.submit(USER, RECORD, rightAnswer());

        assertTrue(saved.isPassed(), "分数照常判，只是不重复发分");
        assertEquals(10, saved.getScore());
        verify(authService, never()).awardPoints(anyLong(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("题目已被删除时交卷不 500，该题按 0 分跳过")
    void submit_missingQuestion_skips() {
        when(questionRepo.findByIdIn(anyList())).thenReturn(List.of());   // 题库已清空
        when(recordRepo.findById(RECORD)).thenReturn(Optional.of(pendingRecord()));
        when(recordRepo.claimSubmit(eq(RECORD), any())).thenReturn(1);
        when(recordRepo.existsByUserIdAndPaperIdAndPassedTrue(USER, PAPER)).thenReturn(false);

        ExamRecord saved = svc.submit(USER, RECORD, rightAnswer());

        assertEquals(0, saved.getScore());
        assertFalse(saved.isPassed());
    }
}
