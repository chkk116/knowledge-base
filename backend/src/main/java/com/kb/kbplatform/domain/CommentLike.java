/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 评论点赞记录：用户对某条评论的点赞关系。
 * (userId, commentId) 唯一，防止重复点赞；实体名用 CommentLike 避免与 JPQL 关键字 LIKE 冲突。
 */
@Entity
@Table(name = "kb_comment_like",
        uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "commentId"}))
public class CommentLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;     // 点赞者 id
    private Long commentId;  // 被点赞评论 id

    private LocalDateTime createdAt = LocalDateTime.now();

    public CommentLike() {}

    public CommentLike(Long userId, Long commentId) {
        this.userId = userId;
        this.commentId = commentId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getCommentId() { return commentId; }
    public void setCommentId(Long commentId) { this.commentId = commentId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
