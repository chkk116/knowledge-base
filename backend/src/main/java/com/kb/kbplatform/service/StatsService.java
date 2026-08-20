package com.kb.kbplatform.service;

import com.kb.kbplatform.repository.CommentRepository;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.FavoriteRepository;
import com.kb.kbplatform.repository.LikeRepository;
import com.kb.kbplatform.repository.NotificationRepository;
import com.kb.kbplatform.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 后台数据看板：聚合各项计数（文档/用户/评论/点赞/收藏/通知 + 浏览量 + 今日新增）。
 * 全部走 JPA 聚合查询（COUNT/SUM），单条 SQL、不加载全量行到内存。
 */
@Service
public class StatsService {

    private final DocumentRepository docRepo;
    private final UserRepository userRepo;
    private final CommentRepository commentRepo;
    private final LikeRepository likeRepo;
    private final FavoriteRepository favRepo;
    private final NotificationRepository notiRepo;

    public StatsService(DocumentRepository docRepo, UserRepository userRepo,
                        CommentRepository commentRepo, LikeRepository likeRepo,
                        FavoriteRepository favRepo, NotificationRepository notiRepo) {
        this.docRepo = docRepo;
        this.userRepo = userRepo;
        this.commentRepo = commentRepo;
        this.likeRepo = likeRepo;
        this.favRepo = favRepo;
        this.notiRepo = notiRepo;
    }

    public Map<String, Object> getStats() {
        LocalDateTime today = LocalDate.now().atStartOfDay();
        Map<String, Object> m = new LinkedHashMap<>();
        // 文档
        m.put("docTotal", docRepo.count());
        m.put("docPublished", docRepo.countByStatus("PUBLISHED"));
        m.put("docPending", docRepo.countByStatus("PENDING"));
        m.put("docDraft", docRepo.countByStatus("DRAFT"));
        m.put("docBlocked", docRepo.countByStatus("BLOCKED"));
        m.put("docToday", docRepo.countByCreatedAtAfter(today));
        m.put("viewsTotal", docRepo.sumViews());
        // 用户
        m.put("userTotal", userRepo.count());
        m.put("userAdmin", userRepo.countByRole("ADMIN"));
        m.put("userBlocked", userRepo.countByEnabledFalse());
        m.put("userToday", userRepo.countByCreatedAtAfter(today));
        // 互动
        m.put("commentTotal", commentRepo.count());
        m.put("likeTotal", likeRepo.count());
        m.put("favTotal", favRepo.count());
        m.put("notiTotal", notiRepo.count());
        return m;
    }
}
