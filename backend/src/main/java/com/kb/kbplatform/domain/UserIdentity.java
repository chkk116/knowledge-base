/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 外部身份绑定（2026-10-26）。
 *
 * 一条记录 = 某个第三方身份（微信 openid/unionid）对应到本站某个用户。
 * 扫码登录时先用 (provider, externalId) 找绑定，找到就直接给该用户签发会话，
 * 找不到再按配置决定「自动注册」还是提示先去绑定 —— **不靠用户名猜**，
 * 否则微信昵称一改就对不上了。
 */
@Entity
@Table(
        name = "kb_user_identity",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_identity", columnNames = {"provider", "external_id"})
)
public class UserIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 身份源标识：wechat / oidc / ldap … */
    @Column(nullable = false, length = 32)
    private String provider;

    /** 该身份源里的稳定标识：微信优先 unionid，没有则 openid */
    @Column(name = "external_id", nullable = false, length = 128)
    private String externalId;

    @Column(name = "open_id", length = 128)
    private String openId;

    @Column(name = "union_id", length = 128)
    private String unionId;

    @Column(length = 64)
    private String nickname;

    @Column(length = 512)
    private String avatar;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }

    public String getOpenId() { return openId; }
    public void setOpenId(String openId) { this.openId = openId; }

    public String getUnionId() { return unionId; }
    public void setUnionId(String unionId) { this.unionId = unionId; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
