/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.PointsLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface PointsLogRepository extends JpaRepository<PointsLog, Long> {
    List<PointsLog> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<PointsLog> findByUsernameOrderByCreatedAtDesc(String username);
    void deleteByUserId(Long userId);   // 自助注销：清空本人积分流水

    // 积分明细页：按用户 + 时间倒序分页
    Page<PointsLog> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);
    Page<PointsLog> findByUsernameAndTypeOrderByCreatedAtDesc(String username, String type, Pageable pageable);

    // 累计获得 / 支出（明细页顶部汇总用，走数据库侧聚合，不加载全表）
    @Query("select coalesce(sum(l.delta), 0) from PointsLog l where l.username = ?1 and l.delta > 0")
    int sumEarned(String username);

    @Query("select coalesce(sum(l.delta), 0) from PointsLog l where l.username = ?1 and l.delta < 0")
    int sumSpent(String username);
}
