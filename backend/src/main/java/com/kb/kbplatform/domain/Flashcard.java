/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 复习卡片（2026-10-19）。
 *
 * 「知识库」的收集链路（速记 / 拍照 / 剪藏）解决「存得下」，
 * 卡片解决「记得住」：正面提问、背面答案，按 SM-2 间隔重复推给你复习。
 *
 * 卡片**仅本人可见可改**（与学习计划、摘录一致），可以挂到某篇文档上做溯源，
 * 但文档删除后卡片仍然保留——复习内容是用户的私人记忆，不该被文档生命周期带走。
 */
@Entity
@Table(name = "kb_flashcard", indexes = {
        @Index(name = "idx_card_user_due", columnList = "userId,dueAt")
})
public class Flashcard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** 溯源：来自哪个空间的哪篇文档（可为空） */
    private Long shareId;
    private Long docId;

    @Column(nullable = false, columnDefinition = "text")
    private String front;

    @Column(nullable = false, columnDefinition = "text")
    private String back;

    /** 来源原文片段（从摘录生成时保留，便于回看上下文） */
    @Column(columnDefinition = "text")
    private String source;

    /** 下次复习时间 */
    @Column(nullable = false)
    private LocalDateTime dueAt = LocalDateTime.now();

    /** 当前间隔（天）；0 表示新卡还没复习过 */
    @Column(nullable = false)
    private int intervalDays = 0;

    /** SM-2 的难度因子，越小越难，下限 1.3 */
    @Column(nullable = false)
    private double easeFactor = 2.5;

    /** 累计复习次数 */
    @Column(nullable = false)
    private int reps = 0;

    /** 遗忘次数（评「重来」的次数） */
    @Column(nullable = false)
    private int lapses = 0;

    private LocalDateTime lastReviewedAt;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Long getShareId() { return shareId; }
    public void setShareId(Long shareId) { this.shareId = shareId; }

    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }

    public String getFront() { return front; }
    public void setFront(String front) { this.front = front; }

    public String getBack() { return back; }
    public void setBack(String back) { this.back = back; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public LocalDateTime getDueAt() { return dueAt; }
    public void setDueAt(LocalDateTime dueAt) { this.dueAt = dueAt; }

    public int getIntervalDays() { return intervalDays; }
    public void setIntervalDays(int intervalDays) { this.intervalDays = intervalDays; }

    public double getEaseFactor() { return easeFactor; }
    public void setEaseFactor(double easeFactor) { this.easeFactor = easeFactor; }

    public int getReps() { return reps; }
    public void setReps(int reps) { this.reps = reps; }

    public int getLapses() { return lapses; }
    public void setLapses(int lapses) { this.lapses = lapses; }

    public LocalDateTime getLastReviewedAt() { return lastReviewedAt; }
    public void setLastReviewedAt(LocalDateTime lastReviewedAt) { this.lastReviewedAt = lastReviewedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
