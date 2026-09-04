package com.kb.kbplatform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 文章正文大图按宽度出缩略图：前端请求 /api/thumb?src=<uploads相对路径>&w=<宽度>，
 * 后端把原图等比缩放到指定宽度（保留透明通道的用 PNG，其余用有损 JPEG），
 * 结果按 宽 缓存在独立目录，命中即直接返回。用于避免老机解码数兆像素的原图。
 *
 * 安全：src 强制剥离前导斜杠并按上传目录归一化，越界即 404；仅处理 JDK 能读的格式，
 * GIF/WebP/动画等不支持或会丢动画的格式一律原样返回。
 */
@RestController
@RequestMapping("/api/thumb")
public class ThumbController {

    @Value("${kb.upload.dir:./uploads}")
    private String uploadDir;

    @Value("${kb.thumb.dir:./thumbs}")
    private String thumbDir;

    private static final long MAX_ORIGINAL_BYTES = 30L * 1024 * 1024;
    private static final int MIN_W = 40, MAX_W = 1600;
    private static final float JPEG_QUALITY = 0.82f;

    @GetMapping
    public ResponseEntity<byte[]> thumb(@RequestParam String src, @RequestParam int w) {
        // 1. 约束宽度范围
        w = Math.max(MIN_W, Math.min(MAX_W, w));

        // 2. 路径归一化 + 防穿越：src 必须先剥离前导斜杠，否则 Path.resolve 会当成绝对路径逃逸
        String rel = src.replaceFirst("^/+", "");
        Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
        Path file = root.resolve(rel).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        // 3. 不支持/会丢动画的格式（gif/webp/avif/svg/bmp 等）原样返回
        String lower = rel.toLowerCase();
        boolean animatable = lower.endsWith(".gif") || lower.endsWith(".webp")
                || lower.endsWith(".avif") || lower.endsWith(".svg") || lower.endsWith(".bmp");
        if (animatable) {
            return serveOriginal(file);
        }

        // 4. 原图已不比目标宽大 → 原样返回（不浪费算力）
        BufferedImage original;
        try {
            original = ImageIO.read(file.toFile());
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
        if (original == null) {
            return serveOriginal(file); // 解码失败（如 WebP）→ 退回原图
        }
        if (original.getWidth() <= w) {
            return serveOriginal(file);
        }

        // 5. 命中缓存直接返回
        Path cache = Paths.get(thumbDir).toAbsolutePath().normalize()
                .resolve(String.valueOf(w)).resolve(rel);
        if (Files.isRegularFile(cache)) {
            return serveFromCache(cache);
        }

        // 6. 等比缩放并重编码
        boolean hasAlpha = original.getColorModel().hasAlpha();
        int h = (int) Math.round(original.getHeight() * (w / (double) original.getWidth()));
        BufferedImage out = new BufferedImage(w, h,
                hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(original, 0, 0, w, h, null);
        g.dispose();

        byte[] bytes;
        MediaType type;
        if (hasAlpha) {
            bytes = toBytes(out, "png");
            type = MediaType.IMAGE_PNG;
        } else {
            bytes = toJpegBytes(out);
            type = MediaType.IMAGE_JPEG;
        }
        if (bytes == null) {
            return serveOriginal(file);
        }

        // 7. 写缓存（失败不影响本次返回）
        try {
            Files.createDirectories(cache.getParent());
            Files.write(cache, bytes);
        } catch (IOException ignore) { /* 缓存不可写则跳过 */ }

        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS))
                .body(bytes);
    }

    private byte[] toBytes(BufferedImage img, String format) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, format, baos);
            return baos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] toJpegBytes(BufferedImage img) {
        try {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            }
            writer.dispose();
            return baos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private ResponseEntity<byte[]> serveOriginal(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > MAX_ORIGINAL_BYTES) {
                return ResponseEntity.status(200)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(bytes);
            }
            return ResponseEntity.ok()
                    .contentType(detectType(file))
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS))
                    .body(bytes);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private ResponseEntity<byte[]> serveFromCache(Path cache) {
        try {
            byte[] bytes = Files.readAllBytes(cache);
            return ResponseEntity.ok()
                    .contentType(detectType(cache))
                    .cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS))
                    .body(bytes);
        } catch (IOException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private MediaType detectType(Path file) {
        String n = file.getFileName().toString().toLowerCase();
        if (n.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (n.endsWith(".gif")) return MediaType.IMAGE_GIF;
        if (n.endsWith(".webp")) return MediaType.valueOf("image/webp");
        if (n.endsWith(".svg")) return MediaType.valueOf("image/svg+xml");
        if (n.endsWith(".bmp")) return MediaType.valueOf("image/bmp");
        return MediaType.IMAGE_JPEG;
    }
}
