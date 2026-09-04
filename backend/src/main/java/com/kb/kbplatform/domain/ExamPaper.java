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
