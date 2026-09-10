package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.AssetBorrow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AssetBorrowRepository extends JpaRepository<AssetBorrow, Long> {

    List<AssetBorrow> findByAssetIdOrderByBorrowAtDesc(Long assetId);

    List<AssetBorrow> findByStatus(String status);

    List<AssetBorrow> findByAssetIdAndStatus(Long assetId, String status);

    /** 当前借出数量（统计看板用，直接 COUNT，不拉记录） */
    long countByStatus(String status);

    /** 是否存在未归还记录（借出校验用，EXISTS 语义，比查列表再判空更快） */
    boolean existsByAssetIdAndStatus(Long assetId, String status);

    /** 仅查给定资产集合中处于未归还状态的 id（列表页注入借出状态用，避免全表借出记录） */
    @Query("select b.assetId from AssetBorrow b where b.status = :status and b.assetId in :ids")
    java.util.Set<Long> findBorrowedAssetIds(@org.springframework.data.repository.query.Param("status") String status,
                                             @org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
