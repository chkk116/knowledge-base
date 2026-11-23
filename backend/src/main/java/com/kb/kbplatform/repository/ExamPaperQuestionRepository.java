/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.ExamPaperQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamPaperQuestionRepository extends JpaRepository<ExamPaperQuestion, Long> {
    List<ExamPaperQuestion> findByPaperIdOrderBySortOrderAscIdAsc(Long paperId);
    void deleteByPaperId(Long paperId);

    /** 试卷题量：ExamPaper.questionCount 是 @Transient（不落库），列表页必须用它回填 */
    long countByPaperId(Long paperId);

    /** 批量题量：成绩列表一次算完所有试卷的题数，避免逐条 count 的 N+1（行 = [paperId, count]） */
    @org.springframework.data.jpa.repository.Query(
            "select pq.paperId, count(pq) from ExamPaperQuestion pq where pq.paperId in :paperIds group by pq.paperId")
    List<Object[]> countGroupByPaperIds(@org.springframework.data.repository.query.Param("paperIds") java.util.Collection<Long> paperIds);

    /** 题目删除时级联清理组卷关联（2026-10-13 审查 H4）；调用方需在事务内 */
    List<ExamPaperQuestion> findByQuestionId(Long questionId);
    void deleteByQuestionId(Long questionId);
}
