/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 个人 API 密钥（对标 GitHub 的 Personal Access Token）。
 *
 * <p>用户在「账号设置 → API 密钥」里自助创建，明文只在创建那一刻返回一次，
 * 库里只存 SHA-256（密钥是高熵随机串，不像密码需要慢哈希加盐）。
 *
 * <p>作用域 {@code scopes} 是权限码子集：留空 = 与本账号权限相同；有值则取交集，
 * 且不再继承管理员身份——否则给管理员发的受限密钥等于没限制。
 */
@Entity
@Table(name = "kb_api_key",
        indexes = {@Index(name = "idx_apikey_user", columnList = "userId")})
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    /** 用户自己起的名字，便于区分用途（最多 40 字） */
    @Column(length = 40)
    private String name;

    /** 展示用前缀，如 kb_9f3a2c7d；列表里只露这一段 */
    @Column(length = 20)
    private String keyPrefix;

    /** SHA-256(明文) 的十六进制，唯一 */
    @Column(length = 64, unique = true)
    private String keyHash;

    /** 权限码逗号分隔；空串表示与账号权限相同 */
    @Column(length = 2000)
    private String scopes = "";

    /** 允许的来源 IP（逗号分隔，支持 IPv4 CIDR）；空 = 不限制（兼容旧密钥） */
    @Column(length = 1000)   // 20 条 IPv6（最长 45 字符）+ 逗号，最坏 919，留余量
    private String allowedIps = "";

    private LocalDateTime expiresAt;

    private LocalDateTime lastUsedAt;

    private boolean revoked = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }

    public String getKeyHash() { return keyHash; }
    public void setKeyHash(String keyHash) { this.keyHash = keyHash; }

    public String getScopes() { return scopes; }
    public void setScopes(String scopes) { this.scopes = scopes; }

    public String getAllowedIps() { return allowedIps; }
    public void setAllowedIps(String allowedIps) { this.allowedIps = allowedIps; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
