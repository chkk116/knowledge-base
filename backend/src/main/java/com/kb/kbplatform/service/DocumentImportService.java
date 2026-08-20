package com.kb.kbplatform.service;

import com.kb.kbplatform.security.HtmlSanitizer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.*;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档导入服务：把上传的文档文件（.md/.txt/.html/.docx/.pdf）解析为 HTML，
 * 组装成「帖子」（Document）并发布。docx 内嵌图片会被提取到 kb.upload.dir，
 * 以 /uploads/... 形式写入 <img src>，经 HtmlSanitizer 净化后保留。
 *
 * 说明：本服务仅在后端解析，前端只需把文件 POST 到 /api/docs/import 即可，
 * 返回创建好的 Document（含 id），前端可直接跳转查看。
 */
@Service
public class DocumentImportService {

    private static final Logger log = LoggerFactory.getLogger(DocumentImportService.class);

    private static final Set<String> SUPPORTED =
            Set.of("md", "markdown", "txt", "text", "html", "htm", "docx", "pdf");

    private static final Pattern HEX_COLOR = Pattern.compile("[0-9A-Fa-f]{6}");

    private final Path uploadRoot;
    private final Parser mdParser;
    private final HtmlRenderer mdRenderer;

    public DocumentImportService(@Value("${kb.upload.dir:./uploads}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        List<?> mdExts = List.of(TablesExtension.create());
        // commonmark 扩展类型在编译期需为 Extension，这里用通配避免显式 import 冲突
        this.mdParser = Parser.builder().extensions((List) mdExts).build();
        this.mdRenderer = HtmlRenderer.builder().extensions((List) mdExts).build();
        try {
            Files.createDirectories(this.uploadRoot);
        } catch (IOException e) {
            log.warn("文档导入：创建上传目录失败 {}", e.getMessage());
        }
    }

    /** 解析上传文件为 {title, content(html)}，不落库；前端把内容填入编辑器供用户修改后再自行发布 */
    public Map<String, String> parseToDraft(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件为空");
        if (file.getSize() > 20L * 1024 * 1024) {
            throw new IllegalArgumentException("文档过大，不能超过 20MB");
        }
        String name = file.getOriginalFilename();
        String ext = extOf(name);
        if (ext == null || !SUPPORTED.contains(ext)) {
            throw new IllegalArgumentException("不支持的文档类型：" + (ext == null ? "(无扩展名)" : ext)
                    + "，支持 " + String.join(" / ", SUPPORTED));
        }
        try {
            byte[] bytes = file.getBytes();
            String title = baseName(name);
            String html = switch (ext) {
                case "md", "markdown" -> parseMarkdown(new String(bytes, StandardCharsets.UTF_8));
                case "txt", "text"    -> parseText(new String(bytes, StandardCharsets.UTF_8));
                case "html", "htm"    -> HtmlSanitizer.sanitize(new String(bytes, StandardCharsets.UTF_8));
                case "docx"           -> parseDocx(bytes);
                case "pdf"            -> parsePdf(bytes);
                default -> throw new IllegalArgumentException("不支持的类型：" + ext);
            };
            if (title.isBlank()) title = "导入的文档";
            Map<String, String> r = new HashMap<>();
            r.put("title", title);
            r.put("content", HtmlSanitizer.sanitize(html));
            return r;
        } catch (IOException e) {
            throw new RuntimeException("读取文档失败：" + e.getMessage(), e);
        }
    }

    // ---------------- Markdown ----------------
    private String parseMarkdown(String md) {
        Node node = mdParser.parse(md);
        return mdRenderer.render(node);
    }

    // ---------------- 纯文本 ----------------
    private String parseText(String txt) {
        StringBuilder sb = new StringBuilder();
        for (String line : txt.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            sb.append("<p>").append(escape(t)).append("</p>\n");
        }
        return sb.toString();
    }

    // ---------------- DOCX（Apache POI）----------------
    private String parseDocx(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = new ByteArrayInputStream(bytes);
             XWPFDocument doc = new XWPFDocument(in)) {
            for (IBodyElement el : doc.getBodyElements()) {
                if (el instanceof XWPFParagraph p) {
                    String h = paragraphToHtml(p, doc);
                    if (!h.isEmpty()) sb.append(h);
                } else if (el instanceof XWPFTable t) {
                    sb.append(tableToHtml(t));
                }
            }
        }
        return sb.toString();
    }

    private String paragraphToHtml(XWPFParagraph p, XWPFDocument doc) {
        StringBuilder inner = new StringBuilder();
        boolean hasText = false;
        if (p.getRuns() != null) {
            for (XWPFRun run : p.getRuns()) {
                String text = run.getText(0);
                if (text != null && !text.isEmpty()) {
                    hasText = true;
                    inner.append(applyRunStyle(escape(text), run));
                }
                try {
                    List<XWPFPicture> pics = run.getEmbeddedPictures();
                    if (pics != null) {
                        for (XWPFPicture pic : pics) {
                            String url = savePicture(pic.getPictureData());
                            if (url != null) inner.append("<img src=\"").append(url).append("\" alt=\"\"/>");
                        }
                    }
                } catch (Exception ignore) {
                    // 单张图片提取失败不影响整体导入
                }
            }
        }
        if (!hasText && inner.length() == 0) return ""; // 空段落不输出
        if (isListItem(p)) {
            return "<ul><li>" + inner + "</li></ul>\n";
        }
        return "<" + headingTag(p, doc) + ">" + inner + "</" + headingTag(p, doc) + ">\n";
    }

