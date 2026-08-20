package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByTitleContainingIgnoreCase(String title);

    /** 本地 MySQL 兜底检索：标题/正文/标签/分类任一包含关键字即命中（不依赖 Meilisearch）。 */
    @Query("SELECT d FROM Document d WHERE d.title LIKE %?1% OR d.content LIKE %?1% OR d.tags LIKE %?1% OR d.category LIKE %?1%")
    List<Document> search(String q);

    /** 我的帖子：按作者倒序 */
    List<Document> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    /** 某作者指定状态的文档：按更新时间倒序（按作者搜 的 MySQL 兜底路径用，返回 List 无需分页） */
    List<Document> findByOwnerIdAndStatusOrderByUpdatedAtDesc(Long ownerId, String status);

    /** 作者已发布文档（分页，供「他的文章」列表/侧边栏） */
    Page<Document> findByOwnerIdAndStatusOrderByCreatedAtDesc(Long ownerId, String status, Pageable pageable);
    Page<Document> findByOwnerIdAndStatusOrderByViewsDesc(Long ownerId, String status, Pageable pageable);
    /** 作者文档按评论数倒序（评论数来自 Comment 表，需子查询） */
    @Query("SELECT d FROM Document d WHERE d.ownerId = :ownerId AND d.status = :status " +
           "ORDER BY (SELECT COUNT(c) FROM Comment c WHERE c.docId = d.id) DESC, d.updatedAt DESC")
    Page<Document> findByOwnerIdAndStatusOrderByCommentsDesc(@Param("ownerId") Long ownerId, @Param("status") String status, Pageable pageable);
    /** 作者文档按收藏数倒序（收藏数来自 Favorite 表，需子查询） */
    @Query("SELECT d FROM Document d WHERE d.ownerId = :ownerId AND d.status = :status " +
           "ORDER BY (SELECT COUNT(f) FROM Favorite f WHERE f.docId = d.id) DESC, d.updatedAt DESC")
    Page<Document> findByOwnerIdAndStatusOrderByFavoritesDesc(@Param("ownerId") Long ownerId, @Param("status") String status, Pageable pageable);

    /** 作者原创（已发布）文档数 */
    long countByOwnerIdAndStatus(Long ownerId, String status);

    /** 按状态计数（后台看板统计：已发布/待审核/草稿/封禁） */
    long countByStatus(String status);

    /** 全站累计浏览量（看板） */
    @Query("SELECT COALESCE(SUM(d.views), 0) FROM Document d")
    long sumViews();

    /** 指定时间之后新增的文档数（看板：今日新增） */
    long countByCreatedAtAfter(java.time.LocalDateTime t);

    /** 作者累计获赞数（已发布文档 likes 求和） */
    @Query("SELECT COALESCE(SUM(d.likes), 0) FROM Document d WHERE d.ownerId = :owner AND d.status = 'PUBLISHED'")
    long sumLikesByOwner(@Param("owner") Long owner);

    /** 审核队列：按状态倒序 */
    List<Document> findByStatusOrderByUpdatedAtDesc(String status);

    /** 公开列表分页：仅已发布，按更新时间倒序 */
    Page<Document> findByStatusOrderByUpdatedAtDesc(String status, Pageable pageable);

    /** 公开列表分页：仅已发布，按发布时间（发帖时间）倒序（最新发帖） */
    Page<Document> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

    /** 公开列表分页：仅已发布，按浏览量倒序（热帖排序） */
    Page<Document> findByStatusOrderByViewsDesc(String status, Pageable pageable);

    /** 公开列表分页：仅已发布，按评论数倒序（最多评论） */
    @Query("SELECT d FROM Document d WHERE d.status = :status " +
           "ORDER BY (SELECT COUNT(c) FROM Comment c WHERE c.docId = d.id) DESC, d.updatedAt DESC")
    Page<Document> findByStatusOrderByCommentsDesc(@Param("status") String status, Pageable pageable);

    /** 公开列表分页：仅已发布，按收藏数倒序（最多收藏） */
    @Query("SELECT d FROM Document d WHERE d.status = :status " +
           "ORDER BY (SELECT COUNT(f) FROM Favorite f WHERE f.docId = d.id) DESC, d.updatedAt DESC")
    Page<Document> findByStatusOrderByFavoritesDesc(@Param("status") String status, Pageable pageable);

    /** 公开列表分页：仅已发布，排序完全由传入 Pageable 决定（用于置顶优先 + 子排序） */
    Page<Document> findByStatus(String status, Pageable pageable);

    /** 公开列表分页：仅已发布，置顶优先 + 评论数倒序 */
    @Query("SELECT d FROM Document d WHERE d.status = :status " +
           "ORDER BY d.pinned DESC, (SELECT COUNT(c) FROM Comment c WHERE c.docId = d.id) DESC, d.updatedAt DESC")
    Page<Document> findByStatusOrderByPinnedCommentsDesc(@Param("status") String status, Pageable pageable);

    /** 公开列表分页：仅已发布，置顶优先 + 收藏数倒序 */
    @Query("SELECT d FROM Document d WHERE d.status = :status " +
           "ORDER BY d.pinned DESC, (SELECT COUNT(f) FROM Favorite f WHERE f.docId = d.id) DESC, d.updatedAt DESC")
    Page<Document> findByStatusOrderByPinnedFavoritesDesc(@Param("status") String status, Pageable pageable);

    /** 按 id 集合 + 状态取文档（用于「我点赞的」「我评论的」），保持传入顺序 */
    List<Document> findByStatusAndIdIn(String status, List<Long> ids);

    /** 管理员检索：覆盖待审核/已发布/封禁，排除草稿（草稿属用户端未提交稿，不进管理后台）；
     *  标题/正文/分类/标签模糊匹配，keyword 为空返回全部 */
    @Query("SELECT d FROM Document d WHERE d.status <> 'DRAFT' AND (:kw IS NULL OR :kw = '' " +
           "OR d.title LIKE %:kw% OR d.content LIKE %:kw% OR d.category LIKE %:kw% OR d.tags LIKE %:kw%)")
    Page<Document> adminSearch(@Param("kw") String kw, Pageable pageable);

    /** 按标签筛选（tags 为逗号分隔字段，做边界匹配避免子串误命中）：仅已发布，按发布时间倒序 */
    @Query("SELECT d FROM Document d WHERE d.status = 'PUBLISHED' AND " +
           "(:tag IS NULL OR d.tags = :tag OR d.tags LIKE CONCAT(:tag, ',%') " +
           "OR d.tags LIKE CONCAT('%,', :tag) OR d.tags LIKE CONCAT('%,', :tag, ',%')) " +
           "ORDER BY d.createdAt DESC")
    Page<Document> findByTag(@Param("tag") String tag, Pageable pageable);

    /** 按标签筛选 + 按浏览量（热帖）倒序 */
    @Query("SELECT d FROM Document d WHERE d.status = 'PUBLISHED' AND " +
           "(:tag IS NULL OR d.tags = :tag OR d.tags LIKE CONCAT(:tag, ',%') " +
           "OR d.tags LIKE CONCAT('%,', :tag) OR d.tags LIKE CONCAT('%,', :tag, ',%')) " +
           "ORDER BY d.views DESC")
    Page<Document> findByTagHot(@Param("tag") String tag, Pageable pageable);

    /** 按分类筛选（仅已发布）：分类为空返回全部；排序由传入 Pageable 决定 */
    @Query("SELECT d FROM Document d WHERE d.status = :status AND " +
           "(:cat IS NULL OR :cat = '' OR d.category = :cat)")
    Page<Document> findByStatusAndCategory(@Param("status") String status, @Param("cat") String cat, Pageable pageable);

    /** 轻量聚合：仅取已发布文档的 category 列（不拉 content TEXT，避免全行回表） */
    @Query("SELECT DISTINCT d.category FROM Document d WHERE d.status = 'PUBLISHED' AND d.category IS NOT NULL")
    List<String> findDistinctCategories();

    /** 轻量聚合：仅取已发布文档的 tags 列（标签云计数用） */
    @Query("SELECT d.tags FROM Document d WHERE d.status = 'PUBLISHED' AND d.tags IS NOT NULL")
    List<String> findAllTagsRaw();
}
