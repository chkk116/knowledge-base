/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 练习作答流水（2026-10-21）。
 *
 * 只用于「错题本 + 练习统计」，不参与考试成绩：考试仍走 kb_exam_record。
 * 每题每答一次写一条，错题本取每道题最近一次结果（答对即视为已掌握，自动移出）。
 */
@Entity
@Table(name = "kb_practice_log", indexes = {
        @Index(name = "idx_plog_user", columnList = "user_id"),
        @Index(name = "idx_plog_user_q", columnList = "user_id,question_id")
})
public class PracticeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "question_id", nullable = false)
    private Long questionId;

    @Column(nullable = false)
    private boolean correct;

    /** 作答内容（label 逗号拼接或文本），只用于复盘，截断 500 字 */
    @Column(length = 500)
    private String answer;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public boolean isCorrect() { return correct; }
    public void setCorrect(boolean correct) { this.correct = correct; }
    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
