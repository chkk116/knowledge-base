/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AI 调用流水：每次真实调用模型 API（含管理页「测试连接」与失败尝试）记一行，
 * 供「AI 模型管理」页的用量统计（请求次数 / tokens / 成功率 / 日趋势）聚合，
 * 对标各平台官方控制台的「用量信息」页。只记元数据，不记问答内容。
 */
@Entity
// 用量统计按时间范围查询（最新 1/7/30/90 天），created_at 索引避免全表扫
@Table(name = "kb_ai_usage_log", indexes = @Index(name = "idx_ai_usage_created", columnList = "created_at"))
public class AiUsageLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模型配置 id（kb_ai_model.id）；配置删除后流水仍保留，靠 name/model 字段回显 */
    @Column(nullable = false)
    private Long modelId;

    /** 调用时的显示名快照 */
    @Column(nullable = false, length = 100)
    private String modelName;

    /** 调用时的模型 id 快照（实际发给平台的 model 参数） */
    @Column(nullable = false, length = 200)
    private String model;

    /** 调用来源：chat=对话问答，test=管理页测试连接 */
    @Column(nullable = false, length = 16)
    private String source;

    @Column(nullable = false)
    private boolean success;

    private int promptTokens;

    private int completionTokens;

    private int totalTokens;

    private long latencyMs;

    /** 失败原因摘要（HTTP 状态 / 异常信息，截断存储），成功时为空 */
    @Column(length = 300)
    private String error;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getModelId() { return modelId; }
    public void setModelId(Long modelId) { this.modelId = modelId; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
