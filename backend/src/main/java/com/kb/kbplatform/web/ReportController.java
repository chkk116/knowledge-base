/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Report;
import com.kb.kbplatform.repository.ReportRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.repository.CommentRepository;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.SharedDocumentRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuditService;
import com.kb.kbplatform.service.NotificationService;
import com.kb.kbplatform.domain.Comment;
import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.SharedDocument;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * 举报 / 反馈。
 *
 * POST /api/reports            提交（targetType=FEEDBACK 表示纯反馈，不需要 targetId）
 * GET  /api/reports/mine       我提交过的
 * GET  /api/admin/reports      管理端列表（status 过滤）
 * PUT  /api/admin/reports/{id} 处理（HANDLED / REJECTED + 备注）
 */
@RestController
public class ReportController {

    private static final Set<String> TYPES = Set.of("POST", "COMMENT", "DOC", "DM", "USER", "FEEDBACK");
    private static final Set<String> STATUSES = Set.of("PENDING", "HANDLED", "REJECTED");

    private final ReportRepository repo;
    private final UserRepository users;
    private final AuditService audit;
    private final com.kb.kbplatform.repository.DmMessageRepository dmRepo;
    private final DocumentRepository docRepo;
    private final CommentRepository commentRepo;
    private final SharedDocumentRepository sharedDocRepo;
    private final NotificationService notify;

    public ReportController(ReportRepository repo, UserRepository users, AuditService audit,
                            DocumentRepository docRepo, CommentRepository commentRepo,
                            SharedDocumentRepository sharedDocRepo, NotificationService notify,
                            com.kb.kbplatform.repository.DmMessageRepository dmRepo) {
        this.repo = repo;
        this.users = users;
        this.audit = audit;
        this.docRepo = docRepo;
        this.commentRepo = commentRepo;
        this.sharedDocRepo = sharedDocRepo;
        this.notify = notify;
        this.dmRepo = dmRepo;
    }

    /** 被举报对象摘要：标题/作者/作者 id，供管理页直接定位与处理 */
    private Map<String, Object> resolveTarget(Report r) {
        Map<String, Object> m = new HashMap<>();
        String type = r.getTargetType();
        Long tid = r.getTargetId();
        if ("POST".equals(type) && tid != null) {
            var d = docRepo.findById(tid).orElse(null);
            if (d != null) { m.put("targetTitle", d.getTitle()); m.put("authorId", d.getOwnerId());
                users.findById(d.getOwnerId()).ifPresent(u -> m.put("authorName", u.getUsername()));
                m.put("targetStatus", d.getStatus()); m.put("link", "/post/" + tid); }
        } else if ("COMMENT".equals(type) && tid != null) {
            var c = commentRepo.findById(tid).orElse(null);
            if (c != null) { m.put("targetTitle", c.getContent()); m.put("authorId", c.getUserId());
                m.put("authorName", c.getUsername()); m.put("link", c.getDocId() == null ? "" : "/post/" + c.getDocId()); }
        } else if ("DM".equals(type) && tid != null) {
            var dm = dmRepo.findById(tid).orElse(null);
            if (dm != null) { m.put("targetTitle", dm.getContent()); m.put("authorId", dm.getFromId());
                m.put("authorName", users.findById(dm.getFromId()).map(u -> u.getUsername()).orElse("")); }
        } else if ("DOC".equals(type) && tid != null) {
            var d = sharedDocRepo.findById(tid).orElse(null);
            if (d != null) { m.put("targetTitle", d.getTitle()); m.put("authorId", d.getOwnerId()); }
        } else if ("USER".equals(type) && tid != null) {
            var u = users.findById(tid).orElse(null);
            if (u != null) { m.put("targetTitle", u.getUsername()); m.put("authorId", u.getId());
                m.put("authorName", u.getUsername()); m.put("targetStatus", u.isEnabled() ? "正常" : "已禁用"); }
        }
        return m;
    }

