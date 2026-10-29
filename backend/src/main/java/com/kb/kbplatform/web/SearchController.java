/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.service.SearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SearchController {

    private final SearchService svc;

    public SearchController(SearchService svc) { this.svc = svc; }

    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String q,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String author,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) Integer days,
                                            @RequestParam(required = false) String tags) {
        return svc.hybrid(q, category, author, sort, days, tags);
    }
}
