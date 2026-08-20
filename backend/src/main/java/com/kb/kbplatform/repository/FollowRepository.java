package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface FollowRepository extends JpaRepository<Follow, Long> {
    boolean existsByFollowerIdAndFollowingId(Long followerId, Long followingId);
    long countByFollowingId(Long followingId);
    long countByFollowerId(Long followerId);
    void deleteByFollowerIdAndFollowingId(Long followerId, Long followingId);

    /** 我关注的人：以「我」为粉丝，取全部关注关系（按 id 倒序，最新关注在前） */
    List<Follow> findByFollowerIdOrderByFollowingIdDesc(Long followerId);
    /** 我的粉丝：以「我」为被关注者，取全部关注关系 */
    List<Follow> findByFollowingIdOrderByFollowerIdDesc(Long followingId);
}
