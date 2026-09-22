/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.CourseChapter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CourseChapterRepository extends JpaRepository<CourseChapter, Long> {
    List<CourseChapter> findByCourseIdOrderBySortOrderAscIdAsc(Long courseId);
    /** 章节数（COUNT 查询，避免为计数拉全部章节实体） */
    long countByCourseId(Long courseId);
    void deleteByCourseId(Long courseId);
}
