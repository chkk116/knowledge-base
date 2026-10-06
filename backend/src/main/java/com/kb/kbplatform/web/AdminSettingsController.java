/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.repository.SysSettingRepository;
import com.kb.kbplatform.security.TokenClaims;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * 系统设置 · 通用键值配置（管理员可写）。
 * 目前用于全局水印参数；后续其它「后台统一配置」也可复用本表。
 * 全部接口受细粒度权限 CORE_MAINTAIN 保护（AuthFilter 已兜底要求 ADMIN 角色）。
 */
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    private final SysSettingRepository settingRepo;

    public AdminSettingsController(SysSettingRepository settingRepo) {
        this.settingRepo = settingRepo;
    }

    private void requirePerm(TokenClaims c) {
        if (c == null || !c.hasPerm("CORE_MAINTAIN")) {
            throw new ResponseStatusException(FORBIDDEN, "需要「核心维护」权限");
        }
    }

    /** 读取某个设置项的值（value 为 JSON 字符串）。
     *  读接口对所有登录用户开放（全局水印需套在每个人屏幕上），写接口才需 CORE_MAINTAIN。
     *  设置项不存在时返回空 value（前端用本地默认值兜底），不返回 404，避免启动期 / 首次使用噪音。 */
    @GetMapping("/{key}")
    public Map<String, Object> get(@RequestAttribute("kbUser") TokenClaims c,
                                   @PathVariable("key") String key) {
        SysSetting s = settingRepo.findByKey(key).orElse(null);
        String value = (s != null && s.getValue() != null) ? s.getValue() : "";
        return Map.of("key", key, "value", value);
    }

    /**
     * 站点页脚安全约束：items 的链接只允许 http/https 绝对地址——登录页对所有访客渲染这些链接，
     * 允许 javascript: 即构成对全部访客的 XSS（2026-10-06 外部安全审查）。
     * 顺带限条数与单条长度，防配置被写爆。
     */
    /** Spring Boot 4 的 Jackson 3 坐标是 tools.jackson.*（com.fasterxml 不在 classpath）；
     *  这里用容器同款 JsonMapper 真正解析后校验——正则扫原始文本会被 JSON 转义绕过
     *  （键名写成 \u0075rl 时正则匹配不到、JSON.parse 之后却是 url）。 */
    private static final tools.jackson.databind.json.JsonMapper SITE_JSON =
            tools.jackson.databind.json.JsonMapper.builder().build();

    /**
     * 校验并**规范化重写** site_info：解析 → 逐字段校验 → 只保留已知字段后重新序列化。
     *
     * 为什么要重写而不是「只校验」：只校验原始文本会留下两类绕过（2026-10-06 复审指出）——
     *   ① 别名键：前端读取时兼容 `it.url ?? it.link ?? it.text`，只校验 url 时 link 可夹带 javascript:；
     *   ② 类型/结构差异：items 不是数组时提前 return，等于完全不校验。
     * 重写后只可能存在 name/url 两个键，绕过面归零。
     *
     * @return 规范化后的 JSON 字符串（调用方应写回该值，而非原始入参）
     */
    @SuppressWarnings("unchecked")
    private static String sanitizeSiteInfo(String value) {
        if (value == null) return null;
        if (value.length() > 20000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "site_info 过长");
        }
        Map<String, Object> root;
        try {
            root = SITE_JSON.readValue(value, Map.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "site_info 不是合法 JSON");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", Boolean.parseBoolean(String.valueOf(root.get("enabled"))));
        out.put("icpNo", clip(root.get("icpNo"), 100));
        out.put("policeNo", clip(root.get("policeNo"), 100));
        // 旧字段（一次性迁移用）：同样校验，避免成为绕过通道
        String gitee = clip(root.get("giteeUrl"), 500);
        requireHttpOrEmpty(gitee, "页脚仓库地址");
        out.put("giteeUrl", gitee);
        out.put("copyright", clip(root.get("copyright"), 300));

        Object itemsObj = root.get("items");
        List<Map<String, Object>> clean = new ArrayList<>();
        if (itemsObj != null) {
            if (!(itemsObj instanceof List<?> items)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "items 必须是数组");
            }
            if (items.size() > 20) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页脚自定义内容最多 20 条");
            }
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> m)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "items 每一项必须是对象");
                }
                String name = clip(m.get("name"), 200);
                String url = clip(m.get("url"), 500);
                requireHttpOrEmpty(url, "页脚链接");
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("name", name);
                one.put("url", url);
                clean.add(one);   // 只保留 name/url：link、text 等别名键在此被丢弃
            }
        }
        out.put("items", clean);
        try {
            return SITE_JSON.writeValueAsString(out);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "site_info 序列化失败");
        }
    }

    /** 取字符串并截断校验（非字符串一律转字符串） */
    private static String clip(Object o, int max) {
        String s = o == null ? "" : String.valueOf(o).trim();
        if (s.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页脚内容过长（单字段≤" + max + "）");
        }
        return s;
    }

    /** 空值放行；非空必须是 http/https 绝对地址（杜绝 javascript: 等 XSS 载体） */
    private static void requireHttpOrEmpty(String url, String what) {
        if (url.isEmpty()) return;
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + "只允许 http/https 绝对地址");
        }
    }

    /** 写入 / 覆盖某个设置项（请求体 { value }） */
    @PutMapping("/{key}")
    public Map<String, Object> put(@RequestAttribute("kbUser") TokenClaims c,
                                   @PathVariable("key") String key,
                                   @RequestBody Map<String, String> body) {
        requirePerm(c);
        String value = body == null ? null : body.get("value");
        if (value == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "value 不能为空");
        // site_info 走「校验 + 规范化重写」：写库的是重写后的严格结构，原始入参不落库
        if ("site_info".equals(key)) value = sanitizeSiteInfo(value);
        SysSetting s = settingRepo.findByKey(key).orElse(new SysSetting(key, value));
        s.setValue(value);
        s.setUpdatedAt(LocalDateTime.now());
        settingRepo.save(s);
        return Map.of("key", key, "value", s.getValue());
    }
}
