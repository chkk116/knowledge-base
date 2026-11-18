/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.MailAccount;
import com.kb.kbplatform.domain.MailMessage;
import com.kb.kbplatform.repository.MailAccountRepository;
import com.kb.kbplatform.repository.MailMessageRepository;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 用绑定账户自己的 SMTP 发信，并在本地留一份「已发送」。
 *
 * 凭据与收信一致：授权码账户直接用授权码，OAuth 账户用刷新出来的 access token（XOAUTH2）。
 * 发信是不可撤销的外部动作，接口只接受显式调用（Agent 侧只允许生成草稿，发送必须人点）。
 */
@Service
public class MailSendService {

    private static final Logger log = LoggerFactory.getLogger(MailSendService.class);

    private final FileService fileService;
    private final MailAccountRepository accountRepo;
    private final MailMessageRepository msgRepo;
    private final MailAccountService accountService;

    public MailSendService(MailAccountRepository accountRepo, MailMessageRepository msgRepo,
                           FileService fileService,
                           @org.springframework.context.annotation.Lazy MailAccountService accountService) {
        this.fileService = fileService;
        this.accountRepo = accountRepo;
        this.msgRepo = msgRepo;
        this.accountService = accountService;
    }

    /**
     * 发送邮件。body 为纯文本；html 可选（为空时用纯文本包一层 pre）。
     * inReplyTo / references 用于让服务端把回复归到同一会话。
     */
    @Transactional
    public MailMessage send(Long userId, MailAccountService.SendRequest req) {
        MailAccount acc = accountRepo.findByIdAndUserId(req.accountId(), userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "邮箱不存在"));
        List<String> to = clean(req.to());
        if (to.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写收件人");
        List<String> cc = clean(req.cc());
        String subject = req.subject() == null ? "" : req.subject().trim();
        String text = req.body() == null ? "" : req.body();
        // 正文上限 1MB：再大多半是误传，也会让 SMTP 端直接拒收
        if (text.length() > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "正文过长（超过 1MB）");
        }
        String html = req.html() == null || req.html().isBlank() ? null : req.html();

