package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.DocumentVersion;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.DocumentService;
import com.kb.kbplatform.service.DocumentImportService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    private final DocumentService svc;
    private final UserRepository userRepo;
    private final DocumentImportService importSvc;

    public DocumentController(DocumentService svc, UserRepository userRepo, DocumentImportService importSvc) {
        this.svc = svc;
        this.userRepo = userRepo;
        this.importSvc = importSvc;
    }

    /** 公开：文档列表（分页，仅已发布文档）。page 从 0 开始，size 默认 15，上限 100；sort=hot 按浏览量（热帖）排序 */
    @GetMapping
    public Page<Document> list(@RequestParam(required = false, defaultValue = "0") int page,
                               @RequestParam(required = false, defaultValue = "15") int size,
                               @RequestParam(required = false) String sort,
                               @RequestParam(required = false) String tag,
                               @RequestParam(required = false) String category,
                               @RequestParam(required = false) Long ownerId) {
        if (page < 0) page = 0;
        if (size <= 0 || size > 100) size = 15;
        if (tag != null && !tag.isBlank()) return svc.listByTag(PageRequest.of(page, size), sort, tag);
        return svc.listPublic(PageRequest.of(page, size), sort, category, ownerId);
    }

    /** 公开：分类列表 */
    @GetMapping("/categories")
    public List<String> categories() { return svc.categories(); }

    /** 公开：标签聚合（去重 + 计数），用于标签云/筛选 */
    @GetMapping("/tags")
    public List<Map<String, Object>> tags() { return svc.allTags(); }

    /** 公开：文档详情。已发布任何人可看；非发布（草稿/待审核/封禁）仅作者本人或管理员可预览，用于审核/编辑前查看 */
    @GetMapping("/{id}")
    public Document get(@RequestAttribute(value = "kbUser", required = false) TokenClaims c,
                        @PathVariable Long id) {
        return svc.view(id, c);
    }

    /** 登录用户：我的帖子（含草稿/封禁，供本人管理） */
    @GetMapping("/mine")
    public List<Document> mine(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.listByOwner(resolveOwner(c));
    }

    /** 登录用户：写文章（作者 = 当前用户）。
     *  普通用户不能自发布：未指定或指定 PUBLISHED 的统一转待审核；
     *  管理员可直接发布（无需自审），仅尊重其显式指定的草稿。 */
    @PostMapping
    public Document create(@RequestAttribute("kbUser") TokenClaims c,
                           @RequestBody Document d) {
        d.setContent(HtmlSanitizer.sanitize(d.getContent()));
        boolean admin = "ADMIN".equals(c.role());
        if (admin) {
            if (d.getStatus() == null || d.getStatus().isBlank()) d.setStatus("PUBLISHED");
        } else if (d.getStatus() == null || d.getStatus().isBlank() || "PUBLISHED".equals(d.getStatus())) {
            d.setStatus("PENDING_REVIEW");
        }
        return svc.createAs(d, resolveOwner(c));
    }

    /** 登录用户：编辑自己的帖子（管理员可改任意） */
    @PutMapping("/{id}")
    public Document update(@RequestAttribute("kbUser") TokenClaims c,
                           @PathVariable Long id,
                           @RequestBody Document d) {
        d.setContent(HtmlSanitizer.sanitize(d.getContent()));
        return svc.updateOwn(id, d, resolveOwner(c));
    }

    /** 登录用户：提交审核（草稿 → 待审核） */
    @PostMapping("/{id}/submit")
    public Document submit(@RequestAttribute("kbUser") TokenClaims c,
                           @PathVariable Long id) {
        return svc.submitForReview(id, resolveOwner(c));
    }

    /** 登录用户：撤回到草稿（待审核 → 草稿，审核前可拉回；已发布 → 草稿，下架重改）；管理员可撤回任意帖 */
    @PostMapping("/{id}/withdraw")
    public Document withdraw(@RequestAttribute("kbUser") TokenClaims c,
                             @PathVariable Long id) {
        boolean admin = "ADMIN".equals(c.role());
        return svc.withdraw(id, resolveOwner(c), admin);
    }

    /** 登录用户：删除自己的帖子（管理员可删任意） */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c,
                                      @PathVariable Long id) {
        svc.deleteOwn(id, resolveOwner(c));
        return Map.of("ok", true);
    }

    /** 登录用户：上传文档文件，解析为 {title, content} 返回（不落库，前端填入编辑器供修改后发布） */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> importDoc(@RequestAttribute("kbUser") TokenClaims c,
                                         @RequestParam("file") MultipartFile file) {
        resolveOwner(c); // 强制登录
        return importSvc.parseToDraft(file);
    }

    /** 版本历史：列出某文档的全部历史版本（作者或管理员可见） */
    @GetMapping("/{id}/versions")
    public List<DocumentVersion> versions(@RequestAttribute("kbUser") TokenClaims c,
                                          @PathVariable Long id) {
        Document d = svc.get(id);
        requireSelfOrAdmin(c, d);
        return svc.listVersions(id);
    }

    /** 版本历史：取单个版本详情 */
    @GetMapping("/{id}/versions/{vid}")
    public DocumentVersion version(@RequestAttribute("kbUser") TokenClaims c,
                                   @PathVariable Long id,
                                   @PathVariable Long vid) {
        Document d = svc.get(id);
        requireSelfOrAdmin(c, d);
        DocumentVersion v = svc.getVersion(vid);
        if (!id.equals(v.getDocId())) {
            throw new ResponseStatusException(BAD_REQUEST, "该版本不属于此文档");
        }
        return v;
    }

    /** 版本历史：回滚到指定版本（作者或管理员） */
    @PostMapping("/{id}/versions/{vid}/restore")
    public Document restore(@RequestAttribute("kbUser") TokenClaims c,
                            @PathVariable Long id,
                            @PathVariable Long vid) {
        Document d = svc.get(id);
        requireSelfOrAdmin(c, d);
        return svc.restoreVersion(id, vid, resolveOwner(c));
    }

    /** 仅作者本人或管理员可查看/操作该文档的版本历史 */
    private void requireSelfOrAdmin(TokenClaims c, Document d) {
        if (c == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        boolean admin = "ADMIN".equals(c.role());
        boolean owner = d.getOwnerId() != null && d.getOwnerId().equals(c.userId());
        if (!admin && !owner) {
            throw new ResponseStatusException(FORBIDDEN, "无权查看该文档的历史版本");
        }
    }

    /** 从令牌解析当前用户 id（强制登录） */
    private Long resolveOwner(TokenClaims c) {
        if (c == null || c.username() == null) {
            throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        }
        return userRepo.findByUsername(c.username())
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "请先登录"));
    }
}
