/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

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

    // 允许常见位图（svg 可能被用于 XSS 故不放行）、常见音频（文章内插入语音条）
    // 与常见视频（科普/培训文档插入讲解视频；视频不含脚本，XSS 面由 HtmlSanitizer 白名单控制）
    private static final Set<String> ALLOWED = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
            "audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/ogg",
            "audio/webm", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/flac",
            "video/mp4", "video/webm", "video/ogg", "video/quicktime",
            "application/pdf"   // 多页扫描导出的 PDF（App 内用链接分享/下载）
    );

    private static final long MAX_SIZE = 50L * 1024 * 1024; // 50MB

    private final Path root;

    public FileService(@Value("${kb.upload.dir:./uploads}") String uploadDir) throws IOException {
        this.root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        log.info("FileService 上传目录已就绪：{}", this.root);
    }

    /** 类型/大小/魔数校验（抽取出来是为了让「去重复用」分支在落盘前就能判定合法性） */
    public void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String ct = file.getContentType();
        if (ct == null || !ALLOWED.contains(ct.toLowerCase())) {
            throw new IllegalArgumentException("仅支持图片/音频/视频/PDF 类型（png/jpg/gif/webp/bmp、mp3/wav/ogg/m4a/flac/aac、mp4/webm 或 pdf）");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new IllegalArgumentException("文件过大，不能超过 50MB");
        }
        // 魔数嗅探：content-type 由客户端自报可伪造，校验文件头真实类型，防 HTML/脚本改名上传
        if (!magicMatches(ct.toLowerCase(), readHead(file))) {
            throw new IllegalArgumentException("文件内容与声明类型不符，上传被拒绝");
        }
    }

    /** 按 /uploads/... 地址取物理文件（发信附件用）；越界或不存在返回 null */
    public java.io.File fileByUrl(String url) {
        if (url == null || !url.startsWith("/uploads/")) return null;
        try {
            Path p = root.resolve(url.substring("/uploads/".length())).normalize();
            if (!p.startsWith(root) || !Files.isRegularFile(p)) return null;
            return p.toFile();
        } catch (Exception e) {
            return null;
        }
    }

    /** 内容摘要（流式，50MB 也不进堆内存）。用于同用户重复上传复用 */
    public String sha256(MultipartFile file) {
        try (var in = file.getInputStream()) {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException("计算文件摘要失败：" + e.getMessage(), e);
        }
    }

    /** 相对 URL 对应的文件是否还在磁盘上（去重复用前必须确认，否则会把用户指到一个 404） */
    public boolean exists(String url) {
        if (url == null || !url.startsWith("/uploads/")) return false;
        Path p = root.resolve(url.substring("/uploads/".length())).normalize();
        return p.startsWith(root) && Files.isRegularFile(p);
    }

    // ==================== 私信附件（私有目录，不经 /uploads 公开白名单） ====================
    // 背景：私信图片原先走编辑器上传落在 /uploads 公开目录，AuthFilter 对 /uploads 全放行，
    // 任何拿到链接的人都能打开，撤回也只清数据库 content、文件永久留盘。
    // 现在私信图片单独落 uploads/dm/：该目录静态映射在 AuthFilter 里被直接 404，
    // 只能经 /api/dm/attachment 按「会话双方」鉴权读取。

    /** 私信图片上传：只返回文件名，消息 content 存 dm:<name>，不暴露物理路径 */
    public String uploadDmImage(MultipartFile file) {
        validate(file);
        String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (!ct.startsWith("image/")) throw new IllegalArgumentException("私信附件仅支持图片");
        if (file.getSize() > 8L * 1024 * 1024) throw new IllegalArgumentException("私信图片不能超过 8MB");
        String ext = extOf(file.getOriginalFilename());
        String name = UUID.randomUUID().toString().replace("-", "") + ext;
        Path dir = root.resolve("dm");
        try {
            Files.createDirectories(dir);
            file.transferTo(dir.resolve(name).toFile());
        } catch (IOException e) {
            throw new RuntimeException("保存私信附件失败：" + e.getMessage(), e);
        }
        return name;
    }

    /**
     * 把消息里的引用解析成磁盘文件。
     * 新格式 dm:<uuid>[.ext] 落在私有目录；旧格式 /uploads/yyyy/mm/dd/<uuid>[.ext] 为历史消息兼容。
     * 文件名严格白名单（32 位十六进制 + 可选扩展名），杜绝 ../ 路径穿越。
     */
    public Path resolveDmRef(String ref) {
        if (ref == null || ref.isBlank()) return null;
        String rel;
        if (ref.startsWith("dm:")) {
            String name = ref.substring(3);
            if (!name.matches("[a-f0-9]{32}(\\.[A-Za-z0-9]{1,8})?")) return null;
            rel = "dm/" + name;
        } else if (ref.startsWith("/uploads/")) {
            rel = ref.substring("/uploads/".length());
            if (!rel.matches("\\d{4}/\\d{2}/\\d{2}/[a-f0-9]{32}(\\.[A-Za-z0-9]{1,8})?")) return null;
        } else {
            return null;
        }
        Path p = root.resolve(rel).normalize();
        return p.startsWith(root) && Files.isRegularFile(p) ? p : null;
    }

    /** 撤回时删除附件。只删私有目录里的新格式；历史 /uploads 图片可能被文章正文引用，不自动动 */
    public void deleteDmRef(String ref) {
        if (ref == null || !ref.startsWith("dm:")) return;
        Path p = resolveDmRef(ref);
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // 权限/占用导致删不掉：消息本身已不可读（content 清空 + kind=RECALL），
            // 但磁盘上会留一份无痕迹的残留，至少要留日志供排查。
            log.warn("删除私信附件失败：{} ({})", ref, e.getMessage());
        }
    }

    public String contentTypeOf(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".bmp")) return "image/bmp";
        return "application/octet-stream";
    }

    /** 保存上传图片，返回可访问的相对 URL */
    public String upload(MultipartFile file) {
        validate(file);
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

    /**
     * zip 批量导入用：按扩展名 + 魔数校验图片字节，UUID 命名落到 uploads，返回可访问 URL。
     * 不走 MultipartFile 路径但复用同一套白名单与目录结构，避免导入包绕过校验塞任意文件。
     */
    public String storeImageBytes(String filename, byte[] bytes) {
        String ext = extOf(filename);
        String ct = switch (ext) {
            case ".png" -> "image/png";
            case ".jpg", ".jpeg" -> "image/jpeg";
            case ".gif" -> "image/gif";
            case ".webp" -> "image/webp";
            case ".bmp" -> "image/bmp";
            default -> throw new IllegalArgumentException("不支持的图片类型：" + filename);
        };
        if (bytes == null || bytes.length < 4 || bytes.length > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("图片为空或过大：" + filename);
        }
        byte[] head = new byte[Math.min(12, bytes.length)];
        System.arraycopy(bytes, 0, head, 0, head.length);
        if (!magicMatches(ct, head)) throw new IllegalArgumentException("图片内容校验失败：" + filename);
        LocalDate d = LocalDate.now();
        Path dateDir = root.resolve(String.format("%d/%02d/%02d",
                d.getYear(), d.getMonthValue(), d.getDayOfMonth()));
        try {
            Files.createDirectories(dateDir);
            String name = UUID.randomUUID().toString().replace("-", "") + ext;
            Files.write(dateDir.resolve(name), bytes);
            return String.format("/uploads/%d/%02d/%02d/%s",
                    d.getYear(), d.getMonthValue(), d.getDayOfMonth(), name);
        } catch (IOException e) {
            throw new RuntimeException("保存图片失败：" + e.getMessage(), e);
        }
    }

    private String extOf(String fn) {
        if (fn == null) return "";
        int i = fn.lastIndexOf('.');
        if (i < 0 || i == fn.length() - 1) return "";
        String e = fn.substring(i).toLowerCase();
        return e.matches("\\.(png|jpe?g|gif|webp|bmp|mp3|wav|ogg|webm|m4a|flac|aac|mp4|pdf)") ? e : "";
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
            case "audio/mp4", "audio/x-m4a", "audio/webm",
                 "video/mp4", "video/quicktime" ->
                    at(h, 4, 0x66, 0x74, 0x79, 0x70)                                    // ....ftyp（M4A/MP4/MOV）
                    || at(h, 0, 0x1A, 0x45, 0xDF, 0xA3);                                // EBML（webm）
            case "video/webm" -> at(h, 0, 0x1A, 0x45, 0xDF, 0xA3);                      // EBML（webm）
            case "video/ogg" -> at(h, 0, 0x4F, 0x67, 0x67, 0x53);                       // OggS
            case "application/pdf" -> at(h, 0, 0x25, 0x50, 0x44, 0x46);                 // %PDF
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
    /**
     * 受保护的顶层目录：不属于「文档/头像/文件中心」引用体系，但仍在对外分发，
     * 孤儿扫描必须跳过，否则管理员点一次清理就会删掉正在分发的安装包（下载页立刻 404）。
     * desktop = 桌面端安装包（发版脚本直接放进去）；apk / app / dist 同理。
     */
    private static final java.util.Set<String> PROTECTED_TOP_DIRS =
            java.util.Set.of("dm", "desktop", "apk", "app", "dist", "files");

    /** 受保护的安装包扩展名：即使放在 uploads 根目录，也不当孤儿 */
    private static final java.util.Set<String> PROTECTED_EXT =
            java.util.Set.of("exe", "msi", "apk", "dmg", "pkg", "deb", "zip");

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
        // 私信附件在私有目录，引用形如 dm:<name>（存在 kb_dm_message.content），不匹配这里
        // /uploads/... 形状的 used 集合。若不排除，每一张正在使用的私信图都会被误报成孤儿，
        // 管理员点「清理」就经由 deleteByUrl 把双方还在看的图删掉。未发送的私信上传另走 TTL 清理。
        Path dmDir = root.resolve("dm");   // 私信图片同上，单独排除
        // try-with-resources 关闭目录流（2026-10-13 审查 L1）：Files.walk 返回的 Stream 持有目录句柄，不关会泄漏文件描述符
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(dmDir))
                    .filter(path -> {
                        // 顶层受保护目录（desktop/apk/app/dist/files）整棵跳过
                        Path relPath = root.relativize(path);
                        if (relPath.getNameCount() > 1
                                && PROTECTED_TOP_DIRS.contains(relPath.getName(0).toString())) return false;
                        // uploads 根目录下的安装包文件同样不算孤儿
                        return true;
                    })
                    .filter(path -> {
                        Path relPath = root.relativize(path);
                        if (relPath.getNameCount() != 1) return true;
                        String fileName = relPath.getFileName().toString();
                        int dot = fileName.lastIndexOf(46);
                        String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase();
                        return !PROTECTED_EXT.contains(ext);
                    })
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
        // 只删常规文件（2026-10-13 审查 L2）：防止 URL 指到目录时误删整个目录
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("目标不是常规文件");
        }
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new RuntimeException("删除失败：" + e.getMessage(), e);
        }
    }
}
