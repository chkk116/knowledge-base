/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DocChunk;
import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.repository.DocChunkRepository;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 向量 RAG 索引服务：文档分块 → embedding → 存 kb_doc_chunk；检索时余弦 top-K。
 *
 * 同步时机：挂在 MeiliSearchClient.sync()/delete()（所有内容变更点的现成钩子），
 * 异步单线程执行（保存请求不等 embedding API）；另有管理端全量重建接口。
 *
 * 量级假设：个人/小团队知识库，分块总数几千——检索全量载入内存算余弦 <20ms，
 * 不引入 pgvector；将来量级上来迁 pgvector 时本类接口不变。
 */
@Service
public class RagIndexService {

    private static final Logger log = LoggerFactory.getLogger(RagIndexService.class);
    private static final int CHUNK_LEN = 500;
    private static final int CHUNK_OVERLAP = 60;
    private static final int MAX_CHUNKS = 60;
    private static final double MIN_SCORE = 0.05;

    private final DocChunkRepository chunkRepo;
    private final DocumentRepository documentRepo;
    private final EmbeddingClient embedding;

    /** 单线程异步索引：串行天然避免同文档并发写；守护线程不阻塞 JVM 退出 */
    private final ExecutorService indexer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "rag-vector-index");
        t.setDaemon(true);
        return t;
    });

    public RagIndexService(DocChunkRepository chunkRepo,
                           DocumentRepository documentRepo,
                           EmbeddingClient embedding) {
        this.chunkRepo = chunkRepo;
        this.documentRepo = documentRepo;
        this.embedding = embedding;
    }

    public boolean isEnabled() { return embedding.isEnabled(); }

    /** 文档保存/状态变更后调用：异步重建该文档的向量块 */
    public void indexDocumentAsync(Document d) {
        if (!isEnabled() || d == null || d.getId() == null) return;
        Long id = d.getId();
        indexer.submit(() -> {
            try {
                indexDocumentNow(id);
            } catch (Exception e) {
                log.warn("向量索引失败 doc={}: {}", id, e.getMessage());
            }
        });
    }

    /** 文档删除/下架后调用：异步移除该文档的向量块 */
    public void removeDocumentAsync(Long docId) {
        if (!isEnabled() || docId == null) return;
        indexer.submit(() -> {
            try {
                chunkRepo.deleteByDocId(docId);
            } catch (Exception e) {
                log.warn("向量索引移除失败 doc={}: {}", docId, e.getMessage());
            }
        });
    }

    /** 同步重建单个文档（reindexAll 与异步任务共用） */
    public void indexDocumentNow(Long docId) throws Exception {
        Document d = documentRepo.findById(docId).orElse(null);
        // 非发布状态（草稿/待审/下架/已删）不留向量块，与 Meili 同一口径
        if (d == null || !"PUBLISHED".equals(d.getStatus())) {
            chunkRepo.deleteByDocId(docId);
            return;
        }
        String text = HtmlSanitizer.toText(d.getContent());
        if (text == null || text.isBlank()) {
            chunkRepo.deleteByDocId(docId);
            return;
        }
        List<String> chunks = splitChunks(text);
        List<float[]> vecs = embedding.embedBatch(chunks);
        if (vecs.size() != chunks.size()) throw new IllegalStateException("embedding 返回数量不符");
        chunkRepo.deleteByDocId(docId);
        for (int i = 0; i < chunks.size(); i++) {
            DocChunk c = new DocChunk();
            c.setDocId(docId);
            c.setChunkIdx(i);
            c.setContent(chunks.get(i));
            c.setEmbedding(toJson(vecs.get(i)));
            c.setDocTitle(abbrev(d.getTitle(), 200));
            c.setUpdatedAt(LocalDateTime.now());
            chunkRepo.save(c);
        }
    }

    /** 管理端全量重建：返回成功索引的文档数 */
    public long reindexAll() {
        if (!isEnabled()) throw new IllegalStateException("向量检索未启用（kb.ai.embedding.enabled=false）");
        List<Document> docs = documentRepo.findByStatusOrderByUpdatedAtDesc("PUBLISHED");
        long ok = 0;
        for (Document d : docs) {
            try {
                indexDocumentNow(d.getId());
                ok++;
            } catch (Exception e) {
                log.warn("重建向量索引失败 doc={}: {}", d.getId(), e.getMessage());
            }
        }
        log.info("向量索引全量重建完成：{}/{}", ok, docs.size());
        return ok;
    }

    /**
     * 向量检索：语义 top-K，按文档去重（每文档保留最高分块）。
     * 返回结构与 RagService.sources 同构（id/title/snippet/fullText），失败抛异常由调用方降级。
     */
    public List<Map<String, Object>> search(String query, int k) {
        if (query == null || query.isBlank()) return List.of();
        float[] qv = embedding.embed(query);
        List<DocChunk> all = chunkRepo.findAll();
        Map<Long, DocChunk> best = new LinkedHashMap<>();
        Map<Long, Double> bestScore = new HashMap<>();
        for (DocChunk c : all) {
            float[] v = parseVec(c.getEmbedding());
            if (v == null || v.length != qv.length) continue;
            double s = cosine(qv, v);
            if (s < MIN_SCORE) continue;
            Double prev = bestScore.get(c.getDocId());
            if (prev == null || s > prev) {
                bestScore.put(c.getDocId(), s);
                best.put(c.getDocId(), c);
            }
        }
        return bestScore.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(k)
                .map(e -> {
                    DocChunk c = best.get(e.getKey());
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("id", c.getDocId());
                    s.put("title", c.getDocTitle() == null || c.getDocTitle().isBlank() ? "（无标题）" : c.getDocTitle());
                    s.put("snippet", abbrev(c.getContent(), 200));
                    s.put("fullText", c.getContent());
                    return s;
                })
                .toList();
    }

    // ---------- 分块 / 向量工具 ----------

    /** 分块：段落优先合并到 ~500 字；超长段按窗口切（overlap 60）；上限 MAX_CHUNKS 防失控 */
    static List<String> splitChunks(String text) {
        text = text == null ? "" : text.trim();
        List<String> paras = new ArrayList<>();
        for (String p : text.split("\\n+")) {
            String t = p.trim();
            if (!t.isEmpty()) paras.add(t);
        }
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String p : paras) {
            if (chunks.size() >= MAX_CHUNKS) break;
            if (p.length() > CHUNK_LEN) {
                if (cur.length() > 0) { chunks.add(cur.toString()); cur.setLength(0); }
                for (int i = 0; i < p.length() && chunks.size() < MAX_CHUNKS; i += CHUNK_LEN - CHUNK_OVERLAP) {
                    chunks.add(p.substring(i, Math.min(p.length(), i + CHUNK_LEN)));
                }
                continue;
            }
            if (cur.length() + p.length() + 1 > CHUNK_LEN && cur.length() > 0) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append('\n');
            cur.append(p);
        }
        if (cur.length() > 0 && chunks.size() < MAX_CHUNKS) chunks.add(cur.toString());
        return chunks;
    }

    static String toJson(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    static float[] parseVec(String json) {
        if (json == null || json.isBlank()) return null;
        String s = json.trim();
        if (!s.startsWith("[") || !s.endsWith("]")) return null;
        try {
            String[] parts = s.substring(1, s.length() - 1).split(",");
            float[] v = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                String t = parts[i].trim();
                if (t.isEmpty()) return null;
                v[i] = Float.parseFloat(t);
            }
            return v.length == 0 ? null : v;
        } catch (Exception e) {
            return null;
        }
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        if (na == 0 || nb == 0) return 0;
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    static String abbrev(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
