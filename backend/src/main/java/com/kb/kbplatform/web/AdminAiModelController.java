/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.AiModel;
import com.kb.kbplatform.domain.AiUsageLog;
import com.kb.kbplatform.repository.AiModelRepository;
import com.kb.kbplatform.repository.AiUsageLogRepository;
import com.kb.kbplatform.service.AiKeyCipher;
import com.kb.kbplatform.service.AiModelClient;
import com.kb.kbplatform.security.TokenClaims;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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

    /** DeepSeek 现役 id 只有 deepseek-flash（2026-10-17 升级为 DeepSeek-V4.1-Flash-1017，调用方式/价格不变）
     *  与 deepseek-v4-pro；deepseek-v4-flash* 旧名仍可调用但模型已下线（路由到 1017 按 Flash 价计费），
     *  chat/reasoner 是 V3 时代旧别名。 */
    static final List<Map<String, Object>> DEEPSEEK_MODELS = List.of(
            mv("deepseek-flash", "DeepSeek V4.1 Flash 1017（最新，原生多模态，1M 上下文）"),
            mv("deepseek-v4-pro", "DeepSeek V4 Pro（上一代旗舰，现路由到 V4.1 Flash）"),
            mv("deepseek-v4-flash", "旧名 V4 Flash（模型已下线，路由至 1017 按 Flash 价计费）"),
            mv("deepseek-v4-flash-vision-exp", "旧名 V4 Flash 视觉实验版（模型已下线，路由至 1017）"),
            mv("deepseek-chat", "旧版对话别名（V3 时代，兼容保留）"),
            mv("deepseek-reasoner", "旧版推理别名（V3 时代，兼容保留）"));

    /** 智谱走 open.bigmodel.cn 的 v4 端点，model id 一律小写；5.x 起是推理模型，注意 thinking 会吃 token */
    /**
     * 智谱 GLM 预设。**Flash 系列是真免费**（无需充值，仅并发/速率受限），排在最前；
     * 其余为按量付费的旗舰/主力模型。注册即送额度，见 https://open.bigmodel.cn/usercenter/apikeys
     */
    static final List<Map<String, Object>> ZHIPU_MODELS = List.of(
            mv("glm-4.7-flash", "GLM-4.7-Flash（免费，200K 上下文，编程强）"),
            mv("glm-4.5-flash", "GLM-4.5-Flash（免费）"),
            mv("glm-4-flash", "GLM-4-Flash（免费，128K 上下文 / 30 并发）"),
            mv("glm-4.6v-flash", "GLM-4.6V-Flash（免费，视觉多模态）"),
            mv("glm-5.3", "GLM-5.3（最新旗舰）"),
            mv("glm-5.3-flash", "GLM-5.3-Flash（旗舰快速版·约9倍性价比/多模态）"),
            mv("glm-5.2", "GLM-5.2"),
            mv("glm-5.1", "GLM-5.1（上一代旗舰，长程任务）"),
            mv("glm-5", "GLM-5（5 系首代）"),
            mv("glm-5-turbo", "GLM-5 Turbo（快速经济）"),
            mv("glm-4.7", "GLM-4.7（通用主力）"),
            mv("glm-4.6", "GLM-4.6（开源 / 多模态）"));

    /**
     * OpenCode 推理服务（opencode.ai/inference）：聚合多家开放模型，走 OpenAI 兼容端点。
     * 模型 id 以官方 https://opencode.ai/inference/v1/models 实时列表为准（2026-10-18 核对）。
     *
     * ⚠️ 重要（2026-10-18 实测）：OpenCode 的 **免费额度只允许官方客户端使用**。
     *    服务端（我们的后端）调用免费模型会直接返回
     *    403 FreeTierError: "OpenCode's free tier can only be used from within OpenCode."
     *    因此免费模型仅列出备查、标注「仅官方客户端」，**排在付费模型之后**，避免管理员误选后一直报错。
     *    beam-free 另因「中国大陆及港澳不可用」不列入。
     */
    static final List<Map<String, Object>> OPENCODE_MODELS = List.of(
            mv("deepseek-v4.1-flash", "DeepSeek V4.1 Flash"),
            mv("glm-5.3", "GLM-5.3"),
            mv("kimi-k2.6", "Kimi K2.6（长上下文）"),
            mv("minimax-m2.7", "MiniMax M2.7"),
            mv("deepseek-v4.1-flash-free", "DeepSeek V4.1 Flash 免费（仅官方客户端）"),
            mv("glm-5.3-flash-free", "GLM-5.3 Flash 免费（仅官方客户端）"),
            mv("big-pickle", "Big Pickle 免费（仅官方客户端）"),
            mv("nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning 免费（仅官方客户端）"),
            mv("nemotron-3.5-super-vl-free", "Nemotron 3.5 Super VL 免费（仅官方客户端，视觉）"),
            mv("mimo-v2.6-flash-free", "MiMo v2.6 Flash 免费（仅官方客户端）"),
            mv("longcat-2.5-preview-free", "LongCat 2.5 Preview 免费（仅官方客户端）"),
            mv("space-bunny-free", "Space Bunny 免费（仅官方客户端）"),
            mv("jev-1.13-free", "Jev 1.13 免费（仅官方客户端，结构化决策）"));

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
                    "defaultModel", "deepseek-flash", "models", DEEPSEEK_MODELS,
                    "doc", "https://platform.deepseek.com/api_keys"),
            Map.of("key", AiModel.PROVIDER_ZHIPU, "label", "智谱 GLM",
                    "baseUrl", "https://open.bigmodel.cn/api/paas/v4",
                    "defaultModel", "glm-4.7-flash", "models", ZHIPU_MODELS,
                    "doc", "https://open.bigmodel.cn/usercenter/apikeys"),
            Map.of("key", AiModel.PROVIDER_OPENCODE, "label", "OpenCode 推理服务",
                    "baseUrl", "https://opencode.ai/inference/openai/v1",
                    "defaultModel", "deepseek-v4.1-flash", "models", OPENCODE_MODELS,
                    "doc", "https://opencode.ai/v2/docs/console/models/"),
            Map.of("key", AiModel.PROVIDER_CUSTOM, "label", "自定义（任何 OpenAI 兼容端点）",
                    "baseUrl", "https://api.openai.com/v1",
                    "defaultModel", "", "models", List.of(),
                    "doc", "换成「自定义」后，接口地址与模型名都可由你填写")
    );

    private final AiModelRepository repo;
    private final AiKeyCipher cipher;
    private final AiModelClient client;
    private final AiUsageLogRepository usageRepo;

    public AdminAiModelController(AiModelRepository repo, AiKeyCipher cipher, AiModelClient client,
                                  AiUsageLogRepository usageRepo) {
        this.repo = repo;
        this.cipher = cipher;
        this.client = client;
        this.usageRepo = usageRepo;
    }

    /** 可选平台（前端下拉据此自动填 baseUrl） */
    @GetMapping("/platforms")
    public List<Map<String, Object>> platforms() {
        return PLATFORMS;
    }

    /**
     * 用量统计（对标各平台官方控制台的「用量信息」页）：
     * 汇总 + 日趋势 + 按模型分组，数据源是 kb_ai_usage_log 调用流水。
     * 参数：days=统计天数（默认 30，上限 180），modelId=可选按配置过滤。
     */
    /** 写操作仅完全管理员：历史上一度依赖过滤器 admin 兜底，AI_MANAGE 放宽 + 路径绕过双重教训，
     * 控制器层必须自查（纵深防御）。 */
    private static void requireAdmin(TokenClaims c) {
        if (c == null || !c.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "AI 模型配置的写操作仅完全管理员可用");
        }
    }

    @GetMapping("/usage")
    public Map<String, Object> usage(@RequestAttribute("kbUser") TokenClaims c,
                                     @RequestParam(value = "days", defaultValue = "30") int days,
                                     @RequestParam(value = "modelId", required = false) Long modelId) {
        if (c == null || (!c.isAdmin() && !c.hasPerm("AI_MANAGE"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要 AI 模型管理权限");
        }
        int d = (days <= 0 || days > 180) ? 30 : days;
        LocalDate since = LocalDate.now().minusDays(d - 1L);
        List<AiUsageLog> rows = new ArrayList<>(usageRepo.findByCreatedAtGreaterThanEqual(since.atStartOfDay()));
        if (modelId != null) rows.removeIf(r -> !modelId.equals(r.getModelId()));

        // ---- 汇总 ----
        long requests = rows.size();
        long okCnt = rows.stream().filter(AiUsageLog::isSuccess).count();
        long tokens = rows.stream().mapToLong(AiUsageLog::getTotalTokens).sum();
        long prompt = rows.stream().mapToLong(AiUsageLog::getPromptTokens).sum();
        long completion = rows.stream().mapToLong(AiUsageLog::getCompletionTokens).sum();
        double avgLat = rows.stream().filter(AiUsageLog::isSuccess)
                .mapToLong(AiUsageLog::getLatencyMs).average().orElse(0);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("requests", requests);
        summary.put("success", okCnt);
        summary.put("failed", requests - okCnt);
        summary.put("tokens", tokens);
        summary.put("promptTokens", prompt);
        summary.put("completionTokens", completion);
        summary.put("avgLatencyMs", Math.round(avgLat));

        // ---- 日趋势（缺数据的日期补零，图表横轴连续） ----
        Map<LocalDate, long[]> byDay = new TreeMap<>();
        for (AiUsageLog r : rows) {
            long[] arr = byDay.computeIfAbsent(r.getCreatedAt().toLocalDate(), k -> new long[2]);
            arr[0]++;                                // 请求次数
            arr[1] += r.getTotalTokens();            // tokens
        }
        List<Map<String, Object>> daily = new ArrayList<>();
        for (LocalDate day = since; !day.isAfter(LocalDate.now()); day = day.plusDays(1)) {
            long[] arr = byDay.getOrDefault(day, new long[2]);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("date", day.toString());
            e.put("requests", arr[0]);
            e.put("tokens", arr[1]);
            daily.add(e);
        }

        // ---- 按模型分组（快照字段聚合，配置删了也能看到历史用量） ----
        Map<String, Map<String, Object>> byModel = new LinkedHashMap<>();
        for (AiUsageLog r : rows) {
            String key = r.getModelId() + "|" + r.getModelName() + "|" + r.getModel();
            Map<String, Object> g = byModel.computeIfAbsent(key, k -> {
                Map<String, Object> g0 = new LinkedHashMap<>();
                g0.put("modelId", r.getModelId());
                g0.put("modelName", r.getModelName());
                g0.put("model", r.getModel());
                g0.put("requests", 0L);
                g0.put("success", 0L);
                g0.put("failed", 0L);
                g0.put("tokens", 0L);
                g0.put("latencySum", 0L);
                return g0;
            });
            g.put("requests", (long) g.get("requests") + 1);
            if (r.isSuccess()) {
                g.put("success", (long) g.get("success") + 1);
                g.put("latencySum", (long) g.get("latencySum") + r.getLatencyMs());
            } else {
                g.put("failed", (long) g.get("failed") + 1);
            }
            g.put("tokens", (long) g.get("tokens") + r.getTotalTokens());
        }
        List<Map<String, Object>> models = new ArrayList<>();
        for (Map<String, Object> g : byModel.values()) {
            long ok = (long) g.get("success");
            g.put("avgLatencyMs", ok > 0 ? Math.round((double) (long) g.get("latencySum") / ok) : 0L);
            g.remove("latencySum");
            models.add(g);
        }
        models.sort((a, b) -> Long.compare((long) b.get("requests"), (long) a.get("requests")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", d);
        out.put("summary", summary);
        out.put("daily", daily);
        out.put("models", models);
        return out;
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
    public Map<String, Object> save(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        requireAdmin(c);
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
    public Map<String, Object> remove(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requireAdmin(c);
        if (!repo.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "配置不存在");
        }
        repo.deleteById(id);
        client.invalidate();
        return Map.of("ok", true);
    }

    @Transactional
    @PostMapping("/{id}/toggle")
    public Map<String, Object> toggle(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        requireAdmin(c);
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
    public Map<String, Object> setDefault(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requireAdmin(c);
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
    public Map<String, Object> test(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requireAdmin(c);
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
        // 失败也回带配置里的模型名，前端「连接失败 · 模型 xxx」才读得通
        return Map.of("ok", false, "error", r.text(), "model", m.getModel());
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
