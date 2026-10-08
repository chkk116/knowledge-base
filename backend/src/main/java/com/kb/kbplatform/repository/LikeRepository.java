/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LikeRepository extends JpaRepository<PostLike, Long> {

    boolean existsByUserIdAndDocId(Long userId, Long docId);

    void deleteByUserIdAndDocId(Long userId, Long docId);

    List<PostLike> findByUserId(Long userId);
    void deleteByUserId(Long userId);   // 自助注销：清空本人帖子点赞

    /** 彻底删除文档时清理关联记录（F12/purge：不留孤儿数据） */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM PostLike x WHERE x.docId = :docId")
    void deleteByDocId(@org.springframework.data.repository.query.Param("docId") Long docId);
}
