package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 徽章 / 勋章体系。
 *
 * 设计：徽章不落库、不挂事件钩子，完全由用户当前的可量化统计（积分 / 发布数 / 获赞 /
 * 被收藏 / 粉丝 / 关注数 / 注册天数）实时派生。好处是零迁移、永远准确、不会因统计回落
 * 而出现"已发但显示未获得"的不一致。
 *
 * 统计维度来自 UserService.getCard（已聚合文档、点赞、收藏、粉丝、关注、积分），
 * 注册天数由 User.createdAt 现场计算。
 */
@Service
public class BadgeService {

    private final UserService userService;
    private final UserRepository userRepo;

    public BadgeService(UserService userService, UserRepository userRepo) {
        this.userService = userService;
        this.userRepo = userRepo;
    }

    /** 单枚徽章定义 */
    public record BadgeDef(
            String id,       // 稳定标识，前端据此渲染
            String name,     // 展示名
            String desc,     // 获得条件描述
            String icon,     // emoji 图标
            String color,    // 主题色（按等级）
            String tier,     // 等级：入门 / 进阶 / 高级 / 荣誉
            String metric,   // 统计维度：POINTS/DOCS/LIKES/FAVS/FANS/FOLLOWING/DAYS
            long threshold   // 达标阈值
    ) {}

    // 等级 → 主题色
    private static final String C_ENTRY = "#16a34a";   // 入门 绿
    private static final String C_ADV = "#0ea5e9";     // 进阶 天蓝
    private static final String C_SENIOR = "#f59e0b";  // 高级 琥珀
    private static final String C_HONOR = "#8b5cf6";   // 荣誉 紫

    /** 徽章目录（静态定义，顺序排列即展示顺序） */
    private static final List<BadgeDef> CATALOG = List.of(
        // —— 创作类 ——
        new BadgeDef("first_post", "初执笔", "发布第 1 篇文章", "🌱", C_ENTRY, "入门", "DOCS", 1),
        new BadgeDef("writer", "写手", "累计发布 5 篇文章", "✍️", C_ADV, "进阶", "DOCS", 5),
        new BadgeDef("author", "著书立说", "累计发布 20 篇文章", "📚", C_SENIOR, "高级", "DOCS", 20),
        new BadgeDef("sage", "知识宗师", "累计发布 50 篇文章", "🏛️", C_HONOR, "荣誉", "DOCS", 50),
        // —— 人气类 ——
        new BadgeDef("liked10", "小有口碑", "获得 10 个赞", "👍", C_ENTRY, "入门", "LIKES", 10),
        new BadgeDef("liked100", "人气作者", "获得 100 个赞", "💖", C_SENIOR, "高级", "LIKES", 100),
        new BadgeDef("liked500", "明星作者", "获得 500 个赞", "🌟", C_HONOR, "荣誉", "LIKES", 500),
        new BadgeDef("fav20", "珍藏之选", "被收藏 20 次", "⭐", C_ADV, "进阶", "FAVS", 20),
        // —— 积分类 ——
        new BadgeDef("pts100", "初露锋芒", "累计 100 积分", "🔥", C_ENTRY, "入门", "POINTS", 100),
        new BadgeDef("pts1000", "积分达人", "累计 1000 积分", "💎", C_SENIOR, "高级", "POINTS", 1000),
        new BadgeDef("pts5000", "积分王者", "累计 5000 积分", "👑", C_HONOR, "荣誉", "POINTS", 5000),
        // —— 社交类 ——
        new BadgeDef("fan1", "初识", "收获第 1 位粉丝", "🤝", C_ENTRY, "入门", "FANS", 1),
        new BadgeDef("fan50", "小有名气", "粉丝达到 50 人", "👥", C_ADV, "进阶", "FANS", 50),
        new BadgeDef("fan200", "意见领袖", "粉丝达到 200 人", "📡", C_HONOR, "荣誉", "FANS", 200),
        new BadgeDef("social10", "社交达人", "关注 10 位作者", "🔗", C_ADV, "进阶", "FOLLOWING", 10),
        // —— 元老类 ——
        new BadgeDef("vet365", "元老用户", "注册满 365 天", "🏅", C_HONOR, "荣誉", "DAYS", 365)
    );

    /** 汇总某用户用于徽章判定的统计维度 */
    private Map<String, Long> statsFor(Long userId) {
        Map<String, Object> card = userService.getCard(userId, userId);
        Map<String, Long> s = new HashMap<>();
        s.put("POINTS", toLong(card.get("points")));
        s.put("DOCS", toLong(card.get("original")));
        s.put("LIKES", toLong(card.get("likes")));
        s.put("FAVS", toLong(card.get("favorites")));
        s.put("FANS", toLong(card.get("fans")));
        s.put("FOLLOWING", toLong(card.get("following")));

        long days = 0;
        User u = userRepo.findById(userId).orElse(null);
        if (u != null && u.getCreatedAt() != null) {
            days = Math.max(0, ChronoUnit.DAYS.between(u.getCreatedAt(), LocalDateTime.now()));
        }
        s.put("DAYS", days);
        return s;
    }

    private long toLong(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    /** 全部徽章定义（含维度/阈值，供前端渲染目录） */
    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> res = new ArrayList<>();
        for (BadgeDef b : CATALOG) res.add(toMap(b));
        return res;
    }

    /** 某用户已获得的徽章列表（公开，用于主页展示） */
    public List<Map<String, Object>> earned(Long userId) {
        Map<String, Long> s = statsFor(userId);
        List<Map<String, Object>> res = new ArrayList<>();
        for (BadgeDef b : CATALOG) {
            if (s.getOrDefault(b.metric(), 0L) >= b.threshold()) res.add(toMap(b));
        }
        return res;
    }

    /** 徽章墙：全部定义 + 当前用户已获得状态 + 进度 + 佩戴状态，供「我的勋章」页使用 */
    @Cacheable(cacheNames = "badgeWall", key = "#userId", unless = "#result == null")
    public Map<String, Object> wall(Long userId) {
        Map<String, Long> s = statsFor(userId);
        User u = userRepo.findById(userId).orElse(null);
        java.util.Set<String> worn = u == null ? java.util.Set.of()
                : new java.util.HashSet<>(u.getWornList());
        List<Map<String, Object>> res = new ArrayList<>();
        int earnedCount = 0;
        for (BadgeDef b : CATALOG) {
            long cur = s.getOrDefault(b.metric(), 0L);
            boolean got = cur >= b.threshold();
            if (got) earnedCount++;
            int progress = b.threshold() <= 0 ? 100
                    : (int) Math.min(100, Math.round((double) cur / b.threshold() * 100));
            Map<String, Object> m = toMap(b);
            m.put("current", cur);
            m.put("earned", got);
            m.put("progress", progress);
            m.put("worn", worn.contains(b.id()));
            res.add(m);
        }
        return Map.of(
                "badges", res,
                "earned", earnedCount,
                "total", CATALOG.size(),
                "wornBadges", new ArrayList<>(worn)
        );
    }

    private Map<String, Object> toMap(BadgeDef b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.id());
        m.put("name", b.name());
        m.put("desc", b.desc());
        m.put("icon", b.icon());
        m.put("color", b.color());
        m.put("tier", b.tier());
        m.put("metric", b.metric());
        m.put("threshold", b.threshold());
        return m;
    }
}
