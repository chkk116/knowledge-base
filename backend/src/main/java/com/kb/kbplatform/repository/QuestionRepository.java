package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    List<Question> findByCategoryOrderByCreatedAtDesc(String category);
    List<Question> findAllByOrderByCreatedAtDesc();
    List<Question> findByIdIn(List<Long> ids);
}
