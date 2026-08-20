package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.DocumentService;
import com.kb.kbplatform.service.UserService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService svc;
    private final DocumentService docSvc;

    public UserController(UserService svc, DocumentService docSvc) {
        this.svc = svc;
        this.docSvc = docSvc;
    }

    /** 作者小卡片（公开） */
    @GetMapping("/{id}/card")
    public Map<String, Object> card(@RequestAttribute(value = "kbUser", required = false) TokenClaims c,
                                    @PathVariable Long id) {
        return svc.getCard(id, c == null ? null : c.userId());
    }

    /** 关注（需登录，不能关注自己） */
    @PostMapping("/{id}/follow")
    public Map<String, Object> follow(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        svc.follow(c.userId(), id);
        return Map.of("ok", true, "following", true);
    }

    /** 作者已发布文章（公开，分页）：供侧边栏「他的文章」列表，按浏览量倒序 */
    @GetMapping("/{id}/articles")
    public Page<Document> articles(@PathVariable Long id,
                                   @RequestParam(required = false, defaultValue = "0") int page,
                                   @RequestParam(required = false, defaultValue = "5") int size) {
        if (page < 0) page = 0;
        if (size <= 0 || size > 50) size = 5;
        return docSvc.listByAuthor(id, PageRequest.of(page, size));
    }

    /** 取消关注（需登录） */
    @DeleteMapping("/{id}/follow")
    public Map<String, Object> unfollow(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        svc.unfollow(c.userId(), id);
        return Map.of("ok", true, "following", false);
    }

    /** 我关注的人（公开，分页） */
    @GetMapping("/{id}/following")
    public List<Map<String, Object>> following(@PathVariable Long id) {
        return svc.listFollowing(id);
    }

    /** 我的粉丝（公开） */
    @GetMapping("/{id}/followers")
    public List<Map<String, Object>> followers(@PathVariable Long id) {
        return svc.listFollowers(id);
    }
}
