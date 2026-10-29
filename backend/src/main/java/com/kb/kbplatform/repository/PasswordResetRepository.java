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

    /** 某用户当前未使用的重置链接（生成新链接时统一作废） */
    List<PasswordReset> findByUserIdAndUsedAtIsNull(Long userId);
}
