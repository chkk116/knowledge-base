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
}
