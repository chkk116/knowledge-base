/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.DocumentVersion;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.security.SensitiveWordFilter;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.DocumentService;
import com.kb.kbplatform.service.DocumentDiscoveryService;
import com.kb.kbplatform.service.DocumentImportService;
import com.kb.kbplatform.service.DocSubscriptionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
    private final DocumentRepository docRepo;
    private final DocumentImportService importSvc;
    private final DocSubscriptionService subSvc;
    private final SensitiveWordFilter sensitiveWordFilter;
    /** 分类 / 标签 / 相关文档：只读发现能力，从 DocumentService 拆出的独立服务 */
    private final DocumentDiscoveryService discovery;

    public DocumentController(DocumentService svc, UserRepository userRepo, DocumentRepository docRepo,
                              DocumentImportService importSvc, DocSubscriptionService subSvc,
                              SensitiveWordFilter sensitiveWordFilter, DocumentDiscoveryService discovery) {
        this.svc = svc;
        this.userRepo = userRepo;
        this.docRepo = docRepo;
        this.importSvc = importSvc;
        this.subSvc = subSvc;
        this.sensitiveWordFilter = sensitiveWordFilter;
        this.discovery = discovery;
    }

    /** 登录用户：首页轻量数据概览 —— 已发布文档总数 / 本周新增 / 启用成员数；管理员或 STATS_VIEW 附待审核数 */
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestAttribute("kbUser") TokenClaims c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalDocs", docRepo.countByStatus("PUBLISHED"));
        m.put("weekNew", docRepo.countByStatusAndCreatedAtAfter("PUBLISHED", java.time.LocalDateTime.now().minusDays(7)));
        m.put("members", userRepo.count() - userRepo.countByEnabledFalse());
        if ("ADMIN".equals(c.role()) || c.hasPerm("STATS_VIEW")) {
            m.put("pendingReview", docRepo.countByStatus("PENDING_REVIEW"));
        }
        return m;
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
        // 标签 / 分类 / 作者统一组合下推（F11）：此前 tag 分支提前 return，会静默忽略 category 与 ownerId
        return svc.listPublicFiltered(PageRequest.of(page, size), sort, category, ownerId, tag);
    }

    /** 公开：分类列表 */
    @GetMapping("/categories")
    public List<String> categories() { return discovery.categories(); }

    /** 公开：标签聚合（去重 + 计数），用于标签云/筛选 */
    @GetMapping("/tags")
    public List<Map<String, Object>> tags() { return discovery.allTags(); }

    /** 登录用户：全局文档树（仅已发布文档，children 递归）。供知识库层级浏览与「父文档」选择器使用 */
    @GetMapping("/tree")
    public List<Map<String, Object>> tree(@RequestAttribute("kbUser") TokenClaims c) {
        resolveOwner(c);   // 强制登录
        return svc.docTree();
    }

    /** 登录用户：文档面包屑（从最顶层祖先到自身，含自身），供详情页展示层级链路 */
    @GetMapping("/{id}/breadcrumb")
    public List<Map<String, Object>> breadcrumb(@RequestAttribute("kbUser") TokenClaims c,
                                                @PathVariable Long id) {
        resolveOwner(c);   // 强制登录
        return svc.breadcrumb(id);
    }

    /** 登录用户：移动文档（换父节点 / 调同级排序）。作者本人，或持 TAXONOMY_MANAGE 权限者 */
    @PostMapping("/{id}/move")
    public Document move(@RequestAttribute("kbUser") TokenClaims c,
                         @PathVariable Long id,
                         @RequestBody Map<String, Object> body) {
        resolveOwner(c);   // 强制登录
        return svc.moveDoc(id, toLong(body.get("parentId")), toInt(body.get("sortOrder")), c);
    }

    /**
     * 审核工具条「一键违禁词检查」：对正文（去 HTML 标签后的纯文本）跑敏感词匹配，返回命中词列表。
     * 仅登录用户可用（审核人员 / 作者自查均可）；不修改文档，纯只读。
     */
    @PostMapping("/{id}/check-banned")
    public Map<String, Object> checkBanned(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        if (c == null) throw new org.springframework.web.server.ResponseStatusException(UNAUTHORIZED, "请先登录");
        Document d = svc.get(id);
        String html = d.getContent() == null ? "" : d.getContent();
        String text = html.replaceAll("<[^>]+>", " ");
        List<String> hits = sensitiveWordFilter.find(text);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hits", hits);
        m.put("count", hits.size());
        return m;
    }

    /** 请求体里的数值容忍 null / 空串 / 字符串数字（前端表单常把数字传成字符串） */
    private Long toLong(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
        try { return Integer.valueOf(s); } catch (NumberFormatException e) { return null; }
    }

    /** 登录用户：订阅文档（幂等）。文档更新时会收到站内通知 */
    @PostMapping("/{id}/subscribe")
    public Map<String, Object> subscribe(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        subSvc.subscribe(resolveOwner(c), id);
        return Map.of("subscribed", true, "count", subSvc.countSubscribers(id));
    }

    /** 登录用户：取消订阅（未订阅也不报错） */
    @DeleteMapping("/{id}/subscribe")
    public Map<String, Object> unsubscribe(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        subSvc.unsubscribe(resolveOwner(c), id);
        return Map.of("subscribed", false, "count", subSvc.countSubscribers(id));
    }

    /** 当前用户对某文档的订阅状态（未登录也返回 subscribed=false，便于详情页按钮初始化） */
    @GetMapping("/{id}/subscribed")
    public Map<String, Object> subscribed(@RequestAttribute(value = "kbUser", required = false) TokenClaims c,
                                          @PathVariable Long id) {
        boolean on = c != null && subSvc.isSubscribed(c.userId(), id);
        return Map.of("subscribed", on, "count", subSvc.countSubscribers(id));
    }

    /** 登录用户：我的订阅列表（含文档标题，文档已删除会标注 deleted） */
    @GetMapping("/subscriptions")
    public List<Map<String, Object>> mySubscriptions(@RequestAttribute("kbUser") TokenClaims c) {
        return subSvc.mySubscriptions(resolveOwner(c));
    }

    /** 公开：相关文档推荐（同分类 + 标签重叠打分，不足时用高浏览文档补位）。limit 默认 6，上限 20 */
    @GetMapping("/{id}/related")
    public List<Document> related(@PathVariable Long id,
                                  @RequestParam(required = false, defaultValue = "6") int limit) {
        return discovery.relatedDocs(id, limit);
    }

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
        // 创建接口不接受任何主键：实体无版本字段，主键非空会让 JPA 走 merge 路径覆盖他人文档（F01）。
        // 服务端另有统一白名单（applyCreateWhitelist）兜底清空统计/审核/删除等内部字段。
        if (d.getId() != null) {
            throw new ResponseStatusException(BAD_REQUEST, "创建文档不能携带 id");
        }
        d.setContent(HtmlSanitizer.sanitize(d.getContent()));
        boolean admin = "ADMIN".equals(c.role());
        if (admin) {
            if (d.getStatus() == null || d.getStatus().isBlank()) d.setStatus("PUBLISHED");
        } else if (d.getStatus() == null || d.getStatus().isBlank() || "PUBLISHED".equals(d.getStatus())) {
            d.setStatus("PENDING_REVIEW");
        }
        Document saved = svc.createAs(d, resolveOwner(c));
        // 普通用户发文被直接置为待审核（不能自发布）→ 同样通知管理员，
        // 否则「写文章」绕过 /{id}/submit，管理员永远收不到待审通知。
        svc.notifyReviewSubmitIfPending(saved, resolveOwner(c));
        return saved;
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

    /** 登录用户：我的回收站（仅自己移入回收站的文档） */
    @GetMapping("/recycle")
    public List<Document> recycle(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.listRecycleBin(resolveOwner(c), false);
    }

    /** 登录用户：从回收站恢复自己的文档 */
    @PostMapping("/{id}/restore")
    public Document restore(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.restore(id, resolveOwner(c), false);
    }

    /** 登录用户：彻底删除自己回收站里的文档 */
    @DeleteMapping("/{id}/purge")
    public Map<String, Object> purge(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        svc.purge(id, resolveOwner(c), false);
        return Map.of("ok", true);
    }

    /** 导出文档：?format=word 返回 HTML 封装的 .doc（Word/WPS 可打开，中文无乱码）；PDF 由前端 window.print 实现 */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@RequestAttribute("kbUser") TokenClaims c,
                                        @PathVariable Long id,
                                        @RequestParam(defaultValue = "word") String format) {
        if (!"word".equals(format)) throw new ResponseStatusException(BAD_REQUEST, "暂仅支持 word 导出");
        Document d = svc.get(id);
        // 安全：非 ADMIN 仅可导出自己名下、或已发布(PUBLISHED)的文档，禁止导出他人草稿/封禁文档
        boolean isAdmin = c != null && "ADMIN".equals(c.role());
        boolean ownDoc = c != null && d.getOwnerId() != null && d.getOwnerId().equals(c.userId());
        boolean published = "PUBLISHED".equals(d.getStatus());
        if (!isAdmin && !ownDoc && !published) {
            throw new ResponseStatusException(FORBIDDEN, "无权导出该文档（仅可导出自己名下或已发布的文档）");
        }
        // 导出需细粒度权限 DOC_EXPORT（ADMIN 角色隐式拥有；普通用户需被显式授予该权限）
        boolean canExport = c != null && c.hasPerm("DOC_EXPORT");
        if (!canExport) throw new ResponseStatusException(FORBIDDEN, "无权导出该文档（需 DOC_EXPORT 权限）");
        String html = svc.exportWord(id, c.userId());
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/msword; charset=utf-8"));
        String fname = (d.getTitle() == null ? "document" : d.getTitle()).replaceAll("[\\\\/:*?\"<>|]", "_");
        try {
            headers.setContentDispositionFormData("attachment", URLEncoder.encode(fname + ".doc", "UTF-8").replace("+", "%20"));
        } catch (Exception ignored) {}
        return new ResponseEntity<>(bytes, headers, org.springframework.http.HttpStatus.OK);
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
