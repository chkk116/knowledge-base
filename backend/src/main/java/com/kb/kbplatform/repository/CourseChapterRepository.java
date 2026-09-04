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
