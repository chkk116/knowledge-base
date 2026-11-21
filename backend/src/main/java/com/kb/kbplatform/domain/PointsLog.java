/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "kb_points_log",
        uniqueConstraints = @jakarta.persistence.UniqueConstraint(
                name = "uk_points_log_ref", columnNames = {"user_id", "type", "ref"}),
        indexes = @jakarta.persistence.Index(
                name = "idx_points_user", columnList = "userId,createdAt"))
public class PointsLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String username;

    private int delta;            // 积分变动量（正=奖励，负=扣减）

    @Column(length = 32)
    private String type;          // LOGIN / PUBLISH / HOT / ADJUST / SYSTEM

    @Column(length = 200)
    private String reason;        // 变动说明（如「发布《标题》」）

    /** 防重复发放的幂等键（如 paper:5 / chapter:12），随 type 一起构成唯一约束；
     *  登录 / 发文这类天然一次一条的不带 ref（NULL 不参与唯一判定）。 */
    @Column(length = 64)
    private String ref;

    private int balance;          // 变动后的积分余额

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public int getDelta() { return delta; }
    public void setDelta(int delta) { this.delta = delta; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getRef() { return ref; }
    public void setRef(String ref) { this.ref = ref; }
    public int getBalance() { return balance; }
    public void setBalance(int balance) { this.balance = balance; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
