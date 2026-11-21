/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** 共享文档版本快照：每次「保存版本」或编辑前自动留档，支持历史回溯。
 *  yjs_state 保存协同态二进制，恢复后协同中继在下次加载时回放。 */
@Entity
@Table(name = "kb_shared_doc_version", indexes = {
        @Index(name = "idx_sdocver_doc", columnList = "sharedDocId,createdAt")
})
public class SharedDocVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sharedDocId;     // 所属共享文档
    private String title;
    @Column(columnDefinition = "text")
    private String content;       // HTML 快照
    @JdbcTypeCode(SqlTypes.LONGVARBINARY)   // PG 映射 bytea（LENGTH 可用），MySQL 映射 longblob
    @Column(name = "yjs_state", columnDefinition = "bytea")
    private byte[] yjsState;      // 协同 Yjs 状态快照（精确还原用）
    /** RESTORE = 回滚前自动留的备份；空/NORMAL = 用户手动快照与保存产生的版本 */
    @Column(name = "kind", length = 16)
    private String kind;

    private Long editorId;        // 保存者
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getSharedDocId() { return sharedDocId; }
    public void setSharedDocId(Long sharedDocId) { this.sharedDocId = sharedDocId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public byte[] getYjsState() { return yjsState; }
    public void setYjsState(byte[] yjsState) { this.yjsState = yjsState; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Long getEditorId() { return editorId; }
    public void setEditorId(Long editorId) { this.editorId = editorId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
