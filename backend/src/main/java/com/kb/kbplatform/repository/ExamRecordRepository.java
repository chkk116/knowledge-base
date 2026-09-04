package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.ExamRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExamRecordRepository extends JpaRepository<ExamRecord, Long> {
    List<ExamRecord> findByUserIdOrderByStartedAtDesc(Long userId);
    /** 该用户此前是否已通过过这门试卷（用于结业证书通知去重，避免重考反复推送） */
    boolean existsByUserIdAndPaperIdAndPassedTrue(Long userId, Long paperId);

    /** 删除试卷时按 paperId 批量删除考试记录（单条 DELETE，替代全表扫描 + 逐条删除） */
    @Modifying
    @Query("DELETE FROM ExamRecord r WHERE r.paperId = :paperId")
    int deleteByPaperId(@Param("paperId") Long paperId);
}
