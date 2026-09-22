/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

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

    /** 一次取回前缀匹配的已占用编号（编号生成器内存探测用，避免逐号 findByCode 的最多 2000 次往返） */
    @org.springframework.data.jpa.repository.Query("select a.code from Asset a where a.code like concat(:prefix, '%')")
    java.util.List<String> findCodeByPrefix(@org.springframework.data.repository.query.Param("prefix") String prefix);

    /** 用于生成下一个资产编号（基于当前最大 id + 1） */
    @Query("select coalesce(max(a.id), 0) from Asset a")
    Long maxId();

    /**
     * 加行锁读取资产（F09）：借出/归还等改变「资产当前占用状态」的流程必须在事务内先锁住资产行，
     * 否则两个并发借出请求会同时通过「无活跃借用」判断，各插一条 BORROWED 记录。
     * 配合数据库「每资产最多一条活跃借用」的部分唯一索引形成最终约束。
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Asset a where a.id = :id")
    Optional<Asset> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    /** 按状态分组计数（统计看板用，SQL 聚合，避免全表拉进内存） */
    @Query("select a.status, count(a) from Asset a group by a.status")
    List<Object[]> countGroupByStatus();

    /** 资产总金额（统计看板用，SQL 聚合） */
    @Query("select coalesce(sum(a.price), 0) from Asset a")
    java.math.BigDecimal sumPrice();

    /** 按分类取资产（删除分类时批量摘除关联，替代 findAll() 全表过滤） */
    List<Asset> findByCategoryId(Long categoryId);
}
