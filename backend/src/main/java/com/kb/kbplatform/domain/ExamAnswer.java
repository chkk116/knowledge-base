/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "kb_exam_answer")
public class ExamAnswer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long recordId;

    @Column(nullable = false)
    private Long questionId;

    @Column(columnDefinition = "TEXT")
    private String answer;           // 用户作答：客观题存选项 label 逗号拼接，填空/简答存文本

    private boolean correct = false;

    private int score = 0;           // 本题得分；主观题默认 0（待人工阅卷）

    /**
     * 主观题待人工阅卷：true = 交卷时未判分（FILL/ESSAY），等管理员在「主观题阅卷」页给分。
     * 客观题恒为 false。阅卷完成后置 false 并写入 score。
     */
    @Column(nullable = false)
    private boolean pending = false;

    /** 阅卷批注（可选）：管理员给主观题写扣分原因，学员在成绩详情可见 */
    @Column(length = 500)
    private String comment;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRecordId() { return recordId; }
    public void setRecordId(Long recordId) { this.recordId = recordId; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public boolean isCorrect() { return correct; }
    public void setCorrect(boolean correct) { this.correct = correct; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public boolean isPending() { return pending; }
    public void setPending(boolean pending) { this.pending = pending; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}
