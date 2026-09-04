package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.ExamAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExamAnswerRepository extends JpaRepository<ExamAnswer, Long> {
    List<ExamAnswer> findByRecordId(Long recordId);

    /** 删除试卷时按 paperId 批量删除其下所有作答明细（子查询圈定记录，单条 DELETE 替代 N+1） */
    @Modifying
    @Query("DELETE FROM ExamAnswer a WHERE a.recordId IN " +
           "(SELECT r.id FROM ExamRecord r WHERE r.paperId = :paperId)")
    int deleteByPaperId(@Param("paperId") Long paperId);
}
