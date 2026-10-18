/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.*;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuditService;
import com.kb.kbplatform.service.DocShareCapacityRequestService;
import com.kb.kbplatform.service.DocShareCreateRequestService;
import com.kb.kbplatform.service.DocShareService;
import com.kb.kbplatform.service.DocumentImportService;
import com.kb.kbplatform.service.OcrService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/api/doc-shares")
public class DocShareController {

    private final DocShareService svc;
    private final UserRepository userRepo;
    private final AuditService audit;
    private final DocumentImportService importSvc;
    private final DocShareCapacityRequestService capacityReqSvc;
    private final DocShareCreateRequestService createReqSvc;
    private final OcrService ocrSvc;

    public DocShareController(DocShareService svc, UserRepository userRepo, AuditService audit, DocumentImportService importSvc,
                              DocShareCapacityRequestService capacityReqSvc, DocShareCreateRequestService createReqSvc,
                              OcrService ocrSvc) {
        this.svc = svc;
        this.userRepo = userRepo;
        this.audit = audit;
        this.importSvc = importSvc;
        this.capacityReqSvc = capacityReqSvc;
        this.createReqSvc = createReqSvc;
        this.ocrSvc = ocrSvc;
    }

    @GetMapping("/my")
    public List<DocShare> myShares(@RequestAttribute("kbUser") TokenClaims c) {
        Long uid = resolveUserId(c);
        return svc.listMine(uid, isSysAdmin(c));
    }

