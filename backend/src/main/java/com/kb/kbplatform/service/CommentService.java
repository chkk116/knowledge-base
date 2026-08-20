package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Comment;
import com.kb.kbplatform.domain.CommentLike;
import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.repository.CommentLikeRepository;
import com.kb.kbplatform.repository.CommentRepository;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.security.SensitiveWordFilter;
import com.kb.kbplatform.security.TokenClaims;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class CommentService {

    private final CommentRepository repo;
    private final DocumentRepository docRepo;
    private final CommentLikeRepository likeRepo;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final NotificationService notificationService;

    public CommentService(CommentRepository repo, DocumentRepository docRepo, CommentLikeRepository likeRepo,
                           SensitiveWordFilter sensitiveWordFilter, NotificationService notificationService) {
        this.repo = repo;
        this.docRepo = docRepo;
        this.likeRepo = likeRepo;
        this.sensitiveWordFilter = sensitiveWordFilter;
        this.notificationService = notificationService;
    }

    public List<Comment> list(Long docId) {
        return repo.findByDocIdOrderByCreatedAtAsc(docId);
    }

    /** 切换评论点赞：已赞则取消（返回 false），未赞则点赞（返回 true），并同步维护 Comment.likes 计数 */
    @Transactional
    public Map<String, Object> like(Long commentId, Long userId) {
        Comment c = repo.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "评论不存在"));
        boolean liked;
        if (likeRepo.existsByUserIdAndCommentId(userId, commentId)) {
            likeRepo.deleteByUserIdAndCommentId(userId, commentId);
            c.setLikes(Math.max(0, c.getLikes() - 1));
            liked = false;
        } else {
            likeRepo.save(new CommentLike(userId, commentId));
            c.setLikes(c.getLikes() + 1);
            liked = true;
        }
        repo.save(c);
        return Map.of("liked", liked, "likes", c.getLikes());
    }

    /** 我点过赞的评论 id 集合（用于前端标记点赞填充态） */
    public Set<Long> myLikedCommentIds(Long userId) {
        return likeRepo.findByUserId(userId).stream()
                .map(CommentLike::getCommentId)
                .collect(Collectors.toSet());
    }

    /** 我评论过的文档列表（仅已发布，按更新时间倒序），用于个人资料页展示 */
    public List<Document> myCommentedDocs(String username) {
        if (username == null || username.isBlank()) return List.of();
        List<Comment> comments = repo.findByUsernameOrderByCreatedAtDesc(username);
        Set<Long> docIds = new LinkedHashSet<>();
        for (Comment c : comments) docIds.add(c.getDocId());
        if (docIds.isEmpty()) return List.of();
        return docRepo.findByStatusAndIdIn("PUBLISHED", new ArrayList<>(docIds)).stream()
                .sorted(Comparator.comparing(Document::getUpdatedAt).reversed())
                .collect(Collectors.toList());
    }

    public Comment add(Long docId, TokenClaims claims, String rawContent, Long parentId) {
        // 敏感词（和谐词）检查：命中则拒绝提交，返回 400
        if (sensitiveWordFilter.contains(rawContent)) {
            throw new IllegalArgumentException("评论内容包含敏感词：" + String.join("、", sensitiveWordFilter.find(rawContent)));
        }
        String content = HtmlSanitizer.sanitize(rawContent);
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("评论内容不能为空");
        }
        Comment c = new Comment();
        c.setDocId(docId);
        c.setParentId(parentId);
        c.setUserId(claims == null ? null : claims.userId());
        c.setUsername(claims == null ? "匿名" : claims.username());
        c.setContent(content);
        Comment saved = repo.save(c);
        String actorName = claims == null ? "匿名" : claims.username();
        Long actorId = claims == null ? null : claims.userId();
        // 评论含 @用户名 时，给被提及的已存在用户发站内通知（排除自己）
        notificationService.notifyMention(docId, actorName, saved.getId(), rawContent);
        Document doc = docRepo.findById(docId).orElse(null);
        String docTitle = doc == null ? "" : doc.getTitle();
        if (parentId != null) {
            // 楼中楼回复：通知父评论作者（排除自己）；父评论不存在则退化为顶层评论逻辑
            Comment parent = repo.findById(parentId).orElse(null);
            if (parent != null && !Objects.equals(parent.getUsername(), claims == null ? null : claims.username())) {
                String preview = rawContent.length() > 40 ? rawContent.substring(0, 40) + "…" : rawContent;
                notificationService.notifyReply(docId, parent.getUserId(), actorName, saved.getId(), docTitle, preview);
            }
        } else if (doc != null) {
            // 顶层评论：通知文章作者（评论自己文章不重复通知）
            String preview = rawContent.length() > 40 ? rawContent.substring(0, 40) + "…" : rawContent;
            notificationService.notifyComment(docId, doc.getOwnerId(), actorId, actorName,
                    saved.getId(), docTitle, preview);
        }
        return saved;
    }

    /** 删除自己的评论：仅评论作者本人可删（按 username 判定归属，兼容旧数据 userId 为空的情况） */
    public void deleteOwn(Long id, TokenClaims claims) {
        if (claims == null) throw new ResponseStatusException(FORBIDDEN, "请先登录");
        Comment c = repo.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "评论不存在"));
        boolean isOwner = claims.username() != null && claims.username().equals(c.getUsername());
        if (!isOwner) {
            throw new ResponseStatusException(FORBIDDEN, "只能删除自己的评论");
        }
        repo.deleteById(id);
    }

    /** 管理员删除（保留原语义） */
    public void delete(Long id) {
        repo.deleteById(id);
    }
}
