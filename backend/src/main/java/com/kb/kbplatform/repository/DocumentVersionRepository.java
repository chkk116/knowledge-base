/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {
    List<DocumentVersion> findByDocIdOrderByCreatedAtDesc(Long docId);


    /** 彻底删除文档时清理其全部历史版本（purge 统一清理） */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM DocumentVersion v WHERE v.docId = :docId")
    void deleteByDocId(@org.springframework.data.repository.query.Param("docId") Long docId);
}
