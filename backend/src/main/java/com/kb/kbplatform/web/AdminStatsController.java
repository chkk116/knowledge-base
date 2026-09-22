/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/stats")
public class AdminStatsController {

    private final StatsService statsSvc;

    public AdminStatsController(StatsService statsSvc) {
        this.statsSvc = statsSvc;
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「" + code + "」权限");
        }
    }

    /** 后台数据看板：各项聚合计数（需 STATS_VIEW 权限或 ADMIN） */
    @GetMapping
    public Map<String, Object> stats(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "STATS_VIEW");
        return statsSvc.getStats();
    }
}
