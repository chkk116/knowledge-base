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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 媒体生成（文生图 / 文生视频）。
 *
 * - 模型来自 kb_ai_model，kind = IMAGE / VIDEO，调用方式为 OpenAI 兼容的
 *   POST {base}/images/generations 与智谱兼容的 POST {base}/videos/generations（异步任务）
 * - 生成的产物下载后存入 uploads，返回站内 /uploads/... 地址（与普通上传一致，可被引用）
 * - 视频为异步任务：createVideo 返回 taskId，videoStatus 轮询取结果
 */
@Service
public class AiMediaService {

    private static final Logger log = LoggerFactory.getLogger(AiMediaService.class);

    private final AiModelRepository repo;
    private final AiKeyCipher cipher;

    @Value("${kb.upload.dir:./uploads}")
    private String uploadDir;

    /** 内存任务表：taskId -> modelId（重启丢失后轮询返回 FAIL，重新生成即可） */
    private final Map<String, Long> videoTasks = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public AiMediaService(AiModelRepository repo, AiKeyCipher cipher) {
        this.repo = repo;
        this.cipher = cipher;
    }

    /** 选一个指定 kind 的可用模型；modelId 非空时优先校验其 kind 匹配 */
    private AiModel pick(String kind, Long modelId) {
        if (modelId != null) {
            AiModel m = repo.findById(modelId).orElse(null);
            if (m != null && m.isEnabled() && kind.equalsIgnoreCase(m.getKind())) return m;
        }
        return repo.findAll().stream()
                .filter(m -> m.isEnabled() && kind.equalsIgnoreCase(m.getKind()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "尚未配置可用的" + ("IMAGE".equals(kind) ? "文生图" : "文生视频") + "模型，请联系管理员在「AI 模型管理」中添加"));
    }

    private String post(AiModel m, String path, String json) {
        try {
            String key = cipher.decrypt(m.getApiKey());
            if (key == null || key.isBlank()) throw new IllegalStateException("API Key 为空或解密失败");
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimBase(m.getBaseUrl()) + path))
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "上游返回 " + resp.statusCode() + "：" + snippet(resp.body()));
            }
            return resp.body();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "请求上游失败：" + e.getMessage());
        }
    }

    private String get(AiModel m, String path) {
        try {
            String key = cipher.decrypt(m.getApiKey());
            HttpRequest req = HttpRequest.newBuilder(URI.create(trimBase(m.getBaseUrl()) + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + key)
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "上游返回 " + resp.statusCode());
            }
            return resp.body();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "查询任务失败：" + e.getMessage());
        }
    }

    /** 文生图：同步返回站内图片地址 */
    public Map<String, Object> generateImage(Long modelId, String prompt) {
        AiModel m = pick("IMAGE", modelId);
        String clean = prompt == null ? "" : prompt.trim();
        if (clean.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入画面描述");
        if (clean.length() > 800) clean = clean.substring(0, 800);

        String body = "{\"model\":\"" + esc(m.getModel()) + "\",\"prompt\":\"" + esc(clean) + "\",\"n\":1,\"size\":\"1024x1024\"}";
        String resp = post(m, "/images/generations", body);

        String url = extractJsonString(resp, "url");
        String b64 = extractJsonString(resp, "b64_json");
        byte[] bytes;
        if (b64 != null && !b64.isBlank()) {
            bytes = Base64.getMimeDecoder().decode(b64);
        } else if (url != null && !url.isBlank()) {
            bytes = download(url);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "上游未返回图片数据");
        }
        String path = save(bytes, "png");
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("url", path);
        return ret;
    }

    /** 文生视频：提交异步任务，返回 taskId */
    public Map<String, Object> createVideo(Long modelId, String prompt) {
        AiModel m = pick("VIDEO", modelId);
        String clean = prompt == null ? "" : prompt.trim();
        if (clean.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入视频描述");
        if (clean.length() > 800) clean = clean.substring(0, 800);

        String body = "{\"model\":\"" + esc(m.getModel()) + "\",\"prompt\":\"" + esc(clean) + "\"}";
        String resp = post(m, "/videos/generations", body);
        String taskId = extractJsonString(resp, "id");
        if (taskId == null || taskId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "上游未返回任务 id");
        }
        videoTasks.put(taskId, m.getId());
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("taskId", taskId);
        return ret;
    }

    /** 轮询视频任务；成功后下载产物存 uploads */
    public Map<String, Object> videoStatus(String taskId) {
        Long modelId = videoTasks.get(taskId);
        if (modelId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在或已过期（服务重启后会丢失）");
        }
        AiModel m = repo.findById(modelId).orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "模型配置已删除"));
        String resp = get(m, "/async-result/" + taskId);
        String status = extractJsonString(resp, "task_status");
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("status", status == null ? "PROCESSING" : status);
        if ("SUCCESS".equalsIgnoreCase(status)) {
            // video_result: [{url, cover_image_url}]
            String videoUrl = firstUrlAfter(resp, "video_result");
            if (videoUrl == null || videoUrl.isBlank()) {
                ret.put("status", "FAIL");
                ret.put("message", "上游未返回视频地址");
                return ret;
            }
            String ext = videoUrl.contains(".mp4") ? "mp4" : "mp4";
            byte[] bytes = download(videoUrl);
            String path = save(bytes, ext);
            ret.put("url", path);
            String cover = firstUrlAfter(resp, "cover_image_url");
            if (cover != null && !cover.isBlank()) {
                try {
                    ret.put("cover", save(download(cover), "jpg"));
                } catch (Exception ignore) { /* 封面失败不影响视频 */ }
            }
        }
        return ret;
    }

    // ---------- 工具 ----------

    private byte[] download(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2 || resp.body().length == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "下载产物失败：" + resp.statusCode());
            }
            return resp.body();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "下载产物失败：" + e.getMessage());
        }
    }

    private String save(byte[] bytes, String ext) {
        try {
            Path root = Path.of(uploadDir).toAbsolutePath().normalize();
            String sub = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy/MM"));
            Path dir = root.resolve("ai-media").resolve(sub);
            Files.createDirectories(dir);
            Path f = dir.resolve(System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + "." + ext);
            Files.write(f, bytes);
            return "/uploads/ai-media/" + sub + "/" + f.getFileName();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "保存文件失败：" + e.getMessage());
        }
    }

    private static String trimBase(String b) {
        return b == null ? "" : b.replaceAll("/+$", "");
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String snippet(String s) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 160 ? t.substring(0, 160) + "…" : t;
    }

    /** 极简 JSON 字符串字段提取（只处理上游约定的扁平结构，避免引入依赖） */
    private static String extractJsonString(String json, String key) {
        if (json == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\/", "/") : null;
    }

    /** 在指定数组键之后的第一段里找第一个 url */
    private static String firstUrlAfter(String json, String arrayKey) {
        if (json == null) return null;
        int i = json.indexOf("\"" + arrayKey + "\"");
        if (i < 0) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"url\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json.substring(i));
        return m.find() ? m.group(1).replace("\\/", "/") : null;
    }
}
