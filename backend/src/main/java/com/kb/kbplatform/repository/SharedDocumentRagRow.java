/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.repository;

/**
 * RAG 检索专用投影：只取检索所需字段（含 Yjs 二进制正文），不构造 SharedDocument 实体。
 * 共享文档的协同正文只存在 yjsState 里，content 通常为空（保存快照时才写），两者都要取。
 */
public interface SharedDocumentRagRow {
    Long getId();
    Long getShareId();
    String getTitle();
    String getContent();
    byte[] getYjsState();
}
