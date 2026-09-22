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

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
