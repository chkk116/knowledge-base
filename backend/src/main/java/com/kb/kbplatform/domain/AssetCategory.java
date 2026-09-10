package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 资产分类（支持两级树：parentId 为 null 表示一级分类）。
 * 前端以扁平列表拉取后自行构建树。
 */
@Entity
@Table(name = "kb_asset_category", indexes = {
        @Index(name = "idx_assetcategory_parent", columnList = "parentId")
})
public class AssetCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;

    /** 父分类 id；null 表示一级分类 */
    @Column
    private Long parentId;

    @Column(nullable = false)
    private int sort = 0;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public int getSort() { return sort; }
    public void setSort(int sort) { this.sort = sort; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