        try {
            Session session = session(acc);
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(acc.getEmail(), acc.getEmail(), "UTF-8"));
            msg.setRecipients(Message.RecipientType.TO, addresses(to));
            if (!cc.isEmpty()) msg.setRecipients(Message.RecipientType.CC, addresses(cc));
            msg.setSubject(subject.isEmpty() ? "（无主题）" : subject, "UTF-8");
            msg.setSentDate(new java.util.Date());
            if (req.inReplyTo() != null && !req.inReplyTo().isBlank()) {
                msg.setHeader("In-Reply-To", req.inReplyTo().trim());
                String refs = req.references() == null || req.references().isBlank()
                        ? req.inReplyTo().trim() : req.references().trim() + " " + req.inReplyTo().trim();
                msg.setHeader("References", refs);
            }
            MimeMultipart content = new MimeMultipart("mixed");
            if (html != null) {
                MimeMultipart alt = new MimeMultipart("alternative");
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(text, "UTF-8");
                MimeBodyPart htmlPart = new MimeBodyPart();
                htmlPart.setContent(html, "text/html; charset=UTF-8");
                alt.addBodyPart(textPart);
                alt.addBodyPart(htmlPart);
                MimeBodyPart altPart = new MimeBodyPart();
                altPart.setContent(alt);
                content.addBodyPart(altPart);
            } else {
                MimeBodyPart textPart = new MimeBodyPart();
                textPart.setText(text, "UTF-8");
                content.addBodyPart(textPart);
            }
            // 附件从站内上传目录取，越界/不存在直接跳过（不把内部路径抛给客户端）
            java.util.List<java.util.Map<String, Object>> savedAttach = new java.util.ArrayList<>();
            if (req.attachments() != null) {
                int attached = 0;
                long totalBytes = 0;   // 多个大附件叠加会被服务商拒收，这里兜一层总大小
                for (java.util.Map<String, Object> a : req.attachments()) {
                    if (attached >= 10) break;
                    String url = a.get("url") == null ? null : String.valueOf(a.get("url"));
                    java.io.File f = fileService.fileByUrl(url);
                    if (f == null) continue;
                    totalBytes += f.length();
                    if (totalBytes > 25L * 1024 * 1024) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "附件总大小超过 25MB");
                    }
                    MimeBodyPart part = new MimeBodyPart();
                    part.setDataHandler(new jakarta.activation.DataHandler(new jakarta.activation.FileDataSource(f)));
                    String name = a.get("name") == null || String.valueOf(a.get("name")).isBlank()
                            ? f.getName() : String.valueOf(a.get("name"));
                    part.setFileName(jakarta.mail.internet.MimeUtility.encodeText(name, "UTF-8", null));
                    content.addBodyPart(part);
                    attached++;
                    java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
                    item.put("name", name);
                    item.put("size", f.length());
                    item.put("url", url);
                    savedAttach.add(item);
                }
            }
            msg.setContent(content);

            Transport t = session.getTransport("smtp");
            try {
                t.connect(acc.getSmtpHost(), acc.getSmtpPort() == null ? 465 : acc.getSmtpPort(),
                        acc.getEmail(), secretOf(acc));
                t.sendMessage(msg, msg.getAllRecipients());
            } finally {
                try { t.close(); } catch (Exception ignore) { }
            }
            return saveSent(acc, msg, to, cc, subject, text, html, savedAttach);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.warn("发信失败（{}）：{}", acc.getEmail(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "发送失败：" + shortMsg(e));
        }
    }

    /** 本地留档：已发送在客户端可见，uid 用负数时间戳避免与 IMAP UID 冲突 */
    private MailMessage saveSent(MailAccount acc, MimeMessage msg, List<String> to, List<String> cc,
                                 String subject, String text, String html,
                                 java.util.List<java.util.Map<String, Object>> attachments) {
        MailMessage m = new MailMessage();
        m.setAccountId(acc.getId());
        m.setUserId(acc.getUserId());
        m.setUid(-System.currentTimeMillis());
        m.setFolder("SENT");
        m.setSubject(subject.isEmpty() ? "（无主题）" : cut(subject, 480));
        m.setFromAddr(acc.getEmail());
        m.setFromName(acc.getEmail());
        m.setToAddrs(cut(String.join(", ", to), 480));
        m.setSentAt(LocalDateTime.now());
        m.setSnippet(cut(text.replaceAll("\\s+", " ").trim(), 200));
        m.setTextBody(text);
        m.setHtmlBody(html);
        m.setHasAttach(!attachments.isEmpty());
        if (!attachments.isEmpty()) {
            try {
                m.setAttachments(new tools.jackson.databind.ObjectMapper().writeValueAsString(attachments));
            } catch (Exception ignore) { }
        }
        m.setUnread(false);
        try {
            m.setMessageId(msg.getMessageID());
            String[] irt = msg.getHeader("In-Reply-To");
            m.setThreadKey(irt != null && irt.length > 0 ? cut(irt[0], 190) : cut(msg.getMessageID(), 190));
        } catch (Exception ignore) { }
        return msgRepo.save(m);
    }

    private Session session(MailAccount acc) {
        Properties p = new Properties();
        p.put("mail.smtp.host", acc.getSmtpHost());
        p.put("mail.smtp.port", String.valueOf(acc.getSmtpPort() == null ? 465 : acc.getSmtpPort()));
        p.put("mail.smtp.auth", "true");
        boolean ssl = acc.getSmtpSsl() == null || acc.getSmtpSsl();
        p.put("mail.smtp.ssl.enable", String.valueOf(ssl));
        if (!ssl) p.put("mail.smtp.starttls.enable", "true");
        p.put("mail.smtp.ssl.checkserveridentity", "true");
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "20000");
        p.put("mail.smtp.writetimeout", "20000");
        if ("OAUTH".equals(acc.getAuthType())) p.put("mail.smtp.auth.mechanisms", "XOAUTH2");
        return Session.getInstance(p);
    }

    private String secretOf(MailAccount acc) {
        return accountService.smtpSecret(acc);
    }

    private static InternetAddress[] addresses(List<String> list) throws Exception {
        InternetAddress[] arr = new InternetAddress[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = new InternetAddress(list.get(i).trim(), true);
        return arr;
    }

    /** 收件人可能是 "a@x.com, b@y.com" 或数组，统一拆开并去掉空项 */
    private static List<String> clean(List<String> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(s -> s != null && !s.isBlank())
                .flatMap(s -> Arrays.stream(s.split("[,;\\s]+")))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.contains("@"))
                .distinct()
                .toList();
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        return m.length() > 260 ? m.substring(0, 260) : m;
    }
}
