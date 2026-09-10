package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByTitleContainingIgnoreCase(String title);

    /**
     * 本地 MySQL 兜底检索：标题/正文/标签/分类任一包含关键字即命中（不依赖 Meilisearch）。
     * 必须限定 PUBLISHED：搜索接口是免登录公开的，若不过滤状态会泄露草稿/待审核/驳回文档的正文摘要。
     */
    @Query("SELECT d FROM Document d WHERE d.status = 'PUBLISHED' "
            + "AND (:q IS NULL OR :q = '' OR LOWER(d.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(d.content) LIKE LOWER(CONCAT('%', :q, '%')) "
            + "OR LOWER(d.tags) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(d.category) LIKE LOWER(CONCAT('%', :q, '%')))")
    List<Document> search(@Param("q") String q);

    /** 我的帖子：按作者倒序 */
    List<Document> findByOwnerIdOrderByUpdatedAtDesc(Long ownerId);

    /** 某作者指定状态的文档：按更新时间倒序（按作者搜 的 MySQL 兜底路径用，返回 List 无需分页） */
    List<Document> findByOwnerIdAndStatusOrderByUpdatedAtDesc(Long ownerId, String status);

    /** 知识空间文档树：取某空间下指定状态的全部文档，按同级排序号、id 升序（由服务层在内存中组装成树） */
    /** 文档树：取某状态下的全部文档（不含 content 大字段由调用方控制），用于构建全局文档树 */
    List<Document> findByStatusOrderBySortOrderAscIdAsc(String status);

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

    /** 指定状态且指定时间之后新增的文档数（首页概览：本周新增已发布） */
    long countByStatusAndCreatedAtAfter(String status, java.time.LocalDateTime t);

    /** 指定时间之后新增的文档数（看板：今日新增） */
    long countByCreatedAtAfter(java.time.LocalDateTime t);

    /** 时间区间内新增文档数（看板：近7天趋势 / 昨日环比） */
    long countByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);

    /** 热门文章 TopN（看板排行，按浏览量倒序） */
    List<Document> findTop5ByStatusOrderByViewsDesc(String status);

    /** 最新发布 TopN（看板「实时动态」取最近发布的文章） */
    List<Document> findTop6ByStatusOrderByCreatedAtDesc(String status);

    /** 作者累计获赞数（已发布文档 likes 求和） */
    @Query("SELECT COALESCE(SUM(d.likes), 0) FROM Document d WHERE d.ownerId = :owner AND d.status = 'PUBLISHED'")
    long sumLikesByOwner(@Param("owner") Long owner);

    /** 审核队列：按状态倒序 */
    List<Document> findByStatusOrderByUpdatedAtDesc(String status);

    /** 附件管理：仅取正文列做 /uploads 引用扫描（标量投影，避免为扫引用而全量加载 Document 实体） */
    @Query("SELECT d.content FROM Document d WHERE d.content IS NOT NULL")
    List<String> findAllContents();

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
    @Query("SELECT d FROM Document d WHERE d.status <> 'DRAFT' AND (:kw IS NULL OR :kw = '' "
            + "OR LOWER(d.title) LIKE LOWER(CONCAT('%', :kw, '%')) OR LOWER(d.content) LIKE LOWER(CONCAT('%', :kw, '%')) "
            + "OR LOWER(d.category) LIKE LOWER(CONCAT('%', :kw, '%')) OR LOWER(d.tags) LIKE LOWER(CONCAT('%', :kw, '%')))")
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

    /** 浏览量原子自增（UPDATE ... SET views = views + 1），避免并发 read-modify-write 丢更新 */
    @Modifying
    @Query("UPDATE Document d SET d.views = d.views + 1 WHERE d.id = :id")
    void incrementViews(@Param("id") Long id);

    /** 点赞数原子自增：并发点赞不再丢更新 */
    @Modifying
    @Query("UPDATE Document d SET d.likes = d.likes + 1 WHERE d.id = :id")
    void incrementLikes(@Param("id") Long id);

    /** 点赞数原子自减（下限 0，防并发下溢成负数） */
    @Modifying
    @Query("UPDATE Document d SET d.likes = CASE WHEN d.likes > 0 THEN d.likes - 1 ELSE 0 END WHERE d.id = :id")
    void decrementLikes(@Param("id") Long id);

    /** 取最新点赞数（标量查询，绕过一级缓存） */
    @Query("SELECT d.likes FROM Document d WHERE d.id = :id")
    Integer findLikesById(@Param("id") Long id);

    /** 取最新浏览量（标量查询，绕过一级缓存，用于热帖阈值判定） */
    @Query("SELECT d.views FROM Document d WHERE d.id = :id")
    Integer findViewsById(@Param("id") Long id);

    // ---------- 回收站（native 查询，绕过 @SQLRestriction 的 deleted=false 过滤） ----------

    /** 全部已软删除文档（管理员回收站），按删除时间倒序 */
    @Query(value = "SELECT * FROM kb_document WHERE deleted = true ORDER BY deleted_at DESC", nativeQuery = true)
    List<Document> findRecycled();

    /** 某用户的已软删除文档（个人回收站），按删除时间倒序 */
    @Query(value = "SELECT * FROM kb_document WHERE deleted = true AND owner_id = ?1 ORDER BY deleted_at DESC", nativeQuery = true)
    List<Document> findRecycledByOwner(Long ownerId);

    /** 绕过软删除限制，按 id 取文档（恢复/彻底删除用） */
    @Query(value = "SELECT * FROM kb_document WHERE id = ?1", nativeQuery = true)
    Optional<Document> findIncludingDeletedById(Long id);

    /** 恢复：清除软删除标记 */
    @Modifying
    @Query(value = "UPDATE kb_document SET deleted = false, deleted_at = NULL WHERE id = ?1", nativeQuery = true)
    int undeleteById(Long id);

    /** 彻底删除（物理删除） */
    @Modifying
    @Query(value = "DELETE FROM kb_document WHERE id = ?1", nativeQuery = true)
    int hardDeleteById(Long id);

    // ---------- 相关文档推荐（轻量投影：只取列表展示需要的列，不拉 content 大字段） ----------

    /** 候选集：已发布、排除自身；源文档有分类时只取同分类，无分类时取全站。按浏览量倒序截断（由 Pageable 控制条数） */
    @Query("SELECT d.id, d.title, d.category, d.tags, d.views FROM Document d " +
           "WHERE d.status = 'PUBLISHED' AND d.id <> :id AND (:cat IS NULL OR d.category = :cat) " +
           "ORDER BY d.views DESC")
    List<Object[]> findRelatedCandidates(@Param("id") Long id, @Param("cat") String cat, Pageable pageable);

    /** 兜底集：相关度不足时，按浏览量取高浏览文档（排除自身与已选中的 id）。
     *  调用方必须保证 excluded 非空——空集合会让 JPQL 生成 `NOT IN ()` 而报 SQL 语法错误。 */
    @Query("SELECT d.id, d.title, d.category, d.tags, d.views FROM Document d " +
           "WHERE d.status = 'PUBLISHED' AND d.id <> :id AND d.id NOT IN :excluded " +
           "ORDER BY d.views DESC")
    List<Object[]> findPopularExcept(@Param("id") Long id, @Param("excluded") List<Long> excluded, Pageable pageable);

    /** 回收站自动清理：取删除时间早于截止点（已超保留期）的软删除文档，只回必要列（不拉 content 大字段）。
     *  返回每行 [id, title, owner_id]。同样用 native 查询绕过 @SQLRestriction。 */
    @Query(value = "SELECT id, title, owner_id FROM kb_document WHERE deleted = true AND deleted_at < ?1", nativeQuery = true)
    List<Object[]> findExpiredRecycled(java.time.LocalDateTime cutoff);
}
