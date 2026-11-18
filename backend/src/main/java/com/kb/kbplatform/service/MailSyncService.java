/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.MailAccount;
import com.kb.kbplatform.domain.MailMessage;
import com.kb.kbplatform.repository.MailAccountRepository;
import com.kb.kbplatform.repository.MailMessageRepository;
import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.FetchProfile;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

/**
 * 邮件增量同步。
 *
 * 关键点：取正文必须用 **BODY.PEEK**（FetchProfile 里加 "BODY.PEEK[]"），
 * 否则 JavaMail 的 getContent() 会发 FETCH BODY[]，IMAP 服务端顺手把邮件标成「已读」——
 * 用户会发现「我没点开，怎么全已读了」。
 *
 * 只同步收件箱、只回溯最近 syncDays 天；附件只落盘图片（复用 FileService.storeImageBytes），
 * 其它附件保留文件名与大小，避免同步被大文件拖垮。
 */
@Service
public class MailSyncService {

    private static final Logger log = LoggerFactory.getLogger(MailSyncService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_PER_SYNC = 200;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "mail-sync");
        t.setDaemon(true);
        return t;
    });

    private final MailAccountRepository accountRepo;
    private final MailMessageRepository msgRepo;
    private final MailAccountService accountService;
    private final FileService fileService;

    public MailSyncService(MailAccountRepository accountRepo, MailMessageRepository msgRepo,
                           @org.springframework.context.annotation.Lazy MailAccountService accountService,
                           FileService fileService) {
        this.accountRepo = accountRepo;
        this.msgRepo = msgRepo;
        this.accountService = accountService;
        this.fileService = fileService;
    }

    public void requestAsync(Long accountId) {
        if (accountId == null) return;
        pool.submit(() -> {
            try {
                sync(accountId);
            } catch (Exception e) {
                log.warn("邮件同步失败（account {}）：{}", accountId, e.getMessage());
            }
        });
    }

    /** 同步单个账户：返回本次新增邮件数 */
    @Transactional
    public int sync(Long accountId) {
        MailAccount a = accountRepo.findById(accountId).orElse(null);
        if (a == null) return 0;
        int added = 0;
        try (Store store = accountService.connect(a)) {
            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            UIDFolder uidFolder = (UIDFolder) inbox;
            long lastUid = a.getLastUid() == null ? 0L : a.getLastUid();
            Message[] msgs;
            if (lastUid <= 0) {
                // 首次：按时间窗取最近的邮件，避免一次拉十年
                LocalDateTime since = LocalDateTime.now().minusDays(a.getSyncDays() == null ? 30 : a.getSyncDays());
                msgs = inbox.search(new jakarta.mail.search.ReceivedDateTerm(
                        jakarta.mail.search.ComparisonTerm.GE,
                        java.util.Date.from(since.atZone(ZoneId.systemDefault()).toInstant())));
                if (msgs.length > MAX_PER_SYNC) {
                    msgs = java.util.Arrays.copyOfRange(msgs, msgs.length - MAX_PER_SYNC, msgs.length);
                }
            } else {
                msgs = uidFolder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
                if (msgs.length > MAX_PER_SYNC) {
                    msgs = java.util.Arrays.copyOfRange(msgs, 0, MAX_PER_SYNC);
                }
            }
            if (msgs.length == 0) {
                markSynced(a, lastUid);
                return 0;
            }
            // BODY.PEEK：取内容但不置已读
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(UIDFolder.FetchProfileItem.UID);
            fp.add("BODY.PEEK[]");
            inbox.fetch(msgs, fp);

            long maxUid = lastUid;
            for (Message m : msgs) {
                long uid = uidFolder.getUID(m);
                if (uid <= 0) continue;
                maxUid = Math.max(maxUid, uid);
                if (msgRepo.existsByAccountIdAndUid(a.getId(), uid)) continue;
                try {
                    msgRepo.save(parse(a, m, uid));
                    added++;
                } catch (Exception e) {
                    log.warn("解析邮件失败（{} uid {}）：{}", a.getEmail(), uid, e.getMessage());
                }
            }
            markSynced(a, maxUid);
            inbox.close(false);
            return added;
        } catch (Exception e) {
            log.warn("同步邮箱失败（{}）：{}", a.getEmail(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "同步失败：" + e.getMessage());
        }
    }

    private void markSynced(MailAccount a, long uid) {
        a.setLastUid(uid);
        a.setLastSyncAt(LocalDateTime.now());
        a.setStatus("OK");
        a.setLastError(null);
        accountRepo.save(a);
    }

    // ---------------- MIME 解析 ----------------

    private MailMessage parse(MailAccount a, Message m, long uid) throws Exception {
        MailMessage out = new MailMessage();
        out.setAccountId(a.getId());
        out.setUserId(a.getUserId());
        out.setUid(uid);
        out.setFolder("INBOX");

        String subject = m.getSubject();
        out.setSubject(subject == null ? "（无主题）" : trim(subject, 480));
        Address[] from = m.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            out.setFromAddr(ia.getAddress());
            out.setFromName(trim(ia.getPersonal(), 90));
        }
        out.setToAddrs(addresses(m.getRecipients(Message.RecipientType.TO)));
        out.setSentAt(m.getSentDate() == null ? LocalDateTime.now()
                : LocalDateTime.ofInstant(m.getSentDate().toInstant(), ZoneId.systemDefault()));
        out.setMessageId(firstHeader(m, "Message-ID"));
        out.setThreadKey(threadKey(m));
        out.setUnread(!m.isSet(jakarta.mail.Flags.Flag.SEEN));

        Body body = new Body();
        collect(m, body, 0);
        out.setTextBody(body.text.isBlank() ? htmlToText(body.html) : body.text);
        out.setHtmlBody(body.html);
        out.setSnippet(trim(out.getTextBody().replaceAll("\\s+", " ").trim(), 200));
        out.setHasAttach(!body.attachments.isEmpty());
        out.setAttachments(body.attachments.isEmpty() ? null : JSON.writeValueAsString(body.attachments));
        return out;
    }

    private static class Body {
        String text = "";
        String html = "";
        final List<Map<String, Object>> attachments = new ArrayList<>();
    }

    private void collect(Part part, Body body, int depth) throws Exception {
        if (depth > 6) return;   // 防御恶意嵌套 multipart
        if (part.isMimeType("text/plain") && body.text.isBlank()) {
            body.text = String.valueOf(part.getContent());
            return;
        }
        if (part.isMimeType("text/html") && body.html.isBlank()) {
            body.html = String.valueOf(part.getContent());
            return;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) collect(mp.getBodyPart(i), body, depth + 1);
            return;
        }
        String filename = part.getFileName();
        String disposition = part.getDisposition();
        if (filename != null && (Part.ATTACHMENT.equalsIgnoreCase(disposition) || filename.contains("."))) {
            byte[] bytes;
            try (InputStream in = part.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 20L * 1024 * 1024) break;   // 单附件上限 20MB
                    bos.write(buf, 0, n);
                }
                bytes = bos.toByteArray();
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", trim(filename, 200));
            item.put("size", bytes.length);
            String url = storeIfImage(filename, bytes);
            if (url != null) item.put("url", url);
            body.attachments.add(item);
        }
    }

    /** 只有图片落盘（复用上传目录与魔数校验），其它附件只留元数据 */
    private String storeIfImage(String filename, byte[] bytes) {
        String lower = filename.toLowerCase();
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp"))) {
            return null;
        }
        try {
            return fileService.storeImageBytes(filename, bytes);
        } catch (Exception e) {
            log.warn("邮件图片附件落盘失败（{}）：{}", filename, e.getMessage());
            return null;
        }
    }

    private static String addresses(Address[] arr) {
        if (arr == null || arr.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (Address a : arr) {
            if (a == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(a.toString());
        }
        return trim(sb.toString(), 480);
    }

    private static String threadKey(Message m) throws Exception {
        String refs = firstHeader(m, "References");
        if (refs != null && !refs.isBlank()) {
            String[] parts = refs.trim().split("\\s+");
            return trim(parts[parts.length - 1], 190);
        }
        String inReplyTo = firstHeader(m, "In-Reply-To");
        if (inReplyTo != null && !inReplyTo.isBlank()) return trim(inReplyTo.trim(), 190);
        String mid = firstHeader(m, "Message-ID");
        return mid == null ? null : trim(mid.trim(), 190);
    }

    private static String firstHeader(Message m, String name) {
        try {
            String[] v = m.getHeader(name);
            return v == null || v.length == 0 ? null : v[0];
        } catch (Exception e) {
            return null;
        }
    }

    private static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        return Jsoup.parse(html).text();
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
