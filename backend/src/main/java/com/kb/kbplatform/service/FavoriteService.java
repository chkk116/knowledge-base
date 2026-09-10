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
    @Transactional
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
