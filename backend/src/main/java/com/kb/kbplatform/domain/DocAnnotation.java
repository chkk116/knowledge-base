/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 文档批注 / 摘录（2026-10-15）。
 *
 * 与正文解耦：只保存「引用文本 + 上下文锚点 + 我的笔记」，绝不回写 SharedDocument.content，
 * 因此不会污染协同编辑的正文，也不会生成正文版本快照。
 * 上下文锚点（prefix/suffix）用于在正文里定位并高亮被引用的片段。
 */
@Entity
@Table(name = "kb_doc_annotation", indexes = {
        @Index(name = "idx_anno_doc", columnList = "shareId,docId"),
        @Index(name = "idx_anno_user", columnList = "userId,createdAt")
})
public class DocAnnotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shareId;

    @Column(nullable = false)
    private Long docId;

    /** 批注作者：批注仅本人可见 */
    @Column(nullable = false)
    private Long userId;

    /** 被引用的原文片段 */
    @Column(nullable = false, length = 500)
    private String quote;

    /** 我写的笔记（可为空——只摘录不写笔记也允许） */
    @Column(length = 2000)
    private String note;

    /** 引用片段之前的若干字符，用于在正文中唯一定位 */
    @Column(length = 80)
    private String prefix;

    /** 引用片段之后的若干字符，用于在正文中唯一定位 */
    @Column(length = 80)
    private String suffix;

    /** 展示用：所在文档标题（服务层注入，不入库） */
    @Transient
    private String docTitle;

    /** 展示用：所在空间名称（服务层注入，不入库） */
    @Transient
    private String spaceName;

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getShareId() { return shareId; }
    public void setShareId(Long shareId) { this.shareId = shareId; }

    public Long getDocId() { return docId; }
    public void setDocId(Long docId) { this.docId = docId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getQuote() { return quote; }
    public void setQuote(String quote) { this.quote = quote; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }

    public String getSuffix() { return suffix; }
    public void setSuffix(String suffix) { this.suffix = suffix; }

    public String getDocTitle() { return docTitle; }
    public void setDocTitle(String docTitle) { this.docTitle = docTitle; }

    public String getSpaceName() { return spaceName; }
    public void setSpaceName(String spaceName) { this.spaceName = spaceName; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
