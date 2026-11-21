/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.UploadedFile;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.SharedDocumentRepository;
import com.kb.kbplatform.repository.UploadedFileRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.DocShareService;
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
    private final DocShareService docShare;
    private final SharedDocumentRepository sharedDocRepo;
    private final com.kb.kbplatform.security.RateLimitService rateLimit;

    public FileController(FileService svc, UploadedFileRepository fileRepo, DocumentRepository docRepo,
                          com.kb.kbplatform.security.RateLimitService rateLimit,
                          DocShareService docShare, SharedDocumentRepository sharedDocRepo) {
        this.svc = svc;
        this.fileRepo = fileRepo;
        this.docRepo = docRepo;
        this.rateLimit = rateLimit;
        this.docShare = docShare;
        this.sharedDocRepo = sharedDocRepo;
    }

    /**
     * 图片上传（需登录）。返回 wangEditor 兼容格式：
     * { "errno": 0, "data": { "url": "...", "alt": "", "href": "" } }
     *
     * ⚠️ 这条是编辑器内联上传用，**不登记**到「我的文件」（否则正文每张配图都会灌进文件中心）。
     */
    @PostMapping
    public Map<String, Object> upload(@RequestAttribute("kbUser") com.kb.kbplatform.security.TokenClaims c,
                                      @RequestParam("file") MultipartFile file) {
        // 编辑器内联上传不登记归属、也不走「我的文件」的配额闸门，任一登录用户可无限传（白盒审计点）。
        // 这里补一道量级闸：单用户每天 300 次、单次仍限 50MB，正常写文章远够用。
        if (c == null || !rateLimit.allow("upload:inline:" + c.userId(), 300, 24L * 60 * 60 * 1000)) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "上传过于频繁，请稍后再试（单账号每天 300 次）");
        }
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
        // 先校验再算摘要：伪造类型/超大文件不必浪费时间读一遍内容
        svc.validate(file);
        String hash = svc.sha256(file);
        // 同一用户传过同样内容、且磁盘文件还在 → 直接复用原记录，不再写第二份
        // （磁盘文件可能已被管理员当孤儿清掉，所以必须确认 exists）
        UploadedFile dup = fileRepo.findFirstByOwnerIdAndHash(c.userId(), hash).orElse(null);
        if (dup != null) {
            if (svc.exists(dup.getUrl())) {
                Map<String, Object> res = payload(dup);
                res.put("deduped", true);
                return res;
            }
            // 残留记录：物理文件已被「孤儿清理」之类的操作删掉。必须清掉再写新的，
            // 否则同一 (ownerId, hash) 会留下两条，而 findFirst 没有排序，
            // 后续上传可能一直命中这条死记录、返回一个 404 链接。
            fileRepo.delete(dup);
        }
        // 配额闸门放在落盘前：否则超标文件已经写进磁盘才报错，等于白占空间
        docShare.assertUserStorage(c.userId(), file.getSize());
        String url = svc.upload(file);
        UploadedFile f = new UploadedFile(
                c.userId(), url,
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename(),
                file.getSize(),
                file.getContentType() == null ? "" : file.getContentType(),
                hash);
        fileRepo.save(f);
        return payload(f);
    }

    private Map<String, Object> payload(UploadedFile f) {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("ok", true);
        res.put("id", f.getId());
        res.put("url", f.getUrl());
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
        // 定向 LIKE 查询替代 findAllContents 全量载入内存（2026-10-13 审查 M8，同 C4）
        boolean referenced = f.getUrl() != null
                && (docRepo.countContentLike("%" + f.getUrl() + "%") > 0
                    || sharedDocRepo.countContentLike("%" + f.getUrl() + "%") > 0);
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