    @PostMapping
    public DocShare create(@RequestAttribute("kbUser") TokenClaims c, @RequestBody Map<String, Object> body) {
        Long uid = resolveUserId(c);
        String type = str(body.get("type"));
        // 创建团队空间需要 SHARE_CREATE 权限（ADMIN 隐式拥有；个人空间自动创建不受限）
        if (c != null && !c.hasPerm("SHARE_CREATE") && !"PERSONAL".equals(type)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无「创建团队空间」权限，请联系系统管理员开通");
        }
        // 团队空间创建上限：非完全管理员，已拥有达到上限（默认 2 个）团队空间则禁止直接创建，需走申请流程
        if (!"PERSONAL".equals(type) && !isSysAdmin(c) && svc.countUserTeamSpaces(uid) >= DocShareService.MAX_TEAM_SPACE_PER_USER) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN,
                    "您已创建 " + DocShareService.MAX_TEAM_SPACE_PER_USER + " 个团队空间（已达上限），如需创建更多请在「新建团队空间」中提交申请，由管理员审批");
        }
        // type 缺省按 TEAM；capacity 为空间容量上限字节数，可空；members 为创建时直接带入的成员 id 列表
        List<Long> members = new java.util.ArrayList<>();
        Object raw = body.get("members");
        if (raw instanceof List<?> ls) {
            for (Object o : ls) {
                try { if (o != null) members.add(Long.valueOf(String.valueOf(o))); } catch (NumberFormatException ignored) {}
            }
        }
        DocShare saved = svc.create(str(body.get("name")), str(body.get("description")), uid,
                type, lng(body.get("capacity")), str(body.get("color")),
                members, str(body.get("memberPermission")));
        if (!"PERSONAL".equals(saved.getType())) {
            audit.log(c.userId(), c.username(), "CREATE_SHARE_SPACE", "SHARE_SPACE", saved.getId(), saved.getName(),
                    "创建团队空间「" + saved.getName() + "」，初始容量 " + (saved.getCapacity() != null ? DocShareService.formatSize(saved.getCapacity()) : "不限"));
        }
        return saved;
    }

    /** 选人搜索（创建/邀请成员）：用户名或昵称模糊匹配，排除自己，最多 20 条 */
    @GetMapping("/users")
    public List<Map<String, Object>> inviteUsers(@RequestAttribute("kbUser") TokenClaims c,
                                                 @RequestParam(required = false, defaultValue = "") String keyword) {
        return svc.searchInviteUsers(keyword, resolveUserId(c));
    }

    @GetMapping("/{id}")
    public DocShare get(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.get(id, resolveUserId(c), isSysAdmin(c));
    }

    @PutMapping("/{id}")
    public DocShare update(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                           @RequestBody Map<String, Object> body) {
        DocShare before = svc.get(id, resolveUserId(c), isSysAdmin(c));
        DocShare updated = svc.update(id, str(body.get("name")), str(body.get("description")), str(body.get("status")),
                lng(body.get("capacity")), resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_SPACE", "SHARE_SPACE", id, updated.getName(),
                "更新团队空间「" + before.getName() + "」信息");
        return updated;
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        DocShare s = svc.get(id, resolveUserId(c), isSysAdmin(c));
        svc.delete(id, resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "DELETE_SHARE_SPACE", "SHARE_SPACE", id, s.getName(),
                "删除团队空间「" + s.getName() + "」");
        return Map.of("ok", true);
    }

    // ---- 成员 ----

    @GetMapping("/{id}/members")
    public List<DocSharePermission> members(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.listMembers(id, resolveUserId(c), isSysAdmin(c));
    }

    @PostMapping("/{id}/members")
    public DocSharePermission addMember(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                        @RequestBody Map<String, Object> body) {
        Long targetId = lng(body.get("userId"));
        String perm = str(body.get("permission"));
        DocSharePermission p = svc.addMember(id, targetId, perm, resolveUserId(c), isSysAdmin(c));
        String memberName = p.getDisplayName() != null && !p.getDisplayName().isBlank()
                ? p.getDisplayName() : (p.getUsername() != null ? p.getUsername() : String.valueOf(p.getUserId()));
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_MEMBER", "SHARE_MEMBER", id, memberName,
                "邀请成员「" + memberName + "」加入空间，权限 " + perm);
        return p;
    }

    @PutMapping("/{id}/members/{userId}")
    public DocSharePermission updateMember(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                           @PathVariable Long userId, @RequestBody Map<String, Object> body) {
        String perm = str(body.get("permission"));
        DocSharePermission p = svc.updateMember(id, userId, perm, resolveUserId(c), isSysAdmin(c));
        String memberName = p.getDisplayName() != null && !p.getDisplayName().isBlank()
                ? p.getDisplayName() : (p.getUsername() != null ? p.getUsername() : String.valueOf(p.getUserId()));
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_MEMBER", "SHARE_MEMBER", id, memberName,
                "调整成员「" + memberName + "」权限为 " + perm);
        return p;
    }

    @DeleteMapping("/{id}/members/{userId}")
    public Map<String, Object> removeMember(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                            @PathVariable Long userId) {
        svc.removeMember(id, userId, resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_MEMBER", "SHARE_MEMBER", id, String.valueOf(userId),
                "移除空间成员 #" + userId);
        return Map.of("ok", true);
    }

    // ---- 文档 ----

    @GetMapping("/{id}/docs")
    public List<SharedDocument> listDocs(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.listDocs(id, resolveUserId(c), isSysAdmin(c));
    }

    /** 跨空间聚合的可读文档列表（个人与参与空间），不返回待整理箱项目。 */
    /**
     * @param withContent 是否一并返回正文（「全部文档」的正文检索用）。
     *                    默认 false：列表只要标题/标签，带上全部正文会让响应变重；
     *                    需要正文搜索时传 true，服务端会把正文截断到 20000 字符再下发。
     */
    @GetMapping("/docs/all")
    public List<SharedDocument> allDocs(@RequestAttribute("kbUser") TokenClaims c,
                                        @RequestParam(value = "withContent", defaultValue = "false") boolean withContent) {
        return svc.listAllDocs(resolveUserId(c), isSysAdmin(c), withContent);
    }

    @PostMapping("/{id}/docs")
    public SharedDocument createDoc(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                    @RequestBody Map<String, Object> body,
                                    @RequestParam(value = "inbox", defaultValue = "false") boolean inbox) {
        // inbox=true：收进待整理箱（快速收纳，先落个人空间并打标记，不出现在空间文档列表）
        SharedDocument d = svc.createDoc(id, str(body.get("title")), str(body.get("content")), resolveUserId(c), isSysAdmin(c), inbox);
        audit.log(c.userId(), c.username(), "CREATE_SHARE_DOC", "SHARE_DOC", d.getId(), d.getTitle(),
                (inbox ? "收进待整理箱「" : "在空间中创建文档「") + d.getTitle() + "」");
        return d;
    }

    /** 待整理箱：本人收进但未归档的文档（跨空间） */
    @GetMapping("/inbox/list")
    public List<SharedDocument> inbox(@RequestAttribute("kbUser") TokenClaims c) {
        return svc.listInbox(resolveUserId(c));
    }

    /** 待整理箱未整理数量（角标） */
    @GetMapping("/inbox/count")
    public Map<String, Object> inboxCount(@RequestAttribute("kbUser") TokenClaims c) {
        return Map.of("count", svc.inboxCount(resolveUserId(c)));
    }

    /** 整理一条待整理项：补标题（可选）+ 归档到目标空间 */
    @PostMapping("/inbox/{docId}/archive")
    public SharedDocument archiveInbox(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long docId,
                                       @RequestBody Map<String, Object> body) {
        Long targetShareId = body.get("targetShareId") == null ? null : Long.valueOf(String.valueOf(body.get("targetShareId")));
        String title = str(body.get("title"));
        SharedDocument d = svc.archiveInbox(docId, targetShareId, title, resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "ARCHIVE_INBOX_DOC", "SHARE_DOC", d.getId(), d.getTitle(),
                "整理待收纳文档「" + d.getTitle() + "」并归档");
        return d;
    }

    @DeleteMapping("/{id}/docs/{docId}")
    public Map<String, Object> deleteDoc(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                         @PathVariable Long docId) {
        SharedDocument d = svc.getDoc(id, docId, resolveUserId(c), isSysAdmin(c));
        svc.deleteDoc(id, docId, resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "DELETE_SHARE_DOC", "SHARE_DOC", docId, d.getTitle(),
                "删除空间文档「" + d.getTitle() + "」");
        return Map.of("ok", true);
    }

    /**
     * 从外部文档导入：复用 DocumentImportService 把 .md/.txt/.html/.docx/.pdf 解析为 HTML，
     * 再作为一篇新的共享文档落库。权限同创建文档（需写入权限）。
     */
    @PostMapping(value = "/{id}/docs/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SharedDocument importDoc(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                   @RequestParam("file") MultipartFile file) {
        Long uid = resolveUserId(c);
        Map<String, String> draft = importSvc.parseToDraft(file); // 解析 + 净化，超 20MB 或不支持类型会抛异常
        SharedDocument d = svc.createDoc(id, draft.get("title"), draft.get("content"), uid, isSysAdmin(c));
        audit.log(c.userId(), c.username(), "CREATE_SHARE_DOC", "SHARE_DOC", d.getId(), d.getTitle(),
                "从外部文件导入共享文档「" + d.getTitle() + "」（" + file.getOriginalFilename() + "）");
        return d;
    }

    /**
     * 图片 OCR 导入：图片经智谱 GLM-4V-Flash 识别出文字，转成段落 HTML 后
     * 作为一篇新的共享文档落库。权限同创建文档（需写入权限）。
     * 文件类型/大小校验与识别失败均抛 IllegalStateException → 400（带中文 error）。
     * persist=false 时只识别不落库（移动端多页扫描合并预览用，第四轮审查 H6：
     * 原先每页都建一篇文档、合并保存后再建一篇 → 每次遗留 N 篇孤儿文档）。
     */
    @PostMapping(value = "/{id}/docs/ocr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SharedDocument ocrDoc(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                 @RequestParam("file") MultipartFile file,
                                 @RequestParam(value = "persist", defaultValue = "true") boolean persist) throws java.io.IOException {
        ocrSvc.validate(file.getContentType(), file.getSize());
        String text = ocrSvc.recognize(file.getBytes(), file.getContentType());
        if (text.isBlank()) {
            throw new IllegalStateException("未能从图片中识别出文字，请确认图片内容清晰");
        }
        String name = file.getOriginalFilename() == null ? "图片" : file.getOriginalFilename();
        int dot = name.lastIndexOf('.');
        String title = (dot > 0 ? name.substring(0, dot) : name) + "（OCR）";
        if (!persist) {
            SharedDocument preview = new SharedDocument();
            preview.setTitle(title);
            preview.setContent(ocrSvc.toParagraphHtml(text));
            return preview;
        }
        SharedDocument d = svc.createDoc(id, title, ocrSvc.toParagraphHtml(text), resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "CREATE_SHARE_DOC", "SHARE_DOC", d.getId(), d.getTitle(),
                "图片 OCR 导入共享文档「" + d.getTitle() + "」（" + name + "）");
        return d;
    }

    @GetMapping("/{id}/docs/{docId}")
    public SharedDocument getDoc(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                 @PathVariable Long docId) {
        return svc.getDoc(id, docId, resolveUserId(c), isSysAdmin(c));
    }

    @PutMapping("/{id}/docs/{docId}")
    public SharedDocument updateDoc(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                    @PathVariable Long docId, @RequestBody Map<String, Object> body) {
        SharedDocument before = svc.getDoc(id, docId, resolveUserId(c), isSysAdmin(c));
        SharedDocument d = svc.updateDoc(id, docId, str(body.get("title")), str(body.get("content")), resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_DOC", "SHARE_DOC", docId, d.getTitle(),
                "更新空间文档「" + before.getTitle() + "」");
        return d;
    }

    /** 更新文档多标签；标签只允许数组格式，空数组表示清除全部标签。 */
    @PutMapping("/{id}/docs/{docId}/tags")
    public SharedDocument updateDocTags(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                        @PathVariable Long docId, @RequestBody Map<String, Object> body) {
        Object raw = body.get("tags");
        if (!(raw instanceof List<?> values))
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "tags 必须是数组");
        List<String> tags = values.stream().map(v -> v == null ? null : String.valueOf(v)).toList();
        SharedDocument doc = svc.updateDocTags(id, docId, tags, resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_DOC", "SHARE_DOC", docId, doc.getTitle(),
                "更新文档标签：「" + (doc.getTags() == null ? "" : doc.getTags()) + "」");
        return doc;
    }

    // ---- 文档批注 / 摘录（私人笔记，仅本人可见） ----

    /** 某文档下我的批注列表 */
    @GetMapping("/{id}/docs/{docId}/annotations")
    public List<DocAnnotation> listAnnotations(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                               @PathVariable Long docId) {
        return svc.listAnnotations(id, docId, resolveUserId(c), isSysAdmin(c));
    }

    /** 新增摘录/批注：quote 为被引用的原文，note 为我的笔记（可为空） */
    @PostMapping("/{id}/docs/{docId}/annotations")
    public DocAnnotation createAnnotation(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                          @PathVariable Long docId, @RequestBody Map<String, Object> body) {
        DocAnnotation a = svc.createAnnotation(id, docId, str(body.get("quote")), str(body.get("note")),
                str(body.get("prefix")), str(body.get("suffix")), resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "CREATE_SHARE_DOC_ANNOTATION", "SHARE_DOC", docId, null,
                "新增文档摘录（" + a.getQuote().length() + " 字）");
        return a;
    }

    /** 修改我的笔记内容 */
    @PutMapping("/{id}/docs/{docId}/annotations/{annoId}")
    public DocAnnotation updateAnnotation(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                          @PathVariable Long docId, @PathVariable Long annoId,
                                          @RequestBody Map<String, Object> body) {
        return svc.updateAnnotation(id, docId, annoId, str(body.get("note")), resolveUserId(c));
    }

    @DeleteMapping("/{id}/docs/{docId}/annotations/{annoId}")
    public Map<String, Object> deleteAnnotation(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                                @PathVariable Long docId, @PathVariable Long annoId) {
        svc.deleteAnnotation(id, docId, annoId, resolveUserId(c));
        return Map.of("ok", true);
    }

    /** 「我的摘录」：跨文档聚合我的全部批注 */
    @GetMapping("/annotations/mine")
    public List<DocAnnotation> myAnnotations(@RequestAttribute("kbUser") TokenClaims c,
                                             @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return svc.myAnnotations(resolveUserId(c), limit);
    }

    @PostMapping("/{id}/docs/{docId}/move")
    public SharedDocument moveDoc(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id,
                                  @PathVariable Long docId, @RequestBody Map<String, Object> body) {
        Long targetId = lng(body.get("targetShareId"));
        return svc.moveDoc(id, docId, targetId, resolveUserId(c), isSysAdmin(c));
    }

    // ---- 文档版本历史 ----
    @PostMapping("/{id}/docs/{docId}/versions")
    public Map<String, Object> saveDocVersion(@RequestAttribute("kbUser") TokenClaims c,
                                             @PathVariable Long id, @PathVariable Long docId) {
        svc.snapshotSharedDoc(id, docId, resolveUserId(c), isSysAdmin(c));
        return Map.of("ok", true);
    }

    @GetMapping("/{id}/docs/{docId}/versions")
    public List<SharedDocVersion> listDocVersions(@RequestAttribute("kbUser") TokenClaims c,
                                                 @PathVariable Long id, @PathVariable Long docId) {
        return svc.listSharedDocVersions(id, docId, resolveUserId(c), isSysAdmin(c));
    }

    @PostMapping("/{id}/docs/{docId}/versions/{vid}/restore")
    public SharedDocument restoreDocVersion(@RequestAttribute("kbUser") TokenClaims c,
                                           @PathVariable Long id, @PathVariable Long docId, @PathVariable Long vid) {
        SharedDocument d = svc.restoreSharedDocVersion(id, docId, vid, resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_DOC", "SHARE_DOC", docId, d.getTitle(),
                "恢复共享文档「" + d.getTitle() + "」到历史版本 #" + vid);
        return d;
    }

    /** 删除某个历史版本（只移除快照，不影响文档当前内容） */
    @DeleteMapping("/{id}/docs/{docId}/versions/{vid}")
    public Map<String, Object> deleteDocVersion(@RequestAttribute("kbUser") TokenClaims c,
                                               @PathVariable Long id, @PathVariable Long docId, @PathVariable Long vid) {
        svc.deleteSharedDocVersion(id, docId, vid, resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_DOC", "SHARE_DOC", docId, null,
                "删除共享文档历史版本 #" + vid);
        return Map.of("ok", true);
    }

    // ---- 文档级权限（在空间权限之上对单篇文档单独收窄/提升） ----

    /** 列出某文档的权限成员：空间成员 + 各自有效权限（含是否单独设置） */
    @GetMapping("/{id}/docs/{docId}/members")
    public List<Map<String, Object>> docMembers(@RequestAttribute("kbUser") TokenClaims c,
                                                @PathVariable Long id, @PathVariable Long docId) {
        return svc.listDocMembers(id, docId, resolveUserId(c), isSysAdmin(c));
    }

    /** 设置某文档对指定成员的有效权限（READ/WRITE/ADMIN）；与空间权限相同时等于清除单独设置 */
    @PutMapping("/{id}/docs/{docId}/members/{userId}")
    public Map<String, Object> setDocMember(@RequestAttribute("kbUser") TokenClaims c,
                                            @PathVariable Long id, @PathVariable Long docId, @PathVariable Long userId,
                                            @RequestBody Map<String, Object> body) {
        String perm = str(body.get("permission"));
        SharedDocPermission p = svc.setDocMember(id, docId, userId, perm, resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_DOC", "SHARE_DOC", docId,
                "文档 #" + docId, "调整成员 #" + userId + " 权限为 " + perm);
        return Map.of("ok", true, "cleared", p == null);
    }

    /** 清除某文档对指定成员的单独权限（回退继承空间权限） */
    @DeleteMapping("/{id}/docs/{docId}/members/{userId}")
    public Map<String, Object> clearDocMember(@RequestAttribute("kbUser") TokenClaims c,
                                              @PathVariable Long id, @PathVariable Long docId, @PathVariable Long userId) {
        svc.clearDocMember(id, docId, userId, resolveUserId(c), isSysAdmin(c));
        audit.log(c.userId(), c.username(), "UPDATE_SHARE_DOC", "SHARE_DOC", docId,
                "文档 #" + docId, "清除成员 #" + userId + " 的单独权限");
        return Map.of("ok", true);
    }

    /** 个人存储用量（有效配额 = 管理员分配或默认 5GB），用于「我的用量」展示与创建拦截 */
    @GetMapping("/storage")
    public Map<String, Object> storage(@RequestAttribute("kbUser") TokenClaims c) {
        Long uid = resolveUserId(c);
        long used = svc.storageUsage(uid);
        return Map.of("used", used, "quota", svc.effectiveUserQuota(uid));
    }

    /** 单个空间的用量（已用 / 上限 / 所有者总配额），空间页顶部展示 */
    @GetMapping("/{id}/storage")
    public Map<String, Object> spaceStorage(@RequestAttribute("kbUser") TokenClaims c, @PathVariable Long id) {
        return svc.spaceStorage(id, resolveUserId(c), isSysAdmin(c));
    }

    // ---- 创建上限配额（前端据此决定是否展示「申请」而非直接创建） ----
    @GetMapping("/my-quota")
    public Map<String, Object> myQuota(@RequestAttribute("kbUser") TokenClaims c) {
        Long uid = resolveUserId(c);
        long owned = svc.countUserTeamSpaces(uid);
        boolean admin = isSysAdmin(c);
        boolean canCreate = admin || owned < DocShareService.MAX_TEAM_SPACE_PER_USER;
        return Map.of(
                "teamLimit", DocShareService.MAX_TEAM_SPACE_PER_USER,
                "ownedTeamCount", owned,
                "isAdmin", admin,
                "canCreate", canCreate);
    }

    // ---- 团队空间创建申请（已达上限的 SHARE_CREATE 用户提交；审批在后台由完全管理员处理） ----
    @PostMapping("/create-requests")
    public DocShareCreateRequest submitCreateRequest(@RequestAttribute("kbUser") TokenClaims c,
                                                     @RequestBody Map<String, Object> body) {
        if (c == null || !c.hasPerm("SHARE_CREATE"))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "无「创建团队空间」权限，请联系系统管理员开通");
        return createReqSvc.submit(c, body);
    }

    @GetMapping("/create-requests/mine")
    public List<DocShareCreateRequest> myCreateRequests(@RequestAttribute("kbUser") TokenClaims c) {
        return createReqSvc.listMine(c);
    }

    // ---- 扩容申请（空间 OWNER/ADMIN 提交；审批在后台由完全管理员处理） ----
    @PostMapping("/{id}/capacity-requests")
    public DocShareCapacityRequest submitCapacityRequest(@RequestAttribute("kbUser") TokenClaims c,
                                                         @PathVariable Long id, @RequestBody Map<String, Object> body) {
        return capacityReqSvc.submit(id, c, lng(body.get("requestedCapacity")), str(body.get("reason")));
    }

    @GetMapping("/{id}/capacity-requests")
    public List<DocShareCapacityRequest> listCapacityRequests(@RequestAttribute("kbUser") TokenClaims c,
                                                             @PathVariable Long id) {
        return capacityReqSvc.listByShare(id, c);
    }

    private Long resolveUserId(TokenClaims c) {
        if (c == null || c.username() == null) throw new ResponseStatusException(UNAUTHORIZED, "请先登录");
        return userRepo.findByUsername(c.username()).map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "请先登录"));
    }

    /** 刻意仅真实 ADMIN（非 isAdmin() 全集语义）：共享空间的系统级审批/配额属资源管理决策，传给 DocShare*Service 的
     *  「完全管理员」旁路只认 ADMIN 角色，不随 AuthFilter 的 isAdmin 门放宽（2026-09-21 ocr 评审后确认为刻意例外）。 */
    private boolean isSysAdmin(TokenClaims c) { return c != null && "ADMIN".equals(c.role()); }

    private String str(Object o) { return o == null ? null : String.valueOf(o); }
    private Long lng(Object o) { return o == null ? null : Long.valueOf(String.valueOf(o)); }
}
