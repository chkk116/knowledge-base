/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.PracticeLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PracticeLogRepository extends JpaRepository<PracticeLog, Long> {

    long countByUserId(Long userId);

    long countByUserIdAndCorrectTrue(Long userId);

    /** 最近作答流水（错题本按「每题最后一次」倒序取，300 条足够覆盖） */
    List<PracticeLog> findTop300ByUserIdOrderByIdDesc(Long userId);

    /** 移除某道题的错题记录（学员点「已掌握」） */
    long deleteByUserIdAndQuestionId(Long userId, Long questionId);

    /** 某题被作答次数 / 答对次数：用于「文档易错点」统计 */
    long countByQuestionId(Long questionId);
    long countByQuestionIdAndCorrectTrue(Long questionId);

    /** 一次聚合全部题目统计，避免逐题 count 造成 N+1（返回 [questionId, total, correct]） */
    @org.springframework.data.jpa.repository.Query(
            "select l.questionId, count(l), sum(case when l.correct = true then 1 else 0 end) "
                    + "from PracticeLog l group by l.questionId")
    java.util.List<Object[]> statsByQuestion();

    /** 用户是否做过这道题：错题转卡只允许「自己做过的题」，防止靠题号批量套答案 */
    boolean existsByUserIdAndQuestionId(Long userId, Long questionId);
}
