/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.ExamAnswer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExamAnswerRepository extends JpaRepository<ExamAnswer, Long> {
    List<ExamAnswer> findByRecordId(Long recordId);

    /** 某场考试里待人工阅卷的主观题数量 */
    long countByRecordIdAndPendingTrue(Long recordId);

    /** 全站待阅卷作答总数（后台红点用） */
    long countByPendingTrue();

    /** 有待阅卷作答的考试记录 id（去重） */
    @Query("SELECT DISTINCT a.recordId FROM ExamAnswer a WHERE a.pending = true")
    List<Long> findRecordIdsWithPending();

    /** 批量统计多条记录各自的待阅卷题数（成绩列表回填，避免 N+1） */
    @Query("SELECT a.recordId, COUNT(a) FROM ExamAnswer a WHERE a.recordId IN :ids AND a.pending = true GROUP BY a.recordId")
    List<Object[]> countPendingByRecordIds(@Param("ids") java.util.Collection<Long> ids);

    /** 删除试卷时按 paperId 批量删除其下所有作答明细（子查询圈定记录，单条 DELETE 替代 N+1） */
    @Modifying
    @Query("DELETE FROM ExamAnswer a WHERE a.recordId IN " +
           "(SELECT r.id FROM ExamRecord r WHERE r.paperId = :paperId)")
    int deleteByPaperId(@Param("paperId") Long paperId);
}
