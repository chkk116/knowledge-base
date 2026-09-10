package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.AssetLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetLogRepository extends JpaRepository<AssetLog, Long> {

    List<AssetLog> findByAssetIdOrderByCreatedAtDesc(Long assetId);
}
