/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    // 多条件可选过滤：action / actorName(模糊) / 时间范围 [start,end]，均倒序分页。
    // 任一参数为 null 即在 WHERE 中跳过该条件，单条查询覆盖全部组合，无需堆叠派生方法。
    // 域过滤（用户/文档/共享）：用 flag + 集合实现「IN / NOT IN 可选」；集合恒传非空（未用时传哑元），
    // 规避 PG 对空集合 IN () 的绑定/语法问题，flag 短路保证语义。
    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:action IS NULL OR a.action = :action) AND " +
           "(:actorPattern IS NULL OR :actorPattern = '' OR LOWER(a.actorName) LIKE LOWER(:actorPattern)) AND " +
           "a.createdAt >= COALESCE(:start, a.createdAt) AND " +
           "a.createdAt <= COALESCE(:end, a.createdAt) AND " +
           "(:domainInAll = true OR a.action IN :domainIn) AND " +
           "(:domainNotInAll = true OR a.action NOT IN :domainNotIn) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLog> search(@Param("action") String action,
                          @Param("actorPattern") String actorPattern,
                          @Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end,
                          @Param("domainInAll") boolean domainInAll,
                          @Param("domainIn") Collection<String> domainIn,
                          @Param("domainNotInAll") boolean domainNotInAll,
                          @Param("domainNotIn") Collection<String> domainNotIn,
                          Pageable pageable);
}
