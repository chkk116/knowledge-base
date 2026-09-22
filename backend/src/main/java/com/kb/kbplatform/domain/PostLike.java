/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 点赞记录：用户对某篇文档的点赞关系。
 * (userId, docId) 唯一，防止重复点赞；实体名用 PostLike 避免与 JPQL 关键字 LIKE 冲突。
 */
@Entity
@Table(name = "kb_like",
        uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "docId"}),
        indexes = @Index(name = "idx_like_doc", columnList = "docId"))   // 按文档统计点赞数
public class PostLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;   // 点赞者 id
    private Long docId;    // 被点赞文档 id

    private LocalDateTime createdAt = LocalDateTime.now();

    public PostLike() {}

    public PostLike(Long userId, Long docId) {
        this.userId = userId;
        this.docId = docId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
