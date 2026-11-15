/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.repository.DocumentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * 文档发现：分类、标签聚合、按标签筛选、相关文档推荐。
 *
 * <p>从 DocumentService 拆出的只读内聚块：只依赖 DocumentRepository，不碰写入事务、审核状态机与
 * Meili 同步，因此可独立测试、独立演进。接口路径不变（DocumentController 直接注入本服务）。</p>
 */
@Service
public class DocumentDiscoveryService {

    private final DocumentRepository repo;

    public DocumentDiscoveryService(DocumentRepository repo) {
        this.repo = repo;
    }

    public List<String> categories() {
        // 只取 category 列（SQL DISTINCT），不再 findAll 拉全行 TEXT
        return repo.findDistinctCategories().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /** 公开：标签聚合（去重 + 计数），按计数倒序，用于标签云/筛选 */
    public List<Map<String, Object>> allTags() {
        Map<String, Integer> count = new LinkedHashMap<>();
        // 只取 tags 列，不再 findAll 拉全行 TEXT
        for (String tags : repo.findAllTagsRaw()) {
            if (tags == null) continue;
            for (String t : tags.split(",")) {
                t = t.trim();
                if (!t.isEmpty()) count.merge(t, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> res = new ArrayList<>();
        count.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tag", e.getKey());
                    m.put("count", e.getValue());
                    res.add(m);
                });
        return res;
    }

    /** 公开：按标签筛选（tags 逗号分隔字段的边界匹配），支持最新/最热排序 */
    public Page<Document> listByTag(Pageable pageable, String sort, String tag) {
        if ("hot".equals(sort)) return repo.findByTagHot(tag, pageable);
        return repo.findByTag(tag, pageable);
    }

    /** 标签拆分：逗号分隔字符串 → 去空白去空的标签集合 */
    private static Set<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String t : tags.split(",")) {
            t = t.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * 相关文档推荐：同分类 + 标签重叠加权打分，得分低的补位高浏览文档，避免内容变成孤岛。
     *
     * 打分规则：同分类 +2；每个共同标签 +3。只回列表展示需要的轻量字段（不含正文）。
     * 只用数据库查询，无外部依赖——MeiliSearch 未启用时同样可用。
     *
     * @param docId 源文档 id
     * @param limit 返回条数（<=0 时取默认 6，上限 20）
     */
    public List<Document> relatedDocs(Long docId, int limit) {
        if (limit <= 0) limit = 6;
        if (limit > 20) limit = 20;
        if (docId == null) return List.of();
        Document src = repo.findById(docId).orElse(null);
        if (src == null) return List.of();

        String cat = (src.getCategory() == null || src.getCategory().isBlank()) ? null : src.getCategory();
        final Set<String> srcTags = parseTags(src.getTags());

        // 候选集：同分类（或源文档无分类时的全站）按浏览量取前 300 条，避免全表扫描后全量打分
        List<Object[]> rows = repo.findRelatedCandidates(docId, cat, PageRequest.of(0, 300));

        record Cand(long id, String title, String category, String tags, int views, int score) {}
        List<Cand> scored = new ArrayList<>();
        for (Object[] r : rows) {
            if (r == null || r.length < 5 || r[0] == null) continue;
            long id = ((Number) r[0]).longValue();
            String title = r[1] == null ? "" : String.valueOf(r[1]);
            String category = r[2] == null ? null : String.valueOf(r[2]);
            String tags = r[3] == null ? null : String.valueOf(r[3]);
            int views = r[4] == null ? 0 : ((Number) r[4]).intValue();
            int score = 0;
            if (cat != null && cat.equals(category)) score += 2;
            Set<String> common = new java.util.LinkedHashSet<>(parseTags(tags));
            common.retainAll(srcTags);          // 标签交集
            score += common.size() * 3;
            if (score > 0) scored.add(new Cand(id, title, category, tags, views, score));
        }
        // 相关度优先降序，同分看浏览量降序，再按 id 稳定兜底。
        // 注意：不能写成 ...reversed().thenComparingInt(...).reversed() —— 最后一个 reversed()
        // 会反转整个组合比较器，把低相关度排到最前面（F13）。
        scored.sort(java.util.Comparator
                .comparingInt(Cand::score).reversed()
                .thenComparing(java.util.Comparator.comparingInt(Cand::views).reversed())
                .thenComparingLong(Cand::id));

        List<Document> res = new ArrayList<>();
        List<Long> picked = new ArrayList<>();
        for (Cand c : scored) {
            if (res.size() >= limit) break;
            res.add(lightDoc(c.id(), c.title(), c.category(), c.tags(), c.views()));
            picked.add(c.id());
        }

        // 相关文档不足（新站/冷门分类）时，用高浏览文档补位，避免底部空白
        if (res.size() < limit) {
            List<Long> excluded = new ArrayList<>(picked);
            excluded.add(docId);                // 保证非空且排除自身
            for (Object[] r : repo.findPopularExcept(docId, excluded, PageRequest.of(0, limit - res.size()))) {
                if (r == null || r.length < 5 || r[0] == null) continue;
                long id = ((Number) r[0]).longValue();
                res.add(lightDoc(id,
                        r[1] == null ? "" : String.valueOf(r[1]),
                        r[2] == null ? null : String.valueOf(r[2]),
                        r[3] == null ? null : String.valueOf(r[3]),
                        r[4] == null ? 0 : ((Number) r[4]).intValue()));
            }
        }
        return res;
    }

    /** 构造只含列表展示字段的轻量 Document（不暴露正文） */
    private static Document lightDoc(long id, String title, String category, String tags, int views) {
        Document d = new Document();
        d.setId(id);
        d.setTitle(title);
        d.setCategory(category);
        d.setTags(tags);
        d.setViews(views);
        return d;
    }
}
