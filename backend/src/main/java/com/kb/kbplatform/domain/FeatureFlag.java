package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 核心维护 · 功能开关。
 * 每个前端功能（AI 问答 / 培训 / 考试 / 共享文档 …）一行记录，
 * enabled=true 表示该功能对外开放（正常态），false 表示管理员在「核心维护」中手动关闭。
 * 表由 JPA（ddl-auto: update）自动创建，默认值由 DataInitializer 注入。
 */
@Entity
@Table(name = "kb_feature_flag")
public class FeatureFlag {

    /** 功能唯一键（与前端路由 meta.maintenance 对应），如 ai_chat / courses / exams */
    @Id
    @Column(name = "feature_key", length = 64)
    private String featureKey;

    /** 是否开启（对外开放）；false = 维护中，前端入口隐藏并跳「敬请期待」 */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 功能中文名（核心维护页展示） */
    @Column(nullable = false, length = 64)
    private String label;

    /** 功能说明 / 关闭理由占位（核心维护页展示） */
    @Column(length = 255)
    private String description;

    /** 所属分组（核心维护页按组归类）：ai / learning / collaboration / community / core */
    @Column(length = 32)
    private String groupKey = "core";

    /** 维护公告：关闭时展示给用户的说明（如「AI 模型训练中，预计今日恢复」） */
    @Column(length = 255)
    private String maintenanceMsg;

    /** 预计恢复时间（可选）：关闭时展示，便于用户预期；null 表示未定 */
    private LocalDateTime expectedRestoreAt;

    /** 对应的后台管理页路由（可选）：核心维护页可一键跳转去管理该功能，如 /admin/courses */
    @Column(length = 128)
    private String adminPage;

    /**
     * 关闭模式（仅 enabled=false 时有意义）：
     *  - HIDDEN：隐藏入口，直接访问跳「敬请期待」（默认）
     *  - MAINTENANCE：入口保留，访问时展示「维护中」界面（公告 + 预计恢复时间）
     */
    @Column(length = 16, nullable = false)
    private String closeMode = "HIDDEN";

    private LocalDateTime updatedAt = LocalDateTime.now();

    public FeatureFlag() {}

    public FeatureFlag(String featureKey, String label, String description, String groupKey) {
        this.featureKey = featureKey;
        this.label = label;
        this.description = description;
        this.groupKey = groupKey;
    }

    public FeatureFlag(String featureKey, String label, String description, String groupKey, String adminPage) {
        this.featureKey = featureKey;
        this.label = label;
        this.description = description;
        this.groupKey = groupKey;
        this.adminPage = adminPage;
    }

    public String getFeatureKey() { return featureKey; }
    public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }
    public String getMaintenanceMsg() { return maintenanceMsg; }
    public void setMaintenanceMsg(String maintenanceMsg) { this.maintenanceMsg = maintenanceMsg; }
    public LocalDateTime getExpectedRestoreAt() { return expectedRestoreAt; }
    public void setExpectedRestoreAt(LocalDateTime expectedRestoreAt) { this.expectedRestoreAt = expectedRestoreAt; }
    public String getAdminPage() { return adminPage; }
    public void setAdminPage(String adminPage) { this.adminPage = adminPage; }
    public String getCloseMode() { return closeMode; }
    public void setCloseMode(String closeMode) { this.closeMode = closeMode; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
