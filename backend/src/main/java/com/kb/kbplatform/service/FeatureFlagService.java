/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.FeatureFlag;
import com.kb.kbplatform.repository.FeatureFlagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 核心维护 · 功能开关读写。
 * 开关状态存 kb_feature_flag，默认值由 DataInitializer 注入（全部开启）。
 */
@Service
public class FeatureFlagService {

    private final FeatureFlagRepository repo;

    public FeatureFlagService(FeatureFlagRepository repo) {
        this.repo = repo;
    }

    /** 列出全部功能开关（核心维护页用） */
    public List<FeatureFlag> listAll() {
        return repo.findAll();
    }

    /** 批量读取开关状态：featureKey → 状态对象，供前端路由守卫与菜单判断（含关闭模式与维护信息） */
    public java.util.Map<String, FeatureState> mapStates() {
        java.util.Map<String, FeatureState> m = new java.util.HashMap<>();
        for (FeatureFlag f : repo.findAll()) {
            m.put(f.getFeatureKey(), new FeatureState(f.isEnabled(), f.getCloseMode(),
                    f.getMaintenanceMsg(), f.getExpectedRestoreAt()));
        }
        return m;
    }

    /** 前端批量状态对象：enabled + 关闭模式 + 维护公告 + 预计恢复时间 */
    public static class FeatureState {
        public boolean enabled;
        public String closeMode;
        public String maintenanceMsg;
        public java.time.LocalDateTime expectedRestoreAt;
        public FeatureState(boolean enabled, String closeMode, String maintenanceMsg,
                            java.time.LocalDateTime expectedRestoreAt) {
            this.enabled = enabled;
            this.closeMode = closeMode;
            this.maintenanceMsg = maintenanceMsg;
            this.expectedRestoreAt = expectedRestoreAt;
        }
    }

    /**
     * 幂等注入功能开关默认值（供 DataInitializer 启动调用）。
     * 必须自有事务：已存在 feature_key 跳过不覆盖，仅补齐缺失项；
     * 跨 bean 调用确保 @Transactional 由 Spring 代理正常开启，避免
     * ApplicationRunner 自调用导致的事务失效（No active transaction）。
     */
    @Transactional
    public void ensureDefaults(java.util.List<FeatureFlag> defaults) {
        for (FeatureFlag def : defaults) {
            if (repo.findByFeatureKey(def.getFeatureKey()).isEmpty()) {
                repo.save(def);
            }
        }
    }

    /** 切换某功能开关；返回切换后的记录 */
    @Transactional
    public FeatureFlag setEnabled(String featureKey, boolean enabled) {
        FeatureFlag f = repo.findByFeatureKey(featureKey)
                .orElseThrow(() -> new IllegalArgumentException("未知功能：" + featureKey));
        f.setEnabled(enabled);
        f.setUpdatedAt(java.time.LocalDateTime.now());
        return repo.save(f);
    }

    /**
     * 编辑某功能的完整维护状态（核心维护页「编辑」用）：
     * 可改 enabled / 维护公告 / 预计恢复时间 / 跳转的后台管理页，其余字段（featureKey/label/description/groupKey）不允许改。
     */
    @Transactional
    public FeatureFlag updateFeature(String featureKey, FeatureEditReq req) {
        FeatureFlag f = repo.findByFeatureKey(featureKey)
                .orElseThrow(() -> new IllegalArgumentException("未知功能：" + featureKey));
        if (req.enabled != null) f.setEnabled(req.enabled);
        if (req.closeMode != null) f.setCloseMode(req.closeMode);
        f.setMaintenanceMsg(req.maintenanceMsg);
        f.setExpectedRestoreAt(req.expectedRestoreAt);
        f.setAdminPage(req.adminPage);
        f.setUpdatedAt(java.time.LocalDateTime.now());
        return repo.save(f);
    }

    /** 核心维护 · 编辑请求体 */
    public static class FeatureEditReq {
        public Boolean enabled;
        public String closeMode;
        public String maintenanceMsg;
        public java.time.LocalDateTime expectedRestoreAt;
        public String adminPage;
    }
}
