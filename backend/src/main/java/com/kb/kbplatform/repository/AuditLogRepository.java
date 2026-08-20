package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    // 多条件可选过滤：action / actorName(模糊) / 时间范围 [start,end]，均倒序分页。
    // 任一参数为 null 即在 WHERE 中跳过该条件，单条查询覆盖全部组合，无需堆叠派生方法。
    @Query("SELECT a FROM AuditLog a WHERE " +
           "(:action IS NULL OR a.action = :action) AND " +
           "(:actor IS NULL OR LOWER(a.actorName) LIKE LOWER(CONCAT('%', :actor, '%'))) AND " +
           "(:start IS NULL OR a.createdAt >= :start) AND " +
           "(:end IS NULL OR a.createdAt <= :end) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLog> search(@Param("action") String action,
                          @Param("actor") String actor,
                          @Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end,
                          Pageable pageable);
}
