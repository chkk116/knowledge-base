package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DocSharePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocSharePermissionRepository extends JpaRepository<DocSharePermission, Long> {

    List<DocSharePermission> findByShareIdOrderByCreatedAtAsc(Long shareId);

    Optional<DocSharePermission> findByShareIdAndUserId(Long shareId, Long userId);

    /** 批量取多个空间的权限行（列表页去 N+1 用） */
    java.util.List<DocSharePermission> findByShareIdIn(java.util.Collection<Long> shareIds);

    void deleteByShareIdAndUserId(Long shareId, Long userId);

    long countByShareIdAndPermission(Long shareId, String permission);
}
