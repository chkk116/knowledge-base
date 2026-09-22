/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Course;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findAllByStatusOrderBySortOrderAscIdAsc(String status);
    List<Course> findAllByStatusAndCategoryOrderBySortOrderAscIdAsc(String status, String category);
    List<Course> findByExamPaperId(Long examPaperId);
}
