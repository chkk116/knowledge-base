/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.StudyPlanItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StudyPlanItemRepository extends JpaRepository<StudyPlanItem, Long> {

    List<StudyPlanItem> findByPlanIdOrderBySortOrderAscIdAsc(Long planId);

    /** 批量取多个计划的条目（列表页去 N+1） */
    List<StudyPlanItem> findByPlanIdInOrderBySortOrderAscIdAsc(Collection<Long> planIds);

    Optional<StudyPlanItem> findByIdAndPlanId(Long id, Long planId);

    long countByPlanId(Long planId);

    void deleteByPlanId(Long planId);

    void deleteByPlanIdIn(Collection<Long> planIds);
}
