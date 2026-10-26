/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

    @Column(columnDefinition = "text")
    private String content;

    /** 实时协同的 Yjs 文档二进制快照（由 collab-relay 读写，HTML 内容仅作兼容快照）。内部字段，不进 JSON。
     *  LONGVARBINARY：PG 映射 bytea（LENGTH 可用）；MySQL 映射 longblob，双库兼容。 */
    @JdbcTypeCode(SqlTypes.LONGVARBINARY)
    @JsonIgnore
    @Column(name = "yjs_state", columnDefinition = "bytea")
    private byte[] yjsState;

    /** 协同状态代次：由服务端在「回滚 / 重置协同态」时自增。
     *  中继（collab-relay）加载时记住该值，落库前比对；不一致说明内存快照已过期，
     *  必须丢弃并强制客户端重载，否则旧房间会把历史版本又覆盖回去（F05）。 */
    @Column(name = "yjs_rev", nullable = false, columnDefinition = "bigint default 0")
    private long yjsRev = 0L;

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

    /** 待整理箱标记（2026-10-15）：true = 快速收进、尚未归档——不出现在空间文档列表，
     *  仅出现在所有者的待整理箱里，补全标题/归档空间后摘掉标记 */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean inbox = false;

    /** 多标签：逗号分隔的规范化短标签；列表筛选由服务端安全范围过滤后在客户端完成 */
    @Column(columnDefinition = "text")
    private String tags;

    // ---- 外部分享（公开只读链接）----
    /** 公开访问令牌（NULL = 未开启）；开启后 /s/{token} 免登录只读 */
    @Column(length = 64, unique = true)
    private String shareToken;

    /** 访问密码的哈希（PasswordUtil，pbkdf2$...）；NULL = 免密 */
    @Column(length = 200)
    private String sharePassHash;

    /** 过期时间（NULL = 永久有效） */
    private LocalDateTime shareExpireAt;

    /** 禁止复制/右键（前端 CSS + 提示，不构成安全边界） */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean shareNoCopy = false;

    /** 累计访问次数（只增不改，用于分享统计） */
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long shareViews = 0;

    @Transient
    private String spaceName;

    @Transient
    private String spaceType;

    @Transient
    private String contentType;

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

    public long getYjsRev() { return yjsRev; }
    public void setYjsRev(long yjsRev) { this.yjsRev = yjsRev; }

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

    public boolean isInbox() { return inbox; }
    public void setInbox(boolean inbox) { this.inbox = inbox; }

    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    public String getSpaceName() { return spaceName; }
    public void setSpaceName(String spaceName) { this.spaceName = spaceName; }

    public String getSpaceType() { return spaceType; }
    public void setSpaceType(String spaceType) { this.spaceType = spaceType; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }


    public String getShareToken() { return shareToken; }
    public void setShareToken(String shareToken) { this.shareToken = shareToken; }

    public String getSharePassHash() { return sharePassHash; }
    public void setSharePassHash(String sharePassHash) { this.sharePassHash = sharePassHash; }

    public LocalDateTime getShareExpireAt() { return shareExpireAt; }
    public void setShareExpireAt(LocalDateTime shareExpireAt) { this.shareExpireAt = shareExpireAt; }

    public boolean isShareNoCopy() { return shareNoCopy; }
    public void setShareNoCopy(boolean shareNoCopy) { this.shareNoCopy = shareNoCopy; }

    public long getShareViews() { return shareViews; }
    public void setShareViews(long shareViews) { this.shareViews = shareViews; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
