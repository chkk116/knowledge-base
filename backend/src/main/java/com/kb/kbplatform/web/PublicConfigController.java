/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.repository.SysSettingRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 公开站点配置（未登录可读）。
 * 登录页在未认证状态下也要渲染二维码等内容，故把 SysSetting 里 login_qrcodes 的
 * 白名单字段公开下发；其余设置项仍走 /api/admin/settings/*（需登录）。
 */
@RestController
@RequestMapping("/api/public")
public class PublicConfigController {

    /** 后台未配置时的缺省二维码地址 */
    private static final String DEF_WX = "https://www.example.com/wx-miniapp";
    private static final String DEF_SUPPORT = "https://www.example.com/support";

    private final SysSettingRepository settingRepo;

    public PublicConfigController(SysSettingRepository settingRepo) {
        this.settingRepo = settingRepo;
    }

    /** 登录页二维码地址：GET /api/public/login-config
     *  items 为可个性化二维码列表（2026-10-06）：[{label, content, image}]，label=下方文字，
     *  content=生成二维码的链接或文本，image=直接上传的二维码图片（/uploads/...，存在时优先显示）。
     *  wxUrl/supportUrl 为旧字段，仅作无 items 时的向后兼容回退。 */
    @GetMapping("/login-config")
    public Map<String, Object> loginConfig() {
        String wx = DEF_WX;
        String support = DEF_SUPPORT;
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        SysSetting s = settingRepo.findByKey("login_qrcodes").orElse(null);
        if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
            // 仅字符串字段，用正则提取即可（Boot 4 默认 Jackson 3 = tools.jackson，避免引入旧包依赖）
            String wxV = extractJsonString(s.getValue(), "wxUrl");
            String supV = extractJsonString(s.getValue(), "supportUrl");
            if (wxV != null && !wxV.isBlank()) wx = wxV;
            if (supV != null && !supV.isBlank()) support = supV;
            String wxImg = extractJsonString(s.getValue(), "wxImage");
            if (wxImg != null && !wxImg.isBlank()) r.put("wxImage", wxImg);
            String supImg = extractJsonString(s.getValue(), "supportImage");
            if (supImg != null && !supImg.isBlank()) r.put("supportImage", supImg);
            // 个性化二维码列表：原样下发 JSON 数组字符串，由前端解析（内容含任意文本，不做转义处理）
            String items = extractJsonArray(s.getValue(), "items");
            if (items != null && !items.isBlank()) r.put("items", items);
        }
        r.put("wxUrl", wx);
        r.put("supportUrl", support);
        return r;
    }

    /** 极简提取 JSON 字符串字段值（形如 "key":"value"），不含转义处理——value 由前端 JSON.stringify 生成，URL/文本场景足够 */
    private static String extractJsonString(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 站点页脚信息：GET /api/public/site-info
     *
     * 登录页在未认证状态下也要展示备案号等内容，故与 login-config 一样公开下发。
     * 数据源是 SysSetting 的 site_info 键（JSON 字符串），由后台「页脚设置」维护。
     *
     * 默认**全部为空**：这样本地开发环境（库里没这条配置）页脚不渲染任何内容，
     * 只有线上后台填过之后才显示 —— 避免把某个部署环境的备案号硬编码进仓库。
     * 版本号不在这里下发：它由构建期从 package.json 注入（__APP_VERSION__），属代码产物。
     */
    @GetMapping("/site-info")
    public Map<String, String> siteInfo() {
        Map<String, String> r = new java.util.LinkedHashMap<>();
        String raw = null;
        SysSetting s = settingRepo.findByKey("site_info").orElse(null);
        if (s != null && s.getValue() != null && !s.getValue().isBlank()) raw = s.getValue();
        r.put("enabled", raw == null ? "false" : boolOf(raw));
        r.put("icpNo", raw == null ? "" : nvl(extractJsonString(raw, "icpNo")));
        r.put("policeNo", raw == null ? "" : nvl(extractJsonString(raw, "policeNo")));
        // 自定义展示内容：整段数组原样下发，前端 JSON.parse 后渲染
        String items = raw == null ? null : extractJsonArray(raw, "items");
        r.put("items", items == null ? "[]" : items);
        // 以下两个是早期版本的固定字段，保留下发仅供前端做一次性迁移（新配置不再写入）
        r.put("giteeUrl", raw == null ? "" : nvl(extractJsonString(raw, "giteeUrl")));
        r.put("copyright", raw == null ? "" : nvl(extractJsonString(raw, "copyright")));
        return r;
    }

    private static String nvl(String v) { return v == null ? "" : v; }

    /** 提取 JSON 数字字段（形如 "key":123） */
    private static Integer extractJsonNumber(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        if (!m.find()) return null;
        try {
            long v = Long.parseLong(m.group(1));
            // 公开下发的阈值是前端展示/比较用，裁到 int 正数域，防超大值 NumberFormatException → 500
            return (v > 0 && v <= Integer.MAX_VALUE) ? (int) v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 提取 JSON 布尔字段（形如 "key":true） */
    private static Boolean extractJsonBool(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(true|false)")
                .matcher(json);
        return m.find() ? Boolean.valueOf(m.group(1)) : null;
    }

    /**
     * AI 消耗守护：GET /api/public/ai-guard
     *
     * AI 页在「发多张图 / 发超长文本」前是否先弹确认，由管理员在「AI 模型管理」里配置，
     * 数据源是 SysSetting 的 ai_cost_guard 键（JSON）。普通用户也要读到，故公开下发。
     * 默认**开启**（保守）：即使从没配过，也不会一次误发就烧掉大量额度。
     */
    @GetMapping("/ai-guard")
    public Map<String, Object> aiGuard() {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("enabled", Boolean.TRUE);
        r.put("imageThreshold", Integer.valueOf(2));
        r.put("textThreshold", Integer.valueOf(8000));
        SysSetting s = settingRepo.findByKey("ai_cost_guard").orElse(null);
        if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
            String raw = s.getValue();
            Boolean en = extractJsonBool(raw, "enabled");
            if (en != null) r.put("enabled", en);
            Integer it = extractJsonNumber(raw, "imageThreshold");
            if (it != null && it.intValue() > 0) r.put("imageThreshold", it);
            Integer tt = extractJsonNumber(raw, "textThreshold");
            if (tt != null && tt.intValue() > 0) r.put("textThreshold", tt);
        }
        return r;
    }

    /**
     * 提取 JSON 数组字段的原始文本（形如 "items":[...]）。
     * 用括号深度扫描而非贪婪正则：条目文字里可能出现 ] 或 [，正则会截错。
     * 找不到或不成对时返回 null（调用方退化为 []）。
     */
    private static String extractJsonArray(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\\[")
                .matcher(json);
        if (!m.find()) return null;
        int start = m.end() - 1;          // 指向 '['
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    /** 取 JSON 串里的 checksum 字段（缺失返回空串） */
    private static String checksumOf(String json) {
        if (json == null || json.isBlank()) return "";
        String c = extractJsonString(json, "checksum");
        return c == null ? "" : c;
    }

    /** 提取布尔字段（形如 "enabled":true），缺省 false */
    private static String boolOf(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"enabled\"\\s*:\\s*(true|false)")
                .matcher(json);
        return m.find() ? m.group(1) : "false";
    }

    /** 提取指定键的布尔值，缺省 false */
    private static boolean boolOfKey(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(true|false)")
                .matcher(json);
        return m.find() && "true".equals(m.group(1));
    }

    /** 提取指定键的整数，缺省 0 */
    private static int intOf(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        try {
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (NumberFormatException e) {
            return 0;   // 管理员配成超大数时该免登录接口不能 500（外部复审 2026-10-09）
        }
    }

    /**
     * App 版本检查：GET /api/public/app-version（免登录）
     *
     * App 启动时拉取，用 versionCode 跟本地比对，决定是否提示升级。
     * 数据源：SysSetting 的 app_version（JSON）——
     *   { "versionCode": 2, "versionName": "1.1", "url": "https://.../知识库-v1.1.apk",
     *     "notes": "更新说明", "force": false }
     * 未配置时 versionCode=0，App 视为「未启用升级检查」，不打扰用户。
     */
    @GetMapping("/app-version")
    public Map<String, Object> appVersion() {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        int code = 0;
        String name = "", url = "", notes = "";
        String raw = "";
        boolean force = false;
        SysSetting s = settingRepo.findByKey("app_version").orElse(null);
        if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
            String v = s.getValue();
            raw = v;
            code = intOf(v, "versionCode");
            String n = extractJsonString(v, "versionName");
            if (n != null) name = n;
            String u = extractJsonString(v, "url");
            if (u != null) url = u;
            String no = extractJsonString(v, "notes");
            if (no != null) notes = no;
            force = boolOfKey(v, "force");
        }
        r.put("versionCode", code);
        r.put("versionName", name);
        r.put("url", url);
        r.put("notes", notes);
        // 安装包 SHA-256：App 下载完成后强制比对，防止包体被替换（外部复审 2026-10-09）
        r.put("checksum", checksumOf(raw));
        r.put("force", force);
        return r;
    }

    /**
     * 前端热更包信息：GET /api/public/app-bundle（免登录）
     *
     * App 启动时拉取，用 version 跟本地 bundle 版本比对，不同则下载 zip 并切换。
     * 数据源：SysSetting 的 app_bundle（JSON）——
     *   { "version": "20261009-1016", "url": "https://.../app-bundles/kb-app-xxx.zip",
     *     "checksum": "sha256hex", "notes": "更新说明" }
     * 未配置时 version 为空串，App 视为「未启用热更」，直接用内置资源。
     */
    @GetMapping("/app-bundle")
    public Map<String, Object> appBundle() {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        String version = "", url = "", checksum = "", notes = "";
        SysSetting s = settingRepo.findByKey("app_bundle").orElse(null);
        if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
            String v = s.getValue();
            String a = extractJsonString(v, "version");
            if (a != null) version = a;
            String b = extractJsonString(v, "url");
            if (b != null) url = b;
            String c = extractJsonString(v, "checksum");
            if (c != null) checksum = c;
            String d = extractJsonString(v, "notes");
            if (d != null) notes = d;
        }
        r.put("version", version);
        r.put("url", url);
        r.put("checksum", checksum);
        r.put("notes", notes);
        return r;
    }
}
