/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.MailAccount;
import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.repository.MailAccountRepository;
import com.kb.kbplatform.repository.SysSettingRepository;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 外部邮箱账户：服务商模板、授权码绑定、OAuth 授权与回调、IMAP 连接。
 *
 * 凭据一律经 AiKeyCipher 加密后入库；接口只返回脱敏信息。
 * OAuth 应用配置存 sys_setting：mail.oauth.google.clientId / clientSecret / redirectUri，
 * mail.oauth.ms.* 同理，管理员在后台填，无需改配置文件。
 */
@Service
public class MailAccountService {

    private static final Logger log = LoggerFactory.getLogger(MailAccountService.class);
    private static final String STATE_PREFIX = "mail:oauth:";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final MailAccountRepository repo;
    private final SysSettingRepository settingRepo;
    private final AiKeyCipher cipher;
    private final KvStore kv;

    public MailAccountService(MailAccountRepository repo, SysSettingRepository settingRepo,
                              AiKeyCipher cipher, KvStore kv) {
        this.repo = repo;
        this.settingRepo = settingRepo;
        this.cipher = cipher;
        this.kv = kv;
    }

    // ---------------- 服务商模板 ----------------

    /** 预设服务商：绑定表单据此自动填服务器，用户只需授权码（或走 OAuth） */
    public record Provider(String id, String name, String authType, String emailHint,
                           String imapHost, int imapPort, String smtpHost, int smtpPort, boolean smtpStartTls) {}

    private static final List<Provider> PROVIDERS = List.of(
            new Provider("QQ", "QQ 邮箱", "CODE", "123456@qq.com", "imap.qq.com", 993, "smtp.qq.com", 465, false),
            new Provider("NETEASE", "网易 163/126", "CODE", "xxx@163.com", "imap.163.com", 993, "smtp.163.com", 465, false),
            new Provider("EXMAIL", "腾讯企业邮", "CODE", "you@company.com", "imap.exmail.qq.com", 993, "smtp.exmail.qq.com", 465, false),
            new Provider("ALIYUN", "阿里/钉钉邮箱", "CODE", "you@company.com", "imap.aliyun.com", 993, "smtp.aliyun.com", 465, false),
            new Provider("GMAIL", "Gmail", "OAUTH", "you@gmail.com", "imap.gmail.com", 993, "smtp.gmail.com", 465, false),
            new Provider("OUTLOOK", "Outlook / Microsoft 365", "OAUTH", "you@outlook.com", "outlook.office365.com", 993, "smtp.office365.com", 587, true),
            new Provider("CUSTOM", "自定义 IMAP", "CODE", "you@example.com", "", 993, "", 465, false)
    );

    public List<Provider> providers() { return PROVIDERS; }

    private Provider providerOf(String id) {
        return PROVIDERS.stream().filter(p -> p.id().equalsIgnoreCase(String.valueOf(id))).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的邮箱服务商"));
    }

    // ---------------- 列表 / 解绑 ----------------

