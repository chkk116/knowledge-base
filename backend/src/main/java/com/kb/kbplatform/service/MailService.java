/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kb.kbplatform.domain.Notification;
import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.SysSettingRepository;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件发送服务（SMTP）。
 *
 * 配置存于 kb_sys_setting 的 mail_smtp 键（JSON）；密码经 AiKeyCipher(AES-GCM) 加密存储，
 * 读取时解密，永不向接口返回明文/密文。SMTP 连接参数（host/port/加密方式/账号/密码）全部动态
 * 从库里取，不依赖 Spring 的静态 mail.* 配置，便于管理员在后台随时改、立即生效。
 *
 * 适用场景：
 *  1) 密码重置链接（管理员生成后自动发到用户已验证邮箱）；
 *  2) 邮箱绑定验证码；
 *  3) 站内通知的邮件抄送（用户开启后）。
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final String KEY = "mail_smtp";
    private static final String TPL_KEY = "mail_templates";

    private final SysSettingRepository settingRepo;
    private final AiKeyCipher cipher;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String siteBaseUrl;

    public MailService(SysSettingRepository settingRepo, AiKeyCipher cipher,
                       @Value("${KB_PUBLIC_BASE_URL:https://chkk116.cn}") String siteBaseUrl) {
        this.settingRepo = settingRepo;
        this.cipher = cipher;
        this.siteBaseUrl = siteBaseUrl.endsWith("/") ? siteBaseUrl.substring(0, siteBaseUrl.length() - 1) : siteBaseUrl;
    }

    /** 配置载体（password 保存时为明文，读取时由 load() 解密后填充） */
    public static class Config {
        public String host;
        public int port = 465;
        public String username;
        public String password;
        public String fromName;
        public String fromAddress;
        public String encryption = "SSL";   // SSL / TLS / NONE
        public boolean enabled = false;
    }

    public boolean isConfigured() {
        Config c = load();
        return c != null && c.enabled && c.host != null && !c.host.isBlank()
                && c.username != null && !c.username.isBlank()
                && c.password != null && !c.password.isBlank();
    }

    /** 读取配置（password 为解密后的明文，仅内部使用，不外传） */
    private Config load() {
        SysSetting s = settingRepo.findByKey(KEY).orElse(null);
        if (s == null || s.getValue() == null || s.getValue().isBlank()) return null;
        try {
            JsonNode n = objectMapper.readTree(s.getValue());
            Config c = new Config();
            c.host = str(n, "host");
            if (n.has("port")) c.port = n.get("port").asInt(465);
            c.username = str(n, "username");
            c.password = cipher.decrypt(str(n, "password"));
            c.fromName = str(n, "fromName");
            c.fromAddress = str(n, "fromAddress");
            c.encryption = str(n, "encryption");
            if (c.encryption == null) c.encryption = "SSL";
            c.enabled = bool(n, "enabled");
            return c;
        } catch (Exception e) {
            log.warn("邮件配置解析失败：{}", e.getMessage());
            return null;
        }
    }

    /** 保存配置；password 为空字符串表示「保留原密码不修改」 */
    @Transactional
    public void save(Config in) {
        SysSetting s = settingRepo.findByKey(KEY).orElse(null);
        ObjectNode n;
        if (s == null) {
            s = new SysSetting();
            s.setKey(KEY);
            n = objectMapper.createObjectNode();
        } else {
            try {
                n = (ObjectNode) objectMapper.readTree(s.getValue());
            } catch (Exception e) {
                n = objectMapper.createObjectNode();
            }
        }
        n.put("host", in.host);
        n.put("port", in.port);
        n.put("username", in.username);
        n.put("fromName", in.fromName);
        n.put("fromAddress", in.fromAddress);
        n.put("encryption", in.encryption);
        n.put("enabled", in.enabled);
        if (in.password != null && !in.password.isBlank()) {
            n.put("password", cipher.encrypt(in.password));   // 加密存储，库里不留明文
        }
        s.setValue(n.toString());
        s.setUpdatedAt(java.time.LocalDateTime.now());
        settingRepo.save(s);
    }

    /** 给前端用的脱敏视图（永不返回明文/密文） */
    public Map<String, Object> publicView() {
        SysSetting s = settingRepo.findByKey(KEY).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null || s.getValue() == null || s.getValue().isBlank()) {
            m.put("configured", false);
            return m;
        }
        try {
            JsonNode n = objectMapper.readTree(s.getValue());
            m.put("host", str(n, "host"));
            m.put("port", n.has("port") ? n.get("port").asInt(465) : 465);
            m.put("username", str(n, "username"));
            m.put("fromName", str(n, "fromName"));
            m.put("fromAddress", str(n, "fromAddress"));
            m.put("encryption", str(n, "encryption") == null ? "SSL" : str(n, "encryption"));
            m.put("enabled", bool(n, "enabled"));
            m.put("passwordSet", str(n, "password") != null && !str(n, "password").isBlank());
        } catch (Exception e) {
            log.warn("邮件配置读取失败：{}", e.getMessage());
        }
        m.put("configured", isConfigured());
        return m;
    }

    /** 底层发送：to 收件人、subject 主题、html 正文。未配置/未启用抛异常由调用方处理 */
    public void send(String to, String subject, String html) {
        Config c = load();
        if (c == null || !c.enabled) throw new IllegalStateException("邮件服务未配置或未启用");
        Properties props = new Properties();
        props.put("mail.smtp.host", c.host);
        props.put("mail.smtp.port", String.valueOf(c.port));
        props.put("mail.smtp.auth", "true");   // 必须：否则不走 AUTH，QQ 邮箱报 503 need EHLO and AUTH first
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "15000");
        props.put("mail.smtp.writetimeout", "15000");
        String enc = c.encryption == null ? "SSL" : c.encryption;
        if ("SSL".equals(enc)) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.trust", c.host);
            props.put("mail.smtp.socketFactory.port", String.valueOf(c.port));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else if ("TLS".equals(enc)) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(c.username, c.password);
            }
        });
        try {
            MimeMessage msg = new MimeMessage(session);
            String fromAddr = (c.fromAddress != null && !c.fromAddress.isBlank()) ? c.fromAddress : c.username;
            String fromName = (c.fromName != null && !c.fromName.isBlank()) ? c.fromName : "知识库";
            msg.setFrom(new InternetAddress(fromAddr, fromName, "UTF-8"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            msg.setSubject(subject, "UTF-8");
            msg.setSentDate(new Date());
            msg.setContent(html, "text/html; charset=UTF-8");
            Transport.send(msg);
            log.info("邮件已发送至 {}（主题：{}）", to, subject);
        } catch (Exception e) {
            throw new IllegalStateException("邮件发送失败：" + e.getMessage(), e);
        }
    }

    // ---------------- 可配置模板 ----------------

    /** 模板变量说明（前端展示） */
    public static final Map<String, String> TPL_VARS = new LinkedHashMap<>();

    static {
        TPL_VARS.put("username", "收件人显示名");
        TPL_VARS.put("title", "通知类型标题");
        TPL_VARS.put("summary", "通知摘要");
        TPL_VARS.put("link", "操作链接（重置/查看）");
        TPL_VARS.put("code", "验证码");
        TPL_VARS.put("site", "站点地址");
    }

    /** 默认模板：首次使用或「恢复默认」时返回 */
    public Map<String, Map<String, String>> defaultTemplates() {
        Map<String, Map<String, String>> m = new LinkedHashMap<>();
        m.put("reset", tplDef(
                "【知识库】密码重置链接",
                "<p>您好 {username}：</p>"
                        + "<p>我们收到了您的密码重置请求。请点击下面的按钮设置新密码（链接 30 分钟内有效，且仅可使用一次）：</p>"
                        + "<p style=\"text-align:center;margin:20px 0;\"><a href=\"{link}\" style=\"display:inline-block;background:#1677ff;color:#fff;padding:10px 22px;border-radius:6px;text-decoration:none;\">重置密码</a></p>"
                        + "<p>若按钮无法点击，请复制链接到浏览器打开：{link}</p>"
                        + "<p>若非本人操作，请忽略此邮件，您的密码不会变更。</p>"));
        m.put("verify", tplDef(
                "【知识库】邮箱绑定验证码",
                "<p>您好：</p><p>您正在绑定邮箱，验证码为：</p>"
                        + "<p style=\"font-size:32px;font-weight:700;letter-spacing:6px;color:#1677ff;background:#f0f6ff;padding:16px 0;text-align:center;border-radius:8px;margin:14px 0;\">{code}</p>"
                        + "<p>验证码 10 分钟内有效。若非本人操作，请忽略此邮件。</p>"));
        m.put("notify", tplDef(
                "【知识库】{title}",
                "<p>您好 {username}：</p><p>【{title}】{summary}</p>"
                        + "<p style=\"text-align:center;margin:20px 0;\"><a href=\"{link}\" style=\"display:inline-block;background:#1677ff;color:#fff;padding:10px 22px;border-radius:6px;text-decoration:none;\">查看通知</a></p>"
                        + "<p>您可以在个人资料页关闭「接收通知邮件」。</p>"));
        return m;
    }

    private static Map<String, String> tplDef(String subject, String body) {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("subject", subject);
        t.put("body", body);
        return t;
    }

    /** 读取模板：库里没有的项用默认值补全 */
    public Map<String, Map<String, String>> templates() {
        Map<String, Map<String, String>> out = defaultTemplates();
        SysSetting s = settingRepo.findByKey(TPL_KEY).orElse(null);
        if (s == null || s.getValue() == null || s.getValue().isBlank()) return out;
        try {
            JsonNode n = objectMapper.readTree(s.getValue());
            for (String key : out.keySet()) {
                JsonNode t = n.get(key);
                if (t == null) continue;
                String subj = str(t, "subject");
                String body = str(t, "body");
                if (subj != null && !subj.isBlank()) out.get(key).put("subject", subj);
                if (body != null && !body.isBlank()) out.get(key).put("body", body);
            }
        } catch (Exception e) {
            log.warn("邮件模板解析失败：{}", e.getMessage());
        }
        return out;
    }

    /** 保存模板（只落 reset / verify / notify 三项） */
    @Transactional
    public void saveTemplates(Map<String, Map<String, String>> in) {
        ObjectNode n = objectMapper.createObjectNode();
        for (String key : List.of("reset", "verify", "notify")) {
            Map<String, String> t = in == null ? null : in.get(key);
            if (t == null) continue;
            ObjectNode o = objectMapper.createObjectNode();
            o.put("subject", t.getOrDefault("subject", ""));
            o.put("body", t.getOrDefault("body", ""));
            n.set(key, o);
        }
        SysSetting s = settingRepo.findByKey(TPL_KEY).orElseGet(() -> {
            SysSetting x = new SysSetting();
            x.setKey(TPL_KEY);
            return x;
        });
        s.setValue(n.toString());
        s.setUpdatedAt(java.time.LocalDateTime.now());
        settingRepo.save(s);
    }

    private String render(String tpl, Map<String, String> vars) {
        String s = tpl == null ? "" : tpl;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                s = s.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return s;
    }

    /** 模板正文外套统一外壳（页脚） */
    private String wrapTpl(String inner) {
        return "<div style=\"max-width:480px;margin:0 auto;font-family:-apple-system,Segoe UI,"
                + "PingFang SC,Microsoft YaHei,sans-serif;color:#1d2129;font-size:14px;line-height:1.7;\">"
                + inner
                + "<hr style=\"border:none;border-top:1px solid #e5e6eb;margin:18px 0;\"/>"
                + "<p style=\"font-size:12px;color:#c9cdd4;margin:0;\">本邮件由知识库系统自动发送，请勿直接回复。</p>"
                + "</div>";
    }

    /** 管理员手动发送：正文按纯文本处理（转义 HTML、保留换行） */
    public void sendCustom(String to, String subject, String text) {
        if (to == null || to.isBlank()) throw new IllegalArgumentException("请填写收件人");
        if (subject == null || subject.isBlank()) throw new IllegalArgumentException("请填写主题");
        String safe = escapeHtml(text == null ? "" : text)
                .replace("\r\n", "\n").replace("\n", "<br/>");
        send(to.trim(), subject.trim(), wrapTpl("<p>" + safe + "</p>"));
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---------------- 业务模板 ----------------

    /** 密码重置链接邮件（主题/正文可在「邮件设置 → 邮件模板」里改） */
    public void sendResetLink(User u, String link) {
        Map<String, String> t = templates().get("reset");
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("username", display(u));
        vars.put("link", link);
        vars.put("site", siteBaseUrl);
        send(u.getEmail(), render(t.get("subject"), vars), wrapTpl(render(t.get("body"), vars)));
    }

    /** 邮箱绑定验证码邮件（主题/正文可配置） */
    public void sendVerifyCode(String to, String code) {
        Map<String, String> t = templates().get("verify");
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("code", code);
        vars.put("site", siteBaseUrl);
        send(to, render(t.get("subject"), vars), wrapTpl(render(t.get("body"), vars)));
    }

    /** 站内通知邮件抄送（主题/正文可配置） */
    public void sendNotification(User u, Notification n) {
        String typeLabel = notificationLabel(n.getType());
        Map<String, String> t = templates().get("notify");
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("username", display(u));
        vars.put("title", typeLabel);
        vars.put("summary", n.getSummary() == null ? "" : n.getSummary());
        vars.put("link", siteBaseUrl + "/notifications");
        vars.put("site", siteBaseUrl);
        send(u.getEmail(), render(t.get("subject"), vars), wrapTpl(render(t.get("body"), vars)));
    }

    /** 测试邮件 */
    public void sendTest(String to) {
        String html = wrapHtml("邮件配置测试",
                "您好：",
                "这是一封测试邮件，说明 SMTP 配置正确，知识库已可通过邮件向您发送信息。",
                null, null, "收到请忽略。");
        send(to, "【知识库】邮件配置测试", html);
    }

    // ---------------- 内部辅助 ----------------

    private String display(User u) {
        if (u == null) return "";
        String n = (u.getDisplayName() != null && !u.getDisplayName().isBlank()) ? u.getDisplayName() : u.getUsername();
        return n == null ? "" : n;
    }

    private static String notificationLabel(String type) {
        if (type == null) return "通知";
        return switch (type) {
            case "COMMENT" -> "文章评论";
            case "COMMENT_REPLY" -> "评论回复";
            case "COMMENT_MENTION", "DOC_MENTION" -> "有人@我";
            case "LIKE" -> "收到点赞";
            case "REVIEW" -> "审核结果";
            case "REVIEW_SUBMIT" -> "待审核";
            case "DOC_UPDATE" -> "文档更新";
            case "CERTIFICATE" -> "结业证书";
            case "AVATAR_APPROVED", "AVATAR_REJECTED" -> "头像审核";
            case "SECURITY_ALERT" -> "安全告警";
            default -> "通知";
        };
    }

    /** 通用 HTML 外壳 */
    private String wrapHtml(String title, String greeting, String desc, String btnUrl, String btnText, String footnote) {
        return wrapHtml(title, greeting, desc, btnUrl, btnText, footnote, null);
    }

    private String wrapHtml(String title, String greeting, String desc, String btnUrl, String btnText, String footnote, String codeBlock) {
        String btn = (btnUrl != null && btnText != null)
                ? "<p style=\"text-align:center;margin:20px 0;\"><a href=\"" + btnUrl + "\" style=\"display:inline-block;background:#1677ff;color:#fff;"
                + "padding:10px 22px;border-radius:6px;text-decoration:none;font-size:15px;\">"
                + btnText + "</a></p>"
                : "";
        String code = (codeBlock != null && !codeBlock.isBlank()) ? codeBlock : "";
        return "<div style=\"max-width:480px;margin:0 auto;font-family:-apple-system,Segoe UI,"
                + "PingFang SC,Microsoft YaHei,sans-serif;color:#1d2129;\">"
                + "<h2 style=\"font-size:18px;margin:0 0 12px;\">" + title + "</h2>"
                + "<p style=\"font-size:14px;line-height:1.7;margin:6px 0;\">" + greeting + "</p>"
                + "<p style=\"font-size:14px;line-height:1.7;margin:6px 0;\">" + desc + "</p>"
                + code
                + btn
                + "<p style=\"font-size:12px;color:#86909c;line-height:1.7;margin:16px 0 0;\">"
                + (footnote == null ? "" : footnote) + "</p>"
                + "<hr style=\"border:none;border-top:1px solid #e5e6eb;margin:18px 0;\"/>"
                + "<p style=\"font-size:12px;color:#c9cdd4;margin:0;\">本邮件由知识库系统自动发送，请勿直接回复。</p>"
                + "</div>";
    }

    private static String str(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean bool(JsonNode n, String k) {
        JsonNode v = n.get(k);
        return v != null && v.asBoolean(false);
    }
}
