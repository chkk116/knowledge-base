/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Announcement;
import com.kb.kbplatform.repository.AnnouncementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AnnouncementService {

    private final AnnouncementRepository repo;

    public AnnouncementService(AnnouncementRepository repo) {
        this.repo = repo;
    }

    /** 前台：仅启用的公告 */
    public List<Announcement> listActive() {
        return repo.findByEnabledTrueOrderByPinnedDescSortOrderAscUpdatedAtDesc();
    }

    /** 后台：全部公告 */
    public List<Announcement> listAll() {
        return repo.findAllByOrderByPinnedDescSortOrderAscUpdatedAtDesc();
    }

    /** 后台：全部公告（分页） */
    public Page<Announcement> pageAll(Pageable pageable) {
        return repo.findAllByOrderByPinnedDescSortOrderAscUpdatedAtDesc(pageable);
    }

    @Transactional
    public Announcement create(String title, String content, boolean pinned, int sortOrder, Long createdBy) {
        title = (title == null ? "" : title.trim());
        if (title.isEmpty()) throw new IllegalArgumentException("公告标题不能为空");
        Announcement a = new Announcement();
        a.setTitle(title);
        a.setContent(content == null ? "" : content.trim());
        a.setPinned(pinned);
        a.setEnabled(true);
        a.setSortOrder(sortOrder);
        a.setCreatedBy(createdBy);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        return repo.save(a);
    }

    @Transactional
    public Announcement update(Long id, String title, String content, Boolean pinned, Integer sortOrder, Boolean enabled) {
        Announcement a = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("公告不存在"));
        if (title != null && !title.trim().isEmpty()) a.setTitle(title.trim());
        if (content != null) a.setContent(content.trim());
        if (pinned != null) a.setPinned(pinned);
        if (sortOrder != null) a.setSortOrder(sortOrder);
        if (enabled != null) a.setEnabled(enabled);
        a.setUpdatedAt(LocalDateTime.now());
        return repo.save(a);
    }

    @Transactional
    public void delete(Long id) {
        repo.deleteById(id);
    }

    /** 快捷置顶/取消置顶 */
    @Transactional
    public Announcement togglePin(Long id) {
        Announcement a = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("公告不存在"));
        a.setPinned(!a.isPinned());
        a.setUpdatedAt(LocalDateTime.now());
        return repo.save(a);
    }

    /** 快捷启用/禁用 */
    @Transactional
    public Announcement toggleEnabled(Long id) {
        Announcement a = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("公告不存在"));
        a.setEnabled(!a.isEnabled());
        a.setUpdatedAt(LocalDateTime.now());
        return repo.save(a);
    }

    public Map<String, Object> toMap(Announcement a) {
        return Map.of(
                "id", a.getId(),
                "title", a.getTitle(),
                "content", a.getContent(),
                "pinned", a.isPinned(),
                "enabled", a.isEnabled(),
                "sortOrder", a.getSortOrder(),
                "createdBy", a.getCreatedBy() == null ? 0 : a.getCreatedBy(),
                "createdAt", a.getCreatedAt() == null ? "" : a.getCreatedAt().toString(),
                "updatedAt", a.getUpdatedAt() == null ? "" : a.getUpdatedAt().toString()
        );
    }
}
