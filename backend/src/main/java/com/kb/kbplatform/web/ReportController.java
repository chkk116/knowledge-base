/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Report;
import com.kb.kbplatform.repository.ReportRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuditService;
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

    public ReportController(ReportRepository repo, UserRepository users, AuditService audit) {
        this.repo = repo;
        this.users = users;
        this.audit = audit;
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
        r.setStatus(st);
        r.setHandleNote(HtmlSanitizer.toText(String.valueOf(body.getOrDefault("note", ""))).trim());
        r.setHandlerId(c.userId());
        r.setHandledAt(LocalDateTime.now());
        repo.save(r);
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
