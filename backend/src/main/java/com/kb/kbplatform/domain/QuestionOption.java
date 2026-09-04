package com.kb.kbplatform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "kb_question_option")
public class QuestionOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long questionId;

    /** 选项标识：A / B / C / D ... */
    @Column(nullable = false)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String content;

    private boolean isCorrect = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public boolean isCorrect() { return isCorrect; }
    public void setCorrect(boolean correct) { isCorrect = correct; }
}
