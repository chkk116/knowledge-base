package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.AuditLog;
import com.kb.kbplatform.repository.AuditLogRepository;
import com.kb.kbplatform.security.TokenClaims;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/audit")
public class AuditLogController {

    private final AuditLogRepository repo;

    public AuditLogController(AuditLogRepository repo) {
        this.repo = repo;
    }

    /** 审计日志列表（仅管理员）：支持按 action / 操作人 / 时间范围过滤 + 分页（倒序） */
    @GetMapping
    public Page<AuditLog> list(@RequestAttribute("kbUser") TokenClaims c,
                               @RequestParam(required = false) String action,
                               @RequestParam(required = false) String actor,
                               @RequestParam(required = false)
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                               @RequestParam(required = false)
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
                               @RequestParam(required = false, defaultValue = "0") int page,
                               @RequestParam(required = false, defaultValue = "20") int size) {
        if (!"ADMIN".equals(c.role())) {
            throw new org.springframework.web.server.ResponseStatusException(FORBIDDEN, "需要管理员权限");
        }
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        String actorPattern = (actor != null && !actor.isBlank()) ? "%" + actor.trim() + "%" : null;
        return repo.search(
                action != null && !action.isBlank() ? action : null,
                actorPattern,
                start, end,
                PageRequest.of(page, size));
    }
}
