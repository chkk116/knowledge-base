/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 私信会话的用户侧状态：归档 / 删除。
 * 会话由消息聚合，本表只记录「某用户对某对端」的标记，互不影响对方。
 */
@Entity
@Table(name = "kb_dm_conv_state", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "peer_id"}))
public class DmConvState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "peer_id", nullable = false)
    private Long peerId;

    @Column(nullable = false)
    private boolean archived = false;

    @Column(nullable = false)
    private boolean deleted = false;

    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    @PrePersist
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getPeerId() { return peerId; }
    public void setPeerId(Long peerId) { this.peerId = peerId; }

    public boolean isArchived() { return archived; }
    public void setArchived(boolean archived) { this.archived = archived; }

    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
