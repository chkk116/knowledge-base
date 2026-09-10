package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资产借还记录。一条资产同一时刻最多一条 status=BORROWED 的活跃借出；归还后置为 RETURNED。
 */
@Entity
@Table(name = "kb_asset_borrow", indexes = {
        @Index(name = "idx_assetborrow_asset", columnList = "assetId"),
        @Index(name = "idx_assetborrow_status", columnList = "status")
})
public class AssetBorrow {

    public static final String STATUS_BORROWED = "BORROWED";
    public static final String STATUS_RETURNED = "RETURNED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Column(length = 80)
    private String borrower;

    @Column
    private Long borrowerId;

    @Column(length = 500)
    private String borrowReason;

    private LocalDateTime borrowAt = LocalDateTime.now();

    @Column
    private LocalDate expectedReturnAt;

    private LocalDateTime returnAt;

    @Column(length = 20)
    private String status = STATUS_BORROWED;

    @Column(length = 500)
    private String returnRemark;

    /** 瞬态：便于前端直接展示资产编号/名称 */
    @Transient
    private String assetCode;
    @Transient
    private String assetName;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }

    public String getBorrower() { return borrower; }
    public void setBorrower(String borrower) { this.borrower = borrower; }

    public Long getBorrowerId() { return borrowerId; }
    public void setBorrowerId(Long borrowerId) { this.borrowerId = borrowerId; }

    public String getBorrowReason() { return borrowReason; }
    public void setBorrowReason(String borrowReason) { this.borrowReason = borrowReason; }

    public LocalDateTime getBorrowAt() { return borrowAt; }
    public void setBorrowAt(LocalDateTime borrowAt) { this.borrowAt = borrowAt; }

    public LocalDate getExpectedReturnAt() { return expectedReturnAt; }
    public void setExpectedReturnAt(LocalDate expectedReturnAt) { this.expectedReturnAt = expectedReturnAt; }

    public LocalDateTime getReturnAt() { return returnAt; }
    public void setReturnAt(LocalDateTime returnAt) { this.returnAt = returnAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) {
        if (status == null) return;
        String s = status.trim().toUpperCase();
        if (STATUS_BORROWED.equals(s) || STATUS_RETURNED.equals(s)) this.status = s;
    }

    public String getReturnRemark() { return returnRemark; }
    public void setReturnRemark(String returnRemark) { this.returnRemark = returnRemark; }

    public String getAssetCode() { return assetCode; }
    public void setAssetCode(String assetCode) { this.assetCode = assetCode; }

    public String getAssetName() { return assetName; }
    public void setAssetName(String assetName) { this.assetName = assetName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
