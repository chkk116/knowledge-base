package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuditService;
import com.kb.kbplatform.service.DocumentService;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/docs")
public class AdminDocController {

    private final DocumentService docSvc;
    private final UserRepository userRepo;
    private final AuditService audit;

    public AdminDocController(DocumentService docSvc, UserRepository userRepo, AuditService audit) {
        this.docSvc = docSvc;
        this.userRepo = userRepo;
        this.audit = audit;
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「" + code + "」权限");
        }
    }

    /** 管理员：全部文档（含封禁），支持检索/排序/分页；sort=updatedAt|created|views|id，dir=desc|asc */
    @GetMapping
    public Page<Document> list(@RequestAttribute("kbUser") TokenClaims c,
                               @RequestParam(required = false, defaultValue = "") String keyword,
                               @RequestParam(required = false, defaultValue = "updatedAt") String sort,
                               @RequestParam(required = false, defaultValue = "desc") String dir,
                               @RequestParam(required = false, defaultValue = "0") int page,
                               @RequestParam(required = false, defaultValue = "15") int size) {
        requirePerm(c, "DOC_REVIEW");
        return docSvc.adminList(keyword, sort, dir, page, size);
    }

    /** 管理员：仅封禁文档（黑名单），分页 */
    @GetMapping("/blocked")
    public Page<Document> blocked(@RequestAttribute("kbUser") TokenClaims c,
                                  @RequestParam(required = false, defaultValue = "0") int page,
                                  @RequestParam(required = false, defaultValue = "15") int size) {
        requirePerm(c, "DOC_REVIEW");
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 15;
        return docSvc.listBlocked(page, size);
    }

    @PostMapping
    public Document create(@RequestAttribute("kbUser") TokenClaims c,
                           @RequestBody Document d) {
        requirePerm(c, "DOC_REVIEW");
        d.setContent(HtmlSanitizer.sanitize(d.getContent()));
        if (d.getStatus() == null || d.getStatus().isBlank()) d.setStatus("PUBLISHED");
        // 记录发布人
        if (c.username() != null) {
            userRepo.findByUsername(c.username()).ifPresent(u -> d.setOwnerId(u.getId()));
        }
        Document saved = docSvc.create(d);
        audit.log(c.userId(), c.username(), "CREATE_DOC", "DOCUMENT", saved.getId(), saved.getTitle(), "创建文档《" + clip(saved.getTitle()) + "》");
        return saved;
    }

    @PutMapping("/{id}")
    public Document update(@RequestAttribute("kbUser") TokenClaims c,
                           @PathVariable Long id,
                           @RequestBody Document d) {
        requirePerm(c, "DOC_REVIEW");
        d.setContent(HtmlSanitizer.sanitize(d.getContent()));
        return docSvc.update(id, d, c.userId());
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long id) {
        requirePerm(c, "DOC_REVIEW");
        String title = docSvc.get(id).getTitle();
        docSvc.delete(id);
        audit.log(c.userId(), c.username(), "DELETE_DOC", "DOCUMENT", id, title, "删除文档《" + clip(title) + "》");
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/block")
    public Map<String, Object> block(@RequestAttribute("kbUser") TokenClaims c,
                                     @PathVariable Long id) {
        requirePerm(c, "DOC_REVIEW");
        String title = docSvc.get(id).getTitle();
        docSvc.block(id);
        audit.log(c.userId(), c.username(), "BLOCK_DOC", "DOCUMENT", id, title, "封禁文档《" + clip(title) + "》");
        return Map.of("ok", true, "status", "BLOCKED");
    }

    @PostMapping("/{id}/unblock")
    public Map<String, Object> unblock(@RequestAttribute("kbUser") TokenClaims c,
                                       @PathVariable Long id) {
        requirePerm(c, "DOC_REVIEW");
        String title = docSvc.get(id).getTitle();
        docSvc.unblock(id);
        audit.log(c.userId(), c.username(), "UNBLOCK_DOC", "DOCUMENT", id, title, "解封文档《" + clip(title) + "》");
        return Map.of("ok", true, "status", "PUBLISHED");
    }

    /** 管理员：待审核队列 */
    @GetMapping("/pending")
    public List<Document> pending(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "DOC_REVIEW");
        return docSvc.listPending();
    }

    /** 管理员：置顶（列表优先展示） */
    @PostMapping("/{id}/pin")
    public Document pin(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "DOC_REVIEW");
        return docSvc.pin(id);
    }

    /** 管理员：取消置顶 */
    @PostMapping("/{id}/unpin")
    public Document unpin(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        requirePerm(c, "DOC_REVIEW");
        return docSvc.unpin(id);
    }

    /** 管理员：通过审核（待审核 → 已发布） */
    @PostMapping("/{id}/approve")
    public Document approve(@RequestAttribute("kbUser") TokenClaims c,
                            @PathVariable Long id) {
        requirePerm(c, "DOC_REVIEW");
        Document d = docSvc.approve(id);
        audit.log(c.userId(), c.username(), "APPROVE_DOC", "DOCUMENT", id, d.getTitle(), "通过文档《" + clip(d.getTitle()) + "》");
        return d;
    }

    /** 管理员：驳回审核（待审核 → 退回草稿，可附带驳回理由） */
    @PostMapping("/{id}/reject")
    public Document reject(@RequestAttribute("kbUser") TokenClaims c,
                           @PathVariable Long id,
                           @RequestBody(required = false) Map<String, String> body) {
        requirePerm(c, "DOC_REVIEW");
        String reason = (body != null) ? body.get("reason") : null;
        Document d = docSvc.reject(id, reason);
        audit.log(c.userId(), c.username(), "REJECT_DOC", "DOCUMENT", id, d.getTitle(), "驳回文档《" + clip(d.getTitle()) + "》" + (reason != null ? "：" + reason : ""));
        return d;
    }

    /** 截断标题用于审计说明，避免过长 */
    private String clip(String t) {
        if (t == null) return "";
        t = t.trim();
        return t.length() > 20 ? t.substring(0, 20) + "…" : t;
    }
}
