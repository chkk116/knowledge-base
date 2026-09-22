/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.LearningProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LearningProgressRepository extends JpaRepository<LearningProgress, Long> {
    List<LearningProgress> findByUserIdAndCourseId(Long userId, Long courseId);
    Optional<LearningProgress> findByUserIdAndChapterId(Long userId, Long chapterId);
    List<LearningProgress> findByUserId(Long userId);

    /** 删除章节时清掉对应学习进度，避免孤儿数据（调用方必须 @Transactional） */
    void deleteByChapterId(Long chapterId);
}
