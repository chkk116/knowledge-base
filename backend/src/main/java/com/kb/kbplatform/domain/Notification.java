/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kb_notification", indexes =
        // 通知中心：按接收者拉取 + 时间倒序（未读数也走 userId 前缀）
        @Index(name = "idx_noti_user", columnList = "userId,createdAt"))
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;          // 接收者 id
    @Column(name = "`type`")       // type 是 MySQL 保留字，需反引号转义
    private String type;          // COMMENT_MENTION 等
    private String actor;         // 触发者用户名
    private Long docId;           // 关联文档
    private Long shareId;         // 共享文档所属空间（DOC_MENTION 跳转用；文章通知为空）
    private Long commentId;       // 关联评论
    @Column(columnDefinition = "text")
    private String summary;       // 评论摘要
    @Column(name = "`read`")       // read 是 MySQL 保留字，需反引号转义
    private boolean read = false;
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }

    public Long getShareId() { return shareId; }
    public void setShareId(Long shareId) { this.shareId = shareId; }

    public Long getCommentId() { return commentId; }
    public void setCommentId(Long commentId) { this.commentId = commentId; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public boolean isRead() { return read; }
    public void setRead(boolean read) { this.read = read; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
