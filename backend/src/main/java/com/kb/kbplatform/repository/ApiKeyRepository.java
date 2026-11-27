/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /** 按哈希查未吊销的密钥（哈希唯一，最多一条） */
    Optional<ApiKey> findByKeyHashAndRevokedFalse(String keyHash);

    List<ApiKey> findByUserIdOrderByIdDesc(Long userId);

    long countByUserIdAndRevokedFalse(Long userId);

    /** 含已吊销的总数：配额判定用这个，避免创建→吊销绕过上限 */
    long countByUserId(Long userId);
}
