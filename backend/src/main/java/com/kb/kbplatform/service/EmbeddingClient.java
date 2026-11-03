/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.AiModel;
import com.kb.kbplatform.repository.AiModelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本向量化客户端（OpenAI 兼容 /embeddings，配合向量 RAG）。
 *
 * 配置完全由「AI 模型管理」页驱动（不再读 yml/环境变量）：
 *   - 管理员添加一条 **kind=EMBEDDING（语义检索/向量化）** 的模型（如智谱 embedding-3）；
 *   - 该条目「启用」即语义检索开关，停用/删除即关闭，保存后 ≤1 分钟生效（缓存 60s，
 *     保存/启停接口会主动 invalidate 立即生效）；
 *   - 模型名支持维度后缀：`embedding-3:1024`（不写后缀用平台默认维度，向量长度以返回为准）。
 *
 * 与 MeiliSearchClient 同一约束：失败抛给调用方，RagIndexService / RagService 均降级回全文检索。
 */
@Service
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);
    private static final long MODEL_TTL_MS = 60_000L;

    private final AiModelRepository aiModelRepo;
    private final AiKeyCipher cipher;

    /** 共享 RestClient（连接池 + 超时）：embedding 端点挂死不能拖垮单线程索引器 */
    private final RestClient http;

    private volatile AiModel cached;
    private volatile long cachedAt;

    public EmbeddingClient(AiModelRepository aiModelRepo, AiKeyCipher cipher) {
        this.aiModelRepo = aiModelRepo;
        this.cipher = cipher;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(10_000);
        rf.setReadTimeout(60_000);
        this.http = RestClient.builder().requestFactory(rf).build();
    }

    /** 语义检索开关 = 存在启用的 EMBEDDING 条目（管理页自助控制，缓存 60s） */
    public boolean isEnabled() { return current() != null; }

    /** 管理页保存/启停/删除模型后调用：立即刷新配置缓存（与 AiModelClient.invalidate 同语义） */
    public void invalidate() {
        cached = null;
        cachedAt = 0L;
    }

    /** 单条向量化 */
    public float[] embed(String text) {
        return embedBatch(List.of(text)).get(0);
    }

    /** 批量向量化：POST {base}/embeddings {model, input:[...], dimensions?} → data[].embedding */
    @SuppressWarnings("unchecked")
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        AiModel m = current();
        if (m == null) throw new IllegalStateException("未配置「语义检索（EMBEDDING）」模型，请在 AI 模型管理中添加");
        String key;
        try {
            key = cipher.decrypt(m.getApiKey());
        } catch (Exception e) {
            throw new IllegalStateException("EMBEDDING 模型 Key 解密失败");
        }
        if (key == null || key.isBlank()) throw new IllegalStateException("EMBEDDING 模型未填 API Key");

        // 模型名支持维度后缀：embedding-3:1024 → model=embedding-3, dimensions=1024
        // 防御：后缀非法（非正数/空模型名）时整段当普通模型名，不改写请求
        String modelSpec = m.getModel() == null ? "" : m.getModel().trim();
        String model = modelSpec;
        Integer dims = null;
        int ci = modelSpec.lastIndexOf(':');
        if (ci > 0) {
            try {
                int d = Integer.parseInt(modelSpec.substring(ci + 1).trim());
                String name = modelSpec.substring(0, ci).trim();
                if (d > 0 && !name.isEmpty()) {
                    dims = d;
                    model = name;
                }
            } catch (NumberFormatException ignored) {
                model = modelSpec;   // 冒号后不是数字就当普通模型名
            }
        }

        String base = m.getBaseUrl() == null || m.getBaseUrl().isBlank()
                ? "https://open.bigmodel.cn/api/paas/v4" : m.getBaseUrl().trim();
        String url = (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/embeddings";

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", model);
        req.put("input", texts);
        if (dims != null && dims > 0) req.put("dimensions", dims);
        Map<String, Object> resp = http.post()
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
        // 按 index 排序回填；index 缺失/重复/越界都会造成 null 槽位 → 下游 NPE，这里显式拦下
        float[][] out = new float[texts.size()][];
        for (Object o : data) {
            Map<String, Object> item = (Map<String, Object>) o;
            int idx = item.get("index") == null ? 0 : ((Number) item.get("index")).intValue();
            List<Number> emb = (List<Number>) item.get("embedding");
            if (emb == null || emb.isEmpty()) throw new IllegalStateException("embedding 数据缺失");
            if (idx < 0 || idx >= out.length) throw new IllegalStateException("embedding index 越界: " + idx);
            if (out[idx] != null) throw new IllegalStateException("embedding index 重复: " + idx);
            float[] v = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) v[i] = emb.get(i).floatValue();
            out[idx] = v;
        }
        for (int i = 0; i < out.length; i++) {
            if (out[i] == null) throw new IllegalStateException("embedding 缺少 index=" + i + " 的数据");
        }
        return Arrays.asList(out);
    }

    /** 取启用的 EMBEDDING 条目，缓存 60s：管理页改配置/启停后最多 1 分钟生效（写路径主动 invalidate） */
    private AiModel current() {
        AiModel c = cached;
        if (c != null && System.currentTimeMillis() - cachedAt < MODEL_TTL_MS) return c;
        synchronized (this) {
            c = cached;
            if (c != null && System.currentTimeMillis() - cachedAt < MODEL_TTL_MS) return c;
            AiModel found = null;
            try {
                for (AiModel m : aiModelRepo.findAll()) {
                    if (!m.isEnabled()) continue;
                    if (!"EMBEDDING".equalsIgnoreCase(m.getKind())) continue;
                    found = m;
                    break;
                }
            } catch (Exception e) {
                log.warn("读取 EMBEDDING 模型配置失败: {}", e.getMessage());
            }
            cached = found;
            cachedAt = System.currentTimeMillis();
            return found;
        }
    }
}
