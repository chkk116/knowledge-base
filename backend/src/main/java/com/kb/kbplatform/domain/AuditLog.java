/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 操作审计日志：记录管理员的关键动作（用户/文档管理、权限变更、重置密码等） */
@Entity
@Table(name = "kb_audit_log", indexes =
        // 审计列表按时间倒序翻页
        @Index(name = "idx_audit_created", columnList = "createdAt"))
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long actorId;        // 操作人 id
    @Column(length = 64)
    private String actorName;    // 操作人用户名（冗余，便于查看）
    @Column(length = 48)
    private String action;       // 动作码：CREATE_USER / DELETE_USER / RESET_PWD / BLOCK_DOC / APPROVE_DOC ...
    @Column(length = 24)
    private String targetType;   // 目标类型：USER / DOCUMENT
    private Long targetId;       // 目标 id
    @Column(length = 64)
    private String targetName;   // 目标名称（冗余：用户名 / 文档标题），便于直接查看
    @Column(length = 1000)
    private String detail;       // 补充说明（如改了什么）
    @Column(length = 64)
    private String ip;           // 操作人 IP
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getActorId() { return actorId; }
    public void setActorId(Long actorId) { this.actorId = actorId; }

    public String getActorName() { return actorName; }
    public void setActorName(String actorName) { this.actorName = actorName; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }

    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }

    public String getTargetName() { return targetName; }
    public void setTargetName(String targetName) { this.targetName = targetName; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
