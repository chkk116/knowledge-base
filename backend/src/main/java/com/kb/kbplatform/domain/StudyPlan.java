/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 学习计划（2026-10-15）：把文档 / 课程 / 自定事项组成一个待办清单，记录完成进度，
 * 并可选设置复习提醒时间。计划与条目均**仅本人可见可改**。
 */
@Entity
@Table(name = "kb_study_plan", indexes = {
        @Index(name = "idx_plan_user", columnList = "userId,updatedAt")
})
public class StudyPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(length = 500)
    private String note;

    /** 复习提醒时间；null = 不提醒 */
    private LocalDateTime remindAt;

    /** 展示用：计划条目（服务层装配，不入库） */
    @Transient
    private List<StudyPlanItem> items;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public LocalDateTime getRemindAt() { return remindAt; }
    public void setRemindAt(LocalDateTime remindAt) { this.remindAt = remindAt; }

    public List<StudyPlanItem> getItems() { return items; }
    public void setItems(List<StudyPlanItem> items) { this.items = items; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