    private Long uid(TokenClaims c) {
        if (c == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return c.userId();
    }

    @PostMapping("/api/reports")
    public Map<String, Object> submit(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        Long me = uid(c);
        String type = String.valueOf(body.getOrDefault("targetType", "FEEDBACK")).toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的举报类型");
        Long targetId = body.get("targetId") instanceof Number n ? n.longValue() : null;
        if (!"FEEDBACK".equals(type) && targetId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少被举报对象");
        }
        String reason = HtmlSanitizer.toText(String.valueOf(body.getOrDefault("reason", ""))).trim();
        if (reason.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择原因");
        if (reason.length() > 120) reason = reason.substring(0, 120);
        String detail = HtmlSanitizer.toText(String.valueOf(body.getOrDefault("detail", ""))).trim();
        if (detail.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "说明太长（上限 2000 字）");

        // 同一人对同一对象 10 分钟内只允许提交一次，避免刷屏
        for (Report r : repo.findByReporterIdOrderByIdDesc(me, PageRequest.of(0, 20))) {
            if (!r.getTargetType().equals(type)) continue;
            if (Objects.equals(r.getTargetId(), targetId)
                    && r.getCreatedAt() != null
                    && r.getCreatedAt().isAfter(LocalDateTime.now().minusMinutes(10))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "你已经提交过，请等待处理");
            }
        }

        Report r = new Report();
        r.setTargetType(type);
        r.setTargetId(targetId);
        r.setReason(reason);
        r.setDetail(detail);
        r.setReporterId(me);
        r.setStatus("PENDING");
        r.setCreatedAt(LocalDateTime.now());
        repo.save(r);
        audit.log(me, String.valueOf(c.username()), "SUBMIT_REPORT", r.getTargetType(), r.getTargetId(),
                reason, detail.length() > 120 ? detail.substring(0, 120) : detail);
        return Map.of("id", r.getId(), "ok", true);
    }

    @GetMapping("/api/reports/mine")
    public List<Map<String, Object>> mine(@RequestAttribute("kbUser") TokenClaims c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Report r : repo.findByReporterIdOrderByIdDesc(uid(c), PageRequest.of(0, 50))) {
            out.add(view(r, false));
        }
        return out;
    }

