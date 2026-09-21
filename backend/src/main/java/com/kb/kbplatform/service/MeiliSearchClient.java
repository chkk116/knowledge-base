package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.security.HtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 轻量级 Meilisearch 客户端（基于 Spring 自带的 RestClient，零额外依赖，避开 SB 4 下 meilisearch-java SDK 的兼容风险）。
 *
 * 设计原则：
 *  - 搜索是「增强」不是「必须」：任何异常都向上抛 MeiliUnavailableException，由 SearchService 回退到 MySQL LIKE。
 *  - 索引同步 best-effort：写入/删除失败仅记日志，绝不影响主流程；MySQL 始终是 source of truth。
 *  - host 为空即视为「未启用」，所有方法空操作或抛 MeiliUnavailableException，老部署零改动继续用 MySQL。
 */
@Component
public class MeiliSearchClient {

    private static final Logger log = LoggerFactory.getLogger(MeiliSearchClient.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final RestClient client;
    private final String host;
    private final String apiKey;
    private final String indexUid;
    private final boolean enabled;

    public MeiliSearchClient(@Value("${kb.search.meili.host:}") String host,
                             @Value("${kb.search.meili.api-key:}") String apiKey,
                             @Value("${kb.search.meili.index:documents}") String indexUid) {
        this.host = host == null ? "" : host.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.indexUid = (indexUid == null || indexUid.isBlank()) ? "documents" : indexUid.trim();
        this.enabled = !this.host.isEmpty();
        if (enabled) {
            // 用静态工厂构建，不依赖 Spring 自动配置的 RestClient.Builder bean（SB 4.1.0 下该 bean 未注入）
            RestClient.Builder b = RestClient.builder().baseUrl(this.host);
            if (!this.apiKey.isEmpty()) {
                b = b.defaultHeader("Authorization", "Bearer " + this.apiKey);
            }
            this.client = b.build();
        } else {
            this.client = null;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 同步单篇文档：已发布则写入索引，否则从索引移除（草稿/待审核/封禁不可被搜到） */
    public void sync(Document d) {
        if (!enabled || d == null || d.getId() == null) return;
        if ("PUBLISHED".equals(d.getStatus())) upsert(d);
        else delete(d.getId());
    }

    public void delete(Long id) {
        if (!enabled || id == null) return;
        try {
            client.delete()
                    .uri("/indexes/{uid}/documents/{id}", indexUid, id)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Meili 删除索引失败 doc={}: {}", id, e.getMessage());
        }
    }

    private void upsert(Document d) {
        try {
            client.post()
                    .uri("/indexes/{uid}/documents", indexUid)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Collections.singletonList(toMap(d)))
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Meili 写入索引失败 doc={}: {}", d.getId(), e.getMessage());
        }
    }

    /** 全量重建索引（仅已发布文档），供启动时 / 管理后台「重建索引」调用。
     *  F10：重建不再「只追加」——上传后拉取索引里现存全部 id，把不在本次已发布集合内的
     *  孤儿条目（撤回/封禁/删除后删除索引失败的残留）批量清掉。 */
    public void reindexAll(List<Document> docs) {
        if (!enabled) return;
        List<Map<String, Object>> payload = new ArrayList<>();
        Set<Long> kept = new HashSet<>();
        for (Document d : docs) {
            if (d != null && d.getId() != null && "PUBLISHED".equals(d.getStatus())) {
                payload.add(toMap(d));
                kept.add(d.getId());
            }
        }
        try {
            client.post()
                    .uri("/indexes/{uid}/documents", indexUid)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("Meili 全量重建索引失败: {}", e.getMessage());
            return;
        }
        // 清理孤儿条目
        try {
            // Meili v1.1+ 返回分页对象 {"results":[...], "offset":.., "limit":.., "total":..}，不能直接反序列化成 List
            java.util.Map<?, ?> page = client.get()
                    .uri(uri -> uri.path("/indexes/{uid}/documents")
                            .queryParam("fields", "id")
                            .queryParam("limit", 100000)
                            .build(indexUid))
                    .retrieve()
                    .body(java.util.Map.class);
            List<?> existing = page == null ? null : (List<?>) page.get("results");
            if (existing == null || existing.isEmpty()) return;
            List<Long> stale = new ArrayList<>();
            for (Object o : existing) {
                if (!(o instanceof Map<?, ?> m) || m.get("id") == null) continue;
                long id;
                try { id = Long.parseLong(String.valueOf(m.get("id"))); } catch (Exception e) { continue; }
                if (!kept.contains(id)) stale.add(id);
            }
            if (stale.isEmpty()) return;
            client.post()
                    .uri("/indexes/{uid}/documents/delete-batch", indexUid)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(stale)
                    .retrieve().toBodilessEntity();
            log.warn("Meili 索引重建：清理孤儿条目 {} 条（索引与数据库不一致的残留）", stale.size());
        } catch (Exception e) {
            log.warn("Meili 孤儿条目清理失败（不影响本次重建）: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> search(String q, String category) {
        if (!enabled) throw new MeiliUnavailableException("Meilisearch 未启用");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", q);
        body.put("limit", 50);
        Map<String, Object> resp;
        try {
            resp = client.post()
                    .uri("/indexes/{uid}/search", indexUid)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw new MeiliUnavailableException(e);
        }
        if (resp == null) return Collections.emptyList();
        List<Map<String, Object>> hits = (List<Map<String, Object>>) resp.getOrDefault("hits", Collections.emptyList());
        String kw = q.toLowerCase();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> hit : hits) {
            String cat = str(hit.get("category"));
            if (category != null && !category.isBlank() && !category.equals(cat)) continue; // 分类筛选在客户端做，避免依赖服务端 filterable 配置
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", longOf(hit.get("id")));
            row.put("title", str(hit.get("title")));
            row.put("category", cat);
            row.put("tags", str(hit.get("tags")));
            row.put("updatedAt", str(hit.get("updatedAt")));
            Object score = hit.get("_score");
            row.put("score", score == null ? 0.0 : score);
            row.put("snippet", snippet(str(hit.get("content")), kw)); // 纯文本摘要，前端自行高亮
            out.add(row);
        }
        return out;
    }

    /** 文档 → Meili 文档（content 存纯文本，搜索/摘要都更干净） */
    private Map<String, Object> toMap(Document d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("title", d.getTitle());
        m.put("content", HtmlSanitizer.toText(d.getContent()));
        m.put("category", d.getCategory());
        m.put("tags", d.getTags());
        m.put("ownerId", d.getOwnerId());
        m.put("status", d.getStatus());
        m.put("updatedAt", d.getUpdatedAt() == null ? "" : d.getUpdatedAt().format(ISO));
        return m;
    }

    /** 中文友好的摘要：定位首个命中词前后取窗口，超出截断 */
    private String snippet(String text, String kw) {
        if (text == null || text.isEmpty()) return "";
        if (kw.isEmpty()) {
            return text.length() <= 160 ? text : text.substring(0, 160) + "…";
        }
        String lower = text.toLowerCase();
        int hit = lower.indexOf(kw);
        String win;
        if (hit < 0) {
            win = text;
        } else {
            int start = Math.max(0, hit - 30);
            int end = Math.min(text.length(), hit + kw.length() + 130);
            win = (start > 0 ? "…" : "") + text.substring(start, end);
        }
        return win.length() <= 160 ? win : win + "…";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static Long longOf(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.valueOf(String.valueOf(o));
        } catch (Exception e) {
            return null;
        }
    }
}
