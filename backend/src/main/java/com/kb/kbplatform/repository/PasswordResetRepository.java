/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.PasswordReset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {

    /** 按 token 哈希找未使用的记录（同一 token 理论上唯一） */
    Optional<PasswordReset> findFirstByTokenHashAndUsedAtIsNullOrderByIdDesc(String tokenHash);

    /**
     * 原子认领：只有把 usedAt 从 NULL 改写成功的那一个请求拿到 1。
     * 原来「查到未使用 → 改密 → 标记已用」是先查后改，两个并发请求可各用一次同一链接。
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "update PasswordReset p set p.usedAt = :now where p.id = :id and p.usedAt is null")
    int claim(@org.springframework.data.repository.query.Param("id") Long id,
              @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    /** 某用户当前未使用的重置链接（生成新链接时统一作废） */
    List<PasswordReset> findByUserIdAndUsedAtIsNull(Long userId);
}
