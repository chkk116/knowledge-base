/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    /** 按角色查询用户（用于「通知全体管理员」等场景） */
    List<User> findByRole(String role);

    /** 按部门查询成员（清理部门前解绑用） */
    List<User> findByDepartmentId(Long departmentId);

    /** 每个部门的成员数聚合（部门管理页展示直属人数用），返回 [departmentId, count] */
    @Query("SELECT u.departmentId, COUNT(u) FROM User u WHERE u.departmentId IS NOT NULL GROUP BY u.departmentId")
    List<Object[]> countGroupedByDepartmentId();

    /** 选人搜索空关键词场景：仅取前 N 个启用用户（DB LIMIT 分页），避免全表加载 */
    Page<User> findByEnabledTrue(Pageable pageable);

    /** 按作者名检索：用户名或昵称模糊匹配（搜索「按作者」筛选用） */
    List<User> findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(String username, String displayName);

    /** 管理员用户检索：用户名或昵称模糊匹配（keyword 为空时返回全部），可按角色 / 启用态过滤；
     *  role 为 null 不过滤角色，enabled 为 null 不过滤启用态；排序/分页由 Pageable 控制 */
    @Query("SELECT u FROM User u WHERE (:kw IS NULL OR :kw = '' OR LOWER(u.username) LIKE LOWER(CONCAT('%', :kw, '%')) OR LOWER(u.displayName) LIKE LOWER(CONCAT('%', :kw, '%'))) AND (:role IS NULL OR u.role = :role) AND (:enabled IS NULL OR u.enabled = :enabled)")
    Page<User> search(@Param("kw") String kw, @Param("role") String role, @Param("enabled") Boolean enabled, Pageable pageable);

    /** 看板统计：按角色 / 禁用态计数 */
    long countByRole(String role);
    /** 只统计启用中的管理员：已注销（软删除 enabled=false）/ 已停用的不应计入，
     *  否则两个管理员先后注销会双双通过唯一性检查，最后启用中的管理员为 0（外部复审 2026-10-08） */
    long countByRoleAndEnabled(String role, boolean enabled);
    long countByEnabledFalse();
    long countByCreatedAtAfter(java.time.LocalDateTime t);
    long countByCreatedAtBetween(java.time.LocalDateTime from, java.time.LocalDateTime to);
    /** 半开区间 [from, to) 计数（外部审查 2026-10-13：替代 BETWEEN 闭区间，避免跨日边界重复计数） */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(java.time.LocalDateTime from, java.time.LocalDateTime to);

    /** 积分排行中「排在我前面」的人数（points 降序、同名按 username 升序），用于计算当前用户名次；
     *  避免为求名次而全表加载所有用户到内存（原 ranking 接口的全表扫描隐患） */
    @Query("SELECT COUNT(u) FROM User u WHERE u.points > :mp OR (u.points = :mp AND u.username < :me)")
    long countRankedAbove(@Param("mp") int mp, @Param("me") String me);

    /** 原子增量更新积分余额（UPDATE ... SET points = points + :delta），避免并发 read-modify-write 丢更新 */
    @Modifying
    @Query("UPDATE User u SET u.points = u.points + :delta WHERE u.id = :id")
    void addPointsRaw(@Param("id") Long id, @Param("delta") int delta);

    /** 取最新积分余额（标量查询，绕过一级缓存，用于写积分明细时的准确快照） */
    @Query("SELECT u.points FROM User u WHERE u.id = :id")
    Integer findPointsById(@Param("id") Long id);

    /** 批量递增令牌版本：角色权限/角色分配变更后调用，使这些用户的旧令牌立即失效（F03-B）。
     *  token_version 为 NULL 的存量行按 0 处理，统一置为 1。 */
    @Modifying
    @Query("UPDATE User u SET u.tokenVersion = COALESCE(u.tokenVersion, 0) + 1 WHERE u.id IN :ids")
    void bumpTokenVersionByIds(@Param("ids") java.util.Collection<Long> ids);

    /** 头像审核：待审用户列表 */
    List<User> findByAvatarPendingIsNotNull();

    /** 附件管理：仅取引用了上传目录的头像 URL（标量投影，避免为扫头像而全量加载 User 实体） */
    @Query("SELECT u.avatar FROM User u WHERE u.avatar IS NOT NULL AND u.avatar LIKE '/uploads/%'")
    List<String> findAllUploadAvatars();

    /** 附件管理：待审头像同样占用上传目录，孤儿清理时须一并计入 */
    @Query("SELECT u.avatarPending FROM User u WHERE u.avatarPending IS NOT NULL AND u.avatarPending LIKE '/uploads/%'")
    List<String> findAllPendingUploadAvatars();
}
