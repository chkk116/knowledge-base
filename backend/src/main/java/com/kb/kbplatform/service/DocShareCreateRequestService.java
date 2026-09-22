/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DocShare;
import com.kb.kbplatform.domain.DocShareCreateRequest;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocShareCreateRequestRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** 团队空间创建申请：已拥有达到上限（默认 2 个）团队空间的用户提交，系统完全管理员（ADMIN 角色）审批/驳回；
 *  审批通过后由服务自动创建空间，申请人成为该空间所有者。 */
@Service
public class DocShareCreateRequestService {

    private final DocShareCreateRequestRepository repo;
    private final DocShareService docShareService;
    private final UserRepository userRepo;
    private final AuditService audit;
    private final NotificationService notificationService;

    public DocShareCreateRequestService(DocShareCreateRequestRepository repo,
                                         DocShareService docShareService,
                                         UserRepository userRepo, AuditService audit,
                                         NotificationService notificationService) {
        this.repo = repo;
        this.docShareService = docShareService;
        this.userRepo = userRepo;
        this.audit = audit;
        this.notificationService = notificationService;
    }

    /** 有 SHARE_CREATE 权限但已达团队空间上限的用户提交创建申请 */
    @Transactional
    public DocShareCreateRequest submit(TokenClaims c, Map<String, Object> body) {
        Long uid = resolveUserId(c);
        String name = str(body.get("requestedName"));
        if (name == null || name.isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写拟创建的空间名称");
        // 解析附带成员（逗号分隔的 id 字符串，或 List）
        List<Long> members = parseMembers(body.get("requestedMembers"));
        DocShareCreateRequest r = new DocShareCreateRequest();
        r.setApplicantId(uid);
        r.setApplicantName(c.username());
        r.setRequestedName(name.trim());
        r.setRequestedDescription(str(body.get("requestedDescription")));
        r.setRequestedColor(str(body.get("requestedColor")));
        r.setRequestedMembers(members == null || members.isEmpty() ? null : joinIds(members));
        r.setRequestedMemberPermission(normalizePerm(str(body.get("requestedMemberPermission"))));
        r.setReason(body.get("reason") == null ? "" : String.valueOf(body.get("reason")).trim());
        r.setStatus("PENDING");
        r.setCreatedAt(LocalDateTime.now());
        DocShareCreateRequest saved = repo.save(r);
        audit.log(c.userId(), c.username(), "APPLY_SHARE_CREATE", "SHARE_SPACE", null, name.trim(),
                "申请创建团队空间「" + name.trim() + "」" + (r.getReason() != null && !r.getReason().isBlank() ? "，理由：" + r.getReason() : ""));
        notificationService.notifyShareRequestSubmit(uid, c.username(), "SHARE_CREATE_PENDING",
                "「" + c.username() + "」申请创建团队空间「" + name.trim() + "」，待你审批");
        return saved;
    }

    /** 申请人查看自己的创建申请（倒序） */
    public List<DocShareCreateRequest> listMine(TokenClaims c) {
        return repo.findByApplicantIdOrderByCreatedAtDesc(resolveUserId(c));
    }

    /** 系统完全管理员查看全部待审批创建申请 */
    public List<DocShareCreateRequest> listPending(TokenClaims c) {
        requireAdmin(c);
        return repo.findByStatusOrderByCreatedAtDesc("PENDING");
    }

    /** 审批通过：自动创建团队空间（申请人成为所有者），容量沿用默认 2GB，并附带入成员 */
    @Transactional
    public DocShareCreateRequest approve(Long requestId, TokenClaims c) {
        requireAdmin(c);
        DocShareCreateRequest r = repo.findByIdLocked(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "申请不存在"));
        if (!"PENDING".equals(r.getStatus()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该申请已处理，不可重复审批");
        // 解析成员
        List<Long> members = null;
        if (r.getRequestedMembers() != null && !r.getRequestedMembers().isBlank()) {
            members = new ArrayList<>();
            for (String s : r.getRequestedMembers().split(",")) {
                try { members.add(Long.valueOf(s.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        DocShare created = docShareService.create(
                r.getRequestedName(), r.getRequestedDescription(), r.getApplicantId(), "TEAM",
                DocShareService.DEFAULT_SPACE_CAPACITY_BYTES, r.getRequestedColor(),
                members, r.getRequestedMemberPermission());
        r.setStatus("APPROVED");
        r.setReviewerId(c.userId());
        r.setReviewerName(c.username());
        r.setReviewedAt(LocalDateTime.now());
        DocShareCreateRequest saved = repo.save(r);
        saved.setRequestedName(created.getName());
        audit.log(c.userId(), c.username(), "APPROVE_SHARE_CREATE", "SHARE_SPACE", created.getId(), created.getName(),
                "批准「" + r.getApplicantName() + "」创建团队空间「" + created.getName() + "」");
        notificationService.notifyShareApproval(r.getApplicantId(), "SHARE_CREATE_APPROVED", c.username(),
                "您申请创建的团队空间「" + created.getName() + "」已通过审批并创建成功，可前往共享文档查看");
        return saved;
    }

    /** 驳回 */
    @Transactional
    public DocShareCreateRequest reject(Long requestId, TokenClaims c, String comment) {
        requireAdmin(c);
        DocShareCreateRequest r = repo.findByIdLocked(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "申请不存在"));
        if (!"PENDING".equals(r.getStatus()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该申请已处理，不可重复操作");
        r.setStatus("REJECTED");
        r.setReviewerId(c.userId());
        r.setReviewerName(c.username());
        r.setReviewComment(comment == null ? "" : comment.trim());
        r.setReviewedAt(LocalDateTime.now());
        DocShareCreateRequest saved = repo.save(r);
        audit.log(c.userId(), c.username(), "REJECT_SHARE_CREATE", "SHARE_SPACE", null, r.getRequestedName(),
                "驳回「" + r.getApplicantName() + "」创建团队空间「" + r.getRequestedName() + "」的申请"
                        + (comment != null && !comment.isBlank() ? "，理由：" + comment : ""));
        notificationService.notifyShareApproval(r.getApplicantId(), "SHARE_CREATE_REJECTED", c.username(),
                "您申请创建的团队空间「" + r.getRequestedName() + "」未通过审批"
                        + (comment != null && !comment.isBlank() ? "，理由：" + comment : ""));
        return saved;
    }

    // ---------- 帮助 ----------

    private List<Long> parseMembers(Object raw) {
        if (raw == null) return null;
        List<Long> out = new ArrayList<>();
        if (raw instanceof List<?> ls) {
            for (Object o : ls) {
                try { if (o != null) out.add(Long.valueOf(String.valueOf(o))); } catch (NumberFormatException ignored) {}
            }
        } else if (raw instanceof String s && !s.isBlank()) {
            for (String p : s.split(",")) {
                try { out.add(Long.valueOf(p.trim())); } catch (NumberFormatException ignored) {}
            }
        }
        return out.isEmpty() ? null : new ArrayList<>(new LinkedHashSet<>(out));
    }

    private String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private String normalizePerm(String p) {
        if (p == null) return "WRITE";
        String t = p.trim().toUpperCase();
        return switch (t) {
            case "READ" -> "READ";
            case "ADMIN" -> "ADMIN";
            default -> "WRITE";
        };
    }

    private Long resolveUserId(TokenClaims c) {
        if (c == null || c.username() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return userRepo.findByUsername(c.username()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录"));
    }

    /** 刻意仅真实 ADMIN（非 isAdmin() 全集语义）：团队空间创建审批属资源管理决策，见类头注释；
     *  不随 AuthFilter 的 isAdmin 门放宽（2026-09-21 ocr 评审后确认为刻意例外）。 */
    private void requireAdmin(TokenClaims c) {
        if (c == null || !"ADMIN".equals(c.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要系统管理员（完全管理员）权限");
    }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
}
