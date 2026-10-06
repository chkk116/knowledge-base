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
    private static void sanitizeSiteInfo(String value) {
        if (value == null) return;
        if (value.length() > 20000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "site_info 过长");
        }
        // 逐个扫描 "url":"..." 字段值：只允许 http/https 绝对地址。
        // 不用 JSON 库的原因：Spring Boot 4 的 Jackson 3 坐标是 tools.jackson.*，本工程未直接依赖；
        // 且此处的 value 由前端 JSON.stringify 生成、格式规整，正则提取足够（与 PublicConfigController 同风格）。
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"url\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(value);
        int n = 0;
        while (m.find()) {
            if (++n > 20) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页脚自定义内容最多 20 条");
            }
            String url = m.group(1).trim();
            if (url.length() > 500) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页脚链接过长（≤500）");
            }
            if (!url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页脚链接只允许 http/https 绝对地址");
            }
        }
        if (value.length() > 4000 && value.split("\"name\"", -1).length - 1 > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "页脚自定义内容最多 20 条");
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
