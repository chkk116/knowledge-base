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
import java.util.List;
import java.util.Set;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/audit")
public class AuditLogController {

    /** 域分组（与前端 AuditLog.vue 的集合保持一致）：用户类动作 */
    private static final Set<String> USER_ACTIONS = Set.of(
            "CREATE_USER", "DELETE_USER", "UPDATE_USER", "TOGGLE_USER", "RESET_PWD", "ADJUST_POINTS", "UPDATE_USER_QUOTA");

    /** 域分组：共享空间类动作 */
    private static final Set<String> SHARE_ACTIONS = Set.of(
            "CREATE_SHARE_SPACE", "UPDATE_SHARE_SPACE", "DELETE_SHARE_SPACE", "UPDATE_SHARE_CAPACITY",
            "UPDATE_SHARE_MEMBER", "CREATE_SHARE_DOC", "UPDATE_SHARE_DOC", "DELETE_SHARE_DOC");

    /** 域过滤用哑元集合：未命中分支时也要绑定非空集合，配合 flag 短路（规避 PG 空集合 IN ()） */
    private static final List<String> DUMMY = List.of("__none__");

    private final AuditLogRepository repo;

    public AuditLogController(AuditLogRepository repo) {
        this.repo = repo;
    }

    /** 审计日志列表（仅管理员）：支持按 action / 域 / 操作人 / 时间范围过滤 + 分页（倒序） */
    @GetMapping
    public Page<AuditLog> list(@RequestAttribute("kbUser") TokenClaims c,
                               @RequestParam(required = false) String action,
                               @RequestParam(required = false) String domain,
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

        // 域过滤翻译：user=仅用户类；share=仅共享类；doc=文档类（非用户且非共享）；其他/空=不过滤
        boolean domainInAll = true, domainNotInAll = true;
        List<String> domainIn = DUMMY, domainNotIn = DUMMY;
        if ("user".equals(domain)) {
            domainInAll = false;
            domainIn = List.copyOf(USER_ACTIONS);
        } else if ("share".equals(domain)) {
            domainInAll = false;
            domainIn = List.copyOf(SHARE_ACTIONS);
        } else if ("doc".equals(domain)) {
            domainNotInAll = false;
            List<String> both = new java.util.ArrayList<>(USER_ACTIONS);
            both.addAll(SHARE_ACTIONS);
            domainNotIn = List.copyOf(both);
        }

        return repo.search(
                action != null && !action.isBlank() ? action : null,
                actorPattern,
                start, end,
                domainInAll, domainIn,
                domainNotInAll, domainNotIn,
                PageRequest.of(page, size));
    }
}
