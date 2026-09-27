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
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * OCR 文字识别服务 —— 零依赖实现，走智谱视觉模型（云端），后端转发、Key 不出网。
 *
 * 模型降级链（2026-09-05 实测）：
 *   1) glm-4.6v-flash            —— 首选（128K，视觉推理），但免费档高峰经常 429/1305「访问量过大」
 *   2) glm-4.1v-thinking-flash   —— 免费且实测可用；输出会包 <|begin_of_box|>...<|end_of_box|> 标记，需剥离
 *   （glm-4v-flash 已排除：max_tokens 上限仅 1024，长文识别必截断）
 *
 * 约束：识别失败抛 IllegalStateException（全局异常处理器映射为 400，响应体带中文 error），
 * 不做静默降级——导入场景用户必须知道识别没成功。
 *
 * Key 来源：不再有独立的 OCR 配置。复用管理员在「AI 模型管理」页配好的**智谱平台**条目——
 * 取其 base-url 与解密后的 api-key，因此管理员只需配一条智谱模型，对话与 OCR 同时生效，
 * 换 Key / 换端点即时生效、无需重启（每次识别时现取，不缓存 Key）。
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    /** 单图上限 10MB，超限直接拒绝 */
    public static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Set<String> ALLOWED_MIME = Set.of("image/png", "image/jpeg", "image/jpg", "image/webp", "image/gif", "image/bmp");
    /** 4.1V-Thinking 会把正式答案包在这对标记里，标记外是它的碎碎念，要剥 */
    private static final String BOX_BEGIN = "<|begin_of_box|>";
    private static final String BOX_END = "<|end_of_box|>";
    /**
     * 「不是识别结果，而是模型在解释自己将怎么做」的开场白特征词。
     * 实测（2026-09-27）：免费档拥挤时降级模型可能不返回文字本身，而是吐这类前言且被 max_tokens 截断；
     * 若不识别，会把一句解释当正文落库，用户拿到一篇只有一句话的文档还显示成功。
     */
    private static final List<String> CHATTER_MARKERS = List.of(
            "由于图片", "由于该图片", "以下是", "图片中的文字", "提取的", "识别结果", "整理如下", "我将", "我会先", "按原图");
    /** 智谱默认端点（管理页未填 base-url 时兜底） */
    private static final String ZHIPU_DEFAULT_BASE = "https://open.bigmodel.cn/api/paas/v4";
    /** 视觉模型降级链：与文本模型不同，须显式指定（管理页配的 model 通常是文本模型） */
    private static final List<String> VISION_MODELS = List.of("glm-4.6v-flash", "glm-4.1v-thinking-flash");

    private final boolean enabled;
    private final AiModelRepository modelRepo;
    private final AiKeyCipher cipher;

    public OcrService(@Value("${kb.ai.enabled:false}") boolean enabled,
                      AiModelRepository modelRepo,
                      AiKeyCipher cipher) {
        this.enabled = enabled;
        this.modelRepo = modelRepo;
        this.cipher = cipher;
    }

    /** 是否可用：受总开关约束，且管理页至少配了一条启用的智谱模型（取不到 Key 时视为不可用） */
    public boolean isEnabled() {
        return enabled && zhipuConfig() != null;
    }

    /**
     * 取一条可用的智谱配置（含解密后的 Key）。管理页可配多条，取排序最前的启用项。
     * 没有智谱配置 / Key 解密失败 / Key 为空时返回 null。
     * 注：AiKeyCipher.decrypt() 内部已吞掉异常并返回空串（主密钥轮换场景），故此处无需 try-catch。
     */
    private ZhipuConfig zhipuConfig() {
        List<AiModel> all = modelRepo.findByEnabledTrueOrderBySortOrderAscIdAsc();
        for (AiModel m : all) {
            if (!AiModel.PROVIDER_ZHIPU.equalsIgnoreCase(m.getProvider())) continue;
            String key = cipher.decrypt(m.getApiKey());
            if (key == null || key.isBlank()) continue;
            String base = m.getBaseUrl() == null || m.getBaseUrl().isBlank() ? ZHIPU_DEFAULT_BASE : m.getBaseUrl().trim();
            return new ZhipuConfig(base, key.trim());
        }
        return null;
    }

    /** 一条智谱端点 + Key 的瞬时快照（不缓存，保证管理页改动即时生效） */
    private record ZhipuConfig(String baseUrl, String apiKey) {
    }

    /** 校验上传的图片类型与大小，不合法抛 IllegalStateException */
    public void validate(String contentType, long size) {
        String mime = contentType == null ? "" : contentType.toLowerCase();
        if (!ALLOWED_MIME.contains(mime)) {
            throw new IllegalStateException("仅支持图片识别（PNG/JPG/WebP/GIF/BMP），当前类型：" + (contentType == null ? "未知" : contentType));
        }
        if (size > MAX_IMAGE_BYTES) {
            throw new IllegalStateException("图片过大（" + (size / 1024 / 1024) + "MB），请控制在 10MB 以内");
        }
    }

    /**
     * 识别图片中的文字。按降级链依次尝试模型：前一个 429/1305（免费档拥挤）或参数不兼容时自动切下一个。
     * @return 提取出的纯文本（保留换行）
     */
    public String recognize(byte[] imageBytes, String contentType) {
        ZhipuConfig conf = enabled ? zhipuConfig() : null;
        if (conf == null) {
            throw new IllegalStateException(enabled
                    ? "OCR 未启用：请在「AI 模型管理」中添加一条启用的智谱（ZHIPU）模型配置并填写 API Key"
                    : "OCR 未启用：kb.ai.enabled=false，请先在配置中开启 AI 功能");
        }
        // 与 AI 模型调用同理：不给超时，模型端 hang 住会把请求线程一直占着
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(
                java.net.http.HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
        rf.setReadTimeout(Duration.ofSeconds(120));
        RestClient client = RestClient.builder()
                .baseUrl(conf.baseUrl())
                .requestFactory(rf)
                .defaultHeader("Authorization", "Bearer " + conf.apiKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
        String mime = contentType.toLowerCase().contains("jpg") ? "image/jpeg" : contentType.toLowerCase();
        String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> textPart = Map.of("type", "text", "text",
                "请识别图片中的全部文字内容，按原来的段落结构与换行输出纯文本。"
                        + "不要输出任何解释、标题修饰或 Markdown 代码块，只输出识别出的文字本身。"
                        + "如果图片中没有文字，直接输出空内容，不要解释。");
        Map<String, Object> imagePart = Map.of("type", "image_url", "image_url", Map.of("url", dataUrl));
        Map<String, Object> userMsg = Map.of("role", "user", "content", List.of(textPart, imagePart));

        String lastErr = "识别服务暂不可用";
        for (String model : VISION_MODELS) {
            try {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", model);
                body.put("temperature", 0.1);
                body.put("max_tokens", 4096);
                body.put("messages", List.of(userMsg));
                // 注意：不给 4.1V-Thinking 传 thinking:disabled —— 实测关闭思考后该模型
                // 输出变成未整理的草稿碎碎念；保持默认反而直接吐出干净的最终答案。

                Map<String, Object> resp = client.post()
                        .uri("/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(Map.class);
                String text = extractContent(resp, model);
                String cleaned = stripWrappers(text);
                // 模型没给文字、只给了「它将怎么做」的解释：换下一个模型再试，别把前言当正文落库
                if (looksLikeChatter(cleaned)) {
                    log.info("OCR 模型 {} 只输出了说明性前言（未给出文字），尝试下一个模型", model);
                    lastErr = "未能从图片中识别出文字，请确认图片内容清晰";
                    continue;
                }
                return cleaned;
            } catch (OcrModelException e) {
                // 模型侧拥挤/不可用：记下原因，降级到链上下一个
                log.info("OCR 模型 {} 不可用: {}", model, e.getMessage());
                lastErr = e.getUserMessage();
            } catch (Exception e) {
                log.warn("OCR 模型 {} 调用失败: {}", model, e.getMessage());
                lastErr = "文字识别服务暂时不可用，请稍后重试";
            }
        }
        throw new IllegalStateException(lastErr);
    }

    /** 识别文本 → 简单段落 HTML（<p>每行一段</p>），交给 HtmlSanitizer 净化后可直接作为 Tiptap 文档内容 */
    public String toParagraphHtml(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").replace("\r", "\n").split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            sb.append("<p>").append(escapeHtml(t)).append("</p>\n");
        }
        return sb.isEmpty() ? "" : com.kb.kbplatform.security.HtmlSanitizer.sanitize(sb.toString());
    }

    /** 剥掉模型包裹物：4.1V 的 <|begin_of_box|> 标记、以及可能的 ```text fences */
    private static String stripWrappers(String s) {
        if (s == null) return "";
        String t = s.trim();
        // 4.1V-Thinking：正式答案夹在 box 标记之间，标记外是模型解释，只取标记内
        if (t.contains(BOX_BEGIN)) {
            int b = t.indexOf(BOX_BEGIN) + BOX_BEGIN.length();
            int e = t.indexOf(BOX_END, b);
            if (e > b) t = t.substring(b, e);
            else t = t.substring(b);
        }
        t = t.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            if (first > 0) t = t.substring(first + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    /**
     * 判断输出是否「只会说它要做什么」而非真的给出了文字。
     * 判据（偏保守，避免误杀真实内容）：
     *   ① 整体很短（≤ 60 字），且
     *   ② 命中开场白特征词，或结尾是「内容」/「如下」这类未完成句式（典型被 max_tokens 截断）
     * 真识别结果通常远长于 60 字，故短+特征词同时成立才判为前言。
     */
    static boolean looksLikeChatter(String s) {
        if (s == null || s.isBlank()) return false;
        String t = s.trim();
        if (t.length() > 60) return false;                 // 够长一般是真内容
        for (String mk : CHATTER_MARKERS) {
            if (t.contains(mk)) return true;
        }
        // 截断特征：结尾停在「内容」/「如下」/「：」，说明句子没写完
        return t.endsWith("内容") || t.endsWith("如下") || t.endsWith("：") || t.endsWith(":");
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 区分「模型侧不可用（可降级）」与其他错误的轻量异常 */
    private static class OcrModelException extends RuntimeException {
        final String userMessage;
        OcrModelException(String userMessage) { super(userMessage); this.userMessage = userMessage; }
        String getUserMessage() { return userMessage; }
    }

    @SuppressWarnings("unchecked")
    private String extractContent(Map<String, Object> resp, String model) {
        if (resp == null) throw new OcrModelException("识别服务返回为空");
        Object err = resp.get("error");
        if (err instanceof Map<?, ?> em) {
            String code = String.valueOf(em.get("code"));
            String msg = String.valueOf(em.get("message"));
            // 1305 = 免费档访问量过大（429），可降级到下一个模型
            if ("1305".equals(code)) throw new OcrModelException("免费识别额度当前拥挤，已自动切换线路，请再试一次");
            log.warn("OCR 模型 {} 返回错误 {}: {}", model, code, msg);
            throw new OcrModelException("图片识别失败，请换一张图片或稍后再试");
        }
        Object choicesObj = resp.get("choices");
        if (choicesObj instanceof List<?> choices && !choices.isEmpty()) {
            Object first = choices.get(0);
            if (first instanceof Map<?, ?> m && m.get("message") instanceof Map<?, ?> msg) {
                Object content = msg.get("content");
                if (content != null) return String.valueOf(content);
            }
        }
        throw new OcrModelException("识别服务返回为空");
    }
}
