/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 部门（组织树节点）。采用 parentId 邻接表模型，前端按 parentId 自组装成树。
 * leaderId 为部门负责人（User.id），可空。
 */
@Entity
@Table(name = "kb_department")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "parent_id")
    private Long parentId;   // 顶级部门为 null

    @Column(name = "leader_id")
    private Long leaderId;   // 部门负责人（kb_user.id），可空

    @Column(name = "sort_order")
    private int sortOrder = 0;

    /** 部门编码（可选，便于与外部系统/工号体系对齐） */
    @Column(length = 50)
    private String code;

    /** ACTIVE（启用）/ DISABLED（停用）。停用只影响下拉可选性与展示，不影响既有成员归属 */
    @Column(length = 20)
    private String status = "ACTIVE";

    @Column(length = 500)
    private String description;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 直属成员数（服务层统计注入，不入库） */
    @Transient
    private int directCount;

    /** 含所有下级部门的成员数（服务层统计注入，不入库） */
    @Transient
    private int totalCount;

    /** 层级路径，如「技术中心 / 后端组」（服务层注入，不入库） */
    @Transient
    private String path;


    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }

    public Long getLeaderId() { return leaderId; }
    public void setLeaderId(Long leaderId) { this.leaderId = leaderId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getDirectCount() { return directCount; }
    public void setDirectCount(int directCount) { this.directCount = directCount; }

    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
