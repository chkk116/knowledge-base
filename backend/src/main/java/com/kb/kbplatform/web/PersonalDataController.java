/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.security.RateLimitService;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.PersonalDataExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;

import static org.springframework.http.HttpStatus.TOO_MANY_REQUESTS;

/**
 * 个人数据导出（隐私政策「你的权利」）。
 * 只导出当前登录用户自己的数据；限流防止反复全量导出拖垮数据库。
 */
@RestController
@RequestMapping("/api/me")
public class PersonalDataController {

    private final PersonalDataExportService exportService;
    private final RateLimitService rateLimit;

    public PersonalDataController(PersonalDataExportService exportService, RateLimitService rateLimit) {
        this.exportService = exportService;
        this.rateLimit = rateLimit;
    }

    /** 下载我的数据（JSON 附件） */
    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export(@RequestAttribute("kbUser") TokenClaims c) {
        // 每用户每小时最多 3 次：全量导出会扫多张表，不能当普通查询用
        if (!rateLimit.allow("me:export:" + c.userId(), 3, 60L * 60 * 1000)) {
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "导出过于频繁，请稍后再试");
        }
        Map<String, Object> data = exportService.export(c.userId());

        String date = LocalDate.now().toString();
        String ascii = "kb-export-" + date + ".json";
        String utf8 = URLEncoder.encode("kb-导出-" + c.username() + "-" + date + ".json", StandardCharsets.UTF_8)
                .replace("+", "%20");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8));
        headers.set(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8);
        // 导出内容含个人信息，禁止任何中间层缓存
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(data, headers, org.springframework.http.HttpStatus.OK);
    }
}
