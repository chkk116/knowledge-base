/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.UploadedFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UploadedFileRepository extends JpaRepository<UploadedFile, Long> {

    /** 某用户的文件（新的在前） */
    List<UploadedFile> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    /** 全部在册文件地址（供孤儿扫描排除：用户上传到文件中心但还没插进任何文档的文件，
     *  磁盘上"没人引用"，但它在「我的文件」里可见，绝不能当孤儿清理掉） */
    @Query("SELECT f.url FROM UploadedFile f WHERE f.url IS NOT NULL")
    List<String> findAllUrls();

    /** 某用户上传文件占用的总字节数（个人配额核算用，需与共享文档占用相加） */
    @Query("SELECT COALESCE(SUM(f.size), 0) FROM UploadedFile f WHERE f.ownerId = ?1")
    long sumSizeByOwnerId(Long ownerId);

    /** 同一用户是否已传过同样内容的文件（去重：命中且磁盘文件还在就复用，不再落新盘） */
    java.util.Optional<UploadedFile> findFirstByOwnerIdAndHash(Long ownerId, String hash);
}
