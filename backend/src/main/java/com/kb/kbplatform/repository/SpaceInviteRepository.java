package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.SpaceInvite;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpaceInviteRepository extends JpaRepository<SpaceInvite, Long> {

    Optional<SpaceInvite> findByToken(String token);

    List<SpaceInvite> findByShareIdAndRevokedFalse(Long shareId);

    /** 条件递增用量：返回 0 表示次数已用尽（UPDATE 的 WHERE 在行上原子判定，避免并发超额） */
    @Modifying
    @Query("update SpaceInvite i set i.usedCount = i.usedCount + 1 " +
            "where i.id = :id and (i.maxUses = 0 or i.usedCount < i.maxUses)")
    int consume(@Param("id") Long id);

    /** 条件递减用量（成员被移除时退回一格）：与 consume 同一套原子写法，
     *  避免「读出来 -1 再 saveAll」把别人刚 consume 的 +1 覆盖掉 */
    @Modifying
    @Query("update SpaceInvite i set i.usedCount = i.usedCount - 1 where i.id = :id and i.usedCount > 0")
    int release(@Param("id") Long id);
}
