/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 学习计划条目（2026-10-15）。
 * type=DOC 时 refShareId/refId 指向空间文档；type=COURSE 时 refId 指向课程；TEXT 为自定事项。
 */
@Entity
@Table(name = "kb_study_plan_item", indexes = {
        @Index(name = "idx_plan_item_plan", columnList = "planId,sortOrder")
})
public class StudyPlanItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long planId;

    /** DOC / COURSE / TEXT */
    @Column(nullable = false, length = 20)
    private String type = "TEXT";

    /** 文档 id（DOC）或课程 id（COURSE）；TEXT 为空 */
    private Long refId;

    /** 文档所在空间 id（仅 DOC 使用，用于跳转） */
    private Long refShareId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean done = false;

    private LocalDateTime doneAt;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private int sortOrder = 0;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPlanId() { return planId; }
    public void setPlanId(Long planId) { this.planId = planId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getRefId() { return refId; }
    public void setRefId(Long refId) { this.refId = refId; }

    public Long getRefShareId() { return refShareId; }
    public void setRefShareId(Long refShareId) { this.refShareId = refShareId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public boolean isDone() { return done; }
    public void setDone(boolean done) { this.done = done; }

    public LocalDateTime getDoneAt() { return doneAt; }
    public void setDoneAt(LocalDateTime doneAt) { this.doneAt = doneAt; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
