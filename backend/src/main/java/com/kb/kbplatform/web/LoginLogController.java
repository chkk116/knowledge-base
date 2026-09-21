package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.LoginLog;
import com.kb.kbplatform.repository.LoginLogRepository;
import com.kb.kbplatform.security.TokenClaims;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/login-logs")
public class LoginLogController {

    private final LoginLogRepository repo;

    public LoginLogController(LoginLogRepository repo) {
        this.repo = repo;
    }

    /** 登录日志列表（仅管理员）：按用户名（模糊）/ 结果 / 时间范围过滤 + 分页（倒序） */
    @GetMapping
    public Page<LoginLog> list(@RequestAttribute("kbUser") TokenClaims c,
                               @RequestParam(required = false) String username,
                               @RequestParam(required = false) Boolean success,
                               @RequestParam(required = false)
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                               @RequestParam(required = false)
                               @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
                               @RequestParam(required = false, defaultValue = "0") int page,
                               @RequestParam(required = false, defaultValue = "20") int size) {
        if (c == null || !c.isAdmin()) {   // 与 AuthFilter 门统一语义：真实 ADMIN 或权限覆盖全集（2026-09-21 统一）
            throw new org.springframework.web.server.ResponseStatusException(FORBIDDEN, "需要管理员权限");
        }
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 20;
        String uname = username != null && !username.isBlank() ? username.trim() : null;
        String pattern = uname != null ? "%" + uname + "%" : null;
        return repo.search(uname, pattern, success, start, end, PageRequest.of(page, size));
    }
}
