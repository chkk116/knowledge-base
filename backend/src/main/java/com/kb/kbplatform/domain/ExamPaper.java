/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kb_exam_paper")
public class ExamPaper {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    private int totalScore = 0;

    private int passScore = 60;

    /**
     * 是否允许学员以「练习模式」作答（不落成绩、逐题看解析）。
     * 用包装类型并允许为 null：老数据没有这列，null 视为允许（2026-10-21）。
     *
     * ⚠️ N100：不要把默认值写成 Boolean.TRUE —— PUT /papers/{id} 用本类接收请求体，
     * Jackson 反序列化新对象时该字段会是 TRUE（而非 null），导致「只传 status 发布」
     * 把库里的 False 静默翻成 True。未传一律保持 null，由 adminSavePaper 在新建时补默认。
     */
    private Boolean practiceEnabled;

    /**
     * 是否为全站「模拟卷」：试卷自身的属性，在试卷列表的操作里直接勾选（2026-10-21 改）。
     * 全站最多一份；设为 true 时服务端会把其它试卷的该标记清掉。
     */
    private Boolean mockEnabled = Boolean.FALSE;

    private Integer durationMin = 0;

    @Column(nullable = false)
    private String status = "PUBLISHED";

    private Long creatorId;

    private LocalDateTime createdAt = LocalDateTime.now();

    @Transient
    private int questionCount = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public int getTotalScore() { return totalScore; }
    public void setTotalScore(int totalScore) { this.totalScore = totalScore; }
    public int getPassScore() { return passScore; }
    public void setPassScore(int passScore) { this.passScore = passScore; }
    public Boolean getPracticeEnabled() { return practiceEnabled; }
    public void setPracticeEnabled(Boolean practiceEnabled) { this.practiceEnabled = practiceEnabled; }
    public Boolean getMockEnabled() { return mockEnabled; }
    public void setMockEnabled(Boolean mockEnabled) { this.mockEnabled = mockEnabled; }
    public Integer getDurationMin() { return durationMin; }
    public void setDurationMin(Integer durationMin) { this.durationMin = durationMin; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public int getQuestionCount() { return questionCount; }
    public void setQuestionCount(int questionCount) { this.questionCount = questionCount; }
}
