package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.PostLike;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.LikeRepository;
import com.kb.kbplatform.repository.UserRepository;
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

    /** 切换点赞：已赞则取消（返回 false），未赞则点赞（返回 true），并同步维护 Document.likes 计数 */
    @Transactional
    public boolean toggle(Long userId, Long docId) {
        Document d = docRepo.findById(docId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "文档不存在"));
        boolean liked;
        if (likeRepo.existsByUserIdAndDocId(userId, docId)) {
            likeRepo.deleteByUserIdAndDocId(userId, docId);
            d.setLikes(Math.max(0, d.getLikes() - 1));
            liked = false;
        } else {
            likeRepo.save(new PostLike(userId, docId));
            d.setLikes(d.getLikes() + 1);
            liked = true;
        }
        docRepo.save(d);
        // 刚点赞成功时，通知文章作者（给自己点赞不通知）
        if (liked) {
            String username = userRepo.findById(userId).map(User::getUsername).orElse("用户");
            notificationService.notifyLike(docId, d.getOwnerId(), userId, username, d.getTitle());
        }
        return liked;
    }

    /** 当前点赞数（供切换后前端即时刷新显示） */
    public int likeCount(Long docId) {
        return docRepo.findById(docId).map(Document::getLikes).orElse(0);
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
