/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.SharedDocVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SharedDocVersionRepository extends JpaRepository<SharedDocVersion, Long> {

    /** 某共享文档的全部版本（倒序，最新在前） */
    @Query("SELECT v FROM SharedDocVersion v WHERE v.sharedDocId = :docId ORDER BY v.createdAt DESC")
    List<SharedDocVersion> findBySharedDocIdOrderByCreatedAtDesc(@Param("docId") Long docId);
}
