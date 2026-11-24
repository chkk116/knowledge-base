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

    /** 关键词检索。
     *  page / size 可选：不传（或 size<=0）时返回全部结果，保持老客户端与前端「一次拉全再前端分页」的原行为；
     *  传了 size 才按页切片。此前这两个参数完全不存在，传了也被忽略、恒返回全量（N70）。 */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String q,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String author,
                                            @RequestParam(required = false) String sort,
                                            @RequestParam(required = false) Integer days,
                                            @RequestParam(required = false) String tags,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        List<Map<String, Object>> all = svc.hybrid(q, category, author, sort, days, tags);
        if (size == null || size <= 0) return all;
        int p = (page == null || page < 0) ? 0 : page;
        int from = Math.min(p * size, all.size());
        int to = Math.min(from + size, all.size());
        return all.subList(from, to);
    }
}
