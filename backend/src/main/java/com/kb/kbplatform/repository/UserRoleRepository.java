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

    /** 整体替换某用户的角色分配前，先清空旧关联 */
    @Modifying
    @Query("DELETE FROM UserRole ur WHERE ur.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    void deleteByUserIdAndRoleId(Long userId, Long roleId);
}
