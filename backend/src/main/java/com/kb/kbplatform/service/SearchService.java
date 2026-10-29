/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    /** Meilisearch 连续不可用时的冷却期：冷却期内直接走 MySQL，避免每次搜索都去连一个挂掉的服务拖慢响应 */
    private static final long MEILI_COOLDOWN_MS = 60_000;

    private final DocumentRepository repo;
    private final MeiliSearchClient meili;
    private final UserRepository userRepo;
    private volatile long meiliCooldownUntil = 0;

    public SearchService(DocumentRepository repo, MeiliSearchClient meili, UserRepository userRepo) {
        this.repo = repo;
        this.meili = meili;
        this.userRepo = userRepo;
    }

    /**
     * 检索测试（管理页「检索测试」用）：跑一条与用户搜索完全相同的链路，
     * 额外回报实际使用的引擎 / 耗时 / 命中数，供管理员验证「搜得到、搜得准」。
     * 不写审计、不改任何状态，纯只读。
     */
    public Map<String, Object> probe(String q, String category) {
        long t0 = System.currentTimeMillis();
        String kw = (q == null ? "" : q).replace("%", "").replace("_", "").trim();
        String engine = "mysql";
        List<Map<String, Object>> hits = Collections.emptyList();
        if (!kw.isEmpty()) {
            if (meili.isEnabled() && System.currentTimeMillis() > meiliCooldownUntil) {
                try {
                    hits = verifyVisible(meili.search(kw, category));
                    engine = "meili";
                } catch (MeiliUnavailableException e) {
                    meiliCooldownUntil = System.currentTimeMillis() + MEILI_COOLDOWN_MS;
                    hits = searchByMysql(kw, category, null, "relevance", null, null);
                } catch (Exception e) {
                    meiliCooldownUntil = System.currentTimeMillis() + MEILI_COOLDOWN_MS;
                    hits = searchByMysql(kw, category, null, "relevance", null, null);
                }
            } else {
                hits = searchByMysql(kw, category, null, "relevance", null, null);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("engine", engine);
        out.put("meiliEnabled", meili.isEnabled());
        out.put("tookMs", System.currentTimeMillis() - t0);
        out.put("total", hits.size());
        out.put("hits", hits);
        return out;
    }

    /**
     * 关键词检索。
     *  - 若配置了 Meilisearch：优先走 Meili（中文分词 / 容错 / 相关度排序更优）；
     *    一旦 Meili 不可用（未启用 / 宕机 / 异常），自动回退到 MySQL LIKE，保证搜索永远可用。
     */
    public List<Map<String, Object>> hybrid(String q, String category, String author) {
        return hybrid(q, category, author, null, null, null);
    }

    /**
     * 混合检索（Meili 优先，失败回退 MySQL）。
     *
     * 排序 / 时间范围 / 标签筛选一律走 MySQL：Meili 索引里没有 views/likes，
     * 也无法按业务标签做 AND 过滤，混着用会出现「排序看着生效、实际没排」的错觉。
     *
     * @param sort  relevance（默认，相关度）| updated（最近更新）| hot（热度：浏览 + 点赞×3）
     * @param days  时间范围：只保留最近 N 天更新的文档；null/<=0 表示不限
     * @param tags  标签筛选，逗号分隔；要求文档同时包含全部标签（AND）
     */
    public List<Map<String, Object>> hybrid(String q, String category, String author,
                                            String sort, Integer days, String tags) {
        String kw = (q == null ? "" : q).replace("%", "").replace("_", "").trim();
        String au = (author == null ? "" : author).trim();
        String so = (sort == null || sort.isBlank()) ? "relevance" : sort.trim().toLowerCase();
        boolean needMysql = !"relevance".equals(so)
                || (days != null && days > 0)
                || (tags != null && !tags.isBlank());
        // 带作者筛选或需要排序/时间/标签：一律走 MySQL（作者名解析也在服务端完成）
        if (!au.isEmpty() || needMysql) return searchByMysql(kw, category, au, so, days, tags);
        if (kw.isEmpty()) return Collections.emptyList();
        if (meili.isEnabled() && System.currentTimeMillis() > meiliCooldownUntil) {
            try {
                return verifyVisible(meili.search(kw, category));
            } catch (MeiliUnavailableException e) {
                meiliCooldownUntil = System.currentTimeMillis() + MEILI_COOLDOWN_MS;
                log.warn("Meilisearch 暂不可用，回退 MySQL LIKE（{}s 内不再重试）: {}",
                        MEILI_COOLDOWN_MS / 1000, e.getMessage());
            } catch (Exception e) {
                meiliCooldownUntil = System.currentTimeMillis() + MEILI_COOLDOWN_MS;
                log.warn("Meilisearch 搜索异常，回退 MySQL LIKE: {}", e.getMessage());
            }
        }
        return searchByMysql(kw, category, null, so, days, tags);
    }

    /**
     * 搜索结果的可见性兜底（F10）：Meili 索引删除失败时，撤回/封禁/删除的文档仍会留在索引里。
     * 返回给用户前，用数据库当前状态核对一遍——只保留「已发布且未进回收站」的条目，
     * 索引与数据库不一致时以数据库为准。
     */
    private List<Map<String, Object>> verifyVisible(List<Map<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) return hits;
        Set<Long> ids = SearchVisibility.extractIds(hits);
        if (ids.isEmpty()) return hits;
        Set<Long> visible = repo.findByStatusAndIdIn("PUBLISHED", new ArrayList<>(ids)).stream()
                .map(Document::getId).collect(Collectors.toSet());
        List<Map<String, Object>> out = SearchVisibility.filterVisible(hits, visible, 0);
        if (out.size() != hits.size()) {
            log.warn("搜索索引与数据库不一致：{} 条命中中有 {} 条已不可见，已过滤（建议重建索引）",
                    hits.size(), hits.size() - out.size());
        }
        return out;
    }

    private List<Map<String, Object>> searchByMysql(String q, String category, String author,
                                                    String sort, Integer days, String tags) {
        String kw = (q == null ? "" : q).replace("%", "").replace("_", "").trim();
        String au = (author == null ? "" : author).trim();
        // 解析作者名 → ownerId 集合（用户名 / 昵称模糊匹配）；空则不做作者过滤
        Set<Long> authorIds = null;
        if (!au.isEmpty()) {
            List<User> users = userRepo.findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(au, au);
            authorIds = users.stream().map(User::getId).collect(Collectors.toSet());
            if (authorIds.isEmpty()) return Collections.emptyList(); // 无匹配作者 → 直接无结果
        }
        final Set<Long> finalAuthorIds = authorIds;
        final boolean kwEmpty = kw.isEmpty();

        // 取候选文档：有作者但无关键词 → 直接取该作者已发布文档；否则按关键词检索
        List<Document> docs;
        if (kwEmpty) {
            if (finalAuthorIds == null) return Collections.emptyList();
            docs = new ArrayList<>();
            for (Long id : finalAuthorIds) {
                docs.addAll(repo.findByOwnerIdAndStatusOrderByUpdatedAtDesc(id, "PUBLISHED"));
            }
        } else {
            docs = repo.search(kw);
        }

        final String cat = (category == null || category.isBlank()) ? null : category;
        final String kwLower = kw.toLowerCase();
        final String so = (sort == null || sort.isBlank()) ? "relevance" : sort.trim().toLowerCase();
        final java.time.LocalDateTime since = (days != null && days > 0)
                ? java.time.LocalDateTime.now().minusDays(days) : null;
        final List<String> tagList = parseTags(tags);

        // 作者名映射，用于结果展示 ownerName（避免列表里看不到是谁写的）。
        // 只取候选文档涉及的作者（findAllById），禁止全表加载所有用户。
        Set<Long> ownerIds = new HashSet<>();
        for (Document d : docs) if (d.getOwnerId() != null) ownerIds.add(d.getOwnerId());
        Map<Long, String> nameMap = new HashMap<>();
        if (!ownerIds.isEmpty()) {
            for (User u : userRepo.findAllById(ownerIds)) {
                nameMap.put(u.getId(), (u.getDisplayName() != null && !u.getDisplayName().isBlank())
                        ? u.getDisplayName() : u.getUsername());
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Document doc : docs) {
            // 免登录公开接口：只暴露已发布文档，草稿/待审核/驳回/封禁一律不进结果
            if (!"PUBLISHED".equals(doc.getStatus())) continue;
            if (cat != null && !cat.equals(doc.getCategory())) continue;
            if (finalAuthorIds != null && !finalAuthorIds.contains(doc.getOwnerId())) continue;
            // 时间范围：只保留最近 N 天更新过的文档
            if (since != null && (doc.getUpdatedAt() == null || doc.getUpdatedAt().isBefore(since))) continue;
            // 标签筛选：要求文档同时包含全部所选标签（AND）
            if (!tagList.isEmpty() && !hasAllTags(doc.getTags(), tagList)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            String title = doc.getTitle() == null ? "" : doc.getTitle();
            String contentText = snippetText(doc.getContent());
            String docTags = doc.getTags() == null ? "" : doc.getTags();
            String docCat = doc.getCategory() == null ? "" : doc.getCategory();
            double score = 0.0;
            if (!kwEmpty) {
                // 相关性：标题命中权重最高，正文按命中次数累加，标签 / 分类命中加权
                int titleCnt = countOccur(title.toLowerCase(), kwLower);
                int bodyCnt = countOccur(contentText.toLowerCase(), kwLower);
                boolean tagHit = !docTags.toLowerCase().contains(kwLower);
                boolean catHit = docCat.toLowerCase().contains(kwLower);
                score = titleCnt * 5.0 + bodyCnt * 1.0 + (tagHit ? 0 : 3.0) + (catHit ? 2.0 : 0);
            }
            row.put("id", doc.getId());
            row.put("score", kwEmpty ? null : score); // 纯作者筛选无关键词，不显示「相关度」
            row.put("title", title);
            row.put("category", doc.getCategory());
            row.put("tags", doc.getTags());
            row.put("ownerId", doc.getOwnerId());
            row.put("ownerName", nameMap.getOrDefault(doc.getOwnerId(), ""));
            row.put("updatedAt", doc.getUpdatedAt());
            row.put("views", doc.getViews());
            row.put("likes", doc.getLikes());
            // 摘要：有关键词则定位首个命中处，否则取开头
            row.put("snippet", kwEmpty ? (contentText.length() > 160 ? contentText.substring(0, 160) + "…" : contentText)
                                       : highlightSnippet(contentText, kwLower, 160));
            result.add(row);
        }
        result.sort((a, b) -> {
            if ("hot".equals(so)) {
                // 热度 = 浏览 + 点赞×3（点赞比浏览更稀缺，权重更高）
                long ha = (long) toInt(a.get("views")) + 3L * toInt(a.get("likes"));
                long hb = (long) toInt(b.get("views")) + 3L * toInt(b.get("likes"));
                int c = Long.compare(hb, ha);
                if (c != 0) return c;
            } else if (!"updated".equals(so) && !kwEmpty) {
                int c = Double.compare((Double) b.get("score"), (Double) a.get("score"));
                if (c != 0) return c;
            }
            // updated 排序 / 同分兜底：按更新时间倒序（最新优先）
            Object ua = a.get("updatedAt"), ub = b.get("updatedAt");
            if (ua == null || ub == null) return Long.compare((Long) a.get("id"), (Long) b.get("id"));
            return ((Comparable) ub).compareTo(ua);
        });
        return result;
    }

    /** 逗号分隔标签 → 去重小写列表 */
    private static List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : tags.split(",")) {
            String s = t.trim().toLowerCase();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    /** 文档标签（逗号分隔）是否同时包含全部所需标签 */
    private static boolean hasAllTags(String docTags, List<String> need) {
        if (docTags == null || docTags.isBlank()) return false;
        String hay = "," + docTags.toLowerCase().replace(" ", "") + ",";
        for (String t : need) {
            if (!hay.contains("," + t.replace(" ", "") + ",")) return false;
        }
        return true;
    }

    private static int toInt(Object o) {
        return (o instanceof Number n) ? n.intValue() : 0;
    }

    /** 统计 needle 在 haystack 中的出现次数（不重叠） */
    private int countOccur(String haystack, String needle) {
        if (haystack.isEmpty() || needle.isEmpty()) return 0;
        int cnt = 0, idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) { cnt++; idx += needle.length(); }
        return cnt;
    }

    /** 去除 HTML 标签并压缩空白，得到纯文本（用于统计 / 摘要 / 高亮定位） */
    private String snippetText(String content) {
        if (content == null) return "";
        return HtmlSanitizer.toText(content).replaceAll("\\s+", " ").trim();
    }

    /** 摘要：优先定位到首个命中词前后取窗口；无命中则取开头 */
    private String highlightSnippet(String text, String kwLower, int max) {
        if (text.isEmpty()) return "";
        int hit = text.toLowerCase().indexOf(kwLower);
        String win;
        if (hit <= 0) {
            win = text;
        } else {
            int start = Math.max(0, hit - 30);
            int end = Math.min(text.length(), hit + kwLower.length() + max - 30);
            win = (start > 0 ? "…" : "") + text.substring(start, end);
        }
        return win.length() <= max ? win : win + "…";
    }
}
