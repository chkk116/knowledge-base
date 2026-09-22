/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DocShare;
import com.kb.kbplatform.domain.DocShareCapacityRequest;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocShareCapacityRequestRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

/** 共享空间扩容申请：空间 OWNER/ADMIN 提交，系统完全管理员（ADMIN 角色）审批/驳回 */
@Service
public class DocShareCapacityRequestService {

    private final DocShareCapacityRequestRepository repo;
    private final DocShareService docShareService;
    private final UserRepository userRepo;
    private final AuditService audit;
    private final NotificationService notificationService;

    public DocShareCapacityRequestService(DocShareCapacityRequestRepository repo,
                                          DocShareService docShareService,
                                          UserRepository userRepo, AuditService audit,
                                          NotificationService notificationService) {
        this.repo = repo;
        this.docShareService = docShareService;
        this.userRepo = userRepo;
        this.audit = audit;
        this.notificationService = notificationService;
    }

    /** 空间 OWNER/ADMIN 提交扩容申请 */
    @Transactional
    public DocShareCapacityRequest submit(Long shareId, TokenClaims c, Long requestedCapacity, String reason) {
        Long uid = resolveUserId(c);
        DocShare s = docShareService.get(shareId, uid, isSysAdmin(c));   // 校验可读
        if (!docShareService.canAdminSpace(shareId, uid, isSysAdmin(c)))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间管理员权限才能申请扩容");
        if (requestedCapacity == null || requestedCapacity < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择目标容量");
        DocShareCapacityRequest r = new DocShareCapacityRequest();
        r.setShareId(shareId);
        r.setApplicantId(uid);
        r.setApplicantName(c.username());
        r.setRequestedCapacity(requestedCapacity == 0L ? 0L : requestedCapacity);
        r.setCurrentCapacity(s.getCapacity());
        r.setReason(reason == null ? "" : reason.trim());
        r.setStatus("PENDING");
        r.setCreatedAt(LocalDateTime.now());
        DocShareCapacityRequest saved = repo.save(r);
        r.setShareName(s.getName());
        audit.log(c.userId(), c.username(), "APPLY_SHARE_CAPACITY", "SHARE_SPACE", shareId, s.getName(),
                "申请将空间「" + s.getName() + "」容量调整为 " + capText(requestedCapacity) + (reason != null && !reason.isBlank() ? "，理由：" + reason : ""));
        notificationService.notifyShareRequestSubmit(uid, c.username(), "SHARE_CAPACITY_PENDING",
                "「" + c.username() + "」申请将团队空间「" + s.getName() + "」扩容至 " + capText(requestedCapacity) + "，待你审批");
        return saved;
    }

    /** 空间成员查看该空间下的全部申请（按时间倒序） */
    public List<DocShareCapacityRequest> listByShare(Long shareId, TokenClaims c) {
        docShareService.get(shareId, resolveUserId(c), isSysAdmin(c)); // 校验可读
        List<DocShareCapacityRequest> list = repo.findByShareIdOrderByCreatedAtDesc(shareId);
        fillShareNames(list);
        return list;
    }

    /** 系统完全管理员查看全部待审批申请 */
    public List<DocShareCapacityRequest> listPending(TokenClaims c) {
        requireAdmin(c);
        List<DocShareCapacityRequest> list = repo.findByStatusOrderByCreatedAtDesc("PENDING");
        fillShareNames(list);
        return list;
    }

    /** 审批通过：自动将空间容量上限调整为申请值（复用 DocShareService.update） */
    @Transactional
    public DocShareCapacityRequest approve(Long requestId, TokenClaims c) {
        requireAdmin(c);
        DocShareCapacityRequest r = repo.findByIdLocked(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "申请不存在"));
        if (!"PENDING".equals(r.getStatus()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该申请已处理，不可重复审批");
        // 应用容量：sysAdmin=true 走 DocShareService.update 的权威路径
        docShareService.update(r.getShareId(), null, null, null, r.getRequestedCapacity(), null, true);
        String shareName = safeShareName(r.getShareId());
        r.setStatus("APPROVED");
        r.setReviewerId(c.userId());
        r.setReviewerName(c.username());
        r.setReviewedAt(LocalDateTime.now());
        DocShareCapacityRequest saved = repo.save(r);
        saved.setShareName(shareName);
        audit.log(c.userId(), c.username(), "APPROVE_SHARE_CAPACITY", "SHARE_SPACE", r.getShareId(), shareName,
                "批准空间「" + shareName + "」扩容至 " + capText(r.getRequestedCapacity()));
        notificationService.notifyShareApproval(r.getApplicantId(), "SHARE_CAPACITY_APPROVED", c.username(),
                "您的团队空间「" + shareName + "」扩容申请已通过，容量调整为 " + capText(r.getRequestedCapacity()));
        return saved;
    }

    /** 驳回 */
    @Transactional
    public DocShareCapacityRequest reject(Long requestId, TokenClaims c, String comment) {
        requireAdmin(c);
        DocShareCapacityRequest r = repo.findByIdLocked(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "申请不存在"));
        if (!"PENDING".equals(r.getStatus()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该申请已处理，不可重复操作");
        String shareName = safeShareName(r.getShareId());
        r.setStatus("REJECTED");
        r.setReviewerId(c.userId());
        r.setReviewerName(c.username());
        r.setReviewComment(comment == null ? "" : comment.trim());
        r.setReviewedAt(LocalDateTime.now());
        DocShareCapacityRequest saved = repo.save(r);
        saved.setShareName(shareName);
        audit.log(c.userId(), c.username(), "REJECT_SHARE_CAPACITY", "SHARE_SPACE", r.getShareId(), shareName,
                "驳回空间「" + shareName + "」扩容申请" + (comment != null && !comment.isBlank() ? "，理由：" + comment : ""));
        notificationService.notifyShareApproval(r.getApplicantId(), "SHARE_CAPACITY_REJECTED", c.username(),
                "您的团队空间「" + shareName + "」扩容申请被驳回"
                        + (comment != null && !comment.isBlank() ? "，理由：" + comment : ""));
        return saved;
    }

    // ---------- 帮助 ----------

    private void fillShareNames(List<DocShareCapacityRequest> list) {
        for (DocShareCapacityRequest r : list) r.setShareName(safeShareName(r.getShareId()));
    }

    private String safeShareName(Long shareId) {
        try {
            DocShare s = docShareService.get(shareId, null, true);
            return s.getName();
        } catch (Exception e) {
            return "空间#" + shareId;
        }
    }

    private String capText(Long cap) {
        if (cap == null || cap == 0L) return "不限";
        return DocShareService.formatSize(cap);
    }

    private Long resolveUserId(TokenClaims c) {
        if (c == null || c.username() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return userRepo.findByUsername(c.username()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
    }

    /** 刻意仅真实 ADMIN（非 isAdmin() 全集语义）：扩容审批属资源管理决策，注释与类头「系统完全管理员（ADMIN 角色）审批」一致，
     *  不随 AuthFilter 的 isAdmin 门放宽——全权限非 ADMIN 账号不参与共享空间资源审批（2026-09-21 ocr 评审后确认为刻意例外）。 */
    private boolean isSysAdmin(TokenClaims c) { return c != null && "ADMIN".equals(c.role()); }

    private void requireAdmin(TokenClaims c) {
        if (c == null || !"ADMIN".equals(c.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要系统管理员（完全管理员）权限");
    }
}
