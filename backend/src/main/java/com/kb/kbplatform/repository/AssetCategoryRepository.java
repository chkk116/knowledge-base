package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.AssetCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssetCategoryRepository extends JpaRepository<AssetCategory, Long> {

    List<AssetCategory> findByParentIdOrderBySortAscIdAsc(Long parentId);

    List<AssetCategory> findAllByOrderBySortAscIdAsc();
}
