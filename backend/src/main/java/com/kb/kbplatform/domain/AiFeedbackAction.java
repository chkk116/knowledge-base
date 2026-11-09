/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AI 反馈处理状态（二期）。
 *
 * 一条「待办」= 前端页面上的一个可操作项：聚合未命中问题（m:问题）、差评（n:id）、
 * 原始未命中记录（r:id）。状态只有 done / ignored 两种（无状态 = 删除本行，待处理）。
 * 单独建表而不是塞进 SysSetting：条目数会随使用增长，单行 JSON 会越写越大。
 *
 * updated_at 是回测基准：处理之后该问题是否再次未命中，用 created_at > updated_at 判定。
 */
@Entity
@Table(name = "kb_ai_feedback_action")
public class AiFeedbackAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_key", nullable = false, unique = true, length = 160)
    private String actionKey;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 200)
    private String note;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    protected AiFeedbackAction() {}

    public AiFeedbackAction(String actionKey) {
        this.actionKey = actionKey;
    }

    public Long getId() { return id; }
    public String getActionKey() { return actionKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
