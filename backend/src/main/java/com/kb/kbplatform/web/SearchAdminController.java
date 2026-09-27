/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.DocumentService;
import com.kb.kbplatform.service.MeiliSearchClient;
import com.kb.kbplatform.service.SearchService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/search")
public class SearchAdminController {

    private final MeiliSearchClient meili;
    private final DocumentService docSvc;
    private final SearchService searchSvc;

    public SearchAdminController(MeiliSearchClient meili, DocumentService docSvc, SearchService searchSvc) {
        this.meili = meili;
        this.docSvc = docSvc;
        this.searchSvc = searchSvc;
    }

    /** 管理员：重建 Meilisearch 索引（启用 Meili 后首次、或怀疑索引与数据库不一致时调用） */
    @PostMapping("/reindex")
    public Map<String, Object> reindex(@RequestAttribute("kbUser") TokenClaims c) {
        if (c == null || !c.isAdmin()) {   // 与 AuthFilter 门统一语义：真实 ADMIN 或权限覆盖全集（2026-09-21 统一）
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        if (!meili.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Meilisearch 未启用（请配置 kb.search.meili.host）");
        }
        meili.reindexAll(docSvc.listAll());
        return Map.of("ok", true, "message", "已提交重建索引任务（异步执行）");
    }

    /**
     * 管理员：检索测试 —— 用与用户搜索完全相同的链路跑一次查询，
     * 回报实际引擎 / 耗时 / 命中明细，供「检索测试」页验证搜索质量。纯只读。
     */
    @GetMapping("/test")
    public Map<String, Object> test(@RequestAttribute("kbUser") TokenClaims c,
                                    @RequestParam("q") String q,
                                    @RequestParam(value = "category", required = false) String category) {
        if (c == null || !c.isAdmin()) {   // 与 reindex 同一语义：仅 ADMIN 角色
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        return searchSvc.probe(q, category);
    }
}
