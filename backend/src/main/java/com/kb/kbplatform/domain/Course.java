/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kb_course")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String cover;

    @Column(columnDefinition = "TEXT")
    private String summary;

    private String category;

    private int level = 1;            // 1 入门 2 进阶 3 高级

    private Long ownerId;

    @Transient
    private String ownerName;

    @Column(nullable = false)
    private String status = "PUBLISHED";

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    private Integer sortOrder = 0;

    /** 学习模式：FREE=自由学习 / GUIDED=引导学习（参照对标后台"学习模式"下拉） */
    @Column(length = 16)
    private String learningMode = "FREE";

    /** 自由学习：是否允许学员自由安排学习顺序 */
    private boolean freeLearn = true;

    /** 进度条：学员侧是否展示学习进度条 */
    private boolean progressBar = true;

    /** 课程标签：逗号分隔的中文短词，对齐对标后台"课程标签"chip 输入 */
    @Column(length = 512)
    private String tags;

    /** 课程正文：富文本 HTML（与章节 content 区分；本字段是课程自身总览/简介富文本） */
    @Column(columnDefinition = "TEXT")
    private String content;

    /** 结业考试：绑定的试卷 ID（可为空表示不绑定）；学员学完可参加该试卷的结业考 */
    private Long examPaperId;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getLevel() { return level; }
    public void setLevel(int level) { this.level = level; }
    public Long getOwnerId() { return ownerId; }
    public void setOwnerId(Long ownerId) { this.ownerId = ownerId; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public String getLearningMode() { return learningMode; }
    public void setLearningMode(String learningMode) { this.learningMode = learningMode; }
    public boolean isFreeLearn() { return freeLearn; }
    public void setFreeLearn(boolean freeLearn) { this.freeLearn = freeLearn; }
    public boolean isProgressBar() { return progressBar; }
    public void setProgressBar(boolean progressBar) { this.progressBar = progressBar; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getExamPaperId() { return examPaperId; }
    public void setExamPaperId(Long examPaperId) { this.examPaperId = examPaperId; }
}