    public List<Map<String, Object>> list(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MailAccount a : repo.findByUserIdOrderByIdAsc(userId)) out.add(view(a));
        return out;
    }

    private Map<String, Object> view(MailAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("provider", a.getProvider());
        m.put("email", maskEmail(a.getEmail()));
        m.put("authType", a.getAuthType());
        m.put("imapHost", a.getImapHost());
        m.put("status", a.getStatus());
        m.put("lastError", a.getLastError());
        m.put("lastSyncAt", a.getLastSyncAt() == null ? "" : a.getLastSyncAt().toString());
        m.put("lastUid", a.getLastUid());
        return m;
    }

    private static String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at <= 1) return email;
        String name = email.substring(0, at);
        String keep = name.length() <= 2 ? name.substring(0, 1) : name.substring(0, 2);
        return keep + "***" + email.substring(at);
    }

    @Transactional
    public void remove(Long userId, Long id) {
        MailAccount a = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "邮箱不存在"));
        repo.delete(a);
    }

    // ---------------- 授权码绑定 ----------------

    @Transactional
    public Map<String, Object> bindWithCode(Long userId, Map<String, Object> body) {
        String providerId = str(body.get("provider"), "CUSTOM");
        Provider p = providerOf(providerId);
        if ("OAUTH".equals(p.authType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该服务商需要走 OAuth 授权");
        }
        String email = str(body.get("email"), "").trim().toLowerCase();
        String code = str(body.get("code"), "").trim();
        if (email.isEmpty() || code.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写邮箱与授权码");
        }
        String imapHost = str(body.get("imapHost"), p.imapHost());
        int imapPort = body.get("imapPort") == null ? p.imapPort() : Integer.parseInt(String.valueOf(body.get("imapPort")));
        String smtpHost = str(body.get("smtpHost"), p.smtpHost());
        int smtpPort = body.get("smtpPort") == null ? p.smtpPort() : Integer.parseInt(String.valueOf(body.get("smtpPort")));
        if (imapHost.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 IMAP 服务器");

        // 先真连一次，连不上直接拒绝，避免把错误凭据存进库
        try {
            verifyImap(imapHost, imapPort, email, code, false, null).close();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接失败：" + shortMsg(e));
        }

        MailAccount a = repo.findByUserIdAndEmail(userId, email).orElseGet(MailAccount::new);
        a.setUserId(userId);
        a.setProvider(p.id());
        a.setEmail(email);
        a.setAuthType("CODE");
        a.setCredentialEnc(cipher.encrypt(code));
        a.setImapHost(imapHost);
        a.setImapPort(imapPort);
        a.setImapSsl(true);
        a.setSmtpHost(smtpHost);
        a.setSmtpPort(smtpPort);
        a.setSmtpSsl(!p.smtpStartTls());
        a.setStatus("OK");
        a.setLastError(null);
        a.setUpdatedAt(LocalDateTime.now());
        repo.save(a);
        syncService.requestAsync(a.getId());
        return view(a);
    }

    @Transactional
    public Map<String, Object> retest(Long userId, Long id) {
        MailAccount a = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "邮箱不存在"));
        try (Store store = connect(a)) {
            a.setStatus("OK");
            a.setLastError(null);
        } catch (Exception e) {
            a.setStatus("ERROR");
            a.setLastError(shortMsg(e));
        }
        a.setUpdatedAt(LocalDateTime.now());
        repo.save(a);
        return view(a);
    }

    // ---------------- OAuth ----------------

    private static final String GOOGLE_AUTH = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String GOOGLE_TOKEN = "https://oauth2.googleapis.com/token";
    private static final String MS_AUTH = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";
    private static final String MS_TOKEN = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    private static final String GOOGLE_SCOPE = "https://mail.google.com/";
    private static final String MS_SCOPE = "offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send";

    /** 返回授权地址；前端 window.location 跳过去即可 */
    public Map<String, Object> oauthStart(Long userId, String providerId) {
        String pid = providerId == null ? "" : providerId.toUpperCase();
        boolean google = "GMAIL".equals(pid);
        boolean ms = "OUTLOOK".equals(pid);
        if (!google && !ms) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该服务商不需要 OAuth");

        String clientId = oauthCfg(google ? "google" : "ms", "clientId");
        String redirect = oauthCfg(google ? "google" : "ms", "redirectUri");
        if (clientId.isBlank() || redirect.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "管理员尚未配置 " + (google ? "Google" : "Microsoft") + " OAuth 应用（clientId / redirectUri）");
        }
        String state = UUID.randomUUID().toString().replace("-", "");
        kv.put(STATE_PREFIX + state, userId + "\n" + pid, Duration.ofMinutes(10));
        String url = (google ? GOOGLE_AUTH : MS_AUTH)
                + "?client_id=" + enc(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + enc(redirect)
                + "&scope=" + enc(google ? GOOGLE_SCOPE : MS_SCOPE)
                + "&state=" + enc(state)
                + (google ? "&access_type=offline&prompt=consent" : "&prompt=select_account");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        return m;
    }

    /** OAuth 回调：换 token → 取邮箱地址 → 存 refresh_token → 首次同步 */
    @Transactional
    public MailAccount oauthFinish(String providerId, String code, String state) {
        String cached = kv.getAndDelete(STATE_PREFIX + state);
        if (cached == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "授权已过期，请重新发起绑定");
        String[] parts = cached.split("\n");
        Long userId = Long.valueOf(parts[0]);
        String pid = parts.length > 1 ? parts[1] : providerId.toUpperCase();
        boolean google = "GMAIL".equals(pid);
        String tokenUrl = google ? GOOGLE_TOKEN : MS_TOKEN;
        String clientId = oauthCfg(google ? "google" : "ms", "clientId");
        String secret = oauthCfg(google ? "google" : "ms", "clientSecret");
        String redirect = oauthCfg(google ? "google" : "ms", "redirectUri");

        JsonNode tok = httpPostForm(tokenUrl, Map.of(
                "grant_type", "authorization_code",
                "code", code,
                "client_id", clientId,
                "client_secret", secret,
                "redirect_uri", redirect));
        String refresh = text(tok, "refresh_token");
        String access = text(tok, "access_token");
        if (refresh.isBlank() && access.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "授权失败：未拿到令牌");

        Provider p = providerOf(google ? "GMAIL" : "OUTLOOK");
        String email = fetchEmail(google, access).toLowerCase();
        if (email.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "授权失败：未取到邮箱地址");

        MailAccount a = repo.findByUserIdAndEmail(userId, email).orElseGet(MailAccount::new);
        a.setUserId(userId);
        a.setProvider(p.id());
        a.setEmail(email);
        a.setAuthType("OAUTH");
        // 优先存 refresh_token（可长期刷新）；没有就存 access_token，能撑一阵
        a.setCredentialEnc(cipher.encrypt(refresh.isBlank() ? access : refresh));
        a.setImapHost(p.imapHost());
        a.setImapPort(p.imapPort());
        a.setImapSsl(true);
        a.setSmtpHost(p.smtpHost());
        a.setSmtpPort(p.smtpPort());
        a.setSmtpSsl(!p.smtpStartTls());
        a.setStatus("OK");
        a.setLastError(null);
        a.setUpdatedAt(LocalDateTime.now());
        repo.save(a);
        syncService.requestAsync(a.getId());
        return a;
    }

    private String oauthCfg(String which, String field) {
        SysSetting s = settingRepo.findByKey("mail.oauth." + which + "." + field).orElse(null);
        return s == null || s.getValue() == null ? "" : s.getValue().trim();
    }

    private JsonNode httpPostForm(String url, Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> sb.append(sb.length() == 0 ? "" : "&").append(enc(k)).append('=').append(enc(v)));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) {
                log.warn("OAuth token 交换失败 {}：{}", resp.statusCode(), resp.body());
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "授权失败（" + resp.statusCode() + "）");
            }
            return JSON.readTree(resp.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "授权请求失败：" + shortMsg(e));
        }
    }

    /** 用 access_token 取邮箱地址：Google userinfo，Microsoft Graph me */
    private String fetchEmail(boolean google, String access) {
        if (access == null || access.isBlank()) return "";
        String url = google ? "https://www.googleapis.com/oauth2/v3/userinfo"
                : "https://graph.microsoft.com/v1.0/me?$select=mail,userPrincipalName";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + access)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 300) return "";
            JsonNode n = JSON.readTree(resp.body());
            String email = text(n, "email");
            if (email.isBlank()) email = text(n, "mail");
            if (email.isBlank()) email = text(n, "userPrincipalName");
            return email;
        } catch (Exception e) {
            log.warn("获取邮箱地址失败：{}", e.getMessage());
            return "";
        }
    }

    // ---------------- IMAP 连接（同步器复用） ----------------

    public Store connect(MailAccount a) throws Exception {
        String secret = cipher.decrypt(a.getCredentialEnc());
        boolean oauth = "OAUTH".equals(a.getAuthType());
        String token = oauth ? accessToken(a, secret) : secret;
        return verifyImap(a.getImapHost(), a.getImapPort() == null ? 993 : a.getImapPort(),
                a.getEmail(), token, oauth, null);
    }

    private Store verifyImap(String host, int port, String email, String secret, boolean oauth, Session ignored) throws Exception {
        Properties p = new Properties();
        p.put("mail.store.protocol", "imaps");
        p.put("mail.imaps.host", host);
        p.put("mail.imaps.port", String.valueOf(port));
        p.put("mail.imaps.ssl.enable", "true");
        p.put("mail.imaps.ssl.checkserveridentity", "true");
        p.put("mail.imaps.connectiontimeout", "8000");
        p.put("mail.imaps.timeout", "12000");
        p.put("mail.imaps.writetimeout", "12000");
        if (oauth) p.put("mail.imaps.auth.mechanisms", "XOAUTH2");
        Session session = Session.getInstance(p);
        Store store = session.getStore("imaps");
        store.connect(host, port, email, secret);
        return store;
    }

    /** OAuth 账户：用 refresh_token 换 access_token（Google / Microsoft 通用） */
    private String accessToken(MailAccount a, String refreshOrAccess) {
        boolean google = "GMAIL".equals(a.getProvider());
        String tokenUrl = google ? GOOGLE_TOKEN : MS_TOKEN;
        String clientId = oauthCfg(google ? "google" : "ms", "clientId");
        String secret = oauthCfg(google ? "google" : "ms", "clientSecret");
        if (clientId.isBlank() || secret.isBlank()) return refreshOrAccess;
        try {
            JsonNode n = httpPostForm(tokenUrl, Map.of(
                    "grant_type", "refresh_token",
                    "refresh_token", refreshOrAccess,
                    "client_id", clientId,
                    "client_secret", secret));
            String access = text(n, "access_token");
            return access.isBlank() ? refreshOrAccess : access;
        } catch (Exception e) {
            log.warn("刷新 access_token 失败（{}）：{}", a.getEmail(), shortMsg(e));
            return refreshOrAccess;
        }
    }

    // ---------------- 工具方法 ----------------

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? def : s;
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) return "";
        return n.get(field).asString("");
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) m = e.getClass().getSimpleName();
        return m.length() > 280 ? m.substring(0, 280) : m;
    }

    /** 由 MailSyncService 注入，避免循环依赖（同步器反过来要用本服务的 connect） */
    private MailSyncService syncService;

    @org.springframework.beans.factory.annotation.Autowired
    public void setSyncService(@org.springframework.context.annotation.Lazy MailSyncService syncService) {
        this.syncService = syncService;
    }
}
