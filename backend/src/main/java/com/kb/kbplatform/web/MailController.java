/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.MailAccount;
import com.kb.kbplatform.domain.MailMessage;
import com.kb.kbplatform.repository.MailAccountRepository;
import com.kb.kbplatform.repository.MailMessageRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.MailAccountService;
import com.kb.kbplatform.service.MailSendService;
import com.kb.kbplatform.service.MailSyncService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 邮箱账户与邮件读取。
 *
 * 所有接口按 token 里的 userId 过滤；OAuth 回调是公开端点，靠 state（KvStore，10 分钟）认用户。
 */
@RestController
@RequestMapping("/api/mail")
public class MailController {

    private final MailAccountService accountService;
    private final MailSyncService syncService;
    private final MailSendService sendService;
    private final MailAccountRepository accountRepo;
    private final MailMessageRepository msgRepo;

    public MailController(MailAccountService accountService, MailSyncService syncService,
                          MailSendService sendService,
                          MailAccountRepository accountRepo, MailMessageRepository msgRepo) {
        this.accountService = accountService;
        this.syncService = syncService;
        this.sendService = sendService;
        this.accountRepo = accountRepo;
        this.msgRepo = msgRepo;
    }

    private static Long uid(TokenClaims c) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        return c.userId();
    }

    /** 支持的服务商与默认服务器（绑定表单用） */
    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return accountService.providers().stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.id());
            m.put("name", p.name());
            m.put("authType", p.authType());
            m.put("emailHint", p.emailHint());
            m.put("imapHost", p.imapHost());
            m.put("imapPort", p.imapPort());
            m.put("smtpHost", p.smtpHost());
            m.put("smtpPort", p.smtpPort());
            return m;
        }).toList();
    }

    @GetMapping("/accounts")
    public List<Map<String, Object>> accounts(@RequestAttribute("kbUser") TokenClaims c) {
        return accountService.list(uid(c));
    }

    /** 授权码绑定（国内邮箱） */
    @PostMapping("/accounts/code")
    public Map<String, Object> bindCode(@RequestAttribute("kbUser") TokenClaims c,
                                       @RequestBody Map<String, Object> body) {
        return accountService.bindWithCode(uid(c), body);
    }

    /** OAuth 绑定第一步：拿授权地址，前端整页跳转 */
    @GetMapping("/oauth/{provider}/start")
    public Map<String, Object> oauthStart(@RequestAttribute("kbUser") TokenClaims c,
                                          @PathVariable String provider) {
        return accountService.oauthStart(uid(c), provider);
    }

    /** OAuth 回调（公开）：成功后返回一个自关闭的小页面，把结果通过 postMessage 交给打开者 */
    @GetMapping(value = "/oauth/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> oauthCallback(@RequestParam(required = false) String code,
                                                @RequestParam(required = false) String state,
                                                @RequestParam(required = false) String error,
                                                @RequestParam(name = "error_description", required = false) String errorDesc) {
        String msg;
        boolean ok = false;
        if (error != null && !error.isBlank()) {
            msg = "授权被拒绝：" + (errorDesc == null ? error : errorDesc);
        } else if (code == null || state == null) {
            msg = "授权参数缺失";
        } else {
            try {
                MailAccount a = accountService.oauthFinish(null, code, state);
                ok = true;
                msg = "已绑定 " + a.getEmail() + "，正在同步邮件…";
            } catch (Exception e) {
                msg = "绑定失败：" + (e.getMessage() == null ? "未知错误" : e.getMessage());
            }
        }
        String html = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>邮箱绑定</title>"
                + "<style>body{font-family:system-ui,'Microsoft YaHei',sans-serif;background:#f8fafc;"
                + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0}"
                + ".card{background:#fff;border:1px solid #e5e7eb;border-radius:12px;padding:28px 32px;"
                + "box-shadow:0 6px 24px rgba(15,23,42,.06);text-align:center;max-width:420px}"
                + "h2{margin:0 0 8px;font-size:17px;color:" + (ok ? "#047857" : "#b91c1c") + "}"
                + "p{margin:0;color:#475569;font-size:13px;line-height:1.7}</style></head><body>"
                + "<div class=\"card\"><h2>" + (ok ? "绑定成功" : "绑定未完成") + "</h2><p>" + esc(msg) + "</p>"
                + "<p style=\"margin-top:10px;color:#94a3b8\">本窗口可关闭，回到系统刷新即可</p></div>"
                + "<script>try{if(window.opener){window.opener.postMessage({type:'kb-mail-oauth',ok:" + ok
                + "},'*')}}catch(e){}setTimeout(function(){try{window.close()}catch(e){}},1200)</script>"
                + "</body></html>";
        return ResponseEntity.ok(html);
    }

    @PostMapping("/accounts/{id}/retest")
    public Map<String, Object> retest(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return accountService.retest(uid(c), id);
    }

    @DeleteMapping("/accounts/{id}")
    public Map<String, Object> remove(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        accountService.remove(uid(c), id);
        return Map.of("ok", true);
    }

    /** 手动同步：同步完把新增数量带回，前端好提示 */
    @PostMapping("/accounts/{id}/sync")
    public Map<String, Object> sync(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                    @RequestParam(required = false, defaultValue = "INBOX") String folder) {
        Long userId = uid(c);
        MailAccount a = accountRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "邮箱不存在"));
        int added = syncService.syncFolder(a.getId(), folder);
        return Map.of("ok", true, "added", added);
    }

    /** 邮件列表：按账号 / 关键词过滤，时间倒序 */
    @GetMapping("/messages")
    public Map<String, Object> messages(@RequestAttribute("kbUser") TokenClaims c,
                                        @RequestParam(required = false) Long accountId,
                                        @RequestParam(required = false, defaultValue = "INBOX") String folder,
                                        @RequestParam(required = false, defaultValue = "") String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "30") int size) {
        Page<MailMessage> p = msgRepo.search(uid(c), accountId, folder == null ? "" : folder.trim(), q == null ? "" : q.trim(),
                PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                        Sort.by(Sort.Direction.DESC, "sentAt")));
        List<Map<String, Object>> items = p.getContent().stream().map(this::brief).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", p.getTotalElements());
        out.put("page", p.getNumber());
        out.put("size", p.getSize());
        return out;
    }

    /** 发送（或回复/转发）：真正投递，失败不会本地留档 */
    @PostMapping("/send")
    public Map<String, Object> send(@RequestAttribute("kbUser") TokenClaims c,
                                    @RequestBody Map<String, Object> body) {
        Long accountId = body.get("accountId") == null ? null : Long.valueOf(String.valueOf(body.get("accountId")));
        if (accountId == null) throw new ResponseStatusException(NOT_FOUND, "请选择发信邮箱");
        MailAccountService.SendRequest req = new MailAccountService.SendRequest(
                accountId,
                strList(body.get("to")),
                strList(body.get("cc")),
                body.get("subject") == null ? "" : String.valueOf(body.get("subject")),
                body.get("body") == null ? "" : String.valueOf(body.get("body")),
                body.get("html") == null ? null : String.valueOf(body.get("html")),
                body.get("inReplyTo") == null ? null : String.valueOf(body.get("inReplyTo")),
                body.get("references") == null ? null : String.valueOf(body.get("references")),
                mapList(body.get("attachments")));
        MailMessage sent = sendService.send(uid(c), req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", sent.getId());
        return out;
    }

    /** 保存草稿（不投递）：folder=DRAFT，uid 用负数时间戳 */
    @PostMapping("/drafts")
    public Map<String, Object> saveDraft(@RequestAttribute("kbUser") TokenClaims c,
                                         @RequestBody Map<String, Object> body) {
        Long userId = uid(c);
        Long accountId = body.get("accountId") == null ? null : Long.valueOf(String.valueOf(body.get("accountId")));
        MailAccount acc = accountId == null ? null : accountRepo.findByIdAndUserId(accountId, userId).orElse(null);
        if (acc == null) throw new ResponseStatusException(NOT_FOUND, "请选择邮箱");

        Long id = body.get("id") == null ? null : Long.valueOf(String.valueOf(body.get("id")));
        MailMessage m = id == null ? new MailMessage()
                : msgRepo.findByIdAndUserId(id, userId).orElse(new MailMessage());
        m.setAccountId(acc.getId());
        m.setUserId(userId);
        if (m.getUid() == null || m.getUid() >= 0) m.setUid(-System.currentTimeMillis());
        m.setFolder("DRAFT");
        m.setSubject(body.get("subject") == null ? "（无主题）" : String.valueOf(body.get("subject")));
        m.setFromAddr(acc.getEmail());
        m.setFromName(acc.getEmail());
        m.setToAddrs(body.get("to") == null ? "" : String.valueOf(body.get("to")));
        m.setSentAt(java.time.LocalDateTime.now());
        String text = body.get("body") == null ? "" : String.valueOf(body.get("body"));
        m.setTextBody(text);
        m.setSnippet(text.replaceAll("\\s+", " ").trim().length() > 200
                ? text.replaceAll("\\s+", " ").trim().substring(0, 200) : text.replaceAll("\\s+", " ").trim());
        m.setUnread(false);
        msgRepo.save(m);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", m.getId());   // Map.of 不接受 null，这里用可空容器
        return out;
    }

    /** 收件人自动补全：历史发件人地址 */
    @GetMapping("/contacts")
    public List<String> contacts(@RequestAttribute("kbUser") TokenClaims c,
                                 @RequestParam(required = false, defaultValue = "") String q) {
        return msgRepo.findContacts(uid(c), q == null ? "" : q.trim(),
                PageRequest.of(0, 10));
    }

    /** 保存账户签名（写信时自动追加） */
    @PutMapping("/accounts/{id}/signature")
    public Map<String, Object> saveSignature(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                             @RequestBody Map<String, Object> body) {
        MailAccount a = accountRepo.findByIdAndUserId(id, uid(c))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "邮箱不存在"));
        String sig = body.get("signature") == null ? "" : String.valueOf(body.get("signature"));
        a.setSignature(sig.length() > 2000 ? sig.substring(0, 2000) : sig);
        a.setUpdatedAt(java.time.LocalDateTime.now());
        accountRepo.save(a);
        return Map.of("ok", true, "signature", a.getSignature());
    }

    /** IMAP 文件夹列表（自定义文件夹 / 已发送在服务器上的对应目录） */
    @GetMapping("/accounts/{id}/folders")
    public List<String> folders(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        MailAccount a = accountRepo.findByIdAndUserId(id, uid(c))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "邮箱不存在"));
        return syncService.listFolders(a.getId());
    }

    /** 标记已读/未读（仅本地状态，不回写 IMAP，避免动用户的邮箱） */
    @PostMapping("/messages/{id}/read")
    public Map<String, Object> markRead(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                        @RequestParam(defaultValue = "true") boolean read,
                                        @RequestParam(defaultValue = "true") boolean syncImap) {
        MailMessage m = msgRepo.findByIdAndUserId(id, uid(c))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "邮件不存在"));
        boolean imapOk = true;
        if (syncImap) imapOk = syncService.setSeen(m, read);   // 回写服务器失败不影响本地状态
        m.setUnread(!read);
        msgRepo.save(m);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("unread", m.getUnread());
        out.put("imapSynced", imapOk);
        return out;
    }

    /** 删除（仅本地隐藏；想同时在服务器删除需要在 IMAP 侧操作，这里不做） */
    @DeleteMapping("/messages/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                      @RequestParam(defaultValue = "true") boolean syncImap) {
        MailMessage m = msgRepo.findByIdAndUserId(id, uid(c))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "邮件不存在"));
        boolean imapOk = true;
        if (syncImap) imapOk = syncService.deleteOnServer(m);
        msgRepo.delete(m);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("imapSynced", imapOk);
        return out;
    }

    @GetMapping("/messages/{id}")
    public Map<String, Object> message(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        MailMessage m = msgRepo.findByIdAndUserId(id, uid(c))
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "邮件不存在"));
        Map<String, Object> o = brief(m);
        o.put("textBody", m.getTextBody());
        o.put("htmlBody", m.getHtmlBody());
        o.put("toAddrs", m.getToAddrs());
        o.put("messageId", m.getMessageId());
        return o;
    }

    private Map<String, Object> brief(MailMessage m) {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("id", m.getId());
        o.put("accountId", m.getAccountId());
        o.put("subject", m.getSubject());
        o.put("fromAddr", m.getFromAddr());
        o.put("fromName", m.getFromName());
        o.put("sentAt", m.getSentAt() == null ? "" : m.getSentAt().toString());
        o.put("snippet", m.getSnippet());
        o.put("hasAttach", m.getHasAttach());
        o.put("unread", m.getUnread());
        o.put("attachments", m.getAttachments());
        return o;
    }

    private static java.util.List<java.util.Map<String, Object>> mapList(Object o) {
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if (o instanceof java.util.Collection<?> col) {
            for (Object x : col) {
                if (x instanceof java.util.Map<?, ?> m) {
                    java.util.Map<String, Object> item = new LinkedHashMap<>();
                    m.forEach((k, v) -> item.put(String.valueOf(k), v));
                    out.add(item);
                }
            }
        }
        return out;
    }

    private static java.util.List<String> strList(Object o) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (o == null) return out;
        if (o instanceof java.util.Collection<?> col) {
            for (Object x : col) if (x != null && !String.valueOf(x).isBlank()) out.add(String.valueOf(x));
        } else {
            String s = String.valueOf(o);
            if (!s.isBlank()) out.add(s);
        }
        return out;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
