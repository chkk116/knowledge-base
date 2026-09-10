package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Asset;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset> {

    Optional<Asset> findByCode(String code);

    /** 用于生成下一个资产编号（基于当前最大 id + 1） */
    @Query("select coalesce(max(a.id), 0) from Asset a")
    Long maxId();

    /** 按状态分组计数（统计看板用，SQL 聚合，避免全表拉进内存） */
    @Query("select a.status, count(a) from Asset a group by a.status")
    List<Object[]> countGroupByStatus();

    /** 资产总金额（统计看板用，SQL 聚合） */
    @Query("select coalesce(sum(a.price), 0) from Asset a")
    java.math.BigDecimal sumPrice();

    /** 按分类取资产（删除分类时批量摘除关联，替代 findAll() 全表过滤） */
    List<Asset> findByCategoryId(Long categoryId);
}