    @GetMapping("/api/admin/reports")
    public Map<String, Object> list(@RequestAttribute("kbUser") TokenClaims c,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        if (!c.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        int sz = Math.min(Math.max(size, 1), 100);
        PageRequest pr = PageRequest.of(Math.max(page, 0), sz);
        List<Report> rows;
        if (status != null && STATUSES.contains(status.toUpperCase(Locale.ROOT))) {
            rows = repo.findByStatusOrderByIdDesc(status.toUpperCase(Locale.ROOT), pr);
        } else {
            rows = repo.findAllByOrderByIdDesc(pr);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Report r : rows) out.add(view(r, true));
        return Map.of("items", out, "pending", repo.countByStatus("PENDING"));
    }

    @PutMapping("/api/admin/reports/{id}")
    public Map<String, Object> handle(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long id, @RequestBody Map<String, Object> body) {
        if (!c.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        Report r = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在"));
        String st = String.valueOf(body.getOrDefault("status", "HANDLED")).toUpperCase(Locale.ROOT);
        if (!"HANDLED".equals(st) && !"REJECTED".equals(st)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态不合法");
        String action = String.valueOf(body.getOrDefault("action", "NONE")).toUpperCase(Locale.ROOT);
        String note = HtmlSanitizer.toText(String.valueOf(body.getOrDefault("note", ""))).trim();

        // ---- 处置动作 ----
        String resultDesc = "已记录，未采取处置";
        Map<String, Object> target = resolveTarget(r);
        Long authorId = target.get("authorId") instanceof Number n ? n.longValue() : null;
        String type = r.getTargetType();

        if ("DELETE_CONTENT".equals(action)) {
            if (("POST".equals(type) || "DOC".equals(type)) && r.getTargetId() != null) {
                docRepo.findById(r.getTargetId()).ifPresent(d -> {
                    d.setStatus("BLOCKED");
                    docRepo.save(d);
                });
                resultDesc = "已下架被举报的内容";
            } else if ("COMMENT".equals(type) && r.getTargetId() != null) {
                commentRepo.findById(r.getTargetId()).ifPresent(cm -> {
                    cm.setContent("[该评论因违规已被管理员删除]");
                    commentRepo.save(cm);
                });
                resultDesc = "已删除被举报的评论";
            } else if ("DM".equals(type) && r.getTargetId() != null) {
                dmRepo.findById(r.getTargetId()).ifPresent(dm -> {
                    dm.setKind("RECALL");
                    dm.setContent("");
                    dmRepo.save(dm);
                });
                resultDesc = "已撤回被举报的私信";
            }
        } else if ("BLOCK_AUTHOR".equals(action)) {
            if (authorId != null && !authorId.equals(c.userId())) {
                users.findById(authorId).ifPresent(u -> {
                    if (!"ADMIN".equals(u.getRole())) {
                        u.setEnabled(false);
                        users.save(u);
                    }
                });
                resultDesc = "已禁用相关账号";
            } else {
                resultDesc = "该对象无可禁用的账号";
            }
        }

        r.setStatus(st);
        String handleNote = ("DELETE_CONTENT".equals(action) ? "[删除内容] " : "BLOCK_AUTHOR".equals(action) ? "[禁用账号] " : "")
                + note;
        r.setHandleNote(handleNote);
        r.setHandlerId(c.userId());
        r.setHandledAt(LocalDateTime.now());
        repo.save(r);

        // ---- 结果通知 ----
        String label = switch (type) {
            case "POST" -> "文章"; case "COMMENT" -> "评论"; case "DOC" -> "文档";
            case "DM" -> "私信"; case "USER" -> "用户"; default -> "反馈";
        };
        String title = target.get("targetTitle") instanceof String t && !t.isBlank() ? "：" + (t.length() > 20 ? t.substring(0, 20) + "…" : t) : "";
        try {
            if ("REJECTED".equals(st)) {
                notify.notifyUser(r.getReporterId(), "REPORT_RESULT",
                        "你提交的举报（" + label + title + "）经核实不构成违规，已驳回。" + (note.isBlank() ? "" : "说明：" + note));
            } else {
                notify.notifyUser(r.getReporterId(), "REPORT_RESULT",
                        "你提交的举报（" + label + title + "）已处理：" + resultDesc + (note.isBlank() ? "" : "，备注：" + note));
                if ("WARN_AUTHOR".equals(action) && authorId != null) {
                    notify.notifyUser(authorId, "REPORT_WARN",
                            "你发布的内容（" + label + title + "）被举报且经核实，请注意遵守社区规范。");
                } else if ("DELETE_CONTENT".equals(action) && authorId != null && !authorId.equals(r.getReporterId())) {
                    notify.notifyUser(authorId, "REPORT_WARN",
                            "你发布的" + label + title + "因违规已被管理员" + ("DM".equals(type) ? "撤回" : "删除/下架") + "。");
                } else if ("BLOCK_AUTHOR".equals(action) && authorId != null) {
                    notify.notifyUser(authorId, "REPORT_WARN", "你的账号因违规已被管理员禁用。");
                }
            }
        } catch (Exception ignore) {
        }

        audit.log(c.userId(), String.valueOf(c.username()), "HANDLE_REPORT", type, r.getTargetId(),
                String.valueOf(target.getOrDefault("targetTitle", "")), st + " / " + action + " / " + note);
        return Map.of("ok", true);
    }

    private Map<String, Object> view(Report r, boolean admin) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("targetType", r.getTargetType());
        m.put("targetId", r.getTargetId());
        m.put("reason", r.getReason());
        m.put("detail", r.getDetail());
        m.put("status", r.getStatus());
        m.put("createdAt", r.getCreatedAt());
        if (admin) {
            m.putAll(resolveTarget(r));
            m.put("reporterId", r.getReporterId());
            users.findById(r.getReporterId()).ifPresent(u ->
                    m.put("reporterName", (u.getDisplayName() == null || u.getDisplayName().isBlank())
                            ? u.getUsername() : u.getDisplayName()));
            m.put("handleNote", r.getHandleNote());
            m.put("handledAt", r.getHandledAt());
        }
        return m;
    }
}
