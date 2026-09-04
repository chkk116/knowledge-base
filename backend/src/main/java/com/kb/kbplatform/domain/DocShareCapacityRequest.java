package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 共享空间扩容申请：空间管理员/所有者提交，系统完全管理员（ADMIN 角色）审批/驳回。
 * 审批通过后由服务自动将空间容量上限调整为申请值。
 */
@Entity
@Table(name = "kb_doc_share_capacity_request")
public class DocShareCapacityRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long shareId;                 // 关联共享空间
    private Long applicantId;            // 申请人 id
    private String applicantName;        // 申请人用户名（冗余，便于展示）
    private Long requestedCapacity;      // 申请的目标容量上限（字节；0 = 不限）
    private Long currentCapacity;        // 申请时空间当前容量快照（字节，可空=不限）

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

    @Transient
    private String shareName;            // 列表展示用，非持久化

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getShareId() { return shareId; }
    public void setShareId(Long shareId) { this.shareId = shareId; }
    public Long getApplicantId() { return applicantId; }
    public void setApplicantId(Long applicantId) { this.applicantId = applicantId; }
    public String getApplicantName() { return applicantName; }
    public void setApplicantName(String applicantName) { this.applicantName = applicantName; }
    public Long getRequestedCapacity() { return requestedCapacity; }
    public void setRequestedCapacity(Long requestedCapacity) { this.requestedCapacity = requestedCapacity; }
    public Long getCurrentCapacity() { return currentCapacity; }
    public void setCurrentCapacity(Long currentCapacity) { this.currentCapacity = currentCapacity; }
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
    public String getShareName() { return shareName; }
    public void setShareName(String shareName) { this.shareName = shareName; }
}
