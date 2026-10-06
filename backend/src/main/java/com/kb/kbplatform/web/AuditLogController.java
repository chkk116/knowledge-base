/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.AuditLog;
import com.kb.kbplatform.security.Perm;
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
        // 只读列表：ADMIN 或 AUDIT_VIEW（操作审计专用只读码，严格判定，不回退 ADMIN_VIEW）
        if (!c.isAdmin() && !c.hasPerm(Perm.AUDIT_VIEW)) {
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
