/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AI 会话消息：保存用户与 AI 的多轮问答，用于历史记录与上下文续聊。
 */
@Entity
@Table(name = "kb_ai_message",
       indexes = @Index(name = "idx_conv", columnList = "conversation_id"))
public class AiMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false)
    private Long conversationId;

    @Column(nullable = false, length = 20)
    private String role;   // user / ai

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    /** 引用来源快照（JSON 数组：[{id,title,snippet}]），仅 AI 消息有；历史消息重开页面后仍可显示 */
    @Column(columnDefinition = "text")
    private String sources;

    /**
     * 本条回答用的模式：kb=基于知识库检索 / general=通用问答。
     * 必须逐条落库，否则重开会话时前端只能按「会话当前选中的模式」猜 ——
     * 同一会话里混用过两种模式时，历史消息的标签会全部显示错误。
     * 历史数据为 null，读取时按 kb 兜底（改动前默认就是 kb）。
     */
    @Column(length = 16)
    private String mode;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getConversationId() { return conversationId; }
    public void setConversationId(Long conversationId) { this.conversationId = conversationId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getSources() { return sources; }
    public void setSources(String sources) { this.sources = sources; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
