/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AI 模型配置：管理员在「AI 模型管理」页中维护的可用模型。
 *
 * OpenAI / 阿里 DashScope(百炼) / DeepSeek / 智谱大模型 对外都提供 OpenAI 兼容的
 * /chat/completions 协议，差异仅在 base-url 与模型名，因此这里只存一套「端点 + Key + 模型名」，
 * 调用层统一按同一份请求体发送（见 AiModelClient），不引第三方 SDK。
 *
 * API Key 落库前用 KB_AI_MASTER_KEY 派生的密钥做 AES-GCM 加密（见 AiKeyCipher），
 * 列表接口只回显脱敏后的形式，明文仅在服务端调用时使用。
 */
@Entity
@Table(name = "kb_ai_model")
public class AiModel {

    public static final String PROVIDER_OPENAI = "OPENAI";
    public static final String PROVIDER_DASHSCOPE = "DASHSCOPE";
    public static final String PROVIDER_DEEPSEEK = "DEEPSEEK";
    public static final String PROVIDER_ZHIPU = "ZHIPU";
    /** OpenCode 推理服务（opencode.ai，OpenAI 兼容端点） */
    public static final String PROVIDER_OPENCODE = "OPENCODE";
    /** 小米 MiMo 开放平台（api.xiaomimimo.com/v1，OpenAI 兼容；v2.6 系列为思考模型） */
    public static final String PROVIDER_MIMO = "MIMO";
    public static final String PROVIDER_CUSTOM = "CUSTOM";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 平台：见上方 PROVIDER_* 常量 */
    @Column(nullable = false, length = 32)
    private String provider;

    /** 管理员自定义的展示名，如「DeepSeek-V3（主力）」 */
    @Column(nullable = false, length = 100)
    private String name;

    /** OpenAI 兼容端点，不含 /chat/completions 后缀 */
    @Column(nullable = false, length = 300)
    private String baseUrl;

    /** API Key：入库前加密（Base64(iv||ciphertext)） */
    @Column(nullable = false, columnDefinition = "text")
    private String apiKey;

    /** 模型名，如 gpt-4o-mini / deepseek-chat / qwen-plus / glm-4.7-flash */
    @Column(nullable = false, length = 200)
    private String model;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 全局默认模型：普通用户未显式选择时使用 */
    @Column(name = "is_default", nullable = false)
    private boolean defaultModel = false;

    private int sortOrder = 0;

    /**
     * 上下文窗口（token）。前端据此算「历史带多少 / 用了多少」。
     * 不给死值是因为各家差得远（64K / 128K / 200K / 1M），写死只会两种结果：
     * 要么白白浪费大窗口，要么把小窗口撑爆。
     */
    @Column(name = "context_window")
    private int contextWindow = 128000;

    /**
     * 是否可作为「图片 OCR / 视觉识别」使用。
     *
     * 勾选后 OcrService 会**优先**用这一条：端点 + Key + 模型名全部取本行配置，
     * 因此换视觉模型（qwen-vl / glm-4.6v-flash / 自建多模态…）只要在管理页改一行，
     * 不用改代码重新发版。
     *
     * 历史行为保留：没有勾选任何条目时，仍按「智谱条目 + 内置视觉降级链」走。
     * ⚠️ 勾了但模型其实不支持图片输入 → OCR 会失败，报错会提示检查这个开关。
     */
    @Column(name = "vision")
    private boolean vision = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    @PrePersist
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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

    public boolean isDefaultModel() { return defaultModel; }
    public void setDefaultModel(boolean defaultModel) { this.defaultModel = defaultModel; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public int getContextWindow() { return contextWindow; }
    public void setContextWindow(int contextWindow) { this.contextWindow = contextWindow; }

    public boolean isVision() { return vision; }
    public void setVision(boolean vision) { this.vision = vision; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
