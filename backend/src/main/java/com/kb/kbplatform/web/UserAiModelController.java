/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.UserAiModelService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 普通用户的自定义 AI 模型（个人设置）。
 * 所有操作都按当前登录用户归属，互不干扰。
 */
@RestController
@RequestMapping("/api/ai/my-models")
public class UserAiModelController {

    private final UserAiModelService svc;

    public UserAiModelController(UserAiModelService svc) {
        this.svc = svc;
    }

    @GetMapping
    public List<Map<String, Object>> list(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.list(c.userId());
    }

    @PostMapping
    public Map<String, Object> create(@RequestAttribute("kbUser") TokenClaims c,
                                      @RequestBody Map<String, Object> body) {
        return svc.create(c.userId(), body);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long id,
                                      @RequestBody Map<String, Object> body) {
        return svc.update(c.userId(), id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long id) {
        svc.delete(c.userId(), id);
        return Map.of("ok", true);
    }
}
