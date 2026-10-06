/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    /** 某用户拥有的全部角色关联 */
    List<UserRole> findByUserId(Long userId);

    Optional<UserRole> findByUserIdAndRoleId(Long userId, Long roleId);

    boolean existsByUserIdAndRoleId(Long userId, Long roleId);

    /** 角色被多少用户使用（删除角色前校验是否仍被引用） */
    long countByRoleId(Long roleId);

    /** 某角色下的全部用户 id（角色权限变更后需批量失效这些用户的旧令牌） */
    @Query("SELECT ur.userId FROM UserRole ur WHERE ur.roleId = :roleId")
    List<Long> findUserIdsByRoleId(@Param("roleId") Long roleId);

    /** 各角色的成员数（角色管理列表展示「成员 N 人」用）：[roleId, count] 两列结果 */
    @Query("SELECT ur.roleId, COUNT(ur) FROM UserRole ur GROUP BY ur.roleId")
    List<Object[]> countGroupByRole();

    /** 整体替换某用户的角色分配前，先清空旧关联 */
    @Modifying
    @Query("DELETE FROM UserRole ur WHERE ur.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    void deleteByUserIdAndRoleId(Long userId, Long roleId);
}
