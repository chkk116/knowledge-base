/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 登录日志：记录每次登录尝试（成功/失败），用于安全审计与异常登录排查 */
@Entity
@Table(name = "kb_login_log", indexes = {
        @Index(name = "idx_loginlog_created", columnList = "createdAt"),
        @Index(name = "idx_loginlog_username", columnList = "username")
})
public class LoginLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;             // 登录用户 id（用户名不存在时为 null）
    @Column(length = 64)
    private String username;         // 登录时输入的用户名（原样记录，便于排查爆破）
    @Column(length = 64)
    private String ip;               // 客户端 IP（X-Forwarded-For 首个地址）
    @Column(length = 255)
    private String userAgent;        // 浏览器 UA（截断 255）
    private boolean success;         // 是否登录成功
    @Column(length = 255)
    private String reason;           // 失败原因（成功时为 null）
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
