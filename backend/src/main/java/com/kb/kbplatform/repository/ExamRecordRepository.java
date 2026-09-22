/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

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

    /** 该用户该试卷是否已有进行中的记录（并发开考时用于二次判定，配合数据库唯一约束兜底） */
    boolean existsByUserIdAndPaperIdAndSubmittedAtIsNull(Long userId, Long paperId);

    /** 原子抢占提交权（F08）：仅当仍处于未提交状态时写入 submittedAt。
     *  返回 0 表示另一个并发请求已经先提交，本次应直接返回已有结果而不是重复判分/重复发分。 */
    @Modifying
    @Query("UPDATE ExamRecord r SET r.submittedAt = :now WHERE r.id = :id AND r.submittedAt IS NULL")
    int claimSubmit(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);
}
