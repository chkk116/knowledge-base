package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.LoginLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginLogRepository extends JpaRepository<LoginLog, Long> {

    /**
     * 登录日志检索（倒序分页）。
     * PG 可移植性写法（与 AuditLogRepository 同规范）：
     *  - 可选字符串过滤：:p IS NULL OR :p = '' 双守卫（给 Hibernate String 类型线索，防 bytea 绑定）
     *  - 可选时间过滤：COALESCE(:p, col)（防 PG "could not determine data type of parameter"）
     *  - LIKE 模式在 Java 里预拼好再传入，JPQL 内不出现 CONCAT
     */
    @Query("SELECT l FROM LoginLog l WHERE " +
           "(:username IS NULL OR :username = '' OR LOWER(l.username) LIKE LOWER(:usernamePattern)) AND " +
           "(:success IS NULL OR l.success = :success) AND " +
           "l.createdAt >= COALESCE(:start, l.createdAt) AND " +
           "l.createdAt <= COALESCE(:end, l.createdAt) " +
           "ORDER BY l.createdAt DESC")
    Page<LoginLog> search(@Param("username") String username,
                          @Param("usernamePattern") String usernamePattern,
                          @Param("success") Boolean success,
                          @Param("start") java.time.LocalDateTime start,
                          @Param("end") java.time.LocalDateTime end,
                          Pageable pageable);
}
