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

    @SuppressWarnings("unchecked")
    private static void sanitizeSiteInfo(String value) {
        if (value == null) return;
        if (value.length() > 20000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "site_info 过长");
        }
        Map<String, Object> root;
        try {
            root = SITE_JSON.readValue(value, Map.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "site_info 不是合法 JSON");
        }
        Object itemsObj = root.get("items");
        if (!(itemsObj instanceof List<?> items)) return;   // 无 items = 只配备案号，不校验
        if (items.size() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页脚自定义内容最多 20 条");
        }
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object nameObj = m.get("name");
            Object urlObj = m.get("url");
            String name = nameObj == null ? "" : String.valueOf(nameObj);
            String url = urlObj == null ? "" : String.valueOf(urlObj).trim();
            if (name.length() > 200 || url.length() > 500) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页脚内容过长（名称≤200，链接≤500）");
            }
            if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页脚链接只允许 http/https 绝对地址");
            }
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
        if ("site_info".equals(key)) sanitizeSiteInfo(value);
        SysSetting s = settingRepo.findByKey(key).orElse(new SysSetting(key, value));
        s.setValue(value);
        s.setUpdatedAt(LocalDateTime.now());
        settingRepo.save(s);
        return Map.of("key", key, "value", s.getValue());
    }
}
