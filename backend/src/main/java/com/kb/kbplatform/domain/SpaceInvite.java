/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 团队空间的邀请链接（一张有效邀请对应一个 token）。
 *
 * 与「文档外部分享链接」不同：那条是匿名只读某篇文档，这条是登录用户凭链接
 * 成为空间成员。权限固定 READ，兑换必须登录，可撤销 / 重生成（旧的立即失效）。
 */
@Entity
@Table(name = "kb_space_invite", indexes = {
        @Index(name = "idx_space_invite_share", columnList = "share_id")
})
public class SpaceInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "share_id", nullable = false)
    private Long shareId;

    @Column(nullable = false, length = 64, unique = true)
    private String token;

    /** 通过邀请加入后拿到的权限：恒为 READ，链接不能用来提权 */
    @Column(nullable = false, length = 16)
    private String permission = "READ";

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    /** 0 = 不限次数 */
    @Column(name = "max_uses", nullable = false)
    private int maxUses = 0;

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(nullable = false)
    private boolean revoked = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getShareId() { return shareId; }
    public void setShareId(Long shareId) { this.shareId = shareId; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getPermission() { return permission; }
    public void setPermission(String permission) { this.permission = permission; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public int getMaxUses() { return maxUses; }
    public void setMaxUses(int maxUses) { this.maxUses = maxUses; }
    public int getUsedCount() { return usedCount; }
    public void setUsedCount(int usedCount) { this.usedCount = usedCount; }
    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
