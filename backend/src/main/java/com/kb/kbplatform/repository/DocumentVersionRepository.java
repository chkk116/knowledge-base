package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {
    List<DocumentVersion> findByDocIdOrderByCreatedAtDesc(Long docId);
}
