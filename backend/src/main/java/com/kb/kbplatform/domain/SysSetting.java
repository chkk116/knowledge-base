/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 通用键值配置（系统设置）。
 * 用于存放「后台统一配置、全站生效」的参数，例如全局水印参数。
 * key 唯一；value 为 JSON 字符串（由前端序列化 / 反序列化）。
 * 表由 JPA（ddl-auto: update）自动创建，无需手动建表。
 */
@Entity
@Table(name = "kb_sys_setting")
public class SysSetting {

    /** 设置项唯一键，如 watermark */
    @Id
    @Column(name = "setting_key", length = 64)
    private String key;

    /** 配置值（JSON 字符串，前端序列化 / 反序列化） */
    @Column(name = "setting_value", columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public SysSetting() {}

    public SysSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
