package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DocShareCreateRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DocShareCreateRequestRepository extends JpaRepository<DocShareCreateRequest, Long> {

    List<DocShareCreateRequest> findByApplicantIdOrderByCreatedAtDesc(Long applicantId);

    List<DocShareCreateRequest> findByStatusOrderByCreatedAtDesc(String status);

    /** 悲观写锁：审批时用，避免并发双提交同时读到 PENDING 导致重复建空间/发通知 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM DocShareCreateRequest r WHERE r.id = :id")
    Optional<DocShareCreateRequest> findByIdLocked(@Param("id") Long id);
}
