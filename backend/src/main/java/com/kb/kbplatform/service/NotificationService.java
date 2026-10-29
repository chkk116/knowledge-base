/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Notification;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocSubscriptionRepository;
import com.kb.kbplatform.repository.NotificationRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Pattern MENTION = Pattern.compile("@([\\w一-龥]+)");

    private final NotificationRepository repo;
    private final UserRepository userRepo;
    private final SseSessionRegistry sse;
    private final DocSubscriptionRepository subRepo;
    private final WebhookNotifier webhook;

    public NotificationService(NotificationRepository repo, UserRepository userRepo, SseSessionRegistry sse,
                               DocSubscriptionRepository subRepo, WebhookNotifier webhook) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.sse = sse;
        this.subRepo = subRepo;
        this.webhook = webhook;
    }

    /** 保存通知后立即通过 SSE 向接收者推送最新未读数（实时刷新顶栏红点）；
     *  同时异步外发到企业微信/钉钉（未配置或失败都不影响站内通知）。 */
    private void saveAndPush(Notification n) {
        this.repo.save(n);
        sse.pushUnread(n.getUserId(), unreadCount(n.getUserId()));
        try {
            webhook.pushAsync(n.getType(), "知识库通知 · " + (n.getType() == null ? "" : n.getType()),
                    (n.getActor() == null ? "" : n.getActor() + "：") + (n.getSummary() == null ? "" : n.getSummary()));
        } catch (Exception ignore) { /* 外发通道异常绝不影响站内通知 */ }
    }

    /**
     * 高危安全告警：管理员账号经非浏览器客户端（脚本/自动化工具，如 python-urllib、curl、java 等）
     * 成功登录 → 通知全体管理员。判定放在 AuthController（UA 无 Mozilla 前缀 + ADMIN 角色），
     * 这里只负责把告警推给每一位 ADMIN（含当事人自己，便于其确认是否为本人操作）。
     */
    public void notifySecurityAlert(String username, String ip, String userAgent) {
        String ua = userAgent == null ? "" : userAgent;
        if (ua.length() > 120) ua = ua.substring(0, 120);
        String summary = "管理员账号「" + username + "」通过非浏览器客户端成功登录（疑似脚本/自动化工具）。"
                + "IP: " + (ip == null || ip.isBlank() ? "未知" : ip) + "，UA: " + ua
                + "。若非本人操作，请立即修改密码并检查登录日志。";
        for (User admin : userRepo.findByRole("ADMIN")) {
            Notification n = new Notification();
            n.setUserId(admin.getId());
            n.setType("SECURITY_ALERT");
            n.setActor(username);
            n.setSummary(summary);
            saveAndPush(n);
        }
    }

    /** 通用单用户系统通知（举报处理结果等） */
    public void notifyUser(Long userId, String type, String summary) {
        notifyUser(userId, type, summary, null);
    }

    /** 带关联文档：通知中心点击可跳原文 */
    public void notifyUser(Long userId, String type, String summary, Long docId) {
        if (userId == null) return;
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setActor("系统");
        n.setSummary(summary);
        n.setDocId(docId);
        saveAndPush(n);
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
        String summary = HtmlSanitizer.preview(rawContent, 50);
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

    /**
     * 共享文档正文 @提及（点选成员即通知）。
     * 与 COMMENT_MENTION 分开用 DOC_MENTION：前端据此跳到「空间文档」而不是文章页，
     * 并带上 shareId 才能拼出 /doc-shares/{shareId}?doc={docId}。
     */
    public void notifyDocMention(Long shareId, Long docId, String docTitle, Long actorUserId,
                                 String actorUsername, Long targetUserId) {
        if (targetUserId == null || Objects.equals(targetUserId, actorUserId)) return;
        Notification n = new Notification();
        n.setUserId(targetUserId);
        n.setType("DOC_MENTION");
        n.setActor(actorName(actorUsername));
        n.setDocId(docId);
        n.setShareId(shareId);
        n.setSummary(docTitle);
        saveAndPush(n);
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

    /** 共享空间相关审批结果通知申请人（容量审批 / 创建审批的通过或驳回）。
     *  type 取值：SHARE_CAPACITY_APPROVED / SHARE_CAPACITY_REJECTED / SHARE_CREATE_APPROVED / SHARE_CREATE_REJECTED */
    public void notifyShareApproval(Long userId, String type, String actorUsername, String summary) {
        if (userId == null) return;
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setActor(actorName(actorUsername));
        n.setSummary(summary);
        saveAndPush(n);
    }

    /** 共享空间审批提交：通知全体管理员（提交者本人若是管理员则不重复通知自己）。
     *  type 取值：SHARE_CREATE_PENDING / SHARE_CAPACITY_PENDING */
    public void notifyShareRequestSubmit(Long applicantId, String actorUsername, String type, String summary) {
        if (summary == null || summary.isBlank()) return;
        String actor = actorName(actorUsername);
        for (User admin : userRepo.findByRole("ADMIN")) {
            if (applicantId != null && Objects.equals(admin.getId(), applicantId)) continue;
            Notification n = new Notification();
            n.setUserId(admin.getId());
            n.setType(type);
            n.setActor(actor);
            n.setSummary(summary);
            saveAndPush(n);
        }
    }

    /**
     * 文档更新：通知全部订阅者（更新者本人不通知自己）。由 DocumentService 在文档更新后调用。
     *
     * 外部审查 2026-10-13（M6）：原实现逐订阅者 save + 逐用户 count 未读，热门文档上千订阅者时
     * 会把文档保存请求阻塞数秒。现改为：
     *  - 通知行一次 saveAll 批量写入；
     *  - 未读数用一条分组查询算完所有接收者，再逐连接推送；
     *  - 入口 notifyDocUpdateAsync 走 @Async 线程池，彻底移出请求线程。
     */
    public void notifyDocUpdate(Long docId, String docTitle, Long editorId, String editorUsername) {
        if (docId == null) return;
        List<Long> userIds;
        try {
            userIds = subRepo.findUserIdsByDocId(docId);
        } catch (Exception e) {
            return;   // 订阅查询失败不影响文档更新主流程
        }
        if (userIds == null || userIds.isEmpty()) return;
        List<Long> targets = userIds.stream()
                .filter(uid -> uid != null && !Objects.equals(uid, editorId))
                .distinct().toList();
        if (targets.isEmpty()) return;
        String t = clipTitle(docTitle);
        String actor = actorName(editorUsername);
        List<Notification> batch = new ArrayList<>(targets.size());
        for (Long uid : targets) {
            Notification n = new Notification();
            n.setUserId(uid);
            n.setType("DOC_UPDATE");
            n.setActor(actor);
            n.setDocId(docId);
            n.setSummary("你订阅的《" + t + "》有更新");
            batch.add(n);
        }
        repo.saveAll(batch);
        // 一条分组查询取全部接收者未读数，再逐连接推送（推送是纯内存 SSE 发送，开销可忽略）
        Map<Long, Long> counts = new HashMap<>();
        try {
            for (Object[] row : repo.countUnreadByUserIds(targets)) {
                counts.put((Long) row[0], (Long) row[1]);
            }
        } catch (Exception e) {
            log.warn("订阅推送未读数批量查询失败 doc={}: {}", docId, e.getMessage());
        }
        for (Long uid : targets) {
            sse.pushUnread(uid, counts.getOrDefault(uid, 0L));
        }
    }

    /** 订阅推送的异步入口：移到线程池执行，文档保存请求不再等待上千订阅者的通知写入（M6）。
     *  线程池见 AsyncConfig；队列满时 CallerRunsPolicy 回退请求线程执行，保证不丢通知。 */
    @org.springframework.scheduling.annotation.Async
    public void notifyDocUpdateAsync(Long docId, String docTitle, Long editorId, String editorUsername) {
        notifyDocUpdate(docId, docTitle, editorId, editorUsername);
    }

    /** 结业证书颁发通知学员 */
    public void notifyCertificate(Long userId, String actorUsername, String summary) {
        if (userId == null) return;
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType("CERTIFICATE");
        n.setActor(actorName(actorUsername));
        n.setSummary(summary);
        saveAndPush(n);
    }

    /** 头像审核流通知（无 docId，纯文本）：提交审核 / 审核通过 / 审核驳回。
     *  type 取值：AVATAR_PENDING / AVATAR_APPROVED / AVATAR_REJECTED，前端通知页进「审核」Tab、点击跳个人资料页。 */
    public void notifyAvatar(Long userId, String type, String summary) {
        if (userId == null || type == null || summary == null || summary.isBlank()) return;
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setActor("系统");
        n.setSummary(summary);
        saveAndPush(n);
    }

    /** 头像提审：通知全体管理员（提交者本人若是管理员则不通知自己），与文章提审同一范式 */
    public void notifyAvatarReviewSubmit(Long submitterId, String submitterUsername) {
        String actor = actorName(submitterUsername);
        for (User admin : userRepo.findByRole("ADMIN")) {
            if (submitterId != null && Objects.equals(admin.getId(), submitterId)) continue;
            Notification n = new Notification();
            n.setUserId(admin.getId());
            n.setType("AVATAR_REVIEW_SUBMIT");
            n.setActor(actor);
            n.setSummary("提交了新头像，待你审核");
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

    /** 全部标记已读：批量写入需事务，否则中途失败会留下「一半已读一半未读」 */
    @Transactional(rollbackFor = Exception.class)
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

    /** 批量删除：仅删除 id 列表中真正属于该用户的通知（其余忽略，不报错）。
     *  deleteByUserIdAndIdIn 是 JPQL DELETE 修改查询，必须有事务包裹。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteMany(List<Long> ids, Long userId) {
        if (ids == null || ids.isEmpty()) return;
        repo.deleteByUserIdAndIdIn(userId, ids);
        sse.pushUnread(userId, unreadCount(userId));
    }

    /** 清空本人全部通知（deleteByUserId 为修改查询，需事务） */
    @Transactional(rollbackFor = Exception.class)
    public void deleteAll(Long userId) {
        repo.deleteByUserId(userId);
        sse.pushUnread(userId, 0);   // 全部清空，未读数归零
    }
}
