/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.service.FileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/files")
public class FileController {

    private final FileService svc;

    public FileController(FileService svc) { this.svc = svc; }

    /**
     * 图片上传（需登录）。返回 wangEditor 兼容格式：
     * { "errno": 0, "data": { "url": "...", "alt": "", "href": "" } }
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
}
