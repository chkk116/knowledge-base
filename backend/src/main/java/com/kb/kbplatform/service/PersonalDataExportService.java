/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.*;
import com.kb.kbplatform.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 个人数据导出（隐私政策「你的权利」）。
 *
 * 只导出当前登录用户自己的数据；不含密码、令牌、他人私信正文。
 * 各类数据设上限，避免超大账号把服务打挂：文章 500、评论 1000、通知 500、AI 会话 50（每条 100 消息）。
 * 文件只导出元数据（文件名/大小/类型/地址），不打包二进制。
 */
@Service
public class PersonalDataExportService {

    private static final int MAX_ARTICLES = 500;
    private static final int MAX_COMMENTS = 1000;
    private static final int MAX_NOTIFICATIONS = 500;
    private static final int MAX_ANNOTATIONS = 500;
    private static final int MAX_CONVERSATIONS = 50;
    private static final int MAX_MESSAGES_PER_CONV = 100;

    private final UserRepository userRepo;
    private final DocumentRepository documentRepo;
    private final CommentRepository commentRepo;
    private final FavoriteRepository favoriteRepo;
    private final LikeRepository likeRepo;
    private final CommentLikeRepository commentLikeRepo;
    private final SharedLikeRepository sharedLikeRepo;
    private final DocSubscriptionRepository subRepo;
    private final UploadedFileRepository fileRepo;
    private final PointsLogRepository pointsRepo;
    private final ExamRecordRepository examRepo;
    private final PracticeLogRepository practiceRepo;
    private final StudyPlanRepository planRepo;
    private final StudyPlanItemRepository planItemRepo;
    private final FlashcardRepository flashRepo;
    private final DocAnnotationRepository annoRepo;
    private final NotificationRepository notifRepo;
    private final AiConversationRepository convRepo;
    private final AiMessageRepository msgRepo;

