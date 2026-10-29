/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 私信开关：user_id 主键，一人一条；没有记录视为默认允许 */
@Entity
@Table(name = "kb_dm_setting")
public class DmSetting {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "allow_dm", nullable = false)
    private boolean allowDm = true;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public boolean isAllowDm() { return allowDm; }
    public void setAllowDm(boolean allowDm) { this.allowDm = allowDm; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
