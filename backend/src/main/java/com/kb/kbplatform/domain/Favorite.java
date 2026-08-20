package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 收藏记录：用户对某篇文档的收藏关系。
 * (userId, docId) 唯一，避免重复收藏。
 */
@Entity
@Table(name = "kb_favorite",
        uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "docId"}),
        indexes = @Index(name = "idx_fav_doc", columnList = "docId"))   // 按文档统计收藏数
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;   // 收藏者 id
    private Long docId;    // 被收藏文档 id

    private LocalDateTime createdAt = LocalDateTime.now();

    public Favorite() {}

    public Favorite(Long userId, Long docId) {
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
