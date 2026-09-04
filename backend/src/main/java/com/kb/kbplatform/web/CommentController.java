package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Comment;
import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.CommentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api")
public class CommentController {

    private final CommentService svc;

    public CommentController(CommentService svc) { this.svc = svc; }

    /** 公开：某文档的评论列表 */
    @GetMapping("/docs/{id}/comments")
    public List<Comment> list(@PathVariable Long id) {
        return svc.list(id);
    }

    /** 我评论过的文档列表（仅已发布，按更新时间倒序），用于个人资料页展示（强制登录） */
    @GetMapping("/comments/mine/docs")
    public List<Document> myDocs(@RequestAttribute("kbUser") TokenClaims c) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        return svc.myCommentedDocs(c.username());
    }

    /** 登录用户发表评论（可带 parentId 进行楼中楼回复） */
    @PostMapping("/docs/{id}/comments")
    public Comment add(@RequestAttribute("kbUser") TokenClaims c,
                       @PathVariable Long id,
                       @RequestBody Map<String, Object> body) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        Object pid = body.get("parentId");
        Long parentId = (pid == null || pid.toString().isBlank()) ? null : Long.valueOf(pid.toString());
        return svc.add(id, c, (String) body.get("content"), parentId);
    }

    /** 通用评论列表（跨对象类型：DOC 帖子 / CHAPTER 章节），公开可见 */
    @GetMapping("/comments")
    public List<Comment> listGeneric(@RequestParam String targetType, @RequestParam Long targetId) {
        return svc.list(targetType, targetId);
    }

    /** 通用发表评论（跨对象类型），登录可用 */
    @PostMapping("/comments")
    public Comment addGeneric(@RequestAttribute("kbUser") TokenClaims c,
                              @RequestBody Map<String, Object> body) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        String targetType = body.get("targetType") == null ? "DOC" : body.get("targetType").toString();
        Object tid = body.get("targetId");
        if (tid == null || tid.toString().isBlank()) throw new IllegalArgumentException("缺少 targetId");
        Long targetId = Long.valueOf(tid.toString());
        Object pid = body.get("parentId");
        Long parentId = (pid == null || pid.toString().isBlank()) ? null : Long.valueOf(pid.toString());
        return svc.add(targetType, targetId, c, (String) body.get("content"), parentId);
    }

    /** 删除评论：管理员可删任意评论，普通用户仅能删自己的 */
    @DeleteMapping("/comments/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long id) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        if ("ADMIN".equals(c.role())) {
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
