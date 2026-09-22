/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.FeatureFlag;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuditService;
import com.kb.kbplatform.service.FeatureFlagService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * 核心维护 · 功能开关管理。
 * 全部接口受细粒度权限 CORE_MAINTAIN 保护（AuthFilter 已兜底要求 ADMIN 角色）。
 */
@RestController
@RequestMapping("/api/admin/features")
public class AdminFeatureController {

    private final FeatureFlagService flagSvc;
    private final AuditService audit;

    public AdminFeatureController(FeatureFlagService flagSvc, AuditService audit) {
        this.flagSvc = flagSvc;
        this.audit = audit;
    }

    private void requirePerm(TokenClaims c) {
        if (c == null || !c.hasPerm("CORE_MAINTAIN")) {
            throw new ResponseStatusException(FORBIDDEN, "需要「核心维护」权限");
        }
    }

    /** 全部功能开关列表 */
    @GetMapping
    public List<FeatureFlag> list(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c);
        return flagSvc.listAll();
    }

    /** 批量开关状态（featureKey → 状态对象），供前端路由守卫与菜单一次性拉取。
     *  任何登录用户可读（AuthFilter 已对该只读接口仅要求登录）；修改接口仍需 CORE_MAINTAIN。 */
    @GetMapping("/map")
    public Map<String, FeatureFlagService.FeatureState> map(@RequestAttribute("kbUser") TokenClaims c) {
        return flagSvc.mapStates();
    }

    /** 编辑单个功能的完整维护状态（核心维护页「编辑」用）。
     *  请求体兼容旧字段：仅传 enabled 等价于「切换开关」。 */
    @PutMapping("/{key}")
    public FeatureFlag update(@RequestAttribute("kbUser") TokenClaims c,
                              @PathVariable("key") String key,
                              @RequestBody EditReq r) {
        requirePerm(c);
        FeatureFlag before = flagSvc.listAll().stream()
                .filter(f -> f.getFeatureKey().equals(key)).findFirst().orElse(null);
        boolean beforeEnabled = before != null && before.isEnabled();

        FeatureFlag f = flagSvc.updateFeature(key, new FeatureFlagService.FeatureEditReq() {{
            this.enabled = r.enabled;
            this.closeMode = r.closeMode;
            this.maintenanceMsg = r.maintenanceMsg;
            this.expectedRestoreAt = r.expectedRestoreAt;
            this.adminPage = r.adminPage;
        }});

        // 审计：状态变化才记开关动作，编辑公告/恢复时间/关闭模式也记一条
        if (r.enabled != null && r.enabled != beforeEnabled) {
            audit.log(c.userId(), c.username(), "TOGGLE_FEATURE", "FEATURE",
                    null, f.getLabel(),
                    (f.isEnabled() ? "开启" : "关闭") + "功能「" + f.getLabel() + "」（模式：" + f.getCloseMode() + "）");
        } else if (r.closeMode != null || r.maintenanceMsg != null || r.expectedRestoreAt != null || r.adminPage != null) {
            audit.log(c.userId(), c.username(), "EDIT_FEATURE", "FEATURE",
                    null, f.getLabel(), "编辑功能「" + f.getLabel() + "」维护信息");
        }
        return f;
    }

    public static class EditReq {
        public Boolean enabled;
        public String closeMode;
        public String maintenanceMsg;
        public java.time.LocalDateTime expectedRestoreAt;
        public String adminPage;
    }
}
