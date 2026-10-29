/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * AI 问答日志：每次 AI 回答落一行，用于「赞/踩反馈」与「知识缺口看板」。
 *
 * 设计要点：
 *  - hit 表示本次检索是否命中知识库；通用模式（mode=general）恒为 false，统计命中率时应排除。
 *  - rating 为空表示用户未评价；1=赞，-1=踩，reason 仅在踩时记录。
 *  - 正文做截断存储：反馈看板只需要能看懂问题，不需要完整长文，避免把消息表再撑大一遍。
 */
@Entity
@Table(name = "kb_ai_qa_log")
public class AiQaLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(columnDefinition = "text")
    private String question;

    @Column(columnDefinition = "text")
    private String answer;

    @Column(length = 20)
    private String mode;

    /** 本次检索是否命中知识库（通用模式恒 false） */
    @Column(nullable = false)
    private boolean hit = false;

    @Column(name = "source_count", nullable = false)
    private int sourceCount = 0;

    @Column(name = "sources_json", columnDefinition = "text")
    private String sourcesJson;

    /** null=未评价；1=赞；-1=踩 */
    private Integer rating;

    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "rated_at")
    private LocalDateTime ratedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getAnswer() { return answer; }
    public void setAnswer(String answer) { this.answer = answer; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public boolean isHit() { return hit; }
    public void setHit(boolean hit) { this.hit = hit; }

    public int getSourceCount() { return sourceCount; }
    public void setSourceCount(int sourceCount) { this.sourceCount = sourceCount; }

    public String getSourcesJson() { return sourcesJson; }
    public void setSourcesJson(String sourcesJson) { this.sourcesJson = sourcesJson; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getRatedAt() { return ratedAt; }
    public void setRatedAt(LocalDateTime ratedAt) { this.ratedAt = ratedAt; }
}
