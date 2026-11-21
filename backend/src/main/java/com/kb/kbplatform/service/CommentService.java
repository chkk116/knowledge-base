/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

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
import org.springframework.dao.DataIntegrityViolationException;
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

    /** 列出某帖子（DOC）的评论（旧接口，等价于 list("DOC", docId)） */
    public List<Comment> list(Long docId) {
        return list("DOC", docId);
    }

    /** 通用：按对象类型 + 目标 id 列出评论（DOC 帖子 / CHAPTER 章节） */
    public List<Comment> list(String targetType, Long targetId) {
        return repo.findByTargetTypeAndDocIdOrderByCreatedAtAsc(targetType, targetId);
    }

    /** 切换评论点赞：已赞则取消（返回 false），未赞则点赞（返回 true）。
     *  计数用原子 UPDATE 维护；并发双击的重复插入由 (userId,commentId) 唯一约束拦下，
     *  noRollbackFor 保证事务仍可提交，按"已点赞"返回。 */
    @Transactional(noRollbackFor = DataIntegrityViolationException.class)
    public Map<String, Object> like(Long commentId, Long userId) {
        repo.findById(commentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "评论不存在"));
        boolean liked;
        if (likeRepo.existsByUserIdAndCommentId(userId, commentId)) {
            likeRepo.deleteByUserIdAndCommentId(userId, commentId);
            repo.decrementLikes(commentId);
            liked = false;
        } else {
            try {
                likeRepo.saveAndFlush(new CommentLike(userId, commentId));
            } catch (DataIntegrityViolationException e) {
                // 并发双击：另一请求已插入点赞行，本次不重复计数
                return Map.of("liked", true, "likes", likeCount(commentId));
            }
            repo.incrementLikes(commentId);
            liked = true;
        }
        return Map.of("liked", liked, "likes", likeCount(commentId));
    }

    /** 当前评论点赞数（标量查询绕过一级缓存，保证原子更新后读到最新值） */
    private int likeCount(Long commentId) {
        Integer n = repo.findLikesById(commentId);
        return n == null ? 0 : n;
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

    /** 公开发表评论到帖子（DOC），保留原有通知逻辑 */
    public Comment add(Long docId, TokenClaims claims, String rawContent, Long parentId) {
        return add("DOC", docId, claims, rawContent, parentId);
    }

    /**
     * 通用评论发表：targetType ∈ {DOC, CHAPTER}；DOC 走文章作者/回复通知，CHAPTER 仅入库不通知。
     * 需要事务：评论入库 + 最多 3 处通知写入（@提及 / 回复 / 作者）必须一起成功，
     * 否则会出现「评论已发出但作者收不到通知」的静默不一致。
     */
    @Transactional(rollbackFor = Exception.class)
    public Comment add(String targetType, Long targetId, TokenClaims claims, String rawContent, Long parentId) {
        String content = HtmlSanitizer.sanitize(rawContent);
        if (content == null || content.isBlank()) {
            throw new BizException("评论内容不能为空或仅含不支持的内容");
        }
        // 敏感词检查必须针对「去掉标签后的可见纯文本」：
        // 若直接拿原始 HTML 匹配，粘贴图片的 base64 data URI 是一串随机大小写字母数字，
        // 极易撞上词库里的短 ASCII 词（sb / cnm / tmd / nmd 等），导致带图的正常评论被拒（400）。
        String plainText = HtmlSanitizer.toText(content);
        if (sensitiveWordFilter.contains(plainText)) {
            throw new BizException("评论内容包含敏感词：" + String.join("、", sensitiveWordFilter.find(plainText)));
        }
        // 双击/网络重试幂等（外部测试 N34）：同一人同一对象 2 秒内同一内容视为同一条
        java.util.Optional<Comment> dup = claims == null ? java.util.Optional.empty()
                : repo.findFirstByDocIdAndUserIdAndContentOrderByCreatedAtDesc(targetType, targetId, claims.userId(), content);
        if (dup.isPresent() && dup.get().getCreatedAt() != null
                && dup.get().getCreatedAt().isAfter(java.time.LocalDateTime.now().minusSeconds(2))) {
            return dup.get();
        }
        Comment c = new Comment();
        // 父评论归属校验（2026-10-13 审查 M10）：parentId 须指向同一对象下的评论，否则可跨文档伪造楼层
        if (parentId != null) {
            Comment parent = repo.findById(parentId).orElse(null);
            if (parent == null || !targetType.equals(parent.getTargetType()) || !targetId.equals(parent.getDocId())) {
                throw new BizException("要回复的评论不存在或不属于当前内容");
            }
        }
        c.setTargetType(targetType);
        c.setDocId(targetId);   // 复用 docId 列作为通用 targetId
        c.setParentId(parentId);
        c.setUserId(claims == null ? null : claims.userId());
        c.setUsername(claims == null ? "匿名" : claims.username());
        c.setContent(content);
        Comment saved = repo.save(c);
        String actorName = claims == null ? "匿名" : claims.username();
        Long actorId = claims == null ? null : claims.userId();
        if ("DOC".equals(targetType)) {
            // 评论含 @用户名 时，给被提及的已存在用户发站内通知（排除自己）
            notificationService.notifyMention(targetId, actorName, saved.getId(), rawContent);
            Document doc = docRepo.findById(targetId).orElse(null);
            String docTitle = doc == null ? "" : doc.getTitle();
            if (parentId != null) {
                // 楼中楼回复：通知父评论作者（排除自己）；父评论不存在则退化为顶层评论逻辑
                Comment parent = repo.findById(parentId).orElse(null);
                if (parent != null && !Objects.equals(parent.getUsername(), claims == null ? null : claims.username())) {
                    String preview = HtmlSanitizer.preview(rawContent, 40);
                    notificationService.notifyReply(targetId, parent.getUserId(), actorName, saved.getId(), docTitle, preview);
                }
            } else if (doc != null) {
                // 顶层评论：通知文章作者（评论自己文章不重复通知）
                String preview = HtmlSanitizer.preview(rawContent, 40);
                notificationService.notifyComment(targetId, doc.getOwnerId(), actorId, actorName,
                        saved.getId(), docTitle, preview);
            }
        }
        return saved;
    }

    /** 删除自己的评论：仅评论作者本人可删（按 username 判定归属，兼容旧数据 userId 为空的情况） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteOwn(Long id, TokenClaims claims) {
        if (claims == null) throw new ResponseStatusException(FORBIDDEN, "请先登录");
        Comment c = repo.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "评论不存在"));
        boolean isOwner = claims.username() != null && claims.username().equals(c.getUsername());
        if (!isOwner) {
            throw new ResponseStatusException(FORBIDDEN, "只能删除自己的评论");
        }
        likeRepo.deleteByCommentId(id);   // 先清点赞记录，避免 kb_comment_like 残留孤儿
        repo.deleteById(id);
    }

    /** 管理员删除（保留原语义） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        likeRepo.deleteByCommentId(id);
        repo.deleteById(id);
    }
}
