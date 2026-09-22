/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.ExamPaper;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExamPaperRepository extends JpaRepository<ExamPaper, Long> {
    List<ExamPaper> findAllByStatusOrderByCreatedAtDesc(String status);

    /** 全部试卷按创建时间倒序（管理端「全部试卷」列表） */
    List<ExamPaper> findAllByOrderByCreatedAtDesc();
}
