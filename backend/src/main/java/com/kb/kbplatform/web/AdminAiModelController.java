/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.AiModel;
import com.kb.kbplatform.repository.AiModelRepository;
import com.kb.kbplatform.service.AiKeyCipher;
import com.kb.kbplatform.service.AiModelClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI 模型管理接口（/api/admin/ai/models）。
 *
 * 门禁：AuthFilter 的 adminPermRequired 对该前缀返回 null，即**仅完全管理员**可访问，
 * 普通管理员即便有细粒度权限也改不了 API Key。列表一律脱敏，明文只在服务端调用时使用。
 *
 * 保存语义：apiKey 传空串或含 ****（脱敏形式）表示「没改」，保留库中原值，避免管理员
 * 为了改个模型名而把已填好的 Key 覆盖成空。
 */
@RestController
@RequestMapping("/api/admin/ai/models")
public class AdminAiModelController {

    private static final Logger log = LoggerFactory.getLogger(AdminAiModelController.class);

    /* 每个平台的可选模型版本：{id=实际调用的模型名, label=给人看的说明}，供管理页下拉与默认值使用 */
    private static Map<String, Object> mv(String id, String label) {
        return Map.of("id", id, "label", label);
    }

    /** GPT-5.6 起 naming 改为能力层级（sol 旗舰 / terra 均衡 / luna 快速经济），旧的 4o、4.1 已 deprecated */
    static final List<Map<String, Object>> OPENAI_MODELS = List.of(
            mv("gpt-5.6-sol", "GPT-5.6 Sol（最新旗舰）"),
            mv("gpt-5.6-terra", "GPT-5.6 Terra（均衡，推荐）"),
            mv("gpt-5.6-luna", "GPT-5.6 Luna（快速经济）"),
            mv("gpt-5.2-pro", "GPT-5.2 Pro"),
            mv("gpt-5.2", "GPT-5.2"),
            mv("gpt-5.1", "GPT-5.1"),
            mv("gpt-5-mini", "GPT-5 mini"),
            mv("gpt-4o", "GPT-4o（旧旗舰，部分场景更便宜）"),
            mv("gpt-4o-mini", "GPT-4o mini（轻量省钱）"));

    static final List<Map<String, Object>> DASHSCOPE_MODELS = List.of(
            mv("qwen-plus", "通义千问 Plus（推荐）"),
            mv("qwen-turbo", "通义千问 Turbo（最快）"),
            mv("qwen-max", "通义千问 Max（最强）"),
            mv("qwen3-max", "通义千问 3 Max"),
            mv("qwen2.5-72b-instruct", "Qwen2.5 72B（开源版）"));

    static final List<Map<String, Object>> DEEPSEEK_MODELS = List.of(
            mv("deepseek-chat", "DeepSeek V3 / 对话版（推荐）"),
            mv("deepseek-reasoner", "DeepSeek R1 推理版"),
            mv("deepseek-coder", "DeepSeek Coder（代码）"));

    /** 智谱走 open.bigmodel.cn 的 v4 端点，model id 一律小写；5.x 起是推理模型，注意 thinking 会吃 token */
    static final List<Map<String, Object>> ZHIPU_MODELS = List.of(
            mv("glm-5.3", "GLM-5.3（最新旗舰）"),
            mv("glm-5.2", "GLM-5.2"),
            mv("glm-5.1", "GLM-5.1（上一代旗舰，长程任务）"),
            mv("glm-5", "GLM-5（5 系首代）"),
            mv("glm-5-turbo", "GLM-5 Turbo（快速经济）"),
            mv("glm-4.7", "GLM-4.7（通用主力）"),
            mv("glm-4.6", "GLM-4.6（开源 / 多模态）"));

    /**
     * 支持的平台：各平台都提供 OpenAI 兼容端点。
     * baseUrl 由平台固定（官方端点，管理页自动填充，无需管理员手抄）；defaultModel + models
     * 供管理页的「模型版本」下拉使用，选中即填入实际调用的模型名。
     */
    static final List<Map<String, Object>> PLATFORMS = List.of(
            Map.of("key", AiModel.PROVIDER_OPENAI, "label", "OpenAI",
                    "baseUrl", "https://api.openai.com/v1",
                    "defaultModel", "gpt-5.6-terra", "models", OPENAI_MODELS,
                    "doc", "https://platform.openai.com/api-keys"),
            Map.of("key", AiModel.PROVIDER_DASHSCOPE, "label", "阿里 DashScope（百炼）",
                    "baseUrl", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "defaultModel", "qwen-plus", "models", DASHSCOPE_MODELS,
                    "doc", "https://bailian.console.aliyun.com/?tab=model#/api-key"),
            Map.of("key", AiModel.PROVIDER_DEEPSEEK, "label", "DeepSeek 深度求索",
                    "baseUrl", "https://api.deepseek.com/v1",
                    "defaultModel", "deepseek-chat", "models", DEEPSEEK_MODELS,
                    "doc", "https://platform.deepseek.com/api_keys"),
            Map.of("key", AiModel.PROVIDER_ZHIPU, "label", "智谱 GLM",
                    "baseUrl", "https://open.bigmodel.cn/api/paas/v4",
                    "defaultModel", "glm-5.3", "models", ZHIPU_MODELS,
                    "doc", "https://open.bigmodel.cn/usercenter/apikeys"),
            Map.of("key", AiModel.PROVIDER_CUSTOM, "label", "自定义（任何 OpenAI 兼容端点）",
                    "baseUrl", "https://api.openai.com/v1",
                    "defaultModel", "", "models", List.of(),
                    "doc", "换成「自定义」后，接口地址与模型名都可由你填写")
    );

