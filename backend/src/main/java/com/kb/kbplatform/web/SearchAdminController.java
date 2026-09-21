package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.DocumentService;
import com.kb.kbplatform.service.MeiliSearchClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/search")
public class SearchAdminController {

    private final MeiliSearchClient meili;
    private final DocumentService docSvc;

    public SearchAdminController(MeiliSearchClient meili, DocumentService docSvc) {
        this.meili = meili;
        this.docSvc = docSvc;
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
}
