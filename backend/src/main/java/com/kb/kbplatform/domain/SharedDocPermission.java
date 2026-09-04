package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 文档级权限（在团队空间「空间级」权限之上，对单篇文档单独收窄或提升）。
 * 默认成员继承空间权限；当此处存在记录时，文档级权限优先于空间权限。
 * 仅对 TEAM 空间有意义；PERSONAL 空间不启用。
 */
@Entity
@Table(name = "kb_shared_doc_permission", indexes = {
        @Index(name = "idx_sdp_share", columnList = "shareId"),
        @Index(name = "idx_sdp_doc", columnList = "docId"),
        @Index(name = "idx_sdp_share_user", columnList = "shareId,userId"),
        @Index(name = "idx_sdp_doc_user", columnList = "docId,userId", unique = true)
})
public class SharedDocPermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shareId;

    @Column(nullable = false)
    private Long docId;

    @Column(nullable = false)
    private Long userId;

    /** READ / WRITE / ADMIN（文档级，ADMIN 表示可管理该文档的权限） */
    @Column(nullable = false, length = 20)
    private String permission = "READ";

    @Transient
    private String username;

    @Transient
    private String displayName;

    @Transient
    private String avatar;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getShareId() { return shareId; }
    public void setShareId(Long shareId) { this.shareId = shareId; }

    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
