package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CommentLikeRepository extends JpaRepository<CommentLike, Long> {

    boolean existsByUserIdAndCommentId(Long userId, Long commentId);

    void deleteByUserIdAndCommentId(Long userId, Long commentId);

    List<CommentLike> findByUserId(Long userId);

    /** 删除评论时清掉它的所有点赞记录，避免孤儿数据（调用方必须 @Transactional） */
    void deleteByCommentId(Long commentId);
}
