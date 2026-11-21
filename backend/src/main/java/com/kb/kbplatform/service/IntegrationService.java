/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kb.kbplatform.repository.SysSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 集成中心：可选第三方能力的配置存取（第一单品：TRTC Chat 离线推送）。
 *
 * 设计口径与 AI 多模型一致：配置存 sys_setting 表（PG 持久化，重启/Redis 故障都不丢）、
 * 密钥 AES 加密、对外脱敏回显，未启用时不影响任何主流程（私有部署不配置 = 完全没有这个功能）。
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);
    private static final String SETTING_PREFIX = "integration.";

    private final SysSettingRepository settingRepo;
    private final AiKeyCipher cipher;
    private final ObjectMapper om = new ObjectMapper();

    public IntegrationService(SysSettingRepository settingRepo, AiKeyCipher cipher) {
        this.settingRepo = settingRepo;
        this.cipher = cipher;
    }

    /** 原始配置（secretKey 是解密后的明文，只在服务端内部使用）；没有 secretKeyEnc 时 secretKey 为 null */
    public Map<String, Object> load(String key) {
        String raw = settingRepo.findByKey(SETTING_PREFIX + key)
                .map(com.kb.kbplatform.domain.SysSetting::getValue).orElse(null);
        if (raw == null || raw.isBlank()) return null;
        try {
            Map<String, Object> cfg = om.readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
            Object enc = cfg.remove("secretKeyEnc");
            if (enc != null) cfg.put("secretKey", cipher.decrypt(String.valueOf(enc)));
            return cfg;
        } catch (Exception e) {
            log.warn("集成配置解析失败 {}: {}", key, e.getMessage());
            return null;
        }
    }

    /** 脱敏视图（给管理页回显）：secretKey 只显示尾 4 位 */
    public Map<String, Object> view(String key) {
        Map<String, Object> cfg = load(key);
        if (cfg == null) return Map.of("key", key, "enabled", false);
        cfg.put("key", key);
        cfg.put("configured", true);
        Object sk = cfg.get("secretKey");
        if (sk != null) cfg.put("secretKey", sk.toString().length() > 8
                ? "****" + sk.toString().substring(sk.toString().length() - 4) : "****");
        return cfg;
    }

    /**
     * 保存：以现有配置为底、覆盖请求里出现的字段（merge，不是整体替换——
     * 否则只传 {"enabled":true} 会把 sdkAppId/adminId 全清掉）。
     * secretKey 带 **** 或为空 = 不改原值；首次配置必须给明文。
     * 并发保存由 sys_setting 的主键约束兜底（后到的失败重试即可），管理端单人操作概率极低。
     */
    public void save(String key, Map<String, Object> body) {
        Map<String, Object> existing = load(key);
        Map<String, Object> merged = new HashMap<>();
        if (existing != null) merged.putAll(existing);
        if (body.get("sdkAppId") != null && !String.valueOf(body.get("sdkAppId")).isBlank()) {
            merged.put("sdkAppId", String.valueOf(body.get("sdkAppId")).trim());
        }
        if (body.get("adminId") != null && !String.valueOf(body.get("adminId")).isBlank()) {
            merged.put("adminId", String.valueOf(body.get("adminId")).trim());
        }
        if (body.get("enabled") instanceof Boolean b) merged.put("enabled", b);

        String plainKey = null;
        Object sk = body.get("secretKey");
        if (sk != null && !String.valueOf(sk).isBlank() && !String.valueOf(sk).contains("****")) {
            plainKey = String.valueOf(sk).trim();
        } else if (existing != null && existing.get("secretKey") != null) {
            plainKey = String.valueOf(existing.get("secretKey"));
        }
        if (plainKey == null || plainKey.isBlank()) {
            throw new IllegalArgumentException("SecretKey 不能为空（首次配置必填）");
        }
        merged.put("secretKeyEnc", cipher.encrypt(plainKey));
        merged.remove("secretKey");

        try {
            com.kb.kbplatform.domain.SysSetting st = settingRepo.findByKey(SETTING_PREFIX + key).orElseGet(() -> {
                com.kb.kbplatform.domain.SysSetting n = new com.kb.kbplatform.domain.SysSetting();
                n.setKey(SETTING_PREFIX + key);
                return n;
            });
            st.setValue(om.writeValueAsString(merged));
            st.setUpdatedAt(java.time.LocalDateTime.now());
            settingRepo.save(st);
        } catch (Exception e) {
            throw new IllegalStateException("集成配置保存失败：" + e.getMessage(), e);
        }
    }

    public boolean enabled(String key) {
        Map<String, Object> cfg = load(key);
        return cfg != null && Boolean.TRUE.equals(cfg.get("enabled"));
    }
}
