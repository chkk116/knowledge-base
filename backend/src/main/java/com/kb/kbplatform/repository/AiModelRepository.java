/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.AiModel;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * AI 模型配置仓储：按启用态 / 默认标记取可用配置，供调用层选择模型。
 */
@Repository
public interface AiModelRepository extends JpaRepository<AiModel, Long> {

    /** 启用的模型，按管理员设定的排序展示（用户选择下拉用） */
    List<AiModel> findByEnabledTrueOrderBySortOrderAscIdAsc();

    /** 全局默认模型（未显式配置时取排序最前的启用项） */
    List<AiModel> findByDefaultModelTrue();
}
