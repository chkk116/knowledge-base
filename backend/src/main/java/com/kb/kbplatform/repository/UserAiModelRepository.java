/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.UserAiModel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAiModelRepository extends JpaRepository<UserAiModel, Long> {

    List<UserAiModel> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    List<UserAiModel> findByUserIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long userId);

    /** 归属校验：只能取到自己的行 */
    Optional<UserAiModel> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}
