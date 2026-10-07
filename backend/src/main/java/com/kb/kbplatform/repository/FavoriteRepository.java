/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    boolean existsByUserIdAndDocId(Long userId, Long docId);

    void deleteByUserIdAndDocId(Long userId, Long docId);
    void deleteByUserId(Long userId);   // 自助注销：清空本人收藏

    List<Favorite> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** 作者文档被收藏总数（仅统计已发布文档） */
    @Query(value = "SELECT COUNT(*) FROM kb_favorite f WHERE f.doc_id IN " +
                   "(SELECT id FROM kb_document WHERE owner_id = :owner AND status = 'PUBLISHED')", nativeQuery = true)
    long countFavByOwner(@Param("owner") Long owner);

    /** 彻底删除文档时清理关联记录（F12/purge：不留孤儿数据） */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM Favorite x WHERE x.docId = :docId")
    void deleteByDocId(@org.springframework.data.repository.query.Param("docId") Long docId);
}
