package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AiConversation;
import com.kb.kbplatform.domain.UploadedFile;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.*;
import com.kb.kbplatform.security.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 自助注销的账号删除服务（2026-10-08 第二轮外部审查后重构）。
 *
 * <p>语义（软删除 + 个人数据硬删）：</p>
 * <ul>
 *   <li><b>锁与事务</b>：ReentrantLock 包住 TransactionTemplate——此前 synchronized 加在
 *       {@code @Transactional} 方法上，锁在事务提交前就释放，并发保护不成立（外部复审指出）。</li>
 *   <li><b>唯一管理员判定</b>：只统计<b>启用中</b>的管理员（countByRoleAndEnabled）——
 *       软删除会把 role 置回 USER，但历史已注销/已停用账号不应再计入；
 *       AdminBootstrap、StatsService 同口径修改。</li>
 *   <li><b>账号软删除</b>：role 置回 USER、权限清空（否则注销后仍被管理员计数命中）、
 *       username 释放、displayName=「已注销用户」、PII（邮箱/简介/性别/头像）清空、
 *       随机密码 + enabled=false + tokenVersion+1（旧令牌立即失效）。</li>
 *   <li><b>个人数据硬删</b>：AI 会话与消息、通知、收藏、积分日志、考试记录、学习进度、课程笔记、
 *       角色关联、帖子/评论点赞、文档订阅、上传文件登记与物理文件。</li>
 *   <li><b>PII 快照清理</b>：评论匿名化（userId=null、username=「已注销用户」——username 快照
 *       会被评论接口公开显示，必须一并清理）；共享空间授权表按 userId 删除授权条目
 *       （DocSharePermission/SharedDocPermission 的 username 快照随条目消失）。</li>
 *   <li><b>物理文件引用检查</b>：与 FileController.deleteMine 同规则——正文被引用的文件只删登记，
 *       避免已发布文章裂图（此前注销路径缺这道检查，外部复审指出）。</li>
 *   <li><b>密码二次确认</b>：不可逆删号仅凭 24h 令牌不够。</li>
 * </ul>
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);
    public static final String DELETED_DISPLAY_NAME = "已注销用户";

    private final UserRepository userRepo;
    private final UserRoleRepository userRoleRepo;
    private final AiConversationRepository aiConvRepo;
    private final AiMessageRepository aiMsgRepo;
    private final NotificationRepository notificationRepo;
    private final FavoriteRepository favoriteRepo;
    private final PointsLogRepository pointsLogRepo;
    private final ExamRecordRepository examRecordRepo;
    private final LearningProgressRepository learningProgressRepo;
    private final NoteRepository noteRepo;
    private final LikeRepository likeRepo;
    private final CommentLikeRepository commentLikeRepo;
    private final DocSubscriptionRepository docSubscriptionRepo;
    private final UploadedFileRepository uploadedFileRepo;
    private final DocumentRepository docRepo;
    private final CommentRepository commentRepo;
    private final DocSharePermissionRepository docSharePermissionRepo;
    private final SharedDocPermissionRepository sharedDocPermissionRepo;
    private final FileService fileService;
    private final TransactionTemplate tx;

    private final ReentrantLock deleteLock = new ReentrantLock();

    public AccountDeletionService(UserRepository userRepo,
                                  UserRoleRepository userRoleRepo,
                                  AiConversationRepository aiConvRepo,
                                  AiMessageRepository aiMsgRepo,
                                  NotificationRepository notificationRepo,
                                  FavoriteRepository favoriteRepo,
                                  PointsLogRepository pointsLogRepo,
                                  ExamRecordRepository examRecordRepo,
                                  LearningProgressRepository learningProgressRepo,
                                  NoteRepository noteRepo,
                                  LikeRepository likeRepo,
                                  CommentLikeRepository commentLikeRepo,
                                  DocSubscriptionRepository docSubscriptionRepo,
                                  UploadedFileRepository uploadedFileRepo,
                                  DocumentRepository docRepo,
                                  CommentRepository commentRepo,
                                  DocSharePermissionRepository docSharePermissionRepo,
                                  SharedDocPermissionRepository sharedDocPermissionRepo,
                                  FileService fileService,
                                  TransactionTemplate tx) {
        this.userRepo = userRepo;
        this.userRoleRepo = userRoleRepo;
        this.aiConvRepo = aiConvRepo;
        this.aiMsgRepo = aiMsgRepo;
        this.notificationRepo = notificationRepo;
        this.favoriteRepo = favoriteRepo;
        this.pointsLogRepo = pointsLogRepo;
        this.examRecordRepo = examRecordRepo;
        this.learningProgressRepo = learningProgressRepo;
        this.noteRepo = noteRepo;
        this.likeRepo = likeRepo;
        this.commentLikeRepo = commentLikeRepo;
        this.docSubscriptionRepo = docSubscriptionRepo;
        this.uploadedFileRepo = uploadedFileRepo;
        this.docRepo = docRepo;
        this.docSharePermissionRepo = docSharePermissionRepo;
        this.sharedDocPermissionRepo = sharedDocPermissionRepo;
        this.commentRepo = commentRepo;
        this.fileService = fileService;
        this.tx = tx;
    }

    /** 锁包住整个事务（锁在提交后才释放）：synchronized 加在 @Transactional 方法上时锁提前释放，并发保护不成立 */
    public void deleteAccount(User u, String password) {
        deleteLock.lock();
        try {
            tx.executeWithoutResult(status -> doDelete(u, password));
        } finally {
            deleteLock.unlock();
        }
    }

    /**
     * 管理端删除用户：走与自助注销完全相同的软删除 + 个人数据清理链路（外部复审 2026-10-09：
     * 此前 AdminService 直接 deleteById，角色/权限残留会让唯一管理员计数虚高）。
     * 无需被删者密码（管理员操作），演示账号保护仍然生效。
     */
    public void deleteByAdmin(User u) {
        deleteLock.lock();
        try {
            tx.executeWithoutResult(status -> doDelete(u, null, true));
        } finally {
            deleteLock.unlock();
        }
    }

    private void doDelete(User u, String password) {
        doDelete(u, password, false);
    }

    private void doDelete(User u, String password, boolean byAdmin) {
        if (!byAdmin) {
            // 密码二次确认：不可逆删号仅凭 24h 令牌不够（管理员路径跳过）
            if (password == null || password.isBlank() || !PasswordUtil.matches(password, u.getPassword())) {
                throw new IllegalArgumentException("密码校验失败，无法注销");
            }
        }
        Long uid = u.getId();

        // 演示账号：公共账号，注销掉别人就进不来了。
        // ⚠️ 判定必须用 permissions 里的 DEMO_MODE 标记，**不能看 role**——
        // 演示号的 role 实际是 USER（id=6），role 判断永远不会命中。
        // AuthFilter 已按「演示只读」拦过一次写操作，这里再显式拦一道，
        // 保证任何调用路径（含将来的内部调用）都拦得住，并给出明确原因而非笼统 403。
        if (com.kb.kbplatform.security.Perm.isDemo(u.getPermissions())) {
            throw new IllegalArgumentException("演示账号为公共账号，不支持注销");
        }

        // 唯一管理员：只统计「启用中」的管理员（已注销/已停用不再计入，外部复审 2026-10-08）
        if ("ADMIN".equals(u.getRole()) && userRepo.countByRoleAndEnabled("ADMIN", true) <= 1) {
            throw new IllegalArgumentException("当前是唯一的管理员账号，无法注销；请先创建其他管理员后再操作");
        }

        // ---- 个人数据硬删 ----
        for (AiConversation c : aiConvRepo.findByUserIdOrderByUpdatedAtDesc(uid)) {
            aiMsgRepo.deleteByConversationId(c.getId());
            aiConvRepo.delete(c);
        }
        notificationRepo.deleteByUserId(uid);
        favoriteRepo.deleteByUserId(uid);
        pointsLogRepo.deleteByUserId(uid);
        examRecordRepo.deleteByUserId(uid);
        learningProgressRepo.deleteByUserId(uid);
        noteRepo.deleteByUserId(uid);
        likeRepo.deleteByUserId(uid);              // 帖子点赞
        commentLikeRepo.deleteByUserId(uid);       // 评论点赞
        docSubscriptionRepo.deleteByUserId(uid);   // 文档订阅
        userRoleRepo.deleteByUserId(uid);          // 角色关联

        // 上传文件：与 deleteMine 同规则——正文被引用的只删登记（防已发布文章裂图），否则物理+登记都删。
        // 定向 LIKE 查询（countContentLike）替代 findAllContents 全量载入内存：数据量大时注销会撑爆堆（2026-10-13 审查 C4）；
        // URL 含 LIKE 通配符时只会「多判为被引用」，与 fail-closed 同向。
        // 引用检查失败按「被引用」处理（fail-closed：宁可残留文件也不制造裂图，外部复审 2026-10-09）
        List<UploadedFile> files = uploadedFileRepo.findByOwnerIdOrderByCreatedAtDesc(uid);
        for (UploadedFile f : files) {
            boolean referenced = false;
            try {
                referenced = f.getUrl() != null && docRepo.countContentLike("%" + f.getUrl() + "%") > 0;
            } catch (Exception e) {
                log.warn("[注销] 引用检查失败，按被引用处理（fail-closed）：{}", f.getUrl());
                referenced = true;
            }
            if (!referenced) {
                try {
                    fileService.deleteByUrl(f.getUrl());
                } catch (Exception e) {
                    log.warn("[注销] 物理文件删除失败：{}", f.getUrl());
                }
            } else {
                log.info("[注销] 文件仍被文档引用，保留物理文件：{}", f.getUrl());
            }
            uploadedFileRepo.delete(f);
        }

        // ---- PII 快照清理 ----
        // 评论：userId=null + username 一并匿名（username 快照会被评论接口公开显示）
        commentRepo.anonymizeByUserId(uid);
        // 共享空间授权：账号已注销，授权条目删除（username 快照随条目消失）
        docSharePermissionRepoAnonymize(uid);
        sharedDocPermissionRepoAnonymize(uid);

        // ---- 账号软删除：role 重置 + PII 清除 + 不可登录 + 令牌全失效 ----
        u.setRole("USER");                          // 注销后不再计入管理员（配套 countByRoleAndEnabled）
        u.setPermissions("");                       // 细粒度权限一并清空
        u.setUsername("deleted_" + uid + "_" + System.currentTimeMillis());   // 释放原用户名
        u.setDisplayName(DELETED_DISPLAY_NAME);
        u.setPassword(PasswordUtil.encode(UUID.randomUUID().toString()));     // 随机新密码，旧密码失效
        u.setEnabled(false);
        u.setEmail(null);
        u.setBio(null);
        u.setGender(null);
        u.setAvatar(null);
        u.setAvatarPending(null);
        u.setPoints(0);
        u.setDepartmentId(null);
        u.setTokenVersion(u.getTokenVersion() + 1);
        userRepo.save(u);

        log.info("[注销] 账号 {}（id={}）已完成注销：个人数据已删除，公共内容匿名显示为「已注销用户」", u.getUsername(), uid);
    }

    /** 共享空间授权按 userId 删除（DocSharePermission） */
    private void docSharePermissionRepoAnonymize(Long uid) {
        try {
            docSharePermissionRepo.deleteByUserId(uid);
        } catch (Exception e) {
            log.warn("[注销] 共享空间授权清理失败（继续注销流程）", e);
        }
    }

    /** 共享文档授权按 userId 删除（SharedDocPermission） */
    private void sharedDocPermissionRepoAnonymize(Long uid) {
        try {
            sharedDocPermissionRepo.deleteByUserId(uid);
        } catch (Exception e) {
            log.warn("[注销] 共享文档授权清理失败（继续注销流程）", e);
        }
    }
}
