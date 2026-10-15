/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.StudyPlan;
import com.kb.kbplatform.domain.StudyPlanItem;
import com.kb.kbplatform.repository.StudyPlanItemRepository;
import com.kb.kbplatform.repository.StudyPlanRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 学习计划服务（2026-10-15）。
 *
 * 设计取舍：计划与条目都只属于创建者本人（无共享），因此权限判断极简——
 * 所有写操作都先按 (id, userId) 取记录，取不到即 404，天然隔离他人数据。
 */
@Service
public class StudyPlanService {

    private static final int MAX_ITEMS = 200;
    private static final Set<String> TYPES = Set.of("DOC", "COURSE", "TEXT");

    private final StudyPlanRepository planRepo;
    private final StudyPlanItemRepository itemRepo;

    public StudyPlanService(StudyPlanRepository planRepo, StudyPlanItemRepository itemRepo) {
        this.planRepo = planRepo;
        this.itemRepo = itemRepo;
    }

    // ---------- 计划 ----------

    /** 我的计划列表（含条目），按更新时间倒序 */
    public List<StudyPlan> listMine(Long userId) {
        List<StudyPlan> plans = planRepo.findByUserIdOrderByUpdatedAtDesc(userId);
        if (plans.isEmpty()) return plans;
        Map<Long, List<StudyPlanItem>> items = itemRepo
                .findByPlanIdInOrderBySortOrderAscIdAsc(plans.stream().map(StudyPlan::getId).toList())
                .stream().collect(Collectors.groupingBy(StudyPlanItem::getPlanId));
        for (StudyPlan p : plans) p.setItems(items.getOrDefault(p.getId(), List.of()));
        return plans;
    }

    /** 待复习汇总：供首页提示与入口角标（due = 今天及以前到期且未完成） */
    public Map<String, Object> summary(Long userId) {
        List<StudyPlan> plans = listMine(userId);
        LocalDateTime endOfToday = LocalDate.now().plusDays(1).atStartOfDay();
        int due = 0, overdue = 0, pending = 0, total = 0;
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        for (StudyPlan p : plans) {
            List<StudyPlanItem> items = p.getItems() == null ? List.of() : p.getItems();
            boolean finished = !items.isEmpty() && items.stream().allMatch(StudyPlanItem::isDone);
            if (finished) continue;                     // 已完成的计划不再提醒
            total++;
            pending += (int) items.stream().filter(i -> !i.isDone()).count();
            if (p.getRemindAt() != null && p.getRemindAt().isBefore(endOfToday)) {
                due++;
                if (p.getRemindAt().isBefore(startOfToday)) overdue++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("pending", pending);
        out.put("due", due);
        out.put("overdue", overdue);
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public StudyPlan create(Long userId, String title, String note, LocalDateTime remindAt) {
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划名称不能为空");
        if (t.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划名称最多 120 字");
        StudyPlan p = new StudyPlan();
        p.setUserId(userId);
        p.setTitle(t);
        p.setNote(normalizeNote(note));
        p.setRemindAt(remindAt);
        StudyPlan saved = planRepo.save(p);
        saved.setItems(List.of());
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public StudyPlan update(Long userId, Long planId, String title, String note, LocalDateTime remindAt, boolean remindAtProvided) {
        StudyPlan p = requirePlan(userId, planId);
        if (title != null) {
            String t = title.trim();
            if (t.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划名称不能为空");
            if (t.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "计划名称最多 120 字");
            p.setTitle(t);
        }
        if (note != null) p.setNote(normalizeNote(note));
        if (remindAtProvided) p.setRemindAt(remindAt);   // 允许显式清空提醒
        p.setUpdatedAt(LocalDateTime.now());
        StudyPlan saved = planRepo.save(p);
        saved.setItems(itemRepo.findByPlanIdOrderBySortOrderAscIdAsc(planId));
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long planId) {
        StudyPlan p = requirePlan(userId, planId);
        itemRepo.deleteByPlanId(p.getId());
        planRepo.delete(p);
    }

    // ---------- 条目 ----------

    @Transactional(rollbackFor = Exception.class)
    public StudyPlan addItem(Long userId, Long planId, String type, Long refId, Long refShareId, String title) {
        StudyPlan p = requirePlan(userId, planId);
        if (itemRepo.countByPlanId(planId) >= MAX_ITEMS)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "单个计划最多 " + MAX_ITEMS + " 个条目");
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "条目内容不能为空");
        if (t.length() > 200) t = t.substring(0, 200);
        String kind = type == null ? "TEXT" : type.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(kind)) kind = "TEXT";
        StudyPlanItem item = new StudyPlanItem();
        item.setPlanId(planId);
        item.setType(kind);
        item.setRefId("TEXT".equals(kind) ? null : refId);
        item.setRefShareId("DOC".equals(kind) ? refShareId : null);
        item.setTitle(t);
        item.setSortOrder((int) itemRepo.countByPlanId(planId));
        itemRepo.save(item);
        touch(p);
        return detail(userId, planId);
    }

    /** 勾选/取消完成（也可同时改标题） */
    @Transactional(rollbackFor = Exception.class)
    public StudyPlan updateItem(Long userId, Long planId, Long itemId, Boolean done, String title) {
        StudyPlan p = requirePlan(userId, planId);
        StudyPlanItem item = itemRepo.findByIdAndPlanId(itemId, planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "条目不存在"));
        if (title != null) {
            String t = title.trim();
            if (t.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "条目内容不能为空");
            item.setTitle(t.length() > 200 ? t.substring(0, 200) : t);
        }
        if (done != null) {
            item.setDone(done);
            item.setDoneAt(done ? LocalDateTime.now() : null);
        }
        itemRepo.save(item);
        touch(p);
        return detail(userId, planId);
    }

    @Transactional(rollbackFor = Exception.class)
    public StudyPlan deleteItem(Long userId, Long planId, Long itemId) {
        StudyPlan p = requirePlan(userId, planId);
        StudyPlanItem item = itemRepo.findByIdAndPlanId(itemId, planId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "条目不存在"));
        itemRepo.delete(item);
        touch(p);
        return detail(userId, planId);
    }

    public StudyPlan detail(Long userId, Long planId) {
        StudyPlan p = requirePlan(userId, planId);
        p.setItems(itemRepo.findByPlanIdOrderBySortOrderAscIdAsc(planId));
        return p;
    }

    // ---------- 帮助方法 ----------

    private StudyPlan requirePlan(Long userId, Long planId) {
        return planRepo.findByIdAndUserId(planId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "计划不存在"));
    }

    private void touch(StudyPlan p) {
        p.setUpdatedAt(LocalDateTime.now());
        planRepo.save(p);
    }

    private static String normalizeNote(String note) {
        if (note == null) return null;
        String n = note.trim();
        if (n.isEmpty()) return null;
        if (n.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "备注最多 500 字");
        return n;
    }
}
