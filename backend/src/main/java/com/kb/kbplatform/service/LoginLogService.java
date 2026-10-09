/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.LoginLog;
import com.kb.kbplatform.repository.LoginLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

/**
 * 登录日志：记录每次登录尝试（成功/失败）。best-effort——任何异常都吞掉，绝不影响登录主流程。
 */
@Service
public class LoginLogService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LoginLogService.class);

    /** 登录日志保留期：隐私政策已声明 90 天（外部复审 2026-10-09） */
    private static final int RETENTION_DAYS = 90;

    private final LoginLogRepository repo;

    public LoginLogService(LoginLogRepository repo) {
        this.repo = repo;
    }

    /** 记录一次登录尝试 */
    public void record(Long userId, String username, boolean success, String reason, HttpServletRequest request) {
        try {
            LoginLog l = new LoginLog();
            l.setUserId(userId);
            l.setUsername(username == null ? "" : username);
            l.setSuccess(success);
            l.setReason(success ? null : (reason == null ? "" : reason));
            l.setIp(clientIp(request));
            String ua = request != null ? request.getHeader("User-Agent") : null;
            if (ua != null && ua.length() > 255) ua = ua.substring(0, 255);
            l.setUserAgent(ua);
            repo.save(l);
        } catch (Exception ignored) {
            // 日志失败不影响登录
        }
    }

    /** 客户端 IP：优先 X-Forwarded-For 首个地址（经 Nginx 反代时 RemoteAddr 是代理地址） */
    private String clientIp(HttpServletRequest request) {
        try {
            if (request == null) return null;
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank()) return ip.split(",")[0].trim();
            return request.getRemoteAddr();
        } catch (Exception e) {
            return null;
        }
    }

    /** 每日 03:30 清理超期登录日志（隐私政策声明保留 90 天，外部复审 2026-10-09） */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 30 3 * * ?")
    public void purgeExpired() {
        try {
            long n = repo.deleteByCreatedAtBefore(java.time.LocalDateTime.now().minusDays(RETENTION_DAYS));
            if (n > 0) log.info("[登录日志] 清理 {} 条超期记录（保留期 {} 天）", n, RETENTION_DAYS);
        } catch (Exception e) {
            log.warn("[登录日志] 清理失败", e);
        }
    }
}
