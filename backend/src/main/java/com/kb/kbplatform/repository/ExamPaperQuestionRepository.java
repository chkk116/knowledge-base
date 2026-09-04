package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.ExamPaperQuestion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamPaperQuestionRepository extends JpaRepository<ExamPaperQuestion, Long> {
    List<ExamPaperQuestion> findByPaperIdOrderBySortOrderAscIdAsc(Long paperId);
    void deleteByPaperId(Long paperId);
}
