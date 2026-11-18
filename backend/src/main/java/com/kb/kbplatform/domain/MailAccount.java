/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户绑定的外部邮箱账户。
 *
 * 凭据（授权码 / OAuth refresh_token）用 AiKeyCipher 加密后存 credential_enc，
 * 接口永不回传明文，只有同步器在服务端解密使用。
 */
@Entity
@Table(name = "kb_mail_account", indexes = {
        @Index(name = "idx_mail_acc_user", columnList = "user_id"),
        @Index(name = "idx_mail_acc_email", columnList = "user_id,email", unique = true)
})
public class MailAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** QQ / NETEASE / EXMAIL / ALIYUN / GMAIL / OUTLOOK / CUSTOM */
    @Column(nullable = false, length = 24)
    private String provider;

    @Column(nullable = false, length = 160)
    private String email;

    /** CODE（授权码）/ OAUTH */
    @Column(name = "auth_type", nullable = false, length = 10)
    private String authType = "CODE";

    /** AES-GCM 加密后的授权码或 refresh_token */
    @Column(name = "credential_enc", columnDefinition = "text")
    private String credentialEnc;

    @Column(name = "imap_host", length = 120)
    private String imapHost;

    @Column(name = "imap_port")
    private Integer imapPort = 993;

    @Column(name = "imap_ssl")
    private Boolean imapSsl = true;

    @Column(name = "smtp_host", length = 120)
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort = 465;

    @Column(name = "smtp_ssl")
    private Boolean smtpSsl = true;

    /** OK / ERROR / DISABLED */
    @Column(nullable = false, length = 10)
    private String status = "OK";

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "last_sync_at")
    private LocalDateTime lastSyncAt;

    /** 已同步到的最大 UID（增量同步游标） */
    @Column(name = "last_uid")
    private Long lastUid = 0L;

    /** 首次同步回溯天数 */
    @Column(name = "sync_days")
    private Integer syncDays = 30;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }
    public String getCredentialEnc() { return credentialEnc; }
    public void setCredentialEnc(String credentialEnc) { this.credentialEnc = credentialEnc; }
    public String getImapHost() { return imapHost; }
    public void setImapHost(String imapHost) { this.imapHost = imapHost; }
    public Integer getImapPort() { return imapPort; }
    public void setImapPort(Integer imapPort) { this.imapPort = imapPort; }
    public Boolean getImapSsl() { return imapSsl; }
    public void setImapSsl(Boolean imapSsl) { this.imapSsl = imapSsl; }
    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }
    public Integer getSmtpPort() { return smtpPort; }
    public void setSmtpPort(Integer smtpPort) { this.smtpPort = smtpPort; }
    public Boolean getSmtpSsl() { return smtpSsl; }
    public void setSmtpSsl(Boolean smtpSsl) { this.smtpSsl = smtpSsl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public Long getLastUid() { return lastUid; }
    public void setLastUid(Long lastUid) { this.lastUid = lastUid; }
    public Integer getSyncDays() { return syncDays; }
    public void setSyncDays(Integer syncDays) { this.syncDays = syncDays; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
