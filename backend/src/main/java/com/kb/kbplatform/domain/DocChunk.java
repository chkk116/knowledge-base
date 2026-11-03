/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * AI 语义检索（向量 RAG）的文档分块。
 *
 * 设计取舍（2026-11-03）：不引入 pgvector——个人/小团队知识库的分块量级在几千，
 * 全量载入内存算余弦 <20ms，而 pgvector 需要每个部署环境装扩展（Windows/宝塔都麻烦）。
 * embedding 以 JSON 浮点数组存 text 列，将来量级上来再迁 pgvector，接口不变。
 */
@Entity
@Table(name = "kb_doc_chunk",
        uniqueConstraints = @UniqueConstraint(name = "uk_doc_chunk", columnNames = {"doc_id", "chunk_idx"}))
public class DocChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Column(name = "chunk_idx", nullable = false)
    private Integer chunkIdx;

    /** 分块正文（已转纯文本） */
    @Column(nullable = false, columnDefinition = "text")
    private String content;

    /** 向量，JSON 浮点数组形式 [0.12,0.83,...]；由 RagIndexService 写入、检索时解析算余弦 */
    @Column(columnDefinition = "text")
    private String embedding;

    /** 冗余文档标题：检索结果直接返回，省一次 join */
    @Column(name = "doc_title", length = 200)
    private String docTitle;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }
    public Integer getChunkIdx() { return chunkIdx; }
    public void setChunkIdx(Integer chunkIdx) { this.chunkIdx = chunkIdx; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
    public String getDocTitle() { return docTitle; }
    public void setDocTitle(String docTitle) { this.docTitle = docTitle; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
