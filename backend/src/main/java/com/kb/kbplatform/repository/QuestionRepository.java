/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    List<Question> findByCategoryOrderByCreatedAtDesc(String category);
    List<Question> findAllByOrderByCreatedAtDesc();
    List<Question> findByIdIn(List<Long> ids);

    /** 个人题库配额：按创建人 + 个人分类统计 */
    long countByCreatorIdAndCategory(Long creatorId, String category);
}
