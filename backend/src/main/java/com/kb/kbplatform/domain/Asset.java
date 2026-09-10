package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 固定资产台账。资产编号全局唯一（code），状态为枚举字符串（在用/闲置/维修/报废）。
 * categoryName、borrowStatus 为列表/详情展示用的瞬态字段，由 Service 注入，不入库。
 */
@Entity
@Table(name = "kb_asset", indexes = {
        @Index(name = "idx_asset_code", columnList = "code", unique = true),
        @Index(name = "idx_asset_category", columnList = "categoryId"),
        @Index(name = "idx_asset_status", columnList = "status"),
        @Index(name = "idx_asset_owner", columnList = "ownerName")
})
public class Asset {

    public static final String STATUS_IN_USE = "IN_USE";     // 在用
    public static final String STATUS_IDLE = "IDLE";         // 闲置
    public static final String STATUS_REPAIR = "REPAIR";     // 维修
    public static final String STATUS_SCRAPPED = "SCRAPPED"; // 报废

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 资产编号，全局唯一，如 AS000123 */
    @Column(nullable = false, length = 40, unique = true)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column
    private Long categoryId;

    @Column(length = 120)
    private String brand;

    @Column(length = 120)
    private String model;

    @Column(length = 120)
    private String sn;

    @Column(length = 20)
    private String status = STATUS_IN_USE;

    @Column(length = 80)
    private String ownerName;

    @Column
    private Long ownerId;

    @Column(length = 80)
    private String department;

    @Column(length = 120)
    private String location;

    @Column
    private LocalDate purchaseDate;

    @Column(precision = 14, scale = 2)
    private BigDecimal price;

    @Column
    private LocalDate warrantyExpire;

    @Column(length = 120)
    private String supplier;

    @Column(length = 1000)
    private String remark;

    @Column(length = 500)
    private String imageUrl;

    @Column(length = 500)
    private String attachmentUrl;

    /** 分类名称（瞬态，由 Service 注入） */
    @Transient
    private String categoryName;

    /** 借出状态（瞬态）：BORROWED 借出中 / AVAILABLE 可借 */
    @Transient
    private String borrowStatus;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = (code == null ? null : code.trim()); }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getSn() { return sn; }
    public void setSn(String sn) { this.sn = sn; }

    public String getStatus() { return status; }
    public void setStatus(String status) {
        if (status == null) return;
        String s = status.trim().toUpperCase();
        if (STATUS_IN_USE.equals(s) || STATUS_IDLE.equals(s) || STATUS_REPAIR.equals(s) || STATUS_SCRAPPED.equals(s)) {
            this.status = s;
        }
    }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public LocalDate getPurchaseDate() { return purchaseDate; }
    public void setPurchaseDate(LocalDate purchaseDate) { this.purchaseDate = purchaseDate; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public LocalDate getWarrantyExpire() { return warrantyExpire; }
    public void setWarrantyExpire(LocalDate warrantyExpire) { this.warrantyExpire = warrantyExpire; }

    public String getSupplier() { return supplier; }
    public void setSupplier(String supplier) { this.supplier = supplier; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getAttachmentUrl() { return attachmentUrl; }
    public void setAttachmentUrl(String attachmentUrl) { this.attachmentUrl = attachmentUrl; }

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }

    public String getBorrowStatus() { return borrowStatus; }
    public void setBorrowStatus(String borrowStatus) { this.borrowStatus = borrowStatus; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
