package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DocSharePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocSharePermissionRepository extends JpaRepository<DocSharePermission, Long> {

    List<DocSharePermission> findByShareIdOrderByCreatedAtAsc(Long shareId);

    Optional<DocSharePermission> findByShareIdAndUserId(Long shareId, Long userId);

    void deleteByShareIdAndUserId(Long shareId, Long userId);

    long countByShareIdAndPermission(Long shareId, String permission);
}
