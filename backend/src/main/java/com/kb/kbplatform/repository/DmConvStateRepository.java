/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.DmConvState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DmConvStateRepository extends JpaRepository<DmConvState, Long> {
    Optional<DmConvState> findByUserIdAndPeerId(Long userId, Long peerId);
    List<DmConvState> findByUserId(Long userId);
}
