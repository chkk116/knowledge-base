/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CommentLikeRepository extends JpaRepository<CommentLike, Long> {

    boolean existsByUserIdAndCommentId(Long userId, Long commentId);

    void deleteByUserIdAndCommentId(Long userId, Long commentId);

    List<CommentLike> findByUserId(Long userId);
    void deleteByUserId(Long userId);   // 自助注销：清空本人评论点赞

    /** 删除评论时清掉它的所有点赞记录，避免孤儿数据（调用方必须 @Transactional） */
    void deleteByCommentId(Long commentId);
}
