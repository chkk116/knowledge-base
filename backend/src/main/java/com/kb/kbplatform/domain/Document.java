package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kb_document", indexes = {
        // 公开列表：按状态过滤 + 发帖时间/浏览量排序（最新/最热两路覆盖）
        @Index(name = "idx_doc_status_created", columnList = "status,createdAt"),
        @Index(name = "idx_doc_status_views", columnList = "status,views"),
        // 作者维度：我的帖子 / 他的文章
        @Index(name = "idx_doc_owner_status", columnList = "ownerId,status"),
        // 分类筛选
        @Index(name = "idx_doc_category", columnList = "category")
})
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    @Column(columnDefinition = "text")
    private String content;

    private String category;
    private String tags;
    private Long ownerId;

    @Transient
    private String ownerName;   // 列表/详情展示作者名，非持久化字段（由 service 查询时填充）

    /** PUBLISHED（公开）/ PENDING_REVIEW（待审核：作者+管理员可见）/ DRAFT（草稿：仅作者可见）/ BLOCKED（已封禁下架） */
    private String status = "PUBLISHED";

    @Column(columnDefinition = "text")
    private String rejectReason;  // 驳回理由（管理员驳回时填写，退回作者草稿后作者可见）

    private int views = 0;        // 浏览量（详情页每次打开 +1）

    private boolean hot = false;  // 是否热帖（浏览量达阈值后标记，仅奖励一次）

    private boolean pinned = false;  // 是否置顶（管理员设置，列表优先展示）

    private int likes = 0;        // 点赞数（由点赞切换同步维护，便于列表/详情直接展示）

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }

    public int getViews() { return views; }
    public void setViews(int views) { this.views = views; }

    public boolean isHot() { return hot; }
    public void setHot(boolean hot) { this.hot = hot; }

    public boolean isPinned() { return pinned; }
    public void setPinned(boolean pinned) { this.pinned = pinned; }

    public int getLikes() { return likes; }
    public void setLikes(int likes) { this.likes = likes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
