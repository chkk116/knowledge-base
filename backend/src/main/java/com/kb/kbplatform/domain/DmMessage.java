/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 私信消息。
 *
 * 会话不单独建表：一对一会话用 `convKey = min(uidA,uidB) + "_" + max(...)` 表示，
 * 会话列表由消息分组派生。好处是没有「会话表与消息表不同步」这类问题，
 * 坏处是会话级状态（置顶/免打扰/未读数）无处存放 —— 这些如果要做再加一张轻表。
 */
@Entity
@Table(name = "kb_dm_message", indexes = {
        @Index(name = "idx_dm_conv_time", columnList = "conv_key,created_at"),
        @Index(name = "idx_dm_to_read", columnList = "to_id,read_at")
})
public class DmMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conv_key", nullable = false, length = 48)
    private String convKey;

    @Column(name = "from_id", nullable = false)
    private Long fromId;

    @Column(name = "to_id", nullable = false)
    private Long toId;

    /** TEXT / IMAGE */
    @Column(nullable = false, length = 16)
    private String kind = "TEXT";

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "read_at")
    private LocalDateTime readAt;

    public static String convKey(Long a, Long b) {
        long x = Math.min(a, b), y = Math.max(a, b);
        return x + "_" + y;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getConvKey() { return convKey; }
    public void setConvKey(String convKey) { this.convKey = convKey; }
    public Long getFromId() { return fromId; }
    public void setFromId(Long fromId) { this.fromId = fromId; }
    public Long getToId() { return toId; }
    public void setToId(Long toId) { this.toId = toId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
}
