package com.kb.kbplatform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "kb_exam_paper_question")
public class ExamPaperQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long paperId;

    @Column(nullable = false)
    private Long questionId;

    private int score = 0;            // 本题分值

    private int sortOrder = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getPaperId() { return paperId; }
    public void setPaperId(Long paperId) { this.paperId = paperId; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
