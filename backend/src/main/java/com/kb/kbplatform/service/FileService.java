package com.kb.kbplatform.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图片上传服务（本地磁盘模式）。
 * 将上传的图片保存到 kb.upload.dir 目录（按 yyyy/MM/dd 分子目录），
 * 返回可公开访问的相对 URL（如 /uploads/2026/08/14/uuid.jpg）。
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    // 允许常见位图（svg 可能被用于 XSS 故不放行）与常见音频（用于文章内插入语音条）
    private static final Set<String> ALLOWED = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/ogg",
            "audio/webm", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/flac"
    );

    private static final long MAX_SIZE = 50L * 1024 * 1024; // 50MB

    private final Path root;

    public FileService(@Value("${kb.upload.dir:./uploads}") String uploadDir) throws IOException {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        log.info("FileService 上传目录已就绪：{}", this.root);
    }

    /** 保存上传图片，返回可访问的相对 URL */
    public String upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String ct = file.getContentType();
        if (ct == null || !ALLOWED.contains(ct.toLowerCase())) {
            throw new IllegalArgumentException("仅支持图片或音频类型（png/jpg/gif/webp/bmp 或 mp3/wav/ogg/m4a/flac/aac）");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("文件过大，不能超过 50MB");
        }
        // 魔数嗅探：content-type 由客户端自报可伪造，校验文件头真实类型，防 HTML/脚本改名上传
        if (!magicMatches(ct.toLowerCase(), readHead(file))) {
            throw new IllegalArgumentException("文件内容与声明类型不符，上传被拒绝");
        }
        LocalDate d = LocalDate.now();
        Path dateDir = root.resolve(String.format("%d/%02d/%02d",
                d.getYear(), d.getMonthValue(), d.getDayOfMonth()));
        try {
            Files.createDirectories(dateDir);
        } catch (IOException e) {
            throw new RuntimeException("创建上传目录失败：" + e.getMessage(), e);
        }
        String ext = extOf(file.getOriginalFilename());
        String name = UUID.randomUUID().toString().replace("-", "") + ext;
        Path target = dateDir.resolve(name);
        try {
            file.transferTo(target.toFile());
        } catch (IOException e) {
            throw new RuntimeException("保存文件失败：" + e.getMessage(), e);
        }
        return String.format("/uploads/%d/%02d/%02d/%s",
                d.getYear(), d.getMonthValue(), d.getDayOfMonth(), name);
    }

    private String extOf(String fn) {
        if (fn == null) return "";
        int i = fn.lastIndexOf('.');
        if (i < 0 || i == fn.length() - 1) return "";
        String e = fn.substring(i).toLowerCase();
        return e.matches("\\.(png|jpe?g|gif|webp|bmp|mp3|wav|ogg|webm|m4a|flac|aac|mp4)") ? e : "";
    }

    /** 读取文件前 12 字节用于魔数校验（不整文件加载，避免大文件占内存） */
    private byte[] readHead(MultipartFile file) {
        try (var in = file.getInputStream()) {
            byte[] head = new byte[12];
            int n = in.readNBytes(head, 0, 12);
            if (n < 12) {
                byte[] trimmed = new byte[Math.max(n, 0)];
                System.arraycopy(head, 0, trimmed, 0, Math.max(n, 0));
                return trimmed;
            }
            return head;
        } catch (IOException e) {
            throw new RuntimeException("读取上传文件失败：" + e.getMessage(), e);
        }
    }

    /** 按声明的 content-type 校验文件头魔数；无法识别的类型一律拒绝（宁严勿宽） */
    private boolean magicMatches(String ct, byte[] h) {
        if (h == null || h.length < 4) return false;
        return switch (ct) {
            case "image/png" -> at(h, 0, 0x89, 0x50, 0x4E, 0x47);                       // 89 50 4E 47 = PNG 头
            case "image/jpeg" -> at(h, 0, 0xFF, 0xD8, 0xFF);                            // JFIF/Exif
            case "image/gif" -> at(h, 0, 0x47, 0x49, 0x46, 0x38);                       // GIF8
            case "image/bmp" -> at(h, 0, 0x42, 0x4D);                                   // BM
            case "image/webp" -> at(h, 0, 0x52, 0x49, 0x46, 0x46) && at(h, 8, 0x57, 0x45, 0x42, 0x50); // RIFF....WEBP
            case "audio/wav", "audio/x-wav" -> at(h, 0, 0x52, 0x49, 0x46, 0x46) && at(h, 8, 0x57, 0x41, 0x56, 0x45); // RIFF....WAVE
            case "audio/ogg" -> at(h, 0, 0x4F, 0x67, 0x67, 0x53);                       // OggS
            case "audio/flac" -> at(h, 0, 0x66, 0x4C, 0x61, 0x43);                      // fLaC
            case "audio/mpeg", "audio/mp3", "audio/aac" ->
                    at(h, 0, 0x49, 0x44, 0x33)                                          // ID3 标签
                    || (b(h, 0) == 0xFF && (b(h, 1) & 0xE0) == 0xE0);                   // MPEG 帧同步
            case "audio/mp4", "audio/x-m4a", "audio/webm" ->
                    at(h, 4, 0x66, 0x74, 0x79, 0x70)                                    // ....ftyp（M4A/MP4）
                    || at(h, 0, 0x1A, 0x45, 0xDF, 0xA3);                                // EBML（webm）
            default -> false;
        };
    }

    private static boolean at(byte[] h, int off, int... expected) {
        if (h.length < off + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((h[off + i] & 0xFF) != expected[i]) return false;
        }
        return true;
    }

    private static int b(byte[] h, int i) { return h[i] & 0xFF; }

    /** 扫描上传目录中未被任何文档引用的孤儿图片（文档正文里出现的 /uploads/... 视为已引用；
     *  contents 传入文档正文列表（标量投影即可，勿传全量实体）；extraUsed 可传入其他已引用
     *  URL（如用户头像 /uploads/...），防止误删在用文件。
     *  返回 [{ url, size, lastModified }]，删除由 deleteByUrl 完成。 */
    public List<Map<String, Object>> findOrphans(List<String> contents, Set<String> extraUsed) {
        Set<String> used = new HashSet<>();
        if (extraUsed != null) used.addAll(extraUsed);
        Pattern p = Pattern.compile("/uploads/[^\"'\\s)]+");
        for (String content : contents) {
            if (content == null || content.isEmpty()) continue;
            Matcher m = p.matcher(content);
            while (m.find()) used.add(m.group());
        }
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Files.walk(root)
                    .filter(Files::isRegularFile)
                    .forEach(path -> {
                        String rel = "/uploads/" + root.relativize(path).toString().replace('\\', '/');
                        if (!used.contains(rel)) {
                            Map<String, Object> info = new LinkedHashMap<>();
                            info.put("url", rel);
                            try {
                                info.put("size", Files.size(path));
                                info.put("lastModified", Files.getLastModifiedTime(path).toMillis());
                            } catch (IOException ignore) {
                                info.put("size", 0L);
                                info.put("lastModified", 0L);
                            }
                            result.add(info);
                        }
                    });
        } catch (IOException e) {
            log.warn("扫描上传目录失败：{}", e.getMessage());
        }
        return result;
    }

    /** 按相对 URL 删除孤儿文件（/uploads/...）。做了目录穿越防护：解析后必须仍在上传根目录内。 */
    public void deleteByUrl(String url) {
        if (url == null || !url.startsWith("/uploads/")) {
            throw new IllegalArgumentException("非法路径");
        }
        Path target = root.resolve(url.substring("/uploads/".length())).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法路径");
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new RuntimeException("删除失败：" + e.getMessage(), e);
        }
    }
}