    private final AiModelRepository repo;
    private final AiKeyCipher cipher;
    private final AiModelClient client;

    public AdminAiModelController(AiModelRepository repo, AiKeyCipher cipher, AiModelClient client) {
        this.repo = repo;
        this.cipher = cipher;
        this.client = client;
    }

    /** 可选平台（前端下拉据此自动填 baseUrl） */
    @GetMapping("/platforms")
    public List<Map<String, Object>> platforms() {
        return PLATFORMS;
    }

    /** 配置列表（脱敏，绝不返回明文 Key） */
    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AiModel m : repo.findAll()) {
            String plain = cipher.decrypt(m.getApiKey());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", m.getId());
            r.put("provider", m.getProvider());
            r.put("name", m.getName());
            r.put("baseUrl", m.getBaseUrl());
            r.put("model", m.getModel());
            r.put("enabled", m.isEnabled());
            r.put("isDefault", m.isDefaultModel());
            r.put("hasKey", plain != null && !plain.isBlank());
            r.put("keyMasked", cipher.mask(plain));
            r.put("updatedAt", m.getUpdatedAt());
            out.add(r);
        }
        return out;
    }

    /** 新增或更新：id 为空表示新增 */
    @Transactional
    @PostMapping
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        Long id = toLong(body.get("id"));
        AiModel m;
        if (id == null) {
            m = new AiModel();
            m.setEnabled(true);
        } else {
            m = repo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
        }
        if (body.get("provider") != null) {
            m.setProvider(String.valueOf(body.get("provider")).trim().toUpperCase());
        }
        if (body.get("name") != null) m.setName(String.valueOf(body.get("name")).trim());
        if (body.get("baseUrl") != null) m.setBaseUrl(String.valueOf(body.get("baseUrl")).trim());
        if (body.get("model") != null) m.setModel(String.valueOf(body.get("model")).trim());
        if (body.get("apiKey") != null) {
            String k = String.valueOf(body.get("apiKey")).trim();
            // 空串 / 含 ****（前端回显的脱敏值）= 未改动，保留原值
            if (!k.isEmpty() && !k.contains("****")) m.setApiKey(cipher.encrypt(k));
        }
        if (body.get("enabled") != null) {
            m.setEnabled(Boolean.parseBoolean(String.valueOf(body.get("enabled"))));
        }
        boolean wantDefault = body.get("isDefault") != null
                && Boolean.parseBoolean(String.valueOf(body.get("isDefault")));
        if (wantDefault) {
            // 全局只能有一个默认模型
            for (AiModel other : repo.findByDefaultModelTrue()) {
                if (!other.getId().equals(m.getId())) other.setDefaultModel(false);
            }
        }
        m.setDefaultModel(wantDefault);
        AiModel saved = repo.save(m);
        client.invalidate();   // 丢弃带旧 Key 的 RestClient 缓存
        log.info("AI 模型配置已保存：id={}, model={}", saved.getId(), saved.getModel());
        return Map.of("ok", true, "id", saved.getId());
    }

    @Transactional
    @DeleteMapping("/{id}")
    public Map<String, Object> remove(@PathVariable Long id) {
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在");
        }
        repo.deleteById(id);
        client.invalidate();
        return Map.of("ok", true);
    }

    @Transactional
    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        AiModel m = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
        boolean v = body != null && body.get("enabled") != null
                ? Boolean.parseBoolean(String.valueOf(body.get("enabled")))
                : !m.isEnabled();
        m.setEnabled(v);
        repo.save(m);
        client.invalidate();
        return Map.of("ok", true, "enabled", v);
    }

    @Transactional
    @PostMapping("/{id}/default")
    public Map<String, Object> setDefault(@PathVariable Long id) {
        AiModel m = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
        for (AiModel other : repo.findByDefaultModelTrue()) {
            if (!other.getId().equals(id)) other.setDefaultModel(false);
        }
        m.setDefaultModel(true);
        repo.save(m);
        client.invalidate();
        return Map.of("ok", true);
    }

    /** 测试连接：发一条极短请求验证 API Key 与端点是否可用（会真实消耗少量 token） */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable Long id) {
        AiModel m = repo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在"));
        if (cipher.decrypt(m.getApiKey()).isBlank()) {
            return Map.of("ok", false, "error", "尚未填写 API Key");
        }
        AiModelClient.AiChatResult r = client.test(id);
        if (r.ok()) {
            return Map.of("ok", true, "model", r.model(),
                    "latencyMs", r.latencyMs(), "reply", r.text());
        }
        return Map.of("ok", false, "error", r.text(), "model", r.model());
    }

    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Long.valueOf(s);
        } catch (Exception e) {
            return null;
        }
    }
}
