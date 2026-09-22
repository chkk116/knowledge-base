/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.FavoriteService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 收藏接口：/api/favorites/** 不在公开放行清单内，走 AuthFilter 强制登录。
 */
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    private final FavoriteService svc;
    private final UserRepository userRepo;

    public FavoriteController(FavoriteService svc, UserRepository userRepo) {
        this.svc = svc;
        this.userRepo = userRepo;
    }

    /** 切换收藏：返回 { favorited: true/false } */
    @PostMapping("/{docId}")
    public Map<String, Object> toggle(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long docId) {
        boolean favorited = svc.toggle(resolveUserId(c), docId);
        return Map.of("favorited", favorited);
    }

    /** 我收藏的文档列表（仅已发布，按收藏时间倒序） */
    @GetMapping("/mine")
    public List<Document> mine(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.myFavorites(resolveUserId(c));
    }

    /** 我收藏的文档 id 列表（用于前端标记卡片星标） */
    @GetMapping("/mine/ids")
    public List<Long> mineIds(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.myFavoriteIds(resolveUserId(c)).stream().sorted().toList();
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
