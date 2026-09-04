package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Course;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findAllByStatusOrderBySortOrderAscIdAsc(String status);
    List<Course> findAllByStatusAndCategoryOrderBySortOrderAscIdAsc(String status, String category);
    List<Course> findByExamPaperId(Long examPaperId);
}
