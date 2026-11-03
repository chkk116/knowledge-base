/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AiModel;
import com.kb.kbplatform.repository.AiModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本向量化客户端（OpenAI 兼容 /embeddings，默认智谱 embedding-3）。
 *
 * Key 解析顺序：
 *   1) kb.ai.embedding.api-key（独立配置，推荐生产用它 + KB_EMBED_KEY 环境变量）；
 *   2) 「AI 模型管理」里 provider 含 zhipu 的已启用模型 —— baseUrl 与 Key 一并复用，
 *      管理员配过智谱对话模型就无需再填 embedding 专用 Key（解析结果缓存 10 分钟）。
 *
 * 与 MeiliSearchClient 同一约束：失败抛给调用方处理，RagIndexService / RagService 均会
 * 降级回全文检索，绝不影响问答主流程。
 */
@Service
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final long KEY_TTL_MS = 600_000L;

    private final boolean enabled;
    private final String cfgBaseUrl;
    private final String cfgKey;
    private final String model;
    private final int dimensions;
    private final AiModelRepository aiModelRepo;
    private final AiKeyCipher cipher;

    private volatile String cachedKey;
    private volatile String cachedBaseUrl;
    private volatile long cachedAt;

    public EmbeddingClient(
            @Value("${kb.ai.embedding.enabled:false}") boolean enabled,
            @Value("${kb.ai.embedding.base-url:https://open.bigmodel.cn/api/paas/v4}") String cfgBaseUrl,
            @Value("${kb.ai.embedding.api-key:}") String cfgKey,
            @Value("${kb.ai.embedding.model:embedding-3}") String model,
            @Value("${kb.ai.embedding.dimensions:1024}") int dimensions,
            AiModelRepository aiModelRepo,
            AiKeyCipher cipher) {
        this.enabled = enabled;
        this.cfgBaseUrl = cfgBaseUrl == null ? "" : cfgBaseUrl.trim();
        this.cfgKey = cfgKey == null ? "" : cfgKey.trim();
        this.model = model;
        this.dimensions = dimensions;
        this.aiModelRepo = aiModelRepo;
        this.cipher = cipher;
    }

    public boolean isEnabled() { return enabled; }

    /** 单条向量化 */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /** 批量向量化：POST {base}/embeddings {model, input:[...], dimensions} → data[].embedding */
    @SuppressWarnings("unchecked")
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        String key = resolveKey();
        if (key == null || key.isBlank()) throw new IllegalStateException("embedding 未配置可用的 API Key");
        String base = cachedBaseUrl == null || cachedBaseUrl.isBlank() ? cfgBaseUrl : cachedBaseUrl;
        if (base.isBlank()) base = "https://open.bigmodel.cn/api/paas/v4";
        String url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/embeddings";

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", model);
        req.put("input", texts);
        req.put("dimensions", dimensions);
        Map<String, Object> resp = RestClient.create().post()
                .uri(url)
                .header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(Map.class);
        List<Object> data = resp == null ? null : (List<Object>) resp.get("data");
        if (data == null || data.size() != texts.size()) {
            throw new IllegalStateException("embedding 返回数量不符：" + (data == null ? 0 : data.size()));
        }
        // 按 index 排序回填（部分平台不保证 data 顺序与 input 一致）
        float[][] out = new float[texts.size()][];
        for (Object o : data) {
            Map<String, Object> item = (Map<String, Object>) o;
            int idx = item.get("index") == null ? 0 : ((Number) item.get("index")).intValue();
            List<Number> emb = (List<Number>) item.get("embedding");
            if (emb == null || emb.isEmpty()) throw new IllegalStateException("embedding 数据缺失");
            float[] v = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) v[i] = emb.get(i).floatValue();
            if (idx >= 0 && idx < out.length) out[idx] = v;
        }
        return Arrays.asList(out);
    }

    /** 解析 Key：独立配置优先，其次复用智谱平台模型；缓存 10 分钟 */
    private synchronized String resolveKey() {
        if (cachedKey != null && System.currentTimeMillis() - cachedAt < KEY_TTL_MS) return cachedKey;
        String k = cfgKey;
        String b = cfgBaseUrl;
        if (k.isEmpty()) {
            try {
                for (AiModel m : aiModelRepo.findAll()) {
                    if (!m.isEnabled()) continue;
                    String p = m.getProvider() == null ? "" : m.getProvider().toLowerCase();
                    if (!p.contains("zhipu") && !p.contains("智谱")) continue;
                    String dk = cipher.decrypt(m.getApiKey());
                    if (dk != null && !dk.isBlank()) {
                        k = dk.trim();
                        if (m.getBaseUrl() != null && !m.getBaseUrl().isBlank()) b = m.getBaseUrl().trim();
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("复用智谱模型 Key 失败: {}", e.getMessage());
            }
        }
        cachedKey = k;
        cachedBaseUrl = b == null ? "" : b;
        cachedAt = System.currentTimeMillis();
        return k;
    }
}
