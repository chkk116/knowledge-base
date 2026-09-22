/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kb_comment", indexes = {
        // 文档详情页评论列表（按文档 + 时间）
        @Index(name = "idx_comment_doc", columnList = "docId,createdAt"),
        // 「我评论的」聚合
        @Index(name = "idx_comment_user", columnList = "userId")
})
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 评论挂载对象类型：DOC=知识库帖子，CHAPTER=培训章节（通用评论体系，docId 复用为 targetId）
    @Column(nullable = false)
    private String targetType = "DOC";

    private Long docId;     // 关联目标 id（帖子 id 或章节 id，由 targetType 决定语义）
    private Long parentId;  // 父评论 id（楼中楼回复；顶层评论为 null）
    private Long userId;    // 评论人 id（可空）
    private String username; // 评论人用户名（冗余，便于展示）

    @Column(columnDefinition = "text")
    private String content;

    private int likes = 0;   // 评论点赞数（由点赞切换同步维护，便于按热度排序/展示）

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getLikes() { return likes; }
    public void setLikes(int likes) { this.likes = likes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
