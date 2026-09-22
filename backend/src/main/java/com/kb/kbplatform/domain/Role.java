/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 角色（权限模板）。一个角色聚合一组白名单内的细粒度权限码；
 * 用户可同时拥有多个角色，其最终权限为「直接权限 ∪ 所有角色权限」。
 * builtin=true 的系统内置角色不可删除、改名（如「超级管理员」「普通成员」）。
 */
@Entity
@Table(name = "kb_role", uniqueConstraints = {
        @UniqueConstraint(columnNames = "name"),
        @UniqueConstraint(columnNames = "code")
})
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 60)
    private String name;        // 展示名，如「内容审核员」

    @Column(nullable = false, length = 60)
    private String code;        // 机器码，如 CONTENT_AUDITOR（唯一）

    @Column(length = 500)
    private String description;

    @Column(length = 2000)
    private String permissions = "";   // 细粒度权限码（逗号分隔，限白名单）

    private boolean builtin = false;   // 系统内置角色：不可删除/改名

    @Column(name = "sort_order")
    private int sortOrder = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();


    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }

    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
