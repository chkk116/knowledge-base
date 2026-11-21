/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.IntegrationService;
import com.kb.kbplatform.service.TrtcPushAdapter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 集成中心：可选第三方能力的配置与测试（第一单品：TRTC Chat 离线推送）。
 * 仅真实 ADMIN 可用（与 AI 模型管理同口径），配置存 sys_setting 表、SecretKey AES 加密。
 */
@RestController
@RequestMapping("/api/admin/integrations")
public class AdminIntegrationController {

    private final IntegrationService integrations;
    private final TrtcPushAdapter trtc;

    public AdminIntegrationController(IntegrationService integrations, TrtcPushAdapter trtc) {
        this.integrations = integrations;
        this.trtc = trtc;
    }

    @GetMapping("/trtc-chat")
    public Map<String, Object> view(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        return integrations.view("trtc-chat");
    }

    @PutMapping("/trtc-chat")
    public Map<String, Object> save(@RequestAttribute("kbUser") TokenClaims c,
                                    @RequestBody Map<String, Object> body) {
        requireAdmin(c);
        integrations.save("trtc-chat", body);
        return Map.of("ok", true);
    }

    /** 测试连接：给当前管理员自己的 TRTC 账号发一条自定义消息（控制台/抓包可查收） */
    @PostMapping("/trtc-chat/test")
    public Map<String, Object> test(@RequestAttribute("kbUser") TokenClaims c) {
        requireAdmin(c);
        if (!trtc.enabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "集成未启用，请先保存配置并开启");
        }
        boolean sent = trtc.sendCustom(c.userId(),
                Map.of("Data", "{\"type\":\"kb-test\",\"msg\":\"TRTC Chat 集成测试\"}"), "知识库集成测试");
        if (!sent) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "发送失败：请检查 SDKAppID / SecretKey / 管理员账号（见后端日志）");
        }
        return Map.of("ok", true, "trtcUserId", TrtcPushAdapter.trtcUserId(c.userId()));
    }

    private void requireAdmin(TokenClaims c) {
        if (!c.isAdmin()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
    }
}
