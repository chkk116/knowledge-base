/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.SharedDocPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SharedDocPermissionRepository extends JpaRepository<SharedDocPermission, Long> {

    /** 某文档的全部文档级权限记录 */
    List<SharedDocPermission> findByDocId(Long docId);

    /** 某空间全部文档级权限（批量注入列表页用） */
    List<SharedDocPermission> findByShareId(Long shareId);

    Optional<SharedDocPermission> findByDocIdAndUserId(Long docId, Long userId);

    @Modifying
    @Query("DELETE FROM SharedDocPermission p WHERE p.docId = :docId AND p.userId = :userId")
    void deleteByDocIdAndUserId(@Param("docId") Long docId, @Param("userId") Long userId);

    /** 删除某文档的全部文档级权限（删文档/移出空间时清理） */
    @Modifying
    @Query("DELETE FROM SharedDocPermission p WHERE p.docId = :docId")
    void deleteAllByDocId(@Param("docId") Long docId);

    /** 删除某空间的全部文档级权限（删空间时清理） */
    @Modifying
    @Query("DELETE FROM SharedDocPermission p WHERE p.shareId = :shareId")
    void deleteAllByShareId(@Param("shareId") Long shareId);
}
