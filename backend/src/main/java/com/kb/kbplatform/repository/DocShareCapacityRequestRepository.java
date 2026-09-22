/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DocShareCapacityRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DocShareCapacityRequestRepository extends JpaRepository<DocShareCapacityRequest, Long> {
    List<DocShareCapacityRequest> findByShareIdOrderByCreatedAtDesc(Long shareId);
    List<DocShareCapacityRequest> findByStatusOrderByCreatedAtDesc(String status);
    List<DocShareCapacityRequest> findByApplicantIdOrderByCreatedAtDesc(Long applicantId);

    /** 悲观写锁：审批时用，避免并发双提交同时读到 PENDING 导致重复处理/发通知 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM DocShareCapacityRequest r WHERE r.id = :id")
    Optional<DocShareCapacityRequest> findByIdLocked(@Param("id") Long id);
}
