/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    @Column(name = "is_correct")
    private boolean isCorrect = false;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getQuestionId() { return questionId; }
    public void setQuestionId(Long questionId) { this.questionId = questionId; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    /**
     * ⚠️ 字段名必须显式声明成 isCorrect。
     *
     * 这个类写的是 `boolean isCorrect` + `isCorrect()` / `setCorrect()`，
     * 按 JavaBean 规范 Jackson 会把属性名推断成 **correct**——于是前端一直传的
     * `isCorrect` 被当成未知字段丢掉，所有选项的正确答案都变成 false，
     * 建题接口报「客观题必须至少有一个正确选项」（2026-10-20 排查 AI 出题入库失败时发现）。
     * 前端（ExamManage / AI 出题 / 移动端）统一读写 `isCorrect`，这里对齐。
     */
    @JsonProperty("isCorrect")
    public boolean isCorrect() { return isCorrect; }

    @JsonProperty("isCorrect")
    public void setCorrect(boolean correct) { isCorrect = correct; }
}