    public PersonalDataExportService(UserRepository userRepo, DocumentRepository documentRepo,
                                     CommentRepository commentRepo, FavoriteRepository favoriteRepo,
                                     LikeRepository likeRepo, CommentLikeRepository commentLikeRepo,
                                     SharedLikeRepository sharedLikeRepo, DocSubscriptionRepository subRepo,
                                     UploadedFileRepository fileRepo, PointsLogRepository pointsRepo,
                                     ExamRecordRepository examRepo, PracticeLogRepository practiceRepo,
                                     StudyPlanRepository planRepo, StudyPlanItemRepository planItemRepo,
                                     FlashcardRepository flashRepo, DocAnnotationRepository annoRepo,
                                     NotificationRepository notifRepo, AiConversationRepository convRepo,
                                     AiMessageRepository msgRepo) {
        this.userRepo = userRepo;
        this.documentRepo = documentRepo;
        this.commentRepo = commentRepo;
        this.favoriteRepo = favoriteRepo;
        this.likeRepo = likeRepo;
        this.commentLikeRepo = commentLikeRepo;
        this.sharedLikeRepo = sharedLikeRepo;
        this.subRepo = subRepo;
        this.fileRepo = fileRepo;
        this.pointsRepo = pointsRepo;
        this.examRepo = examRepo;
        this.practiceRepo = practiceRepo;
        this.planRepo = planRepo;
        this.planItemRepo = planItemRepo;
        this.flashRepo = flashRepo;
        this.annoRepo = annoRepo;
        this.notifRepo = notifRepo;
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> export(Long userId) {
        User u = userRepo.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("formatVersion", 1);
        root.put("exportedAt", LocalDateTime.now());
        root.put("notice", "文件仅含元数据（不打包二进制）；AI 会话与通知按上限截取；不含密码与令牌。");
        root.put("account", account(u));
        root.put("articles", articles(userId));
        root.put("comments", comments(u.getUsername()));
        root.put("favorites", favorites(userId));
        root.put("likes", likes(userId));
        root.put("subscriptions", subscriptions(userId));
        root.put("files", files(userId));
        root.put("points", points(userId));
        root.put("exams", exams(userId));
        root.put("practice", practice(userId));
        root.put("studyPlans", studyPlans(userId));
        root.put("flashcards", flashcards(userId));
        root.put("annotations", annotations(userId));
        root.put("notifications", notifications(userId));
        root.put("aiConversations", aiConversations(userId));
        return root;
    }

    private Map<String, Object> account(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("displayName", u.getDisplayName());
        m.put("email", u.getEmail());
        m.put("bio", u.getBio());
        m.put("gender", u.getGender());
        m.put("role", u.getRole());
        m.put("points", u.getPoints());
        m.put("createdAt", u.getCreatedAt());
        m.put("departmentId", u.getDepartmentId());
        m.put("storageQuota", u.getStorageQuota());
        return m;
    }

    private List<Map<String, Object>> articles(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (Document d : documentRepo.findByOwnerIdOrderByUpdatedAtDesc(userId)) {
            if (n++ >= MAX_ARTICLES) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("title", d.getTitle());
            m.put("category", d.getCategory());
            m.put("tags", d.getTags());
            m.put("status", d.getStatus());
            m.put("views", d.getViews());
            m.put("likes", d.getLikes());
            m.put("createdAt", d.getCreatedAt());
            m.put("updatedAt", d.getUpdatedAt());
            m.put("content", d.getContent());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> comments(String username) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (username == null || username.isBlank()) return out;
        int n = 0;
        for (Comment c : commentRepo.findByUsernameOrderByCreatedAtDesc(username)) {
            if (n++ >= MAX_COMMENTS) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("targetType", c.getTargetType());
            m.put("docId", c.getDocId());
            m.put("parentId", c.getParentId());
            m.put("content", c.getContent());
            m.put("createdAt", c.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> favorites(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Favorite f : favoriteRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", f.getDocId());
            m.put("groupName", f.getGroupName());
            m.put("createdAt", f.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> likes(Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Long> posts = new ArrayList<>();
        for (PostLike l : likeRepo.findByUserId(userId)) posts.add(l.getDocId());
        List<Long> comments = new ArrayList<>();
        for (CommentLike l : commentLikeRepo.findByUserId(userId)) comments.add(l.getCommentId());
        List<Long> shared = new ArrayList<>();
        for (SharedDocLike l : sharedLikeRepo.findByUserId(userId)) shared.add(l.getDocId());
        m.put("postDocIds", posts);
        m.put("commentIds", comments);
        m.put("sharedDocIds", shared);
        return m;
    }

    private List<Map<String, Object>> subscriptions(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocSubscription s : subRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("docId", s.getDocId());
            m.put("createdAt", s.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> files(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UploadedFile f : fileRepo.findByOwnerIdOrderByCreatedAtDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", f.getName());
            m.put("size", f.getSize());
            m.put("contentType", f.getContentType());
            m.put("url", f.getUrl());
            m.put("createdAt", f.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> points(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PointsLog p : pointsRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("delta", p.getDelta());
            m.put("type", p.getType());
            m.put("reason", p.getReason());
            m.put("balance", p.getBalance());
            m.put("createdAt", p.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> exams(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExamRecord r : examRepo.findByUserIdOrderByStartedAtDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("paperId", r.getPaperId());
            m.put("score", r.getScore());
            m.put("passed", r.isPassed());
            m.put("startedAt", r.getStartedAt());
            m.put("submittedAt", r.getSubmittedAt());
            m.put("durationSec", r.getDurationSec());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> practice(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PracticeLog p : practiceRepo.findTop300ByUserIdOrderByIdDesc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("questionId", p.getQuestionId());
            m.put("correct", p.isCorrect());
            m.put("answer", p.getAnswer());
            m.put("createdAt", p.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> studyPlans(Long userId) {
        List<StudyPlan> plans = planRepo.findByUserIdOrderByUpdatedAtDesc(userId);
        List<Map<String, Object>> out = new ArrayList<>();
        if (plans.isEmpty()) return out;
        List<Long> ids = new ArrayList<>();
        for (StudyPlan p : plans) ids.add(p.getId());
        Map<Long, List<StudyPlanItem>> itemsByPlan = new LinkedHashMap<>();
        for (StudyPlanItem it : planItemRepo.findByPlanIdInOrderBySortOrderAscIdAsc(ids)) {
            itemsByPlan.computeIfAbsent(it.getPlanId(), k -> new ArrayList<>()).add(it);
        }
        for (StudyPlan p : plans) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", p.getTitle());
            m.put("note", p.getNote());
            m.put("remindAt", p.getRemindAt());
            m.put("createdAt", p.getCreatedAt());
            m.put("updatedAt", p.getUpdatedAt());
            List<Map<String, Object>> items = new ArrayList<>();
            for (StudyPlanItem it : itemsByPlan.getOrDefault(p.getId(), List.of())) {
                Map<String, Object> im = new LinkedHashMap<>();
                im.put("type", it.getType());
                im.put("refId", it.getRefId());
                im.put("refShareId", it.getRefShareId());
                im.put("title", it.getTitle());
                im.put("done", it.isDone());
                im.put("doneAt", it.getDoneAt());
                items.add(im);
            }
            m.put("items", items);
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> flashcards(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Flashcard f : flashRepo.findByUserIdOrderByDueAtAsc(userId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shareId", f.getShareId());
            m.put("docId", f.getDocId());
            m.put("front", f.getFront());
            m.put("back", f.getBack());
            m.put("source", f.getSource());
            m.put("dueAt", f.getDueAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> annotations(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocAnnotation a : annoRepo.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, MAX_ANNOTATIONS))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("shareId", a.getShareId());
            m.put("docId", a.getDocId());
            m.put("quote", a.getQuote());
            m.put("note", a.getNote());
            m.put("createdAt", a.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> notifications(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (Notification x : notifRepo.findByUserIdOrderByCreatedAtDesc(userId)) {
            if (n++ >= MAX_NOTIFICATIONS) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", x.getType());
            m.put("actor", x.getActor());
            m.put("summary", x.getSummary());
            m.put("read", x.isRead());
            m.put("createdAt", x.getCreatedAt());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> aiConversations(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        int n = 0;
        for (AiConversation c : convRepo.findByUserIdOrderByUpdatedAtDesc(userId)) {
            if (n++ >= MAX_CONVERSATIONS) break;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", c.getTitle());
            m.put("createdAt", c.getCreatedAt());
            m.put("updatedAt", c.getUpdatedAt());
            List<Map<String, Object>> msgs = new ArrayList<>();
            int k = 0;
            for (AiMessage msg : msgRepo.findByConversationIdOrderByCreatedAtAsc(c.getId())) {
                if (k++ >= MAX_MESSAGES_PER_CONV) break;
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("role", msg.getRole());
                mm.put("content", msg.getContent());
                mm.put("createdAt", msg.getCreatedAt());
                msgs.add(mm);
            }
            m.put("messages", msgs);
            out.add(m);
        }
        return out;
    }
}
