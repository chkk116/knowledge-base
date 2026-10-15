/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.StudyPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StudyPlanRepository extends JpaRepository<StudyPlan, Long> {

    /** 我的全部计划，最近更新的在前 */
    List<StudyPlan> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<StudyPlan> findByIdAndUserId(Long id, Long userId);

    void deleteByUserId(Long userId);
}
