package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.CommentLike;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CommentLikeRepository extends JpaRepository<CommentLike, Long> {

    boolean existsByUserIdAndCommentId(Long userId, Long commentId);

    void deleteByUserIdAndCommentId(Long userId, Long commentId);

    List<CommentLike> findByUserId(Long userId);
}
