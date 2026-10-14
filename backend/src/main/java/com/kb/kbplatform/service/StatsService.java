/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Comment;
import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.repository.CommentRepository;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.FavoriteRepository;
import com.kb.kbplatform.repository.LikeRepository;
import com.kb.kbplatform.repository.NotificationRepository;
import com.kb.kbplatform.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台数据看板：聚合各项计数（文档/用户/评论/点赞/收藏/通知 + 浏览量 + 今日新增），
 * 并扩展出：近 7 天增长趋势、昨日环比、热门文章、活跃用户、实时动态（最新发布/评论）。
 * 全部走 JPA 聚合查询（COUNT/SUM），单条 SQL、不加载全量行到内存。
 */
@Service
public class StatsService {

    private final DocumentRepository docRepo;
    private final UserRepository userRepo;
    private final CommentRepository commentRepo;
    private final LikeRepository likeRepo;
    private final FavoriteRepository favRepo;
    private final NotificationRepository notiRepo;

    public StatsService(DocumentRepository docRepo, UserRepository userRepo,
                        CommentRepository commentRepo, LikeRepository likeRepo,
                        FavoriteRepository favRepo, NotificationRepository notiRepo) {
        this.docRepo = docRepo;
        this.userRepo = userRepo;
        this.commentRepo = commentRepo;
        this.likeRepo = likeRepo;
        this.favRepo = favRepo;
        this.notiRepo = notiRepo;
    }

    public Map<String, Object> getStats() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrow = today.plusDays(1);
        LocalDateTime yesterday = today.minusDays(1);
        Map<String, Object> m = new LinkedHashMap<>();
        // 文档
        m.put("docTotal", docRepo.count());
        m.put("docPublished", docRepo.countByStatus("PUBLISHED"));
        m.put("docPending", docRepo.countByStatus("PENDING"));
        m.put("docDraft", docRepo.countByStatus("DRAFT"));
        m.put("docBlocked", docRepo.countByStatus("BLOCKED"));
        // 半开区间 [today, tomorrow) / [yesterday, today)（外部审查 2026-10-13：BETWEEN 闭区间
        // 会把「今日 00:00:00」整点的记录同时算进昨日与今日，造成跨日重复计数）
        m.put("docToday", docRepo.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(today, tomorrow));
        m.put("docYesterday", docRepo.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(yesterday, today));
        m.put("viewsTotal", docRepo.sumViews());
        // 用户
        m.put("userTotal", userRepo.count());
        m.put("userAdmin", userRepo.countByRoleAndEnabled("ADMIN", true));   // 已注销/停用不计入
        m.put("userBlocked", userRepo.countByEnabledFalse());
        m.put("userToday", userRepo.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(today, tomorrow));
        m.put("userYesterday", userRepo.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(yesterday, today));
        // 互动
        m.put("commentTotal", commentRepo.count());
        m.put("likeTotal", likeRepo.count());
        m.put("favTotal", favRepo.count());
        m.put("notiTotal", notiRepo.count());
        m.put("commentToday", commentRepo.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(today, tomorrow));
        m.put("commentYesterday", commentRepo.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(yesterday, today));
        // 近 7 天趋势（含今日，共 7 个点；不含昨日环比基准之外的更多历史）
        m.put("docTrend7", trend7(docRepo::countByCreatedAtGreaterThanEqualAndCreatedAtLessThan));
        m.put("userTrend7", trend7(userRepo::countByCreatedAtGreaterThanEqualAndCreatedAtLessThan));
        m.put("commentTrend7", trend7(commentRepo::countByCreatedAtGreaterThanEqualAndCreatedAtLessThan));
        m.put("trendLabels", trendLabels());
        // 排行榜
        m.put("topDocs", topDocs());
        m.put("topUsers", topUsers());
        // 实时动态：最新发布 + 最新评论 合并按时间倒序
        m.put("activities", activities());
        return m;
    }

    /** 近 7 天逐日计数（含今日） */
    private List<Long> trend7(DayCount q) {
        LocalDate now = LocalDate.now();
        List<Long> out = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) {
            LocalDateTime from = now.minusDays(i).atStartOfDay();
            LocalDateTime to = from.plusDays(1);
            out.add(q.count(from, to));
        }
        return out;
    }

    /** 近 7 天日期标签（MM-dd） */
    private List<String> trendLabels() {
        LocalDate now = LocalDate.now();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MM-dd");
        List<String> out = new ArrayList<>(7);
        for (int i = 6; i >= 0; i--) out.add(now.minusDays(i).format(f));
        return out;
    }

    @FunctionalInterface
    private interface DayCount {
        long count(LocalDateTime from, LocalDateTime to);
    }

    /** 热门文章 Top5：标题 + 浏览量 + 分类 */
    private List<Map<String, Object>> topDocs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : docRepo.findTop5ByStatusOrderByViewsDesc("PUBLISHED")) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("title", d.getTitle());
            e.put("views", d.getViews());
            e.put("category", d.getCategory());
            out.add(e);
        }
        return out;
    }

    /** 活跃用户 Top5：按评论数（username + count） */
    private List<Map<String, Object>> topUsers() {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object[]> rows = commentRepo.countGroupByUsername();
        for (int i = 0; i < rows.size() && i < 5; i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("username", rows.get(i)[0]);
            e.put("count", rows.get(i)[1]);
            out.add(e);
        }
        return out;
    }

    /** 实时动态：最新发布 6 篇 + 最新评论 6 条，合并按时间倒序取前 8 */
    private List<Map<String, Object>> activities() {
        List<Map<String, Object>> out = new ArrayList<>();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MM-dd HH:mm");
        for (Document d : docRepo.findTop6ByStatusOrderByCreatedAtDesc("PUBLISHED")) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", "doc");
            // 标题是用户输入且后台看板以 v-html 渲染动态文本（第四轮审查 C3）：必须转义，
            // 否则标题携带 <img onerror> 之类标记会在管理员浏览器里执行
            e.put("text", HtmlSanitizer.escapeHtml(d.getTitle()));
            e.put("author", d.getOwnerName() != null ? d.getOwnerName() : "");
            e.put("time", d.getCreatedAt() != null ? d.getCreatedAt().format(f) : "");
            e.put("docId", d.getId());          // 供数据面板「点击直达」跳转用
            out.add(e);
        }
        for (Comment c : commentRepo.findTop6ByOrderByCreatedAtDesc()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", "comment");
            String content = c.getContent() == null ? "" : c.getContent().trim();
            e.put("text", HtmlSanitizer.preview(content, 40));
            e.put("author", c.getUsername() != null ? c.getUsername() : "");
            e.put("time", c.getCreatedAt() != null ? c.getCreatedAt().format(f) : "");
            e.put("docId", c.getDocId());        // 所属文档：评论区在文档详情页内
            e.put("commentId", c.getId());       // 指定评论：前端据此滚动定位并高亮
            out.add(e);
        }
        out.sort((a, b) -> String.valueOf(b.get("time")).compareTo(String.valueOf(a.get("time"))));
        return out.size() > 8 ? out.subList(0, 8) : out;
    }
}
