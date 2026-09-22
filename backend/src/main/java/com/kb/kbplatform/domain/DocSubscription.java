/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 文档订阅：用户订阅某篇文档后，该文档被更新时收到站内通知。
 * 联合唯一约束 (userId, docId) 保证重复订阅幂等——直接 save 也不会产生脏数据。
 */
@Entity
@Table(name = "kb_doc_subscription",
        uniqueConstraints = @UniqueConstraint(name = "uk_docsub_user_doc", columnNames = {"userId", "docId"}),
        indexes = {
                @Index(name = "idx_docsub_doc", columnList = "docId"),      // 文档更新时按 docId 拉取订阅者
                @Index(name = "idx_docsub_user", columnList = "userId")     // 「我的订阅」列表
        })
public class DocSubscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private Long docId;
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
