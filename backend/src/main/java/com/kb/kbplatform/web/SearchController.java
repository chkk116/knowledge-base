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
                                            @RequestParam(required = false) String author) {
        return svc.hybrid(q, category, author);
    }
}
