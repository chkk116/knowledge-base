package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.PostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface LikeRepository extends JpaRepository<PostLike, Long> {

    boolean existsByUserIdAndDocId(Long userId, Long docId);

    void deleteByUserIdAndDocId(Long userId, Long docId);

    List<PostLike> findByUserId(Long userId);
}
