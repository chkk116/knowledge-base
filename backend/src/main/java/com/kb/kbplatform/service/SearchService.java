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
     * 关键词检索。
     *  - 若配置了 Meilisearch：优先走 Meili（中文分词 / 容错 / 相关度排序更优）；
     *    一旦 Meili 不可用（未启用 / 宕机 / 异常），自动回退到 MySQL LIKE，保证搜索永远可用。
     */
    public List<Map<String, Object>> hybrid(String q, String category, String author) {
        String kw = (q == null ? "" : q).replace("%", "").replace("_", "").trim();
        String au = (author == null ? "" : author).trim();
        // 带作者筛选：一律走 MySQL（作者名解析在服务端完成），不依赖 Meilisearch
        if (!au.isEmpty()) return searchByMysql(kw, category, au);
        if (kw.isEmpty()) return Collections.emptyList();
        if (meili.isEnabled() && System.currentTimeMillis() > meiliCooldownUntil) {
            try {
                return meili.search(kw, category);
            } catch (MeiliUnavailableException e) {
                meiliCooldownUntil = System.currentTimeMillis() + MEILI_COOLDOWN_MS;
                log.warn("Meilisearch 暂不可用，回退 MySQL LIKE（{}s 内不再重试）: {}",
                        MEILI_COOLDOWN_MS / 1000, e.getMessage());
            } catch (Exception e) {
                meiliCooldownUntil = System.currentTimeMillis() + MEILI_COOLDOWN_MS;
                log.warn("Meilisearch 搜索异常，回退 MySQL LIKE: {}", e.getMessage());
            }
        }
        return searchByMysql(kw, category, null);
    }

    private List<Map<String, Object>> searchByMysql(String q, String category, String author) {
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

        // 作者名映射，用于结果展示 ownerName（避免列表里看不到是谁写的）
        Map<Long, String> nameMap = new HashMap<>();
        for (User u : userRepo.findAll()) {
            nameMap.put(u.getId(), (u.getDisplayName() != null && !u.getDisplayName().isBlank())
                    ? u.getDisplayName() : u.getUsername());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Document doc : docs) {
            if ("BLOCKED".equals(doc.getStatus())) continue; // 排除封禁（黑名单）文档
            if (cat != null && !cat.equals(doc.getCategory())) continue;
            if (finalAuthorIds != null && !finalAuthorIds.contains(doc.getOwnerId())) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            String title = doc.getTitle() == null ? "" : doc.getTitle();
            String contentText = snippetText(doc.getContent());
            String tags = doc.getTags() == null ? "" : doc.getTags();
            String docCat = doc.getCategory() == null ? "" : doc.getCategory();
            double score = 0.0;
            if (!kwEmpty) {
                // 相关性：标题命中权重最高，正文按命中次数累加，标签 / 分类命中加权
                int titleCnt = countOccur(title.toLowerCase(), kwLower);
                int bodyCnt = countOccur(contentText.toLowerCase(), kwLower);
                boolean tagHit = !tags.toLowerCase().contains(kwLower);
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
            // 摘要：有关键词则定位首个命中处，否则取开头
            row.put("snippet", kwEmpty ? (contentText.length() > 160 ? contentText.substring(0, 160) + "…" : contentText)
                                       : highlightSnippet(contentText, kwLower, 160));
            result.add(row);
        }
        result.sort((a, b) -> {
            if (!kwEmpty) {
                int c = Double.compare((Double) b.get("score"), (Double) a.get("score"));
                if (c != 0) return c;
            }
            // 同分 / 纯作者筛选：按更新时间倒序（最新优先）
            Object ua = a.get("updatedAt"), ub = b.get("updatedAt");
            if (ua == null || ub == null) return Long.compare((Long) a.get("id"), (Long) b.get("id"));
            return ((Comparable) ub).compareTo(ua);
        });
        return result;
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
