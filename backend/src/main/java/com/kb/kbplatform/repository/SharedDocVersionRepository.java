/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.SharedDocVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SharedDocVersionRepository extends JpaRepository<SharedDocVersion, Long> {

    /** 某共享文档的全部版本（倒序，最新在前） */
    @Query("SELECT v FROM SharedDocVersion v WHERE v.sharedDocId = :docId ORDER BY v.createdAt DESC")
    List<SharedDocVersion> findBySharedDocIdOrderByCreatedAtDesc(@Param("docId") Long docId);

    /** 删除某共享文档的全部版本历史（文档被删除时一并清理，避免留下孤儿版本行）。
     *  派生删除需在事务中执行，调用方（DocShareService）均已标注 @Transactional。 */
    void deleteAllBySharedDocId(Long docId);

    /** 批量删除多个共享文档的版本历史（删除整个空间时使用） */
    void deleteAllBySharedDocIdIn(java.util.Collection<Long> docIds);
}
