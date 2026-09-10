package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Entity
@Table(name = "kb_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    @JsonIgnore   // 安全：任何接口不得把口令哈希（salt:sha256）序列化到响应体
    private String password;   // 存储格式：salt:sha256hex（前端加盐 + SHA-256）

    private String role = "USER";   // ADMIN / USER
    private String permissions = "";   // 细粒度权限码（逗号分隔，如 USER_MANAGE,DOC_REVIEW）；ADMIN 角色隐式拥有全部
    private String displayName;

    @Column(length = 2000)
    private String avatar;   // 头像：URL 或 data URI（前端上传后转 base64 存储），仅存「已审核通过」的头像

    /** 待审核的新头像 URL：审核通过才写入 avatar；驳回则清空。展示侧只读 avatar，天然不泄露未过审图 */
    @Column(length = 2000)
    private String avatarPending;

    /** 头像审核状态：APPROVED / PENDING / REJECTED（null 视同 APPROVED，兼容存量用户） */
    @Column(length = 20)
    private String avatarStatus;

    @Column(length = 120)
    private String email;    // 邮箱

    @Column(columnDefinition = "text")
    private String bio;      // 个人简介

    @Column(length = 10)
    private String gender;   // 性别：男 / 女 / 保密（可空，空表示不填）

    private boolean hideGender = false;   // 是否对外隐藏性别（隐私开关，默认公开）

    private boolean enabled = true;   // 是否启用（管理员可禁用账号）

    private int points = 0;   // 积分（登录/发帖/热帖奖励累计）

    private LocalDate lastLoginDate;   // 最近登录日期（每日登录发分去重，避免重复刷分）

    private LocalDateTime createdAt = LocalDateTime.now();   // 注册时间

    private long tokenVersion = 0L;   // 令牌版本号：改密/重置密码时自增，使该用户所有旧 token 立即失效

    @Column(nullable = true)
    private Long storageQuota;   // 管理员分配的个人存储总容量（字节）；null = 使用系统默认 5GB

    @Column(name = "department_id")
    private Long departmentId;   // 所属部门（kb_department.id），可空表示未分配部门

    @Column(columnDefinition = "text")
    private String aiPersona;   // 用户 AI 偏好/习惯（JSON：语气、关注领域、自定义指令、回复长度等）

    // 佩戴展示的徽章 id 列表（逗号分隔，最多 3 枚；仅可佩戴已获得徽章；null/空 = 未佩戴）
    @Column(length = 64)
    private String displayBadge;


    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    @JsonIgnore
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getAvatarPending() { return avatarPending; }
    public void setAvatarPending(String avatarPending) { this.avatarPending = avatarPending; }

    public String getAvatarStatus() { return avatarStatus; }
    public void setAvatarStatus(String avatarStatus) { this.avatarStatus = avatarStatus; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public boolean isHideGender() { return hideGender; }
    public void setHideGender(boolean hideGender) { this.hideGender = hideGender; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPoints() { return points; }
    public void setPoints(int points) { this.points = points; }

    public LocalDate getLastLoginDate() { return lastLoginDate; }
    public void setLastLoginDate(LocalDate lastLoginDate) { this.lastLoginDate = lastLoginDate; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public long getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(long tokenVersion) { this.tokenVersion = tokenVersion; }

    public Long getStorageQuota() { return storageQuota; }
    public void setStorageQuota(Long storageQuota) { this.storageQuota = storageQuota; }

    public Long getDepartmentId() { return departmentId; }
    public void setDepartmentId(Long departmentId) { this.departmentId = departmentId; }

    public String getAiPersona() { return aiPersona; }
    public void setAiPersona(String aiPersona) { this.aiPersona = aiPersona; }

    public String getDisplayBadge() { return displayBadge; }
    public void setDisplayBadge(String displayBadge) { this.displayBadge = displayBadge; }

    /** 最多同时佩戴的勋章数 */
    public static final int MAX_WORN = 3;

    /** 把逗号分隔的佩戴列表解析为有序、去空白、去重的数组（与前端逻辑一致） */
    public List<String> getWornList() {
        if (displayBadge == null || displayBadge.isBlank()) return new ArrayList<>();
        return Arrays.stream(displayBadge.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** 写入佩戴列表：去重、保序、截断到 MAX_WORN 枚，空列表则清空字段 */
    public void setWornList(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            displayBadge = null;
            return;
        }
        List<String> clean = ids.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .limit(MAX_WORN)
                .collect(Collectors.toList());
        displayBadge = clean.isEmpty() ? null : String.join(",", clean);
    }
}

