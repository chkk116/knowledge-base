/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户私有 AI 模型。
 *
 * 与管理员维护的 {@link AiModel}（kb_ai_model，全站共享）**完全隔离**：
 * 用户只能看到/操作自己的行；管理员后台不查询本表。问答时若用户选中了自己的模型，
 * 优先用它；未选或不可用时回落到系统模型。
 *
 * API Key 与系统模型一样，落库前用 KB_AI_MASTER_KEY 派生密钥 AES-GCM 加密。
 */
@Entity
@Table(name = "kb_user_ai_model")
public class UserAiModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户：所有读写都必须带 user_id 条件，杜绝越权 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 300)
    private String baseUrl;

    @Column(nullable = false, columnDefinition = "text")
    private String apiKey;

    @Column(nullable = false, length = 200)
    private String model;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private int sortOrder = 0;

    @Column(name = "context_window")
    private int contextWindow = 128000;

    private LocalDateTime createdAt = LocalDateTime.now();
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

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public int getContextWindow() { return contextWindow; }
    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
