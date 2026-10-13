/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.repository;

import com.kb.kbplatform.domain.Announcement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** 前台：返回所有启用公告，按 pinned 降序、sortOrder 升序、updatedAt 降序 */
    List<Announcement> findByEnabledTrueOrderByPinnedDescSortOrderAscUpdatedAtDesc();

    /** 后台：返回全部，按 pinned 降序、sortOrder 升序、updatedAt 降序 */
    List<Announcement> findAllByOrderByPinnedDescSortOrderAscUpdatedAtDesc();

    /** 后台：分页版（同上排序） */
    Page<Announcement> findAllByOrderByPinnedDescSortOrderAscUpdatedAtDesc(Pageable pageable);

    /** 后台：按启用状态分页（同上排序），status tabs 服务端过滤用 */
    Page<Announcement> findByEnabledOrderByPinnedDescSortOrderAscUpdatedAtDesc(boolean enabled, Pageable pageable);
}
