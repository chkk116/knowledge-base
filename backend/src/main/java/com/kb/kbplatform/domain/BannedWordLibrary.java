package com.kb.kbplatform.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 违禁词库：把违规词按业务维度分组（如 政治敏感 / 广告导流 / 色情低俗 / 暴恐 / 自定义）。
 * 管理页可增删词库，并通过 enabled 开关「选择加载」——仅启用中的词库进入实时过滤缓存。
 * 借鉴阿里云 / 腾讯云「关键词库 + 策略挂载」的设计。
 */
@Entity
@Table(name = "kb_banned_word_library",
       uniqueConstraints = @UniqueConstraint(name = "uk_lib_name", columnNames = "name"))
public class BannedWordLibrary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private boolean builtin = false;

    @Column(nullable = false)
    private int priority = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    public BannedWordLibrary() {}

    public BannedWordLibrary(String name, String description, boolean enabled, boolean builtin) {
        this.name = name;
        this.description = description;
        this.enabled = enabled;
        this.builtin = builtin;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isBuiltin() { return builtin; }
    public void setBuiltin(boolean builtin) { this.builtin = builtin; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
