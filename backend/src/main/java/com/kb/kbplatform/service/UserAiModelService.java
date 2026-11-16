/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.UserAiModel;
import com.kb.kbplatform.repository.UserAiModelRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户私有 AI 模型：增删改查，严格按 userId 归属。
 * 与管理员维护的系统模型（AiModel / kb_ai_model）互不干扰。
 */
@Service
public class UserAiModelService {

    private static final int MAX_PER_USER = 10;

    private final UserAiModelRepository repo;
    private final AiKeyCipher cipher;

    public UserAiModelService(UserAiModelRepository repo, AiKeyCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    public List<Map<String, Object>> list(Long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserAiModel m : repo.findByUserIdOrderBySortOrderAscIdAsc(userId)) out.add(view(m));
        return out;
    }

    public Map<String, Object> create(Long userId, Map<String, Object> body) {
        if (repo.countByUserId(userId) >= MAX_PER_USER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最多配置 " + MAX_PER_USER + " 个自定义模型");
        }
        UserAiModel m = new UserAiModel();
        m.setUserId(userId);
        apply(m, body, true);
        return view(repo.save(m));
    }

    public Map<String, Object> update(Long userId, Long id, Map<String, Object> body) {
        UserAiModel m = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在"));
        apply(m, body, false);
        return view(repo.save(m));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        UserAiModel m = repo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在"));
        repo.delete(m);
    }

    private void apply(UserAiModel m, Map<String, Object> body, boolean creating) {
        String provider = str(body.get("provider"));
        String name = str(body.get("name"));
        String baseUrl = str(body.get("baseUrl"));
        String model = str(body.get("model"));
        String apiKey = str(body.get("apiKey"));
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写模型名称");
        if (model == null || model.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写模型名");
        if (baseUrl == null || baseUrl.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写接口地址");
        if (!isSafeBaseUrl(baseUrl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "接口地址不合法（仅支持公网 http/https）");
        }
        if (creating && (apiKey == null || apiKey.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写 API Key");
        }
        m.setProvider(provider == null || provider.isBlank() ? "CUSTOM" : provider.trim().toUpperCase());
        m.setName(name.trim());
        m.setBaseUrl(baseUrl.trim());
        m.setModel(model.trim());
        if (apiKey != null && !apiKey.isBlank()) m.setApiKey(cipher.encrypt(apiKey.trim()));   // 空 = 保留原 Key
        if (body.get("enabled") instanceof Boolean b) m.setEnabled(b);
        if (body.get("contextWindow") instanceof Number n) m.setContextWindow(n.intValue());
        if (body.get("sortOrder") instanceof Number n) m.setSortOrder(n.intValue());
    }

    private Map<String, Object> view(UserAiModel m) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", m.getId());
        r.put("provider", m.getProvider());
        r.put("name", m.getName());
        r.put("baseUrl", m.getBaseUrl());
        r.put("model", m.getModel());
        r.put("enabled", m.isEnabled());
        r.put("contextWindow", m.getContextWindow());
        r.put("keySet", m.getApiKey() != null && !m.getApiKey().isBlank());
        r.put("keyMasked", m.getApiKey() == null ? "" : cipher.mask(cipher.decrypt(m.getApiKey())));
        r.put("scope", "user");
        return r;
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }

    /** 基础 SSRF 防护：仅 http/https，拒绝回环/内网/链路本地地址（用户填的地址会被服务端请求） */
    static boolean isSafeBaseUrl(String url) {
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return false;
            String host = u.getHost();
            if (host == null || host.isBlank()) return false;
            String h = host.toLowerCase();
            if (h.equals("localhost") || h.endsWith(".local") || h.endsWith(".internal")) return false;
            if (h.equals("0.0.0.0") || h.equals("::1") || h.equals("0")) return false;
            if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.")) return false;
            if (h.startsWith("172.")) {
                String[] parts = h.split("\\.");
                if (parts.length > 1) {
                    try {
                        int second = Integer.parseInt(parts[1]);
                        if (second >= 16 && second <= 31) return false;
                    } catch (Exception ignore) { }
                }
            }
            // 字符串黑名单挡不住 DNS 指向内网的域名（攻击者可控解析）：
            // 必须解析所有 A/AAAA 记录，逐条判定，与 WebClipService 的公开主机校验同口径
            for (java.net.InetAddress a : java.net.InetAddress.getAllByName(host)) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
                if (a instanceof java.net.Inet6Address && (a.getAddress()[0] & 0xFE) == 0xFC) return false; // fc00::/7
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
