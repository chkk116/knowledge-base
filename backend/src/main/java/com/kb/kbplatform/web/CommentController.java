/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Comment;
import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.security.RateLimitService;
import com.kb.kbplatform.service.BizException;
import com.kb.kbplatform.service.CommentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api")
public class CommentController {

    private final CommentService svc;
    private final com.kb.kbplatform.repository.DocumentRepository docRepo;
    private final com.kb.kbplatform.repository.UserRepository userRepo;
    private final com.kb.kbplatform.repository.CommentRepository commentRepo;
    private final com.kb.kbplatform.service.AuditService auditService;
    private final RateLimitService rateLimit;

    public CommentController(CommentService svc, RateLimitService rateLimit,
                             com.kb.kbplatform.repository.DocumentRepository docRepo,
                             com.kb.kbplatform.repository.UserRepository userRepo,
                             com.kb.kbplatform.repository.CommentRepository commentRepo,
                             com.kb.kbplatform.service.AuditService auditService) {
        this.docRepo = docRepo;
        this.svc = svc;
        this.rateLimit = rateLimit;
        this.userRepo = userRepo;
        this.commentRepo = commentRepo;
        this.auditService = auditService;
    }

    /** 操作者显示名：优先昵称，回退用户名 */
    private String actorName(TokenClaims c) {
        return userRepo.findByUsername(c.username())
                .map(u -> (u.getDisplayName() != null && !u.getDisplayName().isBlank()) ? u.getDisplayName() : u.getUsername())
                .orElse(c.username());
    }

