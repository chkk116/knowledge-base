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
     *  wxUrl/supportUrl 为链接生成模式；wxImage/supportImage 为后台直接上传的二维码图片（/uploads/...），存在时优先显示 */
    @GetMapping("/login-config")
    public Map<String, String> loginConfig() {
        String wx = DEF_WX;
        String support = DEF_SUPPORT;
        Map<String, String> r = new java.util.LinkedHashMap<>();
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
}
