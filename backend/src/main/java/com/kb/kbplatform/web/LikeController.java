/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.SharedDocument;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.SharedDocumentRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.LikeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * 点赞接口：/api/likes/** 不在公开放行清单内，走 AuthFilter 强制登录。
 *  - 知识库帖子（Document）：POST /api/likes/{docId}
 *  - 空间文档（SharedDocument）：POST /api/likes（body targetType=SDOC + targetId + ownerId）
 */
@RestController
@RequestMapping("/api/likes")
public class LikeController {

    private final LikeService svc;
    private final UserRepository userRepo;
    private final SharedDocumentRepository sharedDocRepo;

    public LikeController(LikeService svc, UserRepository userRepo, SharedDocumentRepository sharedDocRepo) {
        this.svc = svc;
        this.userRepo = userRepo;
        this.sharedDocRepo = sharedDocRepo;
    }

    /** 切换点赞：返回 { liked: true/false, likes: 当前点赞数 } */
    @PostMapping("/{docId}")
    public Map<String, Object> toggle(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long docId) {
        boolean liked = svc.toggle(resolveUserId(c), docId);
        return Map.of("liked", liked, "likes", svc.likeCount(docId));
    }

    /** 通用点赞（空间文档等）：返回 { liked, likes } */
    @PostMapping
    public Map<String, Object> toggleGeneric(@RequestAttribute("kbUser") TokenClaims c,
                                             @Valid @RequestBody LikeReq req) {
        Long uid = resolveUserId(c);
        boolean liked;
        switch (req.targetType) {
            case "DOC" -> {                       // 复用帖子点赞体系
                liked = svc.toggle(uid, req.targetId);
                return Map.of("liked", liked, "likes", svc.likeCount(req.targetId));
            }
            case "SDOC" -> {                      // 空间文档：独立表
                // 外部审查 2026-10-12：ownerId 一律由服务端按 targetId 反查真实属主，
                // 不再采信客户端入参——否则可定向给任意 userId 触发点赞通知（骚扰面）。
                Long realOwner = sharedDocRepo.findById(req.targetId)
                        .map(SharedDocument::getOwnerId).orElse(null);
                liked = svc.toggleShared(uid, req.targetId, realOwner);
                return Map.of("liked", liked, "likes", svc.sharedLikeCount(req.targetId));
            }
            default -> throw new ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "不支持的 targetType");
        }
    }

    /** 空间文档点赞数（前端进入页面时取初始值） */
    @GetMapping("/count")
    public Map<String, Object> count(@RequestParam String targetType,
                                     @RequestParam Long targetId) {
        int n = "SDOC".equals(targetType) ? svc.sharedLikeCount(targetId)
                : "DOC".equals(targetType) ? svc.likeCount(targetId) : 0;
        return Map.of("likes", n);
    }

    /** 我点过赞的文档 id 列表（targetType 缺省=DOC；传 SDOC 返回空间文档） */
    @GetMapping("/mine/ids")
    public List<Long> mineIds(@RequestAttribute("kbUser") TokenClaims c,
                              @RequestParam(required = false) String targetType) {
        Long uid = resolveUserId(c);
        if ("SDOC".equals(targetType)) {
            return svc.mySharedLikeIds(uid).stream().sorted().toList();
        }
        return svc.myLikeIds(uid).stream().sorted().toList();
    }

    /** 我点赞过的文档列表（仅已发布，按更新时间倒序），用于个人资料页展示 */
    @GetMapping("/mine/docs")
    public List<Document> mineDocs(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.myLikedDocs(resolveUserId(c));
    }

    /** 通用点赞请求体 */
    public static class LikeReq {
        @NotBlank public String targetType;   // DOC / SDOC
        @NotNull public Long targetId;        // 被点赞对象 id
        public Long ownerId;                  // 【已废弃·不再采信】属主由服务端反查（外部审查 2026-10-12）
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