    /** 评论列表附带评论人昵称与头像（username 仍是唯一标识；匿名/已注销用户两字段为空） */
    private List<Map<String, Object>> withUser(List<Comment> list) {
        Set<String> names = list.stream()
                .map(Comment::getUsername)
                .filter(u -> u != null && !u.isBlank())
                .collect(Collectors.toSet());
        Map<String, User> byName = names.isEmpty() ? Map.of()
                : userRepo.findByUsernameIn(names).stream()
                    .collect(Collectors.toMap(User::getUsername, u -> u, (a, b) -> a));
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Comment c : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("targetType", c.getTargetType());
            m.put("docId", c.getDocId());
            m.put("parentId", c.getParentId());
            m.put("userId", c.getUserId());
            m.put("username", c.getUsername());
            m.put("content", c.getContent());
            m.put("likes", c.getLikes());
            m.put("createdAt", c.getCreatedAt());
            User u = c.getUsername() == null ? null : byName.get(c.getUsername());
            m.put("displayName", u == null ? null : u.getDisplayName());
            m.put("avatar", u == null ? null : u.getAvatar());
            out.add(m);
        }
        return out;
    }

    /** 公开：某文档的评论列表 */
    @GetMapping("/docs/{id}/comments")
    public List<Map<String, Object>> list(@PathVariable Long id) {
        return withUser(svc.list(id));
    }

    /** 我评论过的文档列表（仅已发布，按更新时间倒序），用于个人资料页展示（强制登录） */
    @GetMapping("/comments/mine/docs")
    public List<Document> myDocs(@RequestAttribute("kbUser") TokenClaims c) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        return svc.myCommentedDocs(c.username());
    }

    /** 登录用户发表评论（可带 parentId 进行楼中楼回复） */
    @PostMapping("/docs/{id}/comments")
    public Map<String, Object> add(@RequestAttribute("kbUser") TokenClaims c,
                       @PathVariable Long id,
                       @RequestBody Map<String, Object> body) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        // 抗刷屏：单用户 10 条/分钟（写接口此前无限流，实测连发 30 条全部成功）
        if (!rateLimit.allow("comment:user:" + c.userId(), 10, 60L * 1000)) {
            throw new BizException("评论过于频繁，请稍后再试");
        }
        // 与 addGeneric 对齐：审核中/下架的文章不允许评论（审查 r20 High）
        var d = docRepo.findById(id).orElse(null);
        if (d == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "内容不存在");
        if (!"PUBLISHED".equals(d.getStatus())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "内容尚未发布（审核中），暂不可评论");
        }
        Object pid = body.get("parentId");
        Long parentId = (pid == null || pid.toString().isBlank()) ? null : Long.valueOf(pid.toString());
        return withUser(List.of(svc.add(id, c, (String) body.get("content"), parentId))).get(0);
    }

    /** 通用评论列表（跨对象类型：DOC 帖子 / CHAPTER 章节），公开可见 */
    @GetMapping("/comments")
    public List<Map<String, Object>> listGeneric(@RequestParam String targetType, @RequestParam Long targetId) {
        return withUser(svc.list(targetType, targetId));
    }

    /** 通用发表评论（跨对象类型），登录可用 */
    @PostMapping("/comments")
    public Map<String, Object> addGeneric(@RequestAttribute("kbUser") TokenClaims c,
                              @RequestBody Map<String, Object> body) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        String targetType = body.get("targetType") == null ? "DOC" : body.get("targetType").toString();
        Object tid = body.get("targetId");
        if (!rateLimit.allow("comment:user:" + c.userId(), 10, 60L * 1000)) {
            throw new BizException("评论过于频繁，请稍后再试");
        }
        if (tid == null || tid.toString().isBlank()) throw new IllegalArgumentException("缺少 targetId");
        Long targetId = Long.valueOf(tid.toString());
        // 审核中/下架的文章不允许评论（DOC=帖子体系）
        if ("DOC".equalsIgnoreCase(targetType)) {
            var d = docRepo.findById(targetId).orElse(null);
            if (d == null) throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "内容不存在");
            if (!"PUBLISHED".equals(d.getStatus())) {
                throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "内容尚未发布（审核中），暂不可评论");
            }
        }
        Object pid = body.get("parentId");
        Long parentId = (pid == null || pid.toString().isBlank()) ? null : Long.valueOf(pid.toString());
        return withUser(List.of(svc.add(targetType, targetId, c, (String) body.get("content"), parentId))).get(0);
    }

    /** 删除评论：管理员可删任意评论，普通用户仅能删自己的 */
    @DeleteMapping("/comments/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long id) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        // 管理员可删任意评论：这里用 isAdmin()（role=ADMIN 或权限码覆盖管理员全集），
        // 与网关同一口径。原先写死 role=="ADMIN"，勾满权限的非 ADMIN 角色在别处是管理员，
        // 到这儿却只能删自己的评论（S-M6）。
        if (c.isAdmin()) {
            // 管理员等价操作（删他人评论）必须留审计：权限可以等价，责任要能追溯。
            // 记清操作者（含全权限非 ADMIN 账号，其 role 仍是 USER，靠 isAdmin() 等价）与评论作者。
            Comment target = commentRepo.findById(id).orElse(null);
            boolean other = target != null && c.username() != null && !c.username().equals(target.getUsername());
            if (other) {
                auditService.log(c.userId(), actorName(c), "ADMIN_DELETE_OTHER_COMMENT", "COMMENT", id,
                        target.getUsername(),
                        "管理员等价操作删除他人评论：operator=" + c.username() + " role=" + c.role()
                                + " targetType=" + target.getTargetType() + " docId=" + target.getDocId());
            }
            svc.delete(id);
        } else {
            svc.deleteOwn(id, c);
        }
        return Map.of("ok", true);
    }

    /** 切换评论点赞：返回 { liked: true/false, likes: 当前点赞数 } */
    @PostMapping("/comments/{id}/like")
    public Map<String, Object> likeComment(@RequestAttribute("kbUser") TokenClaims c,
                                           @PathVariable Long id) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        return svc.like(id, c.userId());
    }

    /** 我点过赞的评论 id 列表（用于前端标记点赞填充态） */
    @GetMapping("/comments/my-likes")
    public List<Long> myLikes(@RequestAttribute("kbUser") TokenClaims c) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        return svc.myLikedCommentIds(c.userId()).stream().sorted().toList();
    }
}
