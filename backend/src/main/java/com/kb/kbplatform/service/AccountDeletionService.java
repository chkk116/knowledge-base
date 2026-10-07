package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AiConversation;
import com.kb.kbplatform.domain.UploadedFile;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.*;
import com.kb.kbplatform.security.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 自助注销的账号删除服务（2026-10-07 外部审查：原实现仅删 user 行，关联数据全部残留，
 * 与 DataSafety 表 / 隐私页声明不符——Play 数据安全表审查的实质性上架风险）。
 *
 * 语义（软删除 + 个人数据硬删）：
 * <ul>
 *   <li>账号行保留：username 释放为 deleted_{id}_{ts}、displayName=「已注销用户」、
 *       password 置随机不可登录、enabled=false、email/简介/性别/头像等 PII 清空、
 *       tokenVersion+1（所有旧令牌立即失效）。公共内容（文章/评论）的作者展示自动变为「已注销用户」。</li>
 *   <li>个人数据硬删：AI 会话与消息、通知、收藏、积分日志、考试记录、学习进度、课程笔记、
 *       角色关联、上传文件登记与物理文件。</li>
 *   <li>密码二次确认：仅凭 24h 令牌不足以完成不可逆删号。</li>
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
    private final UploadedFileRepository uploadedFileRepo;
    private final FileService fileService;

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
                                  UploadedFileRepository uploadedFileRepo,
                                  FileService fileService) {
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
        this.uploadedFileRepo = uploadedFileRepo;
        this.fileService = fileService;
    }

    /** synchronized：单体部署下防「两个管理员并发注销都通过唯一性检查」与注销过程串行化 */
    @Transactional(rollbackFor = Exception.class)
    public synchronized void deleteAccount(User u, String password) {
        // 密码二次确认：不可逆删号仅凭 24h 令牌不够（外部审查 2026-10-07）
        if (password == null || password.isBlank() || !PasswordUtil.matches(password, u.getPassword())) {
            throw new IllegalArgumentException("密码校验失败，无法注销");
        }
        Long uid = u.getId();

        // 唯一管理员：锁内重查（防并发注销双双通过先查后删的检查）
        if ("ADMIN".equals(u.getRole()) && userRepo.countByRole("ADMIN") <= 1) {
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
        userRoleRepo.deleteByUserId(uid);
        for (UploadedFile f : uploadedFileRepo.findByOwnerIdOrderByCreatedAtDesc(uid)) {
            try {
                fileService.deleteByUrl(f.getUrl());   // 物理文件（引用检查针对他人文档引用，本人注销优先清干净）
            } catch (Exception e) {
                log.warn("[注销] 物理文件删除失败（登记仍会清除）：{}", f.getUrl());
            }
            uploadedFileRepo.delete(f);
        }

        // ---- 账号软删除：PII 清除 + 不可登录 + 令牌全失效 ----
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
}
