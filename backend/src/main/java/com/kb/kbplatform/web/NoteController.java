package com.kb.kbplatform.web;

import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.NoteService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api")
public class NoteController {

    private final NoteService svc;

    public NoteController(NoteService svc) { this.svc = svc; }

    /** 取本人某章节笔记（私有） */
    @GetMapping("/chapters/{chapterId}/note")
    public Map<String, Object> get(@RequestAttribute("kbUser") TokenClaims c,
                                    @PathVariable Long chapterId) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        return Map.of("content", svc.getNote(c.userId(), chapterId));
    }

    /** 保存本人某章节笔记（自动 upsert） */
    @PutMapping("/chapters/{chapterId}/note")
    public Map<String, Object> save(@RequestAttribute("kbUser") TokenClaims c,
                                    @PathVariable Long chapterId,
                                    @RequestBody Map<String, String> body) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        String content = svc.saveNote(c.userId(), chapterId, body.get("content"));
        return Map.of("content", content, "ok", true);
    }
}
