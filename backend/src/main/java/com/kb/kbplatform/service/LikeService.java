/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.PostLike;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.LikeRepository;
import com.kb.kbplatform.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class LikeService {

    private final LikeRepository likeRepo;
    private final DocumentRepository docRepo;
    private final UserRepository userRepo;
    private final NotificationService notificationService;

    public LikeService(LikeRepository likeRepo, DocumentRepository docRepo,
                       UserRepository userRepo, NotificationService notificationService) {
        this.likeRepo = likeRepo;
        this.docRepo = docRepo;
        this.userRepo = userRepo;
        this.notificationService = notificationService;
    }

    /** 切换点赞：已赞则取消（返回 false），未赞则点赞（返回 true）。
     *  计数用原子 UPDATE 维护（并发点赞不丢更新）；并发双击的重复插入由 (userId,docId)
     *  唯一约束拦下，noRollbackFor 保证事务仍可提交，按"已点赞"返回。 */
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public boolean toggle(Long userId, Long docId) {
        Document d = docRepo.findById(docId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "文档不存在"));
        boolean liked;
        if (likeRepo.existsByUserIdAndDocId(userId, docId)) {
            likeRepo.deleteByUserIdAndDocId(userId, docId);
            docRepo.decrementLikes(docId);
            liked = false;
        } else {
            try {
                likeRepo.saveAndFlush(new PostLike(userId, docId));
            } catch (DataIntegrityViolationException e) {
                // 并发双击：另一请求已插入点赞行，本次不重复计数
                return true;
            }
            docRepo.incrementLikes(docId);
            liked = true;
        }
        // 刚点赞成功时，通知文章作者（给自己点赞不通知）
        if (liked) {
            String username = userRepo.findById(userId).map(User::getUsername).orElse("用户");
            notificationService.notifyLike(docId, d.getOwnerId(), userId, username, d.getTitle());
        }
        return liked;
    }

    /** 当前点赞数（标量查询绕过一级缓存，保证 toggle 后读到原子更新后的最新值） */
    public int likeCount(Long docId) {
        Integer n = docRepo.findLikesById(docId);
        return n == null ? 0 : n;
    }

    /** 我点过赞的文档 id 集合（用于前端标记 👍 填充态） */
    public Set<Long> myLikeIds(Long userId) {
        return likeRepo.findByUserId(userId).stream()
                .map(PostLike::getDocId)
                .collect(Collectors.toSet());
    }

    /** 我点赞过的文档列表（仅已发布，按更新时间倒序），用于个人资料页展示 */
    public List<Document> myLikedDocs(Long userId) {
        Set<Long> ids = myLikeIds(userId);
        if (ids.isEmpty()) return List.of();
        return docRepo.findByStatusAndIdIn("PUBLISHED", new ArrayList<>(ids)).stream()
                .sorted(Comparator.comparing(Document::getUpdatedAt).reversed())
                .collect(Collectors.toList());
    }
}
