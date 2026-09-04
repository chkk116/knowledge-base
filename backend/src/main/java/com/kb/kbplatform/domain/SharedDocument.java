package com.kb.kbplatform.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kb_shared_document", indexes = {
        @Index(name = "idx_shareddoc_share", columnList = "shareId"),
        @Index(name = "idx_shareddoc_owner", columnList = "ownerId"),
        @Index(name = "idx_shareddoc_updated", columnList = "shareId,updatedAt")
})
public class SharedDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shareId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "longtext")
    private String content;

    /** 实时协同的 Yjs 文档二进制快照（由 collab-relay 读写，HTML 内容仅作兼容快照）。内部字段，不进 JSON。 */
    @Lob
    @JsonIgnore
    @Column(name = "yjs_state", columnDefinition = "longblob")
    private byte[] yjsState;

    @Column(nullable = false)
    private Long ownerId;

    @Transient
    private String ownerName;

    /** 文档占用存储字节数（content + yjs_state），由服务层计算注入，用于配额展示 */
    @Transient
    private long size;

    /** 文档所有者头像（来自 kb_user.avatar），由服务层注入，用于前端展示 */
    @Transient
    private String ownerAvatar;

    /** 当前用户对本文档的有效权限（空间权限 ∪ 文档级权限，文档级优先），列表/详情展示与前端锁编辑用 */
    @Transient
    private String myPerm;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getShareId() { return shareId; }
    public void setShareId(Long shareId) { this.shareId = shareId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public byte[] getYjsState() { return yjsState; }
    public void setYjsState(byte[] yjsState) { this.yjsState = yjsState; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }

    public String getOwnerAvatar() { return ownerAvatar; }
    public void setOwnerAvatar(String ownerAvatar) { this.ownerAvatar = ownerAvatar; }

    public String getMyPerm() { return myPerm; }
    public void setMyPerm(String myPerm) { this.myPerm = myPerm; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
