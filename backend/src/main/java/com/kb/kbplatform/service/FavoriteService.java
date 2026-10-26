/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.Favorite;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.FavoriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class FavoriteService {

    private final FavoriteRepository favoriteRepo;
    private final DocumentRepository docRepo;

    public FavoriteService(FavoriteRepository favoriteRepo, DocumentRepository docRepo) {
        this.favoriteRepo = favoriteRepo;
        this.docRepo = docRepo;
    }

    /** 切换收藏：已收藏则取消（返回 false），未收藏则收藏（返回 true） */
    @Transactional(rollbackFor = Exception.class)
    public boolean toggle(Long userId, Long docId) {
        // 安全/数据完整性：不存在或已删除的文档不允许被收藏（否则可灌入 doc_id 为负数/已删文档的幽灵收藏）
        Document target = docRepo.findById(docId).orElse(null);
        if (target == null || target.isDeleted()) {
            throw new BizException("文档不存在或已删除");
        }
        if (favoriteRepo.existsByUserIdAndDocId(userId, docId)) {
            favoriteRepo.deleteByUserIdAndDocId(userId, docId);
            return false;
        }
        favoriteRepo.save(new Favorite(userId, docId));
        return true;
    }

    /** 设置收藏分组（空串 = 取消分组）；返回归一化后的分组名 */
    public String setGroup(Long userId, Long docId, String group) {
        Favorite f = favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .filter((x) -> x.getDocId().equals(docId))
                .findFirst()
                .orElseThrow(() -> new BizException("尚未收藏该文档"));
        String g = group == null ? "" : group.trim();
        if (g.length() > 30) g = g.substring(0, 30);
        f.setGroupName(g.isEmpty() ? null : g);
        favoriteRepo.save(f);
        return g;
    }

    /** 我的全部分组 + 各自数量（未分组不计入，前端单独展示「未分组」） */
    public java.util.List<java.util.Map<String, Object>> groupStats(Long userId) {
        java.util.Map<String, Integer> cnt = new java.util.LinkedHashMap<>();
        for (Favorite f : favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
            String g = f.getGroupName();
            if (g == null || g.isBlank()) continue;
            cnt.merge(g, 1, Integer::sum);
        }
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        cnt.forEach((name, n) -> out.add(java.util.Map.of("name", name, "count", n)));
        out.sort((x, y) -> Integer.compare((Integer) y.get("count"), (Integer) x.get("count")));
        return out;
    }

    /** docId → 分组名（前端把分组标到卡片上） */
    public java.util.Map<Long, String> groupOf(Long userId) {
        java.util.Map<Long, String> m = new java.util.HashMap<>();
        for (Favorite f : favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (f.getGroupName() != null && !f.getGroupName().isBlank()) m.put(f.getDocId(), f.getGroupName());
        }
        return m;
    }

    /** 我收藏的文档 id 集合（用于前端标记卡片星标状态） */
    public Set<Long> myFavoriteIds(Long userId) {
        return favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(Favorite::getDocId)
                .collect(Collectors.toCollection(HashSet::new));
    }

    /** 我收藏的文档列表：按收藏时间倒序，仅返回已发布（草稿/封禁会造成详情 404 死链） */
    public List<Document> myFavorites(Long userId) {
        List<Favorite> favs = favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId);
        if (favs.isEmpty()) return new ArrayList<>();
        List<Long> ids = favs.stream().map(Favorite::getDocId).collect(Collectors.toList());
        Map<Long, Document> byId = docRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(Document::getId, Function.identity()));
        List<Document> result = new ArrayList<>();
        for (Long id : ids) {
            Document d = byId.get(id);
            if (d != null && "PUBLISHED".equals(d.getStatus())) result.add(d);
        }
        return result;
    }
}
