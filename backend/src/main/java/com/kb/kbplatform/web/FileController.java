/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.UploadedFile;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.UploadedFileRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.FileService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService svc;
    private final UploadedFileRepository fileRepo;
    private final DocumentRepository docRepo;

    public FileController(FileService svc, UploadedFileRepository fileRepo, DocumentRepository docRepo) {
        this.svc = svc;
        this.fileRepo = fileRepo;
        this.docRepo = docRepo;
    }

    /**
     * 图片上传（需登录）。返回 wangEditor 兼容格式：
     * { "errno": 0, "data": { "url": "...", "alt": "", "href": "" } }
     *
     * ⚠️ 这条是编辑器内联上传用，**不登记**到「我的文件」（否则正文每张配图都会灌进文件中心）。
     */
    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) {
        try {
            String url = svc.upload(file);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", url);
            data.put("alt", "");
            data.put("href", "");
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("errno", 0);
            res.put("data", data);
            return res;
        } catch (Exception e) {
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("errno", 1);
            res.put("message", e.getMessage() == null ? "上传失败" : e.getMessage());
            return res;
        }
    }

    // ==================== 「我的文件」文件中心 ====================

    /** 上传到文件中心：落盘 + 登记归属（编辑器上传不走这里） */
    @PostMapping("/mine")
    public Map<String, Object> uploadMine(@RequestAttribute("kbUser") TokenClaims c,
                                          @RequestParam("file") MultipartFile file) {
        String url = svc.upload(file);
        UploadedFile f = new UploadedFile(
                c.userId(), url,
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename(),
                file.getSize(),
                file.getContentType() == null ? "" : file.getContentType());
        fileRepo.save(f);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("id", f.getId());
        res.put("url", url);
        res.put("name", f.getName());
        res.put("size", f.getSize());
        res.put("contentType", f.getContentType());
        res.put("createdAt", f.getCreatedAt() == null ? "" : f.getCreatedAt().toString());
        return res;
    }

    /** 我的文件列表（新的在前） */
    @GetMapping("/mine")
    public List<Map<String, Object>> listMine(@RequestAttribute("kbUser") TokenClaims c) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UploadedFile f : fileRepo.findByOwnerIdOrderByCreatedAtDesc(c.userId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("url", f.getUrl());
            m.put("name", f.getName());
            m.put("size", f.getSize());
            m.put("contentType", f.getContentType());
            m.put("createdAt", f.getCreatedAt() == null ? "" : f.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    /**
     * 删除我的文件。
     * 安全取舍：文档正文仍在引用该文件时**只解除登记、不删物理文件** ——
     * 否则用户随手在文件中心一删，某篇文档里的配图就变裂图了。
     */
    @Transactional(rollbackFor = Exception.class)
    @DeleteMapping("/mine/{id}")
    public Map<String, Object> deleteMine(@RequestAttribute("kbUser") TokenClaims c,
                                          @PathVariable Long id) {
        UploadedFile f = fileRepo.findById(id).orElse(null);
        if (f == null) throw new ResponseStatusException(NOT_FOUND, "文件不存在");
        if (!Objects.equals(f.getOwnerId(), c.userId())) {
            throw new ResponseStatusException(FORBIDDEN, "只能删除自己的文件");
        }
        boolean referenced = docRepo.findAllContents().stream()
                .anyMatch(s -> s != null && f.getUrl() != null && s.contains(f.getUrl()));
        if (!referenced) {
            try {
                svc.deleteByUrl(f.getUrl());
            } catch (Exception ignore) {
                // 物理删除失败不影响登记解除（磁盘文件后续由管理员孤儿清理兜底）
            }
        }
        fileRepo.delete(f);
        return Map.of("ok", true);
    }
}
