/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.SharedDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SharedDocumentRepository extends JpaRepository<SharedDocument, Long> {

    List<SharedDocument> findByShareIdOrderByUpdatedAtDesc(Long shareId);

    Optional<SharedDocument> findByIdAndShareId(Long id, Long shareId);

    /** 外部分享：按公开令牌查文档（免登录只读） */
    Optional<SharedDocument> findByShareToken(String shareToken);

    /** 跨空间索引投影：只取筛选/展示所需字段，避免加载大型 Yjs 二进制快照。 */
    @Query("SELECT d.id AS id, d.shareId AS shareId, d.title AS title, d.content AS content, d.tags AS tags, " +
            "d.ownerId AS ownerId, d.createdAt AS createdAt, d.updatedAt AS updatedAt " +
            "FROM SharedDocument d WHERE d.shareId IN :shareIds AND d.inbox = false ORDER BY d.updatedAt DESC")
    List<SharedDocumentSummary> findLibrarySummariesByShareIdIn(@org.springframework.data.repository.query.Param("shareIds") java.util.Collection<Long> shareIds);

    /**
     * 「全部文档」列表用：正文只取 200 字前缀（够 classifyContentType 判类型），不把每篇全文拉进内存。
     * 完整正文由 findContentByIdIn 按需批量取（仅列表前若干篇），避免万级文档时 DB 返回体与堆内存爆掉。
     */
    @Query("SELECT d.id AS id, d.shareId AS shareId, d.title AS title, SUBSTRING(d.content, 1, 200) AS content, " +
            "d.tags AS tags, d.ownerId AS ownerId, d.createdAt AS createdAt, d.updatedAt AS updatedAt " +
            "FROM SharedDocument d WHERE d.shareId IN :shareIds AND d.inbox = false ORDER BY d.updatedAt DESC")
    List<SharedDocumentSummary> findLibraryMetaByShareIdIn(@org.springframework.data.repository.query.Param("shareIds") java.util.Collection<Long> shareIds);

    /** 列表正文检索：按 id 批量取完整正文（只对前 N 篇调用） */
    @Query("SELECT d.id AS id, d.content AS content FROM SharedDocument d WHERE d.id IN :ids")
    List<SharedDocumentSummary> findContentByIdIn(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);

    /** 待整理箱（2026-10-15）：本人收进但未归档的文档，跨空间、按更新时间倒序 */
    List<SharedDocument> findByOwnerIdAndInboxTrueOrderByUpdatedAtDesc(Long ownerId);

    /** 待整理箱未整理数量（角标用） */
    long countByOwnerIdAndInboxTrue(Long ownerId);

    /** 某用户作为文档所有者占用的总存储字节数（content + yjs_state），用于个人配额(默认 200MB)核算；
     *  两列任一为 NULL 时用 COALESCE 归零，避免整行被 SUM 丢弃导致配额失效 */
    @Query(value = "SELECT COALESCE(SUM(COALESCE(LENGTH(content),0) + COALESCE(LENGTH(yjs_state),0)), 0) FROM kb_shared_document WHERE owner_id = ?1", nativeQuery = true)
    long sumSizeByOwnerId(Long ownerId);

    /** 某空间内全部文档占用的总存储字节数（content + yjs_state），用于空间容量上限核算；
     *  两列任一为 NULL 时用 COALESCE 归零，避免整行被 SUM 丢弃导致容量统计失真 */
    @Query(value = "SELECT COALESCE(SUM(COALESCE(LENGTH(content),0) + COALESCE(LENGTH(yjs_state),0)), 0) FROM kb_shared_document WHERE share_id = ?1", nativeQuery = true)
    long sumSizeByShareId(Long shareId);

    /** 批量聚合多个空间的已用字节数（列表页去 N+1 用），返回 [shareId, bytes]；
     *  用原生 SQL 直接对真实列 content/yjs_state 求和，size 是 @Transient 派生字段不可用于 JPQL */
    @Query(value = "SELECT share_id AS shareId, COALESCE(SUM(COALESCE(LENGTH(content),0) + COALESCE(LENGTH(yjs_state),0)), 0) AS bytes " +
            "FROM kb_shared_document WHERE share_id IN (:ids) GROUP BY share_id", nativeQuery = true)
    java.util.List<Object[]> sumSizeByShareIdIn(@org.springframework.data.repository.query.Param("ids") java.util.Collection<Long> ids);
}
