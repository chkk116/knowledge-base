/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 举报 / 反馈（同一张表）：
 *  - targetType = FEEDBACK 时不带 targetId，纯意见反馈
 *  - targetType = POST / COMMENT / DOC / DM / USER 时带 targetId
 * 状态流转：PENDING → HANDLED（已处理）/ REJECTED（驳回）
 */
@Entity
@Table(name = "kb_report", indexes = {
        @Index(name = "idx_report_status_time", columnList = "status,created_at"),
        @Index(name = "idx_report_target", columnList = "target_type,target_id")
})
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", nullable = false, length = 24)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    /** 简短原因（下拉选项）：垃圾广告 / 辱骂攻击 / 违法违规 / 侵权 / 功能建议 / 其它 */
    @Column(nullable = false, length = 120)
    private String reason;

    @Column(columnDefinition = "text")
    private String detail;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Column(nullable = false, length = 16)
    private String status = "PENDING";

    @Column(name = "handle_note", columnDefinition = "text")
    private String handleNote;

    @Column(name = "handler_id")
    private Long handlerId;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "handled_at")
    private LocalDateTime handledAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Long getReporterId() { return reporterId; }
    public void setReporterId(Long reporterId) { this.reporterId = reporterId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String handleNote) { this.handleNote = handleNote; }
    public Long getHandlerId() { return handlerId; }
    public void setHandlerId(Long handlerId) { this.handlerId = handlerId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getHandledAt() { return handledAt; }
    public void setHandledAt(LocalDateTime handledAt) { this.handledAt = handledAt; }
}
