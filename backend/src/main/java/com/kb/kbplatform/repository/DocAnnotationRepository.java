/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DocAnnotation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocAnnotationRepository extends JpaRepository<DocAnnotation, Long> {

    /** 某文档下「我的」批注，按创建时间倒序 */
    List<DocAnnotation> findByShareIdAndDocIdAndUserIdOrderByCreatedAtDesc(Long shareId, Long docId, Long userId);

    /** 归属校验用：只能改/删自己的批注 */
    Optional<DocAnnotation> findByIdAndUserId(Long id, Long userId);

    /** 「我的摘录」聚合页：跨文档的我的批注，按创建时间倒序 */
    List<DocAnnotation> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    /** 文档被删除时清理其批注 */
    void deleteByDocId(Long docId);

    /** 账号注销时清理 */
    void deleteByUserId(Long userId);
}
