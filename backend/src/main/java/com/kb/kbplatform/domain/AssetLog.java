/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 资产变更流水（建档/修改/借出/归还/报废/删除等），best-effort 记录，不阻塞主流程。
 */
@Entity
@Table(name = "kb_asset_log", indexes = {
        @Index(name = "idx_assetlog_asset", columnList = "assetId"),
        @Index(name = "idx_assetlog_created", columnList = "createdAt")
})
public class AssetLog {

    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_BORROW = "BORROW";
    public static final String ACTION_RETURN = "RETURN";
    public static final String ACTION_SCRAP = "SCRAP";
    public static final String ACTION_TRANSFER = "TRANSFER";
    public static final String ACTION_DELETE = "DELETE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long assetId;

    @Column(length = 30)
    private String action;

    @Column
    private Long operatorId;

    @Column(length = 80)
    private String operatorName;

    @Column(length = 1000)
    private String detail;

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

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }

    public String getOperatorName() { return operatorName; }
    public void setOperatorName(String operatorName) { this.operatorName = operatorName; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getAssetCode() { return assetCode; }
    public void setAssetCode(String assetCode) { this.assetCode = assetCode; }

    public String getAssetName() { return assetName; }
    public void setAssetName(String assetName) { this.assetName = assetName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
