/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.SharedDocument;
import com.kb.kbplatform.domain.UploadedFile;
import com.kb.kbplatform.repository.UploadedFileRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 外部知识包一键导入：把本地 Markdown 文件夹或 Notion / 语雀 / 飞书导出的 zip 批量导入共享空间。
 *
 * <p>共享空间的 SharedDocument 是扁平模型（没有 parentId），因此目录结构以「完整相对路径作标题」保留
 * （如 guide/intro），不新增 DDL；图片按 zip 内相对路径转存到 uploads 并在净化前改写引用。</p>
 *
 * <p>安全口径：流式逐条目读取，拒绝 ../ / 绝对路径 / 隐藏条目，限制条目数、单条与累计解压体积，
 * 图片落盘走 FileService 的魔数校验；逐篇独立事务，中途失败不影响已导入文档（与批量改标签同语义）。</p>
 */
@Service
public class ZipImportService {

    private static final Logger log = LoggerFactory.getLogger(ZipImportService.class);
    private static final long MAX_ZIP = 50L * 1024 * 1024;
    private static final int MAX_ENTRIES = 500;
    private static final long MAX_TOTAL = 100L * 1024 * 1024;
    private static final long MAX_ENTRY = 20L * 1024 * 1024;
    private static final Set<String> DOC_EXT = Set.of("md", "markdown", "txt", "text", "html", "htm", "docx", "pdf");
    private static final Set<String> IMG_EXT = Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    private final DocumentImportService importSvc;
    private final DocShareService shareSvc;
    private final FileService fileService;
    private final UploadedFileRepository fileRepo;

    public ZipImportService(DocumentImportService importSvc, DocShareService shareSvc,
                            FileService fileService, UploadedFileRepository fileRepo) {
        this.importSvc = importSvc;
        this.shareSvc = shareSvc;
        this.fileService = fileService;
        this.fileRepo = fileRepo;
    }

    /** 返回 created/skipped/errors/images 计数与最多 20 条明细，便于前端一次汇总展示 */
    public Map<String, Object> importZip(Long shareId, Long userId, boolean sysAdmin, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("压缩包为空");
        if (file.getSize() > MAX_ZIP) throw new IllegalArgumentException("压缩包不能超过 50MB");
        byte[] zipBytes;
        try {
            zipBytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("读取压缩包失败：" + e.getMessage(), e);
        }

        // Windows 压缩工具常用 GBK 且未必置 UTF-8 flag：先按 UTF-8 扫一遍条目名，乱码则整体换 GBK
        Map<String, byte[]> entries = readEntries(zipBytes, detectCharset(zipBytes));

        // 0) 落盘任何图片之前先过一次用户配额闸门：配额不足直接 413，
        //    否则会出现「图片都已写到 uploads、文档却因配额被拒」的孤儿文件
        long incoming = 0;
        for (byte[] b : entries.values()) incoming += b.length;
        shareSvc.assertUserStorage(userId, incoming);

        // 1) 图片先落盘并建立「zip 内相对路径 → /uploads URL」映射
        Map<String, String> images = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (!isImage(e.getKey())) continue;
            try {
                String url = fileService.storeImageBytes(e.getKey(), e.getValue());
                registerUpload(userId, e.getKey(), e.getValue(), url);
                images.put(e.getKey(), url);
            } catch (Exception ex) {
                errors.add(e.getKey() + "：" + ex.getMessage());
            }
        }

        // 2) 同空间同标题跳过（重复传包不生成整套副本）；列不出来时不去重，宁可重复也不误跳过
        Set<String> existing = new HashSet<>();
        try {
            for (SharedDocument d : shareSvc.listDocs(shareId, userId, sysAdmin)) {
                if (d.getTitle() != null) existing.add(d.getTitle());
            }
        } catch (Exception e) {
            log.warn("导入前读取已有文档失败，本次跳过同名检查: {}", e.getMessage());
        }

