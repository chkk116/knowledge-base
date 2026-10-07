/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    List<Comment> findByDocIdOrderByCreatedAtAsc(Long docId);

    /** 评论点赞数原子自增：并发点赞不再丢更新 */
    @Modifying
    @Query("UPDATE Comment c SET c.likes = c.likes + 1 WHERE c.id = :id")
    void incrementLikes(@Param("id") Long id);

    /** 评论点赞数原子自减（下限 0，防并发下溢成负数） */
    @Modifying
    @Query("UPDATE Comment c SET c.likes = CASE WHEN c.likes > 0 THEN c.likes - 1 ELSE 0 END WHERE c.id = :id")
    void decrementLikes(@Param("id") Long id);

    /** 取最新评论点赞数（标量查询，绕过一级缓存） */
    @Query("SELECT c.likes FROM Comment c WHERE c.id = :id")
    Integer findLikesById(@Param("id") Long id);

    /** 按对象类型 + 目标 id 列出评论（DOC 帖子 / CHAPTER 章节） */
    List<Comment> findByTargetTypeAndDocIdOrderByCreatedAtAsc(String targetType, Long docId);

    List<Comment> findByUsernameOrderByCreatedAtDesc(String username);

    /** 时间区间内新增评论数（看板：近7天趋势 / 昨日环比） */
    long countByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    /** 指定时间之后新增的评论数（看板：今日新增评论） */
    long countByCreatedAtAfter(LocalDateTime t);

    /** 最新评论 TopN（看板「实时动态」） */
    List<Comment> findTop6ByOrderByCreatedAtDesc();

    /** 评论数最多的用户（看板「活跃用户」排行，按评论数倒序） */
    @Query("SELECT c.username, COUNT(c) FROM Comment c GROUP BY c.username ORDER BY COUNT(c) DESC")
    List<Object[]> countGroupByUsername();

    /** 彻底删除文档时清理关联记录（F12/purge：不留孤儿数据）。
     *  必须限定 targetType='DOC'：Comment.docId 复用为 targetId（CHAPTER 评论存章节 id），
     *  文档与章节分表自增、id 必然大量重叠——不加过滤会误删无辜章节的评论。 */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM Comment x WHERE x.docId = :docId AND x.targetType = 'DOC'")
    void deleteByDocId(@org.springframework.data.repository.query.Param("docId") Long docId);
    /** 自助注销：本人评论保留但匿名（userId 置 null，前端对空 userId 显示「已注销用户」） */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Comment c set c.userId = null where c.userId = :uid")
    void anonymizeByUserId(@org.springframework.data.repository.query.Param("uid") Long uid);
}