    private String tableToHtml(XWPFTable t) {
        StringBuilder sb = new StringBuilder("<table>\n<tbody>\n");
        for (XWPFTableRow row : t.getRows()) {
            sb.append("<tr>");
            for (XWPFTableCell cell : row.getTableCells()) {
                sb.append("<td>");
                StringBuilder cellHtml = new StringBuilder();
                for (XWPFParagraph cp : cell.getParagraphs()) {
                    cellHtml.append(escape(cp.getText())).append("<br/>");
                }
                sb.append(cellHtml).append("</td>");
            }
            sb.append("</tr>\n");
        }
        sb.append("</tbody>\n</table>\n");
        return sb.toString();
    }

    private String headingTag(XWPFParagraph p, XWPFDocument doc) {
        String styleId = p.getStyle();
        if (styleId != null && doc.getStyles() != null) {
            try {
                XWPFStyle style = doc.getStyles().getStyle(styleId);
                if (style != null && style.getName() != null
                        && style.getName().toLowerCase().contains("heading")) {
                    Matcher m = Pattern.compile("(\\d)").matcher(style.getName());
                    if (m.find()) {
                        int lvl = Integer.parseInt(m.group(1));
                        return "h" + Math.min(Math.max(lvl, 1), 6);
                    }
                    return "h2";
                }
            } catch (Exception ignore) {
                // 样式解析失败则按普通段落处理
            }
        }
        return "p";
    }

    private boolean isListItem(XWPFParagraph p) {
        try {
            var ppr = p.getCTP().getPPr();
            return ppr != null && ppr.getNumPr() != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String applyRunStyle(String text, XWPFRun run) {
        String s = text;
        try {
            String color = run.getColor();
            if (color != null && HEX_COLOR.matcher(color).matches()) {
                s = "<span style=\"color:#" + color + "\">" + s + "</span>";
            }
        } catch (Exception ignore) {
        }
        try {
            if (run.isStrikeThrough()) s = "<s>" + s + "</s>";
        } catch (Exception ignore) {
        }
        try {
            if (run.isItalic()) s = "<em>" + s + "</em>";
        } catch (Exception ignore) {
        }
        try {
            if (run.isBold()) s = "<strong>" + s + "</strong>";
        } catch (Exception ignore) {
        }
        return s;
    }

    /** 提取 docx 内嵌图片到上传目录，返回 /uploads/... 可访问 URL（失败返回 null） */
    private static final Set<String> IMG_EXT =
            Set.of("png", "jpg", "jpeg", "gif", "webp", "bmp");

    private String savePicture(XWPFPictureData data) {
        if (data == null) return null;
        try {
            byte[] bytes = data.getData();
            if (bytes == null || bytes.length == 0) return null;
            // 仅允许已知图片扩展名：docx 内嵌文件名可能携带 ../ 或异常后缀，
            // 若不过滤会造成路径穿越或把非图片二进制落盘到 /uploads
            String ext = data.suggestFileExtension();
            if (ext == null) ext = "png";
            ext = ext.toLowerCase().replaceAll("[^a-z0-9]", "");
            if (!IMG_EXT.contains(ext)) return null;
            LocalDate d = LocalDate.now();
            Path dateDir = uploadRoot.resolve(String.format("%d/%02d/%02d",
                    d.getYear(), d.getMonthValue(), d.getDayOfMonth()));
            Files.createDirectories(dateDir);
            String name = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            Files.write(dateDir.resolve(name), bytes);
            return String.format("/uploads/%d/%02d/%02d/%s",
                    d.getYear(), d.getMonthValue(), d.getDayOfMonth(), name);
        } catch (Exception e) {
            log.warn("提取 docx 图片失败：{}", e.getMessage());
            return null;
        }
    }

    // ---------------- PDF（Apache PDFBox）----------------
    private String parsePdf(byte[] bytes) throws IOException {
        StringBuilder sb = new StringBuilder();
        // PDFBox 3.0 起 PDDocument.load(InputStream/byte[]) 已移除，改用 Loader.loadPDF(...)
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            for (String line : text.split("\\r?\\n")) {
                String t = line.trim();
                if (t.isEmpty()) continue;
                sb.append("<p>").append(escape(t)).append("</p>\n");
            }
        }
        return sb.toString();
    }

    // ---------------- 工具 ----------------
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String extOf(String fn) {
        if (fn == null) return null;
        int i = fn.lastIndexOf('.');
        if (i < 0 || i == fn.length() - 1) return null;
        return fn.substring(i + 1).toLowerCase();
    }

    private static String baseName(String fn) {
        if (fn == null) return "";
        int i = fn.lastIndexOf('.');
        return (i < 0 ? fn : fn.substring(0, i)).trim();
    }
}
