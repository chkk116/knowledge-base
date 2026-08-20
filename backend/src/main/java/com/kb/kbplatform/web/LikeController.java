package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.LikeService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 点赞接口：/api/likes/** 不在公开放行清单内，走 AuthFilter 强制登录。
 */
@RestController
@RequestMapping("/api/likes")
public class LikeController {

    private final LikeService svc;
    private final UserRepository userRepo;

    public LikeController(LikeService svc, UserRepository userRepo) {
        this.svc = svc;
        this.userRepo = userRepo;
    }

    /** 切换点赞：返回 { liked: true/false, likes: 当前点赞数 } */
    @PostMapping("/{docId}")
    public Map<String, Object> toggle(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long docId) {
        boolean liked = svc.toggle(resolveUserId(c), docId);
        return Map.of("liked", liked, "likes", svc.likeCount(docId));
    }

    /** 我点过赞的文档 id 列表（用于前端标记 👍 填充态） */
    @GetMapping("/mine/ids")
    public List<Long> mineIds(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.myLikeIds(resolveUserId(c)).stream().sorted().toList();
    }

    /** 我点赞过的文档列表（仅已发布，按更新时间倒序），用于个人资料页展示 */
    @GetMapping("/mine/docs")
    public List<Document> mineDocs(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.myLikedDocs(resolveUserId(c));
    }

    /** 从令牌解析当前用户 id（强制登录） */
    private Long resolveUserId(TokenClaims c) {
        if (c == null || c.username() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        }
        return userRepo.findByUsername(c.username())
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "请先登录"));
    }
}
