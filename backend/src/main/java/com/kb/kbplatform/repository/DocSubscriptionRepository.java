/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DocSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DocSubscriptionRepository extends JpaRepository<DocSubscription, Long> {

    Optional<DocSubscription> findByUserIdAndDocId(@Param("userId") Long userId, @Param("docId") Long docId);

    /** 文档更新推送：按文档拉取订阅者（userId 列表，不回实体省一次映射） */
    @Query("SELECT s.userId FROM DocSubscription s WHERE s.docId = :docId")
    List<Long> findUserIdsByDocId(@Param("docId") Long docId);

    /** 「我的订阅」：按订阅时间倒序 */
    List<DocSubscription> findByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    long countByDocId(@Param("docId") Long docId);

    @Modifying
    @Query("DELETE FROM DocSubscription s WHERE s.userId = :userId AND s.docId = :docId")
    int deleteByUserAndDoc(@Param("userId") Long userId, @Param("docId") Long docId);

    /** 删除文档时清理其全部订阅，避免遗留孤儿数据 */
    @Modifying
    @Query("DELETE FROM DocSubscription s WHERE s.docId = :docId")
    int deleteAllByDocId(@Param("docId") Long docId);
}
