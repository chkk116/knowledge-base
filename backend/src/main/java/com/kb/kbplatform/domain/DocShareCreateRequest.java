/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 团队空间创建申请：已拥有达到上限（默认 2 个）团队空间、但仍需创建更多空间的用户提交，
 * 由系统完全管理员（ADMIN 角色）审批/驳回。审批通过后由服务自动创建空间（申请人成为所有者）。
 * 与扩容申请的差异：扩容是调整已有空间容量，创建申请是新建空间。
 */
@Entity
@Table(name = "kb_doc_share_create_request")
public class DocShareCreateRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long applicantId;            // 申请人 id
    private String applicantName;        // 申请人用户名（冗余，便于展示）

    @Column(length = 120)
    private String requestedName;        // 申请创建的空间名称
    @Column(length = 500)
    private String requestedDescription; // 申请创建的空间描述
    @Column(length = 20)
    private String requestedColor;       // 申请空间的主题色键（indigo/teal/...）

    /** 申请时附带邀请的成员 id 列表，逗号分隔（可空）；审批通过后一并加入 */
    @Column(length = 500)
    private String requestedMembers;
    @Column(length = 16)
    private String requestedMemberPermission = "WRITE"; // 新成员统一权限

    @Column(length = 500)
    private String reason;               // 申请理由（可空）

    @Column(length = 16)
    private String status = "PENDING";  // PENDING / APPROVED / REJECTED

    private Long reviewerId;            // 审批人 id
    private String reviewerName;        // 审批人用户名

    @Column(length = 500)
    private String reviewComment;       // 驳回理由（可空）

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime reviewedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getApplicantId() { return applicantId; }
    public void setApplicantId(Long applicantId) { this.applicantId = applicantId; }
    public String getApplicantName() { return applicantName; }
    public void setApplicantName(String applicantName) { this.applicantName = applicantName; }
    public String getRequestedName() { return requestedName; }
    public void setRequestedName(String requestedName) { this.requestedName = requestedName; }
    public String getRequestedDescription() { return requestedDescription; }
    public void setRequestedDescription(String requestedDescription) { this.requestedDescription = requestedDescription; }
    public String getRequestedColor() { return requestedColor; }
    public void setRequestedColor(String requestedColor) { this.requestedColor = requestedColor; }
    public String getRequestedMembers() { return requestedMembers; }
    public void setRequestedMembers(String requestedMembers) { this.requestedMembers = requestedMembers; }
    public String getRequestedMemberPermission() { return requestedMemberPermission; }
    public void setRequestedMemberPermission(String requestedMemberPermission) { this.requestedMemberPermission = requestedMemberPermission; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public String getReviewerName() { return reviewerName; }
    public void setReviewerName(String reviewerName) { this.reviewerName = reviewerName; }
    public String getReviewComment() { return reviewComment; }
    public void setReviewComment(String reviewComment) { this.reviewComment = reviewComment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
}
