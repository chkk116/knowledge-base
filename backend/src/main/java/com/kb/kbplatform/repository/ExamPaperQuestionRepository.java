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

    /** 题目删除时级联清理组卷关联（2026-10-13 审查 H4）；调用方需在事务内 */
    List<ExamPaperQuestion> findByQuestionId(Long questionId);
    void deleteByQuestionId(Long questionId);
}
