/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.SharedDocLike;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SharedLikeRepository extends JpaRepository<SharedDocLike, Long> {

    boolean existsByUserIdAndDocId(Long userId, Long docId);

    void deleteByUserIdAndDocId(Long userId, Long docId);

    long countByDocId(Long docId);

    List<SharedDocLike> findByUserId(Long userId);

    /** 彻底删除空间文档时清理关联点赞（不留孤儿数据） */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM SharedDocLike x WHERE x.docId = :docId")
    void deleteByDocId(@org.springframework.data.repository.query.Param("docId") Long docId);
}
