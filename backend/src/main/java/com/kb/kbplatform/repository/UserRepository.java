package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    /** 按角色查询用户（用于「通知全体管理员」等场景） */
    List<User> findByRole(String role);

    /** 按作者名检索：用户名或昵称模糊匹配（搜索「按作者」筛选用） */
    List<User> findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(String username, String displayName);

    /** 管理员用户检索：用户名或昵称模糊匹配（keyword 为空时返回全部），可按角色 / 启用态过滤；
     *  role 为 null 不过滤角色，enabled 为 null 不过滤启用态；排序/分页由 Pageable 控制 */
    @Query("SELECT u FROM User u WHERE (:kw IS NULL OR :kw = '' OR u.username LIKE %:kw% OR u.displayName LIKE %:kw%) AND (:role IS NULL OR u.role = :role) AND (:enabled IS NULL OR u.enabled = :enabled)")
    Page<User> search(@Param("kw") String kw, @Param("role") String role, @Param("enabled") Boolean enabled, Pageable pageable);

    /** 看板统计：按角色 / 禁用态计数 */
    long countByRole(String role);
    long countByEnabledFalse();
    long countByCreatedAtAfter(java.time.LocalDateTime t);

    /** 积分排行中「排在我前面」的人数（points 降序、同名按 username 升序），用于计算当前用户名次；
     *  避免为求名次而全表加载所有用户到内存（原 ranking 接口的全表扫描隐患） */
    @Query("SELECT COUNT(u) FROM User u WHERE u.points > :mp OR (u.points = :mp AND u.username < :me)")
    long countRankedAbove(@Param("mp") int mp, @Param("me") String me);
}
