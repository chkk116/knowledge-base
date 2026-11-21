/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.LearningProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LearningProgressRepository extends JpaRepository<LearningProgress, Long> {
    /**
     * 把「未完成」改成「已完成」，返回受影响行数：并发提交同一章节时只有一条能拿到 1，
     * 另一条拿到 0 就不再发章节积分（原先是先查 finished 再保存，两个请求可能都判成首次）。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "update LearningProgress p set p.finished = true, p.progress = 100, p.lastAt = :now "
                    + "where p.userId = :uid and p.chapterId = :cid and p.finished = false")
    int markFinished(@org.springframework.data.repository.query.Param("uid") Long userId,
                     @org.springframework.data.repository.query.Param("cid") Long chapterId,
                     @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    List<LearningProgress> findByUserIdAndCourseId(Long userId, Long courseId);
    Optional<LearningProgress> findByUserIdAndChapterId(Long userId, Long chapterId);
    List<LearningProgress> findByUserId(Long userId);
    void deleteByUserId(Long userId);   // 自助注销：清空本人学习进度

    /** 删除章节时清掉对应学习进度，避免孤儿数据（调用方必须 @Transactional） */
    void deleteByChapterId(Long chapterId);
}
