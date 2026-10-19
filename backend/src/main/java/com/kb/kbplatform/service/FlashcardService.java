/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Flashcard;
import com.kb.kbplatform.repository.FlashcardRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 复习卡片：SM-2 间隔重复。
 *
 * 卡片仅本人可见可改——复习是私人记忆，不做共享、不做统计上报。
 *
 * 评四档（对齐 Anki 的常用四键），而不是原始的 0~5：
 *   again（重来）→ 10 分钟后重来，难度因子 -0.2
 *   hard （模糊）→ 间隔 ×1.2，因子 -0.05
 *   good （记得）→ 新卡 1 天 / 第二次 3 天 / 之后 ×因子
 *   easy （太简单）→ 间隔 ×因子×1.3，因子 +0.15
 * 上限一年，避免间隔膨胀到几十年后（那时卡片等于丢了）。
 */
@Service
public class FlashcardService {

    private static final int MAX_CARDS_PER_USER = 2000;
    private static final int MAX_INTERVAL_DAYS = 365;
    private static final int FRONT_MAX = 500;
    private static final int BACK_MAX = 5000;
    private static final int SOURCE_MAX = 5000;

    private final FlashcardRepository repo;

    public FlashcardService(FlashcardRepository repo) {
        this.repo = repo;
    }

    public List<Flashcard> list(Long userId) {
        return repo.findByUserIdOrderByDueAtAsc(userId);
    }

    /** 概览：总数 / 待复习 / 已学过 / 新卡 */
    public Map<String, Object> summary(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Flashcard> all = repo.findByUserIdOrderByDueAtAsc(userId);
        long due = all.stream().filter((c) -> !c.getDueAt().isAfter(now)).count();
        long learned = all.stream().filter((c) -> c.getReps() > 0).count();
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("total", all.size());
        ret.put("due", due);
        ret.put("learned", learned);
        ret.put("new", all.size() - learned);
        return ret;
    }

    /** 今天该复习的（到期时间 <= 现在），按到期升序 */
    public List<Flashcard> due(Long userId) {
        return repo.findByUserIdAndDueAtLessThanEqualOrderByDueAtAsc(userId, LocalDateTime.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public Flashcard create(String front, String back, String source, Long shareId, Long docId, Long userId) {
        if (repo.countByUserId(userId) >= MAX_CARDS_PER_USER)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "卡片数量已达上限（" + MAX_CARDS_PER_USER + " 张），请先删除一些");
        Flashcard c = new Flashcard();
        c.setUserId(userId);
        c.setFront(clean(front, FRONT_MAX, "正面问题不能为空"));
        c.setBack(clean(back, BACK_MAX, "背面答案不能为空"));
        c.setSource(source == null ? null : truncate(source.trim(), SOURCE_MAX));
        c.setShareId(shareId);
        c.setDocId(docId);
        c.setDueAt(LocalDateTime.now());
        c.setCreatedAt(LocalDateTime.now());
        c.setUpdatedAt(LocalDateTime.now());
        return repo.save(c);
    }

    @Transactional(rollbackFor = Exception.class)
    public Flashcard update(Long id, String front, String back, String source, Long userId) {
        Flashcard c = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "卡片不存在"));
        if (front != null) c.setFront(clean(front, FRONT_MAX, "正面问题不能为空"));
        if (back != null) c.setBack(clean(back, BACK_MAX, "背面答案不能为空"));
        if (source != null) c.setSource(truncate(source.trim(), SOURCE_MAX));
        c.setUpdatedAt(LocalDateTime.now());
        return repo.save(c);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long userId) {
        Flashcard c = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "卡片不存在"));
        repo.delete(c);
    }

    /** 复习一次：按评分推进间隔（SM-2 简化版，四档） */
    @Transactional(rollbackFor = Exception.class)
    public Flashcard review(Long id, String grade, Long userId) {
        Flashcard c = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "卡片不存在"));

        String g = grade == null ? "" : grade.trim().toLowerCase(Locale.ROOT);
        double ease = c.getEaseFactor();
        int interval = c.getIntervalDays();
        LocalDateTime dueAt;

        switch (g) {
            case "again" -> {
                ease = Math.max(1.3, ease - 0.20);
                interval = 0;
                c.setLapses(c.getLapses() + 1);
                dueAt = LocalDateTime.now().plusMinutes(10);   // 本轮内还会再出现一次
            }
            case "hard" -> {
                ease = Math.max(1.3, ease - 0.05);
                interval = Math.max(1, (int) Math.round(Math.max(interval, 1) * 1.2));
                dueAt = LocalDateTime.now().plusDays(interval);
            }
            case "good" -> {
                interval = interval <= 0 ? 1 : (interval == 1 ? 3 : (int) Math.round(interval * ease));
                dueAt = LocalDateTime.now().plusDays(interval);
            }
            case "easy" -> {
                ease = Math.min(3.0, ease + 0.15);
                interval = interval <= 0 ? 2 : (int) Math.round(interval * ease * 1.3);
                dueAt = LocalDateTime.now().plusDays(interval);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "评分只能是 again / hard / good / easy");
        }

        interval = Math.min(interval, MAX_INTERVAL_DAYS);
        c.setEaseFactor(ease);
        c.setIntervalDays(interval);
        c.setReps(c.getReps() + 1);
        c.setLastReviewedAt(LocalDateTime.now());
        c.setDueAt(dueAt);
        c.setUpdatedAt(LocalDateTime.now());
        return repo.save(c);
    }

    private static String clean(String raw, int max, String emptyMsg) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, emptyMsg);
        return truncate(s, max);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
