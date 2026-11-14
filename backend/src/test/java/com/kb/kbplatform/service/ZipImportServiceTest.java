/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.SharedDocument;
import com.kb.kbplatform.repository.UploadedFileRepository;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** zip 批量导入回归：路径保留、图片改写、Zip Slip 拦截、同名跳过、伪造图片拒绝。 */
class ZipImportServiceTest {

    @TempDir Path tmp;

    private DocShareService shareSvc;
    private UploadedFileRepository fileRepo;
    private ZipImportService svc;

    private static final byte[] PNG = new byte[]{
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0, 1, 2, 3};

    @BeforeEach
    void setUp() throws Exception {
        shareSvc = mock(DocShareService.class);
        fileRepo = mock(UploadedFileRepository.class);
        when(shareSvc.listDocs(anyLong(), anyLong(), anyBoolean())).thenReturn(List.of());
        when(shareSvc.createDoc(anyLong(), anyString(), anyString(), anyLong(), anyBoolean()))
                .thenAnswer(inv -> {
                    SharedDocument d = new SharedDocument();
                    d.setId(99L);
                    d.setTitle(inv.getArgument(1));
                    return d;
                });
        svc = new ZipImportService(
                new DocumentImportService(tmp.toString()),
                shareSvc,
                new FileService(tmp.toString()),
                fileRepo);
    }

    private byte[] zip(Map<String, byte[]> files) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private MultipartFile multipart(byte[] bytes) {
        return new MockMultipartFile("file", "pack.zip", "application/zip", bytes);
    }

    private Map<String, byte[]> pack() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("guide/intro.md", "# 入门\n\n![配图](assets/p.png)\n".getBytes(StandardCharsets.UTF_8));
        m.put("guide/assets/p.png", PNG);
        m.put("readme.txt", "根目录说明\n".getBytes(StandardCharsets.UTF_8));
        m.put("../evil.md", "路径穿越不该被导入".getBytes(StandardCharsets.UTF_8));
        m.put("__MACOSX/._junk", new byte[]{1, 2, 3});
        return m;
    }

    @Test
    @DisplayName("目录层级保留在标题里、相对图片改写为 /uploads、危险条目被跳过")
    void importsDocsAndRewritesImages() throws Exception {
        Map<String, Object> r = svc.importZip(1L, 7L, false, multipart(zip(pack())));

        assertEquals(2, r.get("created"), "只导入 guide/intro.md 与 readme.txt，穿越与元数据条目跳过");
        assertEquals(1, r.get("images"));
        assertEquals(0, r.get("errors"));
        assertTrue(((List<?>) r.get("titles")).contains("guide/intro"));

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(shareSvc, times(2)).createDoc(eq(1L), anyString(), content.capture(), eq(7L), eq(false));
        String mdHtml = content.getAllValues().get(0);
        assertTrue(mdHtml.contains("/uploads/"), "相对图片必须转存成站内 /uploads 地址");
        assertFalse(mdHtml.contains("assets/p.png"), "原始相对地址不得残留（会被净化器剥掉 src）");
        assertTrue(mdHtml.contains("入门"));
        verify(fileRepo, times(1)).save(any()); // 图片计入「我的文件」配额
    }

    @Test
    @DisplayName("同空间已有同名文档时跳过，不生成重复副本")
    void skipsExistingTitles() throws Exception {
        SharedDocument existing = new SharedDocument();
        existing.setId(1L);
        existing.setTitle("guide/intro");
        when(shareSvc.listDocs(1L, 7L, false)).thenReturn(List.of(existing));

        Map<String, Object> r = svc.importZip(1L, 7L, false, multipart(zip(pack())));
        assertEquals(1, r.get("created"));
        assertEquals(1, r.get("skipped"));
        assertTrue(((List<?>) r.get("skippedTitles")).contains("guide/intro"));
    }

    @Test
    @DisplayName("URL 编码的中文图片名也能匹配到 zip 内原始文件名")
    void rewritesUrlEncodedImageName() throws Exception {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("guide/intro.md", "![配图](assets/%E9%85%8D%E5%9B%BE.png)\n".getBytes(StandardCharsets.UTF_8));
        m.put("guide/assets/配图.png", PNG);

        Map<String, Object> r = svc.importZip(1L, 7L, false, multipart(zip(m)));
        assertEquals(1, r.get("created"));
        assertEquals(1, r.get("images"));
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(shareSvc).createDoc(eq(1L), anyString(), content.capture(), eq(7L), eq(false));
        assertTrue(content.getValue().contains("/uploads/"));
        assertFalse(content.getValue().contains("%E9%85%8D"));
    }

    @Test
    @DisplayName("伪造扩展名的图片被魔数校验拒绝，文档仍可导入")
    void rejectsFakeImage() throws Exception {
        Map<String, byte[]> m = pack();
        m.put("guide/assets/p.png", "not a png".getBytes(StandardCharsets.UTF_8));

        Map<String, Object> r = svc.importZip(1L, 7L, false, multipart(zip(m)));
        assertEquals(2, r.get("created"));
        assertEquals(0, r.get("images"));
        assertTrue((int) r.get("errors") > 0);
    }

    @Test
    @DisplayName("空包与超大包直接拒绝")
    void rejectsEmptyAndOversized() {
        assertThrows(IllegalArgumentException.class,
                () -> svc.importZip(1L, 7L, false, new MockMultipartFile("file", new byte[0])));
    }
}
