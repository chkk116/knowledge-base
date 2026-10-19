/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuditService;
import com.kb.kbplatform.service.WebClipService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 网页剪藏：只做「抓取预览」，保存走既有的建文档接口。
 *
 * 拆成两步而不是「一键存」的原因：
 *   1. 抓取可能抽错正文（动态渲染页、需要登录的页），用户得先看一眼再决定存不存；
 *   2. 标题/摘要让用户能改，比直接塞一篇乱码文档体验好得多。
 */
@RestController
@RequestMapping("/api/clip")
public class WebClipController {

    private final WebClipService svc;
    private final AuditService audit;

    public WebClipController(WebClipService svc, AuditService audit) {
        this.svc = svc;
        this.audit = audit;
    }

    /** 抓取网页并返回 { url, title, site, excerpt, content, length } */
    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestAttribute("kbUser") TokenClaims c,
                                       @RequestBody Map<String, Object> body) {
        Object raw = body.get("url");
        String url = raw == null ? "" : String.valueOf(raw);
        Map<String, Object> res = svc.preview(url, c.userId());
        audit.log(c.userId(), c.username(), "CLIP_WEB_PAGE", "WEB_CLIP", null, null,
                "剪藏网页：" + res.get("site") + " · " + res.get("title"));
        return res;
    }
}
