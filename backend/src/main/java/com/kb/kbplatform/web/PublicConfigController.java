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

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 公开站点配置（未登录可读）。
 * 登录页在未认证状态下也要渲染二维码等内容，故把 SysSetting 里 login_qrcodes 的
 * 白名单字段公开下发；其余设置项仍走 /api/admin/settings/*（需登录）。
 *
 * JSON 解析统一走 Jackson 树（外部审查 2026-10-11：原正则提取不处理转义——
 * 值含引号/反斜杠时截断残缺下发；boolOf 误扫嵌套同名键；公开接口解析异常绝不 500）。
 */
@RestController
@RequestMapping("/api/public")
public class PublicConfigController {

    /** 后台未配置时的缺省二维码地址 */
    private static final String DEF_WX = "https://www.example.com/wx-miniapp";
    private static final String DEF_SUPPORT = "https://www.example.com/support";

    /** Spring Boot 4 的 Jackson 3 坐标是 tools.jackson.*（com.fasterxml 不在 classpath）；
     *  JsonMapper 线程安全，静态复用（与 AdminSettingsController 同口径） */
    private static final tools.jackson.databind.json.JsonMapper JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    private final SysSettingRepository settingRepo;

    public PublicConfigController(SysSettingRepository settingRepo) {
        this.settingRepo = settingRepo;
    }

    // ---------------- JSON 树读取工具（全部容错，坏配置一律走缺省值） ----------------

