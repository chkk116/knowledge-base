package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.FeatureFlag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, String> {
    Optional<FeatureFlag> findByFeatureKey(String featureKey);
}