        // 3) 按路径排序逐篇导入，每篇 createDoc 自带事务
        List<String> names = new ArrayList<>(entries.keySet());
        Collections.sort(names);
        List<String> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String name : names) {
            if (!isDoc(name)) continue;
            String title = titleOf(name);
            if (existing.contains(title)) {
                skipped.add(title);
                continue;
            }
            try {
                Map<String, String> draft = importSvc.parseToDraft(name, entries.get(name),
                        html -> rewriteImages(html, name, images));
                shareSvc.createDoc(shareId, title, draft.get("content"), userId, sysAdmin);
                created.add(title);
                existing.add(title);
            } catch (Exception ex) {
                errors.add(title + "：" + (ex.getMessage() == null ? "导入失败" : ex.getMessage()));
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("created", created.size());
        r.put("skipped", skipped.size());
        r.put("errors", errors.size());
        r.put("images", images.size());
        r.put("titles", created.stream().limit(20).toList());
        r.put("skippedTitles", skipped.stream().limit(20).toList());
        r.put("errorList", errors.stream().limit(20).toList());
        return r;
    }

    /** 图片登记到「我的文件」，否则 zip 导入的图片不占配额、磁盘可被无限塞 */
    private void registerUpload(Long userId, String name, byte[] bytes, String url) {
        try {
            UploadedFile u = new UploadedFile();
            u.setOwnerId(userId);
            u.setUrl(url);
            u.setName(name.length() > 255 ? name.substring(0, 255) : name);
            u.setSize((long) bytes.length);
            u.setContentType(contentTypeOf(name));
            u.setHash(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            fileRepo.save(u);
        } catch (Exception e) {
            log.warn("登记导入图片失败（文件已落盘，不影响文档引用）: {}", e.getMessage());
        }
    }

    /** 把 Markdown/HTML 里的相对图片地址换成转存后的 /uploads URL；必须在 HtmlSanitizer 之前执行 */
    private String rewriteImages(String html, String docRel, Map<String, String> images) {
        if (html == null || html.isBlank() || images.isEmpty()) return html;
        org.jsoup.nodes.Document doc = Jsoup.parseBodyFragment(html);
        for (Element img : doc.select("img[src]")) {
            String src = img.attr("src").trim();
            if (src.isEmpty() || src.startsWith("/") || src.startsWith("http://")
                    || src.startsWith("https://") || src.startsWith("data:")) continue;
            String parent = docRel.contains("/") ? docRel.substring(0, docRel.lastIndexOf('/')) : "";
            String url = images.get(normalize(parent.isEmpty() ? src : parent + "/" + src));
            if (url == null) url = images.get(normalize(src)); // 部分导出包用仓库根目录相对路径
            if (url == null) {
                // Notion / 语雀导出常把中文图片名写成 %XX，而 zip 内条目是原始文件名
                try {
                    String decoded = java.net.URLDecoder.decode(src, StandardCharsets.UTF_8);
                    if (!decoded.equals(src)) {
                        url = images.get(normalize(parent.isEmpty() ? decoded : parent + "/" + decoded));
                        if (url == null) url = images.get(normalize(decoded));
                    }
                } catch (Exception ignored) {
                    // 非法百分号编码按原样处理，最多这一张图不改写
                }
            }
            if (url != null) img.attr("src", url);
        }
        return doc.body().html();
    }

    private Charset detectCharset(byte[] zip) {
        boolean garbled = false;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            int n = 0;
            while ((e = zin.getNextEntry()) != null && n++ < MAX_ENTRIES) {
                String name = e.getName();
                if (name.indexOf('\uFFFD') >= 0 || name.contains("?")) {
                    garbled = true;
                    break;
                }
            }
        } catch (Exception ignored) {
            // 探测失败按 UTF-8 走，正式解析会给出可读错误
        }
        return garbled ? Charset.forName("GBK") : StandardCharsets.UTF_8;
    }

    private Map<String, byte[]> readEntries(byte[] zip, Charset cs) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        byte[] buf = new byte[8192];
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zip), cs)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String raw = e.getName();
                if (!safeName(raw)) {
                    log.warn("导入跳过不安全条目：{}", raw);
                    continue;
                }
                if (++count > MAX_ENTRIES) throw new IllegalArgumentException("压缩包条目超过 " + MAX_ENTRIES + " 个");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int n;
                long size = 0;
                while ((n = zin.read(buf)) != -1) {
                    size += n;
                    total += n;
                    if (size > MAX_ENTRY || total > MAX_TOTAL) {
                        throw new IllegalArgumentException("压缩包解压后体积超限（可能是压缩炸弹）");
                    }
                    bos.write(buf, 0, n);
                }
                out.put(normalize(raw), bos.toByteArray());
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析压缩包失败：" + e.getMessage(), e);
        }
        return out;
    }

    /** 拒绝 ../、绝对路径、盘符、隐藏文件与 macOS 元数据目录（Zip Slip） */
    private boolean safeName(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String n = raw.replace('\\', '/');
        if (n.startsWith("/") || n.contains(":") || n.startsWith("__MACOSX/")) return false;
        for (String seg : n.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.startsWith(".")) return false;
        }
        return true;
    }

    private String normalize(String raw) {
        String n = raw.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        return Paths.get(n).normalize().toString().replace('\\', '/');
    }

    private String titleOf(String rel) {
        int i = rel.lastIndexOf('.');
        String t = i > 0 ? rel.substring(0, i) : rel;
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private String ext(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private boolean isDoc(String name) { return DOC_EXT.contains(ext(name)); }
    private boolean isImage(String name) { return IMG_EXT.contains(ext(name)); }

    private String contentTypeOf(String name) {
        return switch (ext(name)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "image/bmp";
        };
    }
}
