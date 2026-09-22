/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DocShare;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocShareRepository extends JpaRepository<DocShare, Long> {

    /** 我创建的空间 */
    List<DocShare> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    /** 我参与的共享空间（通过权限表） */
    @Query("SELECT s FROM DocShare s WHERE s.id IN (SELECT p.shareId FROM DocSharePermission p WHERE p.userId = :userId) ORDER BY s.updatedAt DESC")
    List<DocShare> findByMemberUserIdOrderByUpdatedAtDesc(@Param("userId") Long userId);

    /** 我创建或参与的空间 */
    @Query("SELECT DISTINCT s FROM DocShare s LEFT JOIN DocSharePermission p ON s.id = p.shareId " +
           "WHERE s.ownerId = :userId OR p.userId = :userId ORDER BY s.updatedAt DESC")
    List<DocShare> findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(@Param("userId") Long userId);

    Optional<DocShare> findByIdAndOwnerId(Long id, Long ownerId);

    /** 某用户的个人空间（type=PERSONAL，唯一归属本人） */
    List<DocShare> findByOwnerIdAndTypeOrderByUpdatedAtDesc(Long ownerId, String type);

    /** 存量团队空间中尚未设置容量上限（null）的记录，启动迁移时补默认 2GB 用 */
    List<DocShare> findByTypeAndCapacityIsNull(String type);

    /** 某用户作为所有者拥有的团队空间数量（用于「每人最多建 N 个团队空间」上限判定） */
    long countByOwnerIdAndType(Long ownerId, String type);
}
