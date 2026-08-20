package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "kb_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;   // 存储格式：salt:sha256hex（前端加盐 + SHA-256）

    private String role = "USER";   // ADMIN / USER
    private String permissions = "";   // 细粒度权限码（逗号分隔，如 USER_MANAGE,DOC_REVIEW）；ADMIN 角色隐式拥有全部
    private String displayName;

    @Column(length = 2000)
    private String avatar;   // 头像：URL 或 data URI（前端上传后转 base64 存储）

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


    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

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
}

