package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Notification;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.NotificationRepository;
import com.kb.kbplatform.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class NotificationService {

    private static final Pattern MENTION = Pattern.compile("@([\\w一-龥]+)");

    private final NotificationRepository repo;
    private final UserRepository userRepo;
    private final SseSessionRegistry sse;

    public NotificationService(NotificationRepository repo, UserRepository userRepo, SseSessionRegistry sse) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.sse = sse;
    }

    /** 保存通知后立即通过 SSE 向接收者推送最新未读数（实时刷新顶栏红点） */
    private void saveAndPush(Notification n) {
        this.repo.save(n);
        sse.pushUnread(n.getUserId(), unreadCount(n.getUserId()));
    }

    /** 触发者展示名：管理员显示「管理员+名称」，普通用户显示昵称/用户名 */
    private String actorName(String username) {
        if (username == null || username.isBlank()) return "某人";
        return userRepo.findByUsername(username)
                .map(u -> {
                    String name = (u.getDisplayName() != null && !u.getDisplayName().isBlank())
                            ? u.getDisplayName() : u.getUsername();
                    return "ADMIN".equals(u.getRole()) ? "管理员" + name : name;
                })
                .orElse(username);
    }

    /** 标题截断（避免通知正文过长） */
    private String clipTitle(String t) {
        if (t == null) return "";
        t = t.trim();
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
    }

    /** 解析评论中的 @用户名：仅对系统中已存在、且非评论者本人的用户生成通知 */
    public void notifyMention(Long docId, String actor, Long commentId, String rawContent) {
        if (rawContent == null) return;
        Set<String> mentions = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(rawContent);
        while (m.find()) mentions.add(m.group(1));
        if (mentions.isEmpty()) return;
        String summary = rawContent.length() > 50 ? rawContent.substring(0, 50) + "…" : rawContent;
        String actorDisplay = actorName(actor);
        for (String name : mentions) {
            if (name.equals(actor)) continue;
            userRepo.findByUsername(name).ifPresent(u -> {
                Notification n = new Notification();
                n.setUserId(u.getId());
                n.setType("COMMENT_MENTION");
                n.setActor(actorDisplay);
                n.setDocId(docId);
                n.setCommentId(commentId);
                n.setSummary(summary);
                saveAndPush(n);
            });
        }
    }

    /** 文章被评论：通知文章作者（评论自己文章不重复通知） */
    public void notifyComment(Long docId, Long docOwnerId, Long actorUserId, String actorUsername,
                              Long commentId, String docTitle, String preview) {
        if (docOwnerId == null || Objects.equals(docOwnerId, actorUserId)) return;
        String p = (preview == null) ? "" : (preview.length() > 40 ? preview.substring(0, 40) + "…" : preview);
        Notification n = new Notification();
        n.setUserId(docOwnerId);
        n.setType("COMMENT");
        n.setActor(actorName(actorUsername));
        n.setDocId(docId);
        n.setCommentId(commentId);
        n.setSummary("《" + clipTitle(docTitle) + "》 " + p);
        saveAndPush(n);
    }

    /** 评论被回复（楼中楼）：通知父评论作者 */
    public void notifyReply(Long docId, Long parentUserId, String actorUsername, Long commentId, String docTitle, String preview) {
        if (parentUserId == null) return;
        String p = (preview == null) ? "" : (preview.length() > 40 ? preview.substring(0, 40) + "…" : preview);
        Notification n = new Notification();
        n.setUserId(parentUserId);
        n.setType("COMMENT_REPLY");
        n.setActor(actorName(actorUsername));
        n.setDocId(docId);
        n.setCommentId(commentId);
        n.setSummary("《" + clipTitle(docTitle) + "》 " + p);
        saveAndPush(n);
    }

    /** 文章被点赞：通知文章作者（给自己点赞不通知） */
    public void notifyLike(Long docId, Long docOwnerId, Long actorUserId, String actorUsername, String docTitle) {
        if (docOwnerId == null || Objects.equals(docOwnerId, actorUserId)) return;
        Notification n = new Notification();
        n.setUserId(docOwnerId);
        n.setType("LIKE");
        n.setActor(actorName(actorUsername));
        n.setDocId(docId);
        n.setSummary("《" + clipTitle(docTitle) + "》");
        saveAndPush(n);
    }

    /** 审核结果通知作者：通过 / 驳回（驳回可带理由） */
    public void notifyReview(Long docId, String docTitle, Long authorId, boolean approved, String reason) {
        if (authorId == null) return;
        String t = clipTitle(docTitle);
        Notification n = new Notification();
        n.setUserId(authorId);
        n.setType("REVIEW");
        n.setDocId(docId);
        n.setSummary(approved
                ? "您的文章《" + t + "》已通过审核并公开"
                : "您的文章《" + t + "》未通过审核" + (reason != null && !reason.isBlank() ? "：" + reason : ""));
        saveAndPush(n);
    }

    /** 文章提交审核：通知全体管理员（提交者本人若是管理员则不重复通知自己） */
    public void notifyReviewSubmit(Long docId, String docTitle, String actorUsername, Long actorUserId) {
        String t = clipTitle(docTitle);
        String actor = actorName(actorUsername);
        for (User admin : userRepo.findByRole("ADMIN")) {
            if (actorUserId != null && Objects.equals(admin.getId(), actorUserId)) continue;
            Notification n = new Notification();
            n.setUserId(admin.getId());
            n.setType("REVIEW_SUBMIT");
            n.setActor(actor);
            n.setDocId(docId);
            n.setSummary("《" + t + "》已提交审核，请前往处理");
            saveAndPush(n);
        }
    }

    public List<Notification> list(Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public long unreadCount(Long userId) {
        return repo.countByUserIdAndReadFalse(userId);
    }

    public void markRead(Long id, Long userId) {
        Notification n = repo.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "通知不存在"));
        if (!Objects.equals(n.getUserId(), userId)) {
            throw new ResponseStatusException(FORBIDDEN, "无权操作该通知");
        }
        n.setRead(true);
        saveAndPush(n);
    }

    public void markAllRead(Long userId) {
        List<Notification> all = repo.findByUserIdOrderByCreatedAtDesc(userId);
        for (Notification n : all) n.setRead(true);
        repo.saveAll(all);
        sse.pushUnread(userId, unreadCount(userId));   // 广播未读数归零，刷新顶栏红点 / 多标签页同步
    }

    /** 删除单条（校验归属，越权抛 403） */
    public void deleteOne(Long id, Long userId) {
        Notification n = repo.findById(id).orElse(null);
        if (n == null) return;
        if (!Objects.equals(n.getUserId(), userId)) {
            throw new ResponseStatusException(FORBIDDEN, "无权操作该通知");
        }
        repo.delete(n);
        sse.pushUnread(userId, unreadCount(userId));   // 若删的是未读，红点同步递减
    }

    /** 批量删除：仅删除 id 列表中真正属于该用户的通知（其余忽略，不报错） */
    public void deleteMany(List<Long> ids, Long userId) {
        if (ids == null || ids.isEmpty()) return;
        repo.deleteByUserIdAndIdIn(userId, ids);
        sse.pushUnread(userId, unreadCount(userId));
    }

    /** 清空本人全部通知 */
    public void deleteAll(Long userId) {
        repo.deleteByUserId(userId);
        sse.pushUnread(userId, 0);   // 全部清空，未读数归零
    }
}
