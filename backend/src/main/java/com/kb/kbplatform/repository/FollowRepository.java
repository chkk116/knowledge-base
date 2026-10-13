/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Follow;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /** 分页版（外部审查 2026-10-13：原全量加载在大粉丝量下是 O(N) 内存 + 后续 N+1 查询）。
     *  排序按 Follow.id 倒序 = 关注时间倒序（id 自增单调），由调用方通过 Pageable 传入。 */
    Page<Follow> findByFollowerId(Long followerId, Pageable pageable);
    Page<Follow> findByFollowingId(Long followingId, Pageable pageable);
}
