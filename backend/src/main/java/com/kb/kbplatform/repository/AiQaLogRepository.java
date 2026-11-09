/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.AiQaLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AiQaLogRepository extends JpaRepository<AiQaLog, Long> {

    Optional<AiQaLog> findByIdAndUserId(Long id, Long userId);

    long countByHitTrue();

    long countByHitFalse();

    /** 知识库模式（非 general）的提问总数：命中率分母 */
    long countByModeNot(String mode);

    long countByRating(Integer rating);

    /** 最近的赞/踩（rating=1 或 -1），按 id 倒序 */
    List<AiQaLog> findTop20ByRatingOrderByIdDesc(Integer rating);

    /** 最近未命中的提问（知识缺口原始清单） */
    List<AiQaLog> findTop50ByHitFalseAndModeNotOrderByIdDesc(String mode);

    /**
     * 高频未命中问题：按问题原文聚合，倒序取前 N。
     * 用 Pageable 限制条数（JPQL 不写死 limit，兼容不同数据库）。
     */
    @Query("select l.question, count(l) from AiQaLog l " +
           "where l.hit = false and l.mode <> 'general' and l.createdAt >= :since " +
           "group by l.question order by count(l) desc")
    List<Object[]> topMissedSince(@Param("since") LocalDateTime since, Pageable pageable);

    /** 二期回测：某个问题在处理时间之后是否再次检索未命中（补文档有没有用） */
    long countByQuestionAndHitFalseAndCreatedAtAfter(String question, LocalDateTime since);
}