    /** SysSetting 的 JSON 串 → 树；空/坏 JSON 返回 null（公开接口不能 500） */
    private static JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try { return JSON.readTree(json); } catch (Exception e) { return null; }
    }

    /** 字符串字段（含数字等其他标量的 asText 兜底），缺失/空返回 null */
    private static String str(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        if (v == null || v.isNull()) return null;
        String s = v.isTextual() ? v.textValue() : v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 布尔字段，缺失/类型不符返回 null */
    private static Boolean bool(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return (v == null || !v.isBoolean()) ? null : v.booleanValue();
    }

    /** 正整数字段，越界/缺失返回 null（防管理员配超大值 → 500，外部复审 2026-10-09） */
    private static Integer intClamped(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        if (v == null || !v.isNumber()) return null;
        long x = v.longValue();
        return (x > 0 && x <= Integer.MAX_VALUE) ? (int) x : null;
    }

    /** 数组字段原样下发（重新序列化，转义由 Jackson 保证），缺失/非数组返回 null */
    private static String arrayRaw(JsonNode n, String key) {
        JsonNode v = n == null ? null : n.get(key);
        return (v == null || !v.isArray()) ? null : v.toString();
    }

    private static String nvl(String v) { return v == null ? "" : v; }

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
            JsonNode n = parse(s.getValue());
            String wxV = str(n, "wxUrl");
            String supV = str(n, "supportUrl");
            if (wxV != null) wx = wxV;
            if (supV != null) support = supV;
            String wxImg = str(n, "wxImage");
            if (wxImg != null) r.put("wxImage", wxImg);
            String supImg = str(n, "supportImage");
            if (supImg != null) r.put("supportImage", supImg);
            // 个性化二维码列表：原样下发 JSON 数组，由前端解析
            String items = arrayRaw(n, "items");
            if (items != null && !items.isBlank()) r.put("items", items);
        }
        r.put("wxUrl", wx);
        r.put("supportUrl", support);
        return r;
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
        JsonNode n = parse(raw);
        Boolean en = bool(n, "enabled");   // 顶层字段精确读取，不再误扫嵌套 enabled（外部审查 2026-10-11）
        r.put("enabled", Boolean.TRUE.equals(en) ? "true" : "false");
        r.put("icpNo", nvl(str(n, "icpNo")));
        r.put("policeNo", nvl(str(n, "policeNo")));
        // 自定义展示内容：整段数组原样下发，前端 JSON.parse 后渲染
        String items = arrayRaw(n, "items");
        r.put("items", items == null ? "[]" : items);
        // 以下两个是早期版本的固定字段，保留下发仅供前端做一次性迁移（新配置不再写入）
        r.put("giteeUrl", nvl(str(n, "giteeUrl")));
        r.put("copyright", nvl(str(n, "copyright")));
        return r;
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
            JsonNode n = parse(s.getValue());
            Boolean en = bool(n, "enabled");
            if (en != null) r.put("enabled", en);
            Integer it = intClamped(n, "imageThreshold");
            if (it != null) r.put("imageThreshold", it);
            Integer tt = intClamped(n, "textThreshold");
            if (tt != null) r.put("textThreshold", tt);
        }
        return r;
    }

    /**
     * 下载中心配置：GET /api/public/download-config（免登录）
     *
     * 网站页脚/下载页要展示「客户端下载」入口，入口与平台的开关由管理员在后台控制。
     * 数据源 SysSetting 的 download_center（JSON）：
     *   { "enabled": true, "app": true, "desktop": true, "title": "客户端下载", "desc": "..." }
     * 未配置时**默认启用**（三个开关都为 true），管理员关掉后页脚入口与下载页即隐藏。
     * 具体版本信息仍走 /api/public/app-version 各通道，不在这里下发。
     */
    @GetMapping("/download-config")
    public Map<String, Object> downloadConfig() {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("enabled", Boolean.TRUE);
        r.put("app", Boolean.TRUE);
        r.put("desktop", Boolean.TRUE);
        r.put("title", "客户端下载");
        r.put("desc", "下载知识库客户端，随时随地使用");
        SysSetting s = settingRepo.findByKey("download_center").orElse(null);
        if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
            JsonNode n = parse(s.getValue());
            Boolean en = bool(n, "enabled");
            if (en != null) r.put("enabled", en);
            Boolean ap = bool(n, "app");
            if (ap != null) r.put("app", ap);
            Boolean de = bool(n, "desktop");
            if (de != null) r.put("desktop", de);
            String t = str(n, "title");
            // 公开接口对所有人可读：按后台输入框同样的上限裁剪，避免脏配置无限制外溢
            if (t != null) r.put("title", t.length() > 30 ? t.substring(0, 30) : t);
            String d = str(n, "desc");
            if (d != null) r.put("desc", d.length() > 120 ? d.substring(0, 120) : d);
        }
        return r;
    }

    /**
     * App 版本检查：GET /api/public/app-version（免登录）
     *
     * App 启动时拉取，用 versionCode 跟本地比对，决定是否提示升级。
     * 数据源：SysSetting 的 app_version（JSON）——
     *   { "versionCode": 2, "versionName": "1.1", "url": "https://.../知识库-v1.1.apk",
     *     "notes": "更新说明", "force": false }
     * 未配置时 versionCode=0，App 视为「未启用升级检查」，不打扰用户。
     *
     * **多通道**（2026-10-25）：`?channel=native` 读 `app_version_native`，供 Kotlin 原生壳使用；
     * 不带 channel 仍读 `app_version`（Capacitor 套壳），两条通道互不干扰、各自发版。
     * channel 只认白名单，**绝不能**把参数直接拼成 setting_key（否则等于开放任意设置项读取）。
     */
    @GetMapping("/app-version")
    public Map<String, Object> appVersion(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String channel) {
        String key = "app_version";
        if (channel != null && !channel.isBlank()) {
            if (!APP_VERSION_CHANNELS.contains(channel)) {
                // 未知通道不报错、不泄露其它设置项：按「未启用」返回，App 侧静默
                return emptyVersion();
            }
            key = "app_version_" + channel;
        }
        return readVersion(key);
    }

    /** 允许的版本通道（channel 参数值）。新增原生/平板/桌面等独立发版通道时在这里登记 */
    private static final java.util.Set<String> APP_VERSION_CHANNELS = java.util.Set.of("native", "desktop");

    private Map<String, Object> emptyVersion() {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("versionCode", 0);
        r.put("versionName", "");
        r.put("url", "");
        r.put("notes", "");
        r.put("checksum", "");
        r.put("force", false);
        return r;
    }

    private Map<String, Object> readVersion(String settingKey) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        int code = 0;
        String name = "", url = "", notes = "";
        JsonNode n = null;
        boolean force = false;
        SysSetting s = settingRepo.findByKey(settingKey).orElse(null);
        if (s != null && s.getValue() != null && !s.getValue().isBlank()) {
            n = parse(s.getValue());
            Integer c = intClamped(n, "versionCode");
            if (c != null) code = c;
            name = nvl(str(n, "versionName"));
            url = nvl(str(n, "url"));
            notes = nvl(str(n, "notes"));
            force = Boolean.TRUE.equals(bool(n, "force"));
        }
        r.put("versionCode", code);
        r.put("versionName", name);
        r.put("url", url);
        r.put("notes", notes);
        // 安装包 SHA-256：App 下载完成后强制比对，防止包体被替换（外部复审 2026-10-09）
        r.put("checksum", nvl(str(n, "checksum")));
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
        SysSetting s = settingRepo.findByKey("app_bundle").orElse(null);
        JsonNode n = (s == null) ? null : parse(s.getValue());
        r.put("version", nvl(str(n, "version")));
        r.put("url", nvl(str(n, "url")));
        r.put("checksum", nvl(str(n, "checksum")));
        // 热更包签名（RS256，覆盖 version\nurl\nchecksum）：App 内置公钥验签，缺签名一律拒绝
        r.put("signature", nvl(str(n, "signature")));
        r.put("sigAlg", nvl(str(n, "sigAlg")));
        r.put("notes", nvl(str(n, "notes")));
        return r;
    }
}
