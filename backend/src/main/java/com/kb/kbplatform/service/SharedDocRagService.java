/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DocShare;
import com.kb.kbplatform.repository.DocShareRepository;
import com.kb.kbplatform.repository.SharedDocumentRagRow;
import com.kb.kbplatform.repository.SharedDocumentRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.util.YjsTextExtractor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * 共享文档（DocShare 空间）接入 RAG 的检索服务。
 *
 * <p>与公开文章库刻意分开：两套表各自 IDENTITY、id 会撞号，现有 Meili / 向量索引都没有空间维度，
 * 直接塞进全局索引会让任何登录用户命中别人的团队文档。这里走独立的「按用户检索 + 数据库当前权限
 * 逐条复核」，索引与数据库权限不一致时以数据库为准（fail-closed）。</p>
 *
 * <p>正文来源：content 是 HTML 兼容快照（保存快照时才写），协同实时正文在 yjsState 二进制里，
 * 两者都取，取更长的那份参与关键词匹配。</p>
 */
@Service
public class SharedDocRagService {

    private static final Logger log = LoggerFactory.getLogger(SharedDocRagService.class);
    /** 单次问答最多扫描的共享文档数：防止万级文档时把大量 Yjs 二进制拉进堆内存 */
    private static final int MAX_SCAN = 500;
    private static final int FRAG_LEN = 400;

    private final DocShareRepository shareRepo;
    private final SharedDocumentRepository docRepo;
    private final DocShareService docShareService;

    public SharedDocRagService(DocShareRepository shareRepo,
                               SharedDocumentRepository docRepo,
                               DocShareService docShareService) {
        this.shareRepo = shareRepo;
        this.docRepo = docRepo;
        this.docShareService = docShareService;
    }

    /**
     * 检索当前用户可读的共享文档。
     *
     * @param userId   登录用户 id；为 null 一律返回空（内部无用户上下文的调用不得读取私有文档）
     * @param keywords 已拆好的检索词
     * @param limit    最多返回几条
     */
    public List<Map<String, Object>> search(String q, Long userId, List<String> keywords, int limit) {
        if (userId == null || limit <= 0) return List.of();
        List<String> kws = keywords == null || keywords.isEmpty() ? List.of(q == null ? "" : q) : keywords;
        try {
            // 不复用 listMine（它带 ensurePersonalSpace 的写副作用）；直接查「我创建或参与的空间」再逐条复核
            Map<Long, String> readable = new LinkedHashMap<>();
            for (DocShare s : shareRepo.findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(userId)) {
                if ("ARCHIVED".equals(s.getStatus())) continue;
                if (!docShareService.canReadSpace(s.getId(), userId, false)) continue;
                readable.put(s.getId(), s.getName() == null ? "" : s.getName());
            }
            if (readable.isEmpty()) return List.of();

            List<SharedDocumentRagRow> rows = docRepo.findRagRowsByShareIdIn(
                    readable.keySet(), PageRequest.of(0, MAX_SCAN));

            // 先做内存里的关键词筛选，再只对最终入选的候选查数据库权限：
            // 未命中（不会送进模型）的文档根本不需要文档级复核，避免扫描 500 篇触发上千次查询
            record Candidate(SharedDocumentRagRow row, String title, String text, int score) {}
            List<Candidate> candidates = new ArrayList<>();
            for (SharedDocumentRagRow row : rows) {
                if (row.getId() == null || row.getShareId() == null) continue;
                // 只认本次确认可读的空间：防止 docId 跨空间串数据
                if (!readable.containsKey(row.getShareId())) continue;

                String title = row.getTitle() == null ? "" : row.getTitle();
                String html = HtmlSanitizer.toText(row.getContent() == null ? "" : row.getContent());
                String yjs = YjsTextExtractor.extract(row.getYjsState());
                String text = yjs.length() > html.length() ? yjs : html;
                String hay = (title + " " + text).toLowerCase(Locale.ROOT);
                int score = 0;
                for (String kw : kws) {
                    String k = kw == null ? "" : kw.trim().toLowerCase(Locale.ROOT);
                    if (!k.isEmpty() && hay.contains(k)) score++;
                }
                if (score == 0) continue;
                candidates.add(new Candidate(row, title, text, score));
            }
            candidates.sort((a, b) -> Integer.compare(b.score(), a.score()));

            List<Map<String, Object>> picked = new ArrayList<>();
            for (Candidate c : candidates) {
                if (picked.size() >= limit) break;
                SharedDocumentRagRow row = c.row();
                // 文档级权限可收窄空间权限，且索引/缓存与数据库可能不一致 —— 送入模型前用当前状态复核
                if (docShareService.docPermission(row.getShareId(), row.getId(), userId, false) == null) continue;
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", row.getId());
                s.put("title", c.title());
                s.put("snippet", window(c.text(), kws));
                s.put("fullText", window(c.text(), kws));
                s.put("docType", "share");
                s.put("shareId", row.getShareId());
                s.put("docId", row.getId());
                s.put("spaceName", readable.get(row.getShareId()));
                picked.add(s);
            }
            return picked;
        } catch (Exception e) {
            // 检索失败不阻断公开文章检索，也绝不放行任何未复核的私有内容
            log.warn("共享文档检索失败，本次忽略共享来源: {}", e.getMessage());
            return List.of();
        }
    }

    /** 取覆盖关键词的窗口，避免把整篇长文塞进提示词 */
    private String window(String text, List<String> keywords) {
        if (text == null || text.isEmpty()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        int hit = -1;
        for (String kw : keywords) {
            String k = kw == null ? "" : kw.trim().toLowerCase(Locale.ROOT);
            if (k.isEmpty()) continue;
            int i = lower.indexOf(k);
            if (i >= 0 && (hit < 0 || i < hit)) hit = i;
        }
        if (hit < 0) return text.substring(0, Math.min(FRAG_LEN, text.length()));
        int from = Math.max(0, hit - FRAG_LEN / 3);
        int to = Math.min(text.length(), from + FRAG_LEN);
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
    }
}
