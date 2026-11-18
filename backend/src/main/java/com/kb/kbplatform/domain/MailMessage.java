/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 已同步的外部邮件（收件箱为主）。
 *
 * attachments 存 JSON 数组：[{"name":"a.pdf","size":12345,"url":"/uploads/..."}]，
 * 只落盘图片附件（复用 FileService.storeImageBytes），其它附件保留文件名与大小。
 */
@Entity
@Table(name = "kb_mail_message", indexes = {
        @Index(name = "idx_mail_msg_acc_uid", columnList = "account_id,folder,uid", unique = true),
        @Index(name = "idx_mail_msg_user_time", columnList = "user_id,sent_at"),
        @Index(name = "idx_mail_msg_thread", columnList = "thread_key")
})
public class MailMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** IMAP UID（账户内唯一） */
    @Column(nullable = false)
    private Long uid;

    @Column(nullable = false, length = 20)
    private String folder = "INBOX";

    /** In-Reply-To / References 归并出的会话键 */
    @Column(name = "thread_key", length = 200)
    private String threadKey;

    /** RFC Message-ID，回复时用于 In-Reply-To */
    @Column(name = "message_id", length = 300)
    private String messageId;

    @Column(length = 500)
    private String subject;

    @Column(name = "from_addr", length = 200)
    private String fromAddr;

    @Column(name = "from_name", length = 100)
    private String fromName;

    @Column(name = "to_addrs", length = 500)
    private String toAddrs;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    /** 列表页摘要（纯文本前 200 字） */
    @Column(length = 240)
    private String snippet;

    @Column(name = "text_body", columnDefinition = "text")
    private String textBody;

    @Column(name = "html_body", columnDefinition = "text")
    private String htmlBody;

    @Column(name = "has_attach")
    private Boolean hasAttach = false;

    @Column(name = "attachments", columnDefinition = "text")
    private String attachments;

    @Column(name = "is_unread")
    private Boolean unread = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getUid() { return uid; }
    public void setUid(Long uid) { this.uid = uid; }
    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }
    public String getThreadKey() { return threadKey; }
    public void setThreadKey(String threadKey) { this.threadKey = threadKey; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getFromAddr() { return fromAddr; }
    public void setFromAddr(String fromAddr) { this.fromAddr = fromAddr; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getToAddrs() { return toAddrs; }
    public void setToAddrs(String toAddrs) { this.toAddrs = toAddrs; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
    public String getTextBody() { return textBody; }
    public void setTextBody(String textBody) { this.textBody = textBody; }
    public String getHtmlBody() { return htmlBody; }
    public void setHtmlBody(String htmlBody) { this.htmlBody = htmlBody; }
    public Boolean getHasAttach() { return hasAttach; }
    public void setHasAttach(Boolean hasAttach) { this.hasAttach = hasAttach; }
    public String getAttachments() { return attachments; }
    public void setAttachments(String attachments) { this.attachments = attachments; }
    public Boolean getUnread() { return unread; }
    public void setUnread(Boolean unread) { this.unread = unread; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
