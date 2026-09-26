/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DocSubscription;
import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.repository.DocSubscriptionRepository;
import com.kb.kbplatform.repository.DocumentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 文档订阅：用户订阅文档后，文档被更新时由 NotificationService 推送站内通知。
 * 订阅仅对已发布文档开放——草稿/待审核/封禁内容不该被订阅推送。
 */
@Service
public class DocSubscriptionService {

    private final DocSubscriptionRepository repo;
    private final DocumentRepository docRepo;

    public DocSubscriptionService(DocSubscriptionRepository repo, DocumentRepository docRepo) {
        this.repo = repo;
        this.docRepo = docRepo;
    }

    /** 订阅（幂等：重复订阅直接返回，不报错） */
    public boolean subscribe(Long userId, Long docId) {
        Document d = docRepo.findById(docId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "文档不存在"));
        if (!"PUBLISHED".equals(d.getStatus()))
            throw new ResponseStatusException(FORBIDDEN, "只能订阅已发布的文档");
        if (repo.findByUserIdAndDocId(userId, docId).isPresent()) return false;   // 已订阅，无变化
        DocSubscription s = new DocSubscription();
        s.setUserId(userId);
        s.setDocId(docId);
        repo.save(s);
        return true;
    }

    /** 取消订阅（未订阅也不报错，返回 false 表示本来就没订阅）。
     *  注意：deleteByUserAndDoc 是 JPQL DELETE 修改查询，必须在事务内执行，否则报
     *  "No active transaction for update or delete query"。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean unsubscribe(Long userId, Long docId) {
        return repo.deleteByUserAndDoc(userId, docId) > 0;
    }

    public boolean isSubscribed(Long userId, Long docId) {
        if (userId == null) return false;
        return repo.findByUserIdAndDocId(userId, docId).isPresent();
    }

    /** 文档订阅人数（详情页展示） */
    public long countSubscribers(Long docId) {
        return repo.countByDocId(docId);
    }

    /** 我的订阅列表：附带文档标题与分类，文档已被删除时标注 deleted 供前端置灰 */
    public List<Map<String, Object>> mySubscriptions(Long userId) {
        List<DocSubscription> subs = repo.findByUserIdOrderByCreatedAtDesc(userId);
        if (subs.isEmpty()) return List.of();
        List<Long> docIds = new ArrayList<>();
        for (DocSubscription s : subs) docIds.add(s.getDocId());
        Map<Long, Document> docs = new LinkedHashMap<>();
        for (Document d : docRepo.findAllById(docIds)) docs.put(d.getId(), d);

        List<Map<String, Object>> res = new ArrayList<>();
        for (DocSubscription s : subs) {
            Document d = docs.get(s.getDocId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", s.getDocId());
            m.put("title", d == null ? "（文档已删除）" : d.getTitle());
            m.put("category", d == null ? null : d.getCategory());
            m.put("deleted", d == null);
            m.put("subscribedAt", s.getCreatedAt());
            res.add(m);
        }
        return res;
    }
}
