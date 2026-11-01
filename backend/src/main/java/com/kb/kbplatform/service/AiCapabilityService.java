/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AiCapability;
import com.kb.kbplatform.domain.AiModel;
import com.kb.kbplatform.repository.AiCapabilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 能力配置：文生图 / 文生视频 / OCR 各自一套「平台 + 端点 + Key + 模型名」。
 *
 * 与「AI 模型管理」（kb_ai_model，对话模型池）**完全独立**，互不干扰：
 * 这里配置的 Key 只用于对应能力，不会出现在对话模型下拉里，反之亦然。
 * 未配置时业务侧仍按原逻辑兜底（媒体取模型池、OCR 走视觉模型链），不会因为没配就不可用。
 *
 * 密钥用 AiKeyCipher 加密后落库；管理页只回显掩码。
 */
@Service
public class AiCapabilityService {

    public static final String IMAGE = "image";
    public static final String VIDEO = "video";
    public static final String OCR = "ocr";
    private static final List<String> ALL = List.of(IMAGE, VIDEO, OCR);

    private final AiCapabilityRepository repo;
    private final AiKeyCipher cipher;

    public AiCapabilityService(AiCapabilityRepository repo, AiKeyCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    /** 解析后的可用配置（apiKey 已解密） */
    public record Resolved(String provider, String baseUrl, String apiKey, String model) {}

    /** 取某项能力的配置；未配置/已禁用/缺 Key 或模型时返回 null，调用方走原兜底 */
    public Resolved resolve(String capability) {
        AiCapability c = repo.findByCapability(capability).orElse(null);
        if (c == null || !c.isEnabled()) return null;
        String key = cipher.decrypt(c.getApiKey());
        if (key == null || key.isBlank()) return null;
        if (c.getModel() == null || c.getModel().isBlank()) return null;
        return new Resolved(c.getProvider(), c.getBaseUrl(), key, c.getModel().trim());
    }

    /**
     * 把能力配置包装成 AiModel 供媒体服务复用（Key 重新加密，因为下游会 decrypt）。
     * 未配置时返回 null。
     */
    public AiModel asModel(String capability) {
        Resolved r = resolve(capability);
        if (r == null) return null;
        AiModel m = new AiModel();
        m.setName("能力配置-" + capability);
        m.setProvider(r.provider());
        m.setBaseUrl(r.baseUrl());
        m.setModel(r.model());
        m.setApiKey(cipher.encrypt(r.apiKey()));
        m.setEnabled(true);
        m.setKind(IMAGE.equals(capability) ? "IMAGE" : "VIDEO");
        return m;
    }

    /** 管理页：三项能力的当前配置（Key 只回显掩码） */
    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String cap : ALL) {
            AiCapability c = repo.findByCapability(cap).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("capability", cap);
            if (c == null) {
                m.put("configured", false);
                out.put(cap, m);
                continue;
            }
            String plain = cipher.decrypt(c.getApiKey());
            boolean configured = plain != null && !plain.isBlank()
                    && c.getModel() != null && !c.getModel().isBlank();
            m.put("configured", configured);
            m.put("enabled", c.isEnabled());
            m.put("provider", c.getProvider());
            m.put("baseUrl", c.getBaseUrl());
            m.put("model", c.getModel());
            m.put("keyMasked", mask(plain));
            m.put("updatedAt", c.getUpdatedAt());
            out.put(cap, m);
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> save(CapReq image, CapReq video, CapReq ocr) {
        saveOne(IMAGE, image);
        saveOne(VIDEO, video);
        saveOne(OCR, ocr);
        return view();
    }

    private void saveOne(String cap, CapReq r) {
        if (r == null) return;
        AiCapability c = repo.findByCapability(cap).orElseGet(() -> {
            AiCapability n = new AiCapability();
            n.setCapability(cap);
            n.setEnabled(true);
            return n;
        });
        if (r.provider != null) c.setProvider(r.provider.trim());
        if (r.baseUrl != null) c.setBaseUrl(r.baseUrl.trim());
        if (r.model != null) c.setModel(r.model.trim());
        if (r.enabled != null) c.setEnabled(r.enabled);
        if (Boolean.TRUE.equals(r.clearKey)) {
            c.setApiKey(null);
        } else if (r.apiKey != null && !r.apiKey.isBlank()) {
            c.setApiKey(cipher.encrypt(r.apiKey.trim()));
        }
        c.setUpdatedAt(LocalDateTime.now());

        if (c.isEnabled()) {
            if (c.getModel() == null || c.getModel().isBlank()) {
                throw new IllegalArgumentException(label(cap) + "：请填写模型名");
            }
            String key = cipher.decrypt(c.getApiKey());
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException(label(cap) + "：请填写 API Key");
            }
        }
        repo.save(c);
    }

    private static String label(String cap) {
        return IMAGE.equals(cap) ? "文生图" : VIDEO.equals(cap) ? "文生视频" : "OCR";
    }

    private static String mask(String plain) {
        if (plain == null || plain.isBlank()) return "";
        if (plain.length() <= 8) return "****";
        return plain.substring(0, 4) + "****" + plain.substring(plain.length() - 4);
    }

    /** 单张能力配置的提交体 */
    public static class CapReq {
        public String provider;
        public String baseUrl;
        public String apiKey;
        public String model;
        public Boolean enabled;
        public Boolean clearKey;
    }
}
