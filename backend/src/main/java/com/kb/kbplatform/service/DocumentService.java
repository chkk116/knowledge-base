package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.DocumentVersion;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.DocumentVersionRepository;
import com.kb.kbplatform.repository.DocSubscriptionRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.service.AuditService;
import com.kb.kbplatform.security.HtmlSanitizer;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.AuthService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@Service
public class DocumentService {

    public static final int HOT_THRESHOLD = 50;   // 浏览量达到该值即视为热帖

    private final DocumentRepository repo;
    private final AuthService authService;
    private final NotificationService notificationService;
    private final UserRepository userRepo;
    private final MeiliSearchClient meili;
    private final DocumentVersionRepository versionRepo;
    private final AuditService auditService;
    private final DocSubscriptionRepository subRepo;

    public DocumentService(DocumentRepository repo, AuthService authService, NotificationService notificationService, UserRepository userRepo, MeiliSearchClient meili, DocumentVersionRepository versionRepo, AuditService auditService, DocSubscriptionRepository subRepo) {
        this.repo = repo;
        this.authService = authService;
        this.notificationService = notificationService;
        this.userRepo = userRepo;
        this.meili = meili;
        this.versionRepo = versionRepo;
        this.auditService = auditService;
        this.subRepo = subRepo;
    }

    /** 已发布文档数（COUNT 查询，供启动日志等只计数场景使用，避免拉全量实体） */
    public long countPublic() {
        return repo.countByStatus("PUBLISHED");
    }

    /** 公开列表（分页）：仅返回已发布文档，按更新时间倒序，由数据库 LIMIT/OFFSET 完成，不拉全量 */
    public Page<Document> listPublic(Pageable pageable) {
        return listPublic(pageable, null);
    }

    /** 公开列表（分页 + 排序）：
     *  sort=hot       按浏览量倒序（热帖）
     *  sort=comments  按评论数倒序（最多评论）
     *  sort=favorites 按收藏数倒序（最多收藏）
     *  其余/缺省       按发布时间（发帖时间）倒序（最新发帖） */
    public Page<Document> listPublic(Pageable pageable, String sort) {
        int pn = pageable.getPageNumber();
        int sz = pageable.getPageSize();
        Sort pin = Sort.by(Sort.Direction.DESC, "pinned");
        if ("hot".equals(sort)) {
            return repo.findByStatus("PUBLISHED", PageRequest.of(pn, sz, pin.and(Sort.by(Sort.Direction.DESC, "views"))));
        }
        if ("comments".equals(sort)) {
            return repo.findByStatusOrderByPinnedCommentsDesc("PUBLISHED", pageable);
        }
        if ("favorites".equals(sort)) {
            return repo.findByStatusOrderByPinnedFavoritesDesc("PUBLISHED", pageable);
        }
        return repo.findByStatus("PUBLISHED", PageRequest.of(pn, sz, pin.and(Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    /** 公开列表（分页 + 排序 + 分类筛选）：分类为空时退化为无筛选版本 */
    public Page<Document> listPublic(Pageable pageable, String sort, String category) {
        if (category == null || category.isBlank()) {
            return listPublic(pageable, sort);
        }
        Sort s = toSort(sort);
        return repo.findByStatusAndCategory("PUBLISHED", category,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), s));
    }

    /** 排序串 → Sort（与无分类列表保持一致的顺序），置顶优先 */
    private Sort toSort(String sort) {
        Sort pin = Sort.by(Sort.Direction.DESC, "pinned");
        if ("hot".equals(sort)) return pin.and(Sort.by(Sort.Direction.DESC, "views"));
        if ("comments".equals(sort)) return pin.and(Sort.by(Sort.Direction.DESC, "comments"));
        if ("favorites".equals(sort)) return pin.and(Sort.by(Sort.Direction.DESC, "favorites"));
        return pin.and(Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    /** 公开详情：仅已发布文档可见（草稿/待审核/封禁均不可直接访问） */
    public Document getPublic(Long id) {
        Document d = get(id);
        if (!"PUBLISHED".equals(d.getStatus())) {
            throw new ResponseStatusException(NOT_FOUND, "文档不存在或已下架");
        }
        return d;
    }

    /** 管理员：全部文档（含封禁） */
    public List<Document> listAll() {
        return repo.findAll();
    }

    /** 管理员：全部文档（含草稿/待审核/封禁）分页检索 + 排序
     *  sort 支持 updatedAt / created / views / id；dir 支持 desc（默认）/ asc */
    public Page<Document> adminList(String keyword, String sort, String dir, int page, int size) {
        Sort.Direction d = "asc".equalsIgnoreCase(dir) ? Sort.Direction.ASC : Sort.Direction.DESC;
        Sort s;
        if ("created".equals(sort)) {
            s = Sort.by(d, "createdAt");
        } else if ("views".equals(sort)) {
            s = Sort.by(d, "views");
        } else if ("id".equals(sort)) {
            s = Sort.by(d, "id");
        } else {
            s = Sort.by(d, "updatedAt");
        }
        s = Sort.by(Sort.Direction.DESC, "pinned").and(s); // 置顶优先
        Pageable pageable = PageRequest.of(clampPage(page), clampSize(size), s);
        String kw = (keyword == null) ? "" : keyword.trim();
        return repo.adminSearch(kw, pageable);
    }

    /** 分页参数兜底：页码负数归零，避免 PageRequest.of 抛 IllegalArgumentException 返回 500 */
    private static int clampPage(int page) { return page < 0 ? 0 : page; }

    /** 分页参数兜底：每页条数限制在 1..100，避免 ?size=2147483647 一次性拉全表打爆堆内存 */
    private static int clampSize(int size) { return size <= 0 ? 15 : Math.min(size, 100); }

    /** 管理员：仅封禁文档（黑名单）——按状态直接查，不再全表内存过滤 */
    public List<Document> listBlocked() {
        return repo.findByStatusOrderByUpdatedAtDesc("BLOCKED");
    }

    /** 管理员：封禁文档（黑名单）分页 */
    public Page<Document> listBlocked(int page, int size) {
        return repo.findByStatusOrderByUpdatedAtDesc("BLOCKED", PageRequest.of(clampPage(page), clampSize(size)));
    }

    /** 管理员：待审核队列 */
    public List<Document> listPending() {
        return repo.findByStatusOrderByUpdatedAtDesc("PENDING_REVIEW");
    }

    @Transactional
    public Document create(Document d) {
        if (d.getStatus() == null || d.getStatus().isBlank()) d.setStatus("PUBLISHED");
        if (d.getOwnerId() == null) d.setOwnerId(1L);
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        if ("PUBLISHED".equals(saved.getStatus())) {
            authService.awardPoints(saved.getOwnerId(), AuthService.PUBLISH_POINTS, "PUBLISH",
                    "发布文章《" + clip(saved.getTitle()) + "》");
        }
        return saved;
    }

    /** 普通用户写帖：作者固定为当前用户 */
    @Transactional
    public Document createAs(Document d, Long ownerId) {
        if (d.getStatus() == null || d.getStatus().isBlank()) d.setStatus("PUBLISHED");
        d.setOwnerId(ownerId);
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        if ("PUBLISHED".equals(saved.getStatus())) {
            authService.awardPoints(ownerId, AuthService.PUBLISH_POINTS, "PUBLISH",
                    "发布文章《" + clip(saved.getTitle()) + "》");
        }
        auditDoc("CREATE_DOC", ownerId, saved.getId(), saved.getTitle(), "创建文档《" + clip(saved.getTitle()) + "》");
        return saved;
    }

    public Document get(Long id) {
        return repo.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "文档不存在"));
    }

    /** 管理员：置顶（列表优先展示） */
    @Transactional
    public Document pin(Long id) {
        Document d = get(id);
        d.setPinned(true);
        d.setUpdatedAt(LocalDateTime.now());
        return repo.save(d);
    }

    /** 管理员：取消置顶 */
    @Transactional
    public Document unpin(Long id) {
        Document d = get(id);
        d.setPinned(false);
        d.setUpdatedAt(LocalDateTime.now());
        return repo.save(d);
    }

    public List<Document> listByOwner(Long ownerId) {
        return repo.findByOwnerIdOrderByUpdatedAtDesc(ownerId);
    }

    /** 作者已发布文档（分页）：供「他的文章」列表与侧边栏展示，按浏览量倒序取热门 */
    public Page<Document> listByAuthor(Long ownerId, Pageable pageable) {
        return repo.findByOwnerIdAndStatusOrderByViewsDesc(ownerId, "PUBLISHED", pageable);
    }

    /** 公开列表（分页 + 排序 + 分类 + 作者筛选）：ownerId 非空时仅返回该作者已发布文档 */
    public Page<Document> listPublic(Pageable pageable, String sort, String category, Long ownerId) {
        Page<Document> page;
        if (ownerId != null) {
            if ("hot".equals(sort)) page = repo.findByOwnerIdAndStatusOrderByViewsDesc(ownerId, "PUBLISHED", pageable);
            else if ("comments".equals(sort)) page = repo.findByOwnerIdAndStatusOrderByCommentsDesc(ownerId, "PUBLISHED", pageable);
            else if ("favorites".equals(sort)) page = repo.findByOwnerIdAndStatusOrderByFavoritesDesc(ownerId, "PUBLISHED", pageable);
            else page = repo.findByOwnerIdAndStatusOrderByCreatedAtDesc(ownerId, "PUBLISHED", pageable);
        } else {
            page = listPublic(pageable, sort, category);
        }
        fillOwners(page.getContent());
        return page;
    }

    /** 批量填充作者名（displayName 优先，回退 username）：一次 IN 查询，无 N+1 */
    private void fillOwners(List<Document> docs) {
        Set<Long> ids = docs.stream().map(Document::getOwnerId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return;
        Map<Long, String> nameMap = userRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u ->
                        (u.getDisplayName() != null && !u.getDisplayName().isBlank())
                                ? u.getDisplayName() : u.getUsername()));
        for (Document d : docs) {
            Long oid = d.getOwnerId();
            d.setOwnerName(oid == null ? null : nameMap.get(oid));
        }
    }

    /** 详情访问：
     *  - 已发布：任何人可看，浏览量 +1，达热帖阈值给作者一次性奖励；
     *  - 非发布（草稿/待审核/封禁）：仅作者本人或管理员可预览（用于审核/编辑前查看），不计浏览量、不发热帖奖励；
     *    其余人访问按“不存在/已下架”处理，避免出现“待审核文章想看却看不了、进而无法审核”的死循环。 */
    @Transactional
    public Document view(Long id, TokenClaims c) {
        Document d = get(id);
        boolean published = "PUBLISHED".equals(d.getStatus());
        if (!published) {
            boolean canPreview = c != null &&
                    ("ADMIN".equals(c.role()) || (d.getOwnerId() != null && d.getOwnerId().equals(c.userId())));
            if (!canPreview) throw new ResponseStatusException(NOT_FOUND, "文档不存在或已下架");
            return d; // 预览：不累计浏览量、不发热帖奖励
        }
        repo.incrementViews(id);                          // 浏览量原子自增，避免并发丢更新
        int views = repo.findViewsById(id);               // 标量查询取最新浏览量（绕过一级缓存）
        d.setViews(views);
        if (!d.isHot() && views >= HOT_THRESHOLD) {
            d.setHot(true);
            authService.awardPoints(d.getOwnerId(), AuthService.HOT_POINTS, "HOT",
                    "热帖奖励（浏览达 " + HOT_THRESHOLD + "）");
        }
        return repo.save(d);
    }

    /** 管理员用：不校验归属，直接改 */
    @Transactional
    public Document update(Long id, Document patch, Long editorId) {
        Document d = get(id);
        snapshot(d, editorId);   // 改前先存一版快照
        if (patch.getTitle() != null) d.setTitle(patch.getTitle());
        if (patch.getContent() != null) d.setContent(patch.getContent());
        if (patch.getCategory() != null) d.setCategory(patch.getCategory());
        if (patch.getTags() != null) d.setTags(patch.getTags());
        if (patch.getStatus() != null) d.setStatus(patch.getStatus());
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        auditDoc("UPDATE_DOC", editorId, saved.getId(), saved.getTitle(), "更新文档《" + clip(saved.getTitle()) + "》");
        // 订阅推送：仅已发布文档的更新才推送，避免草稿反复编辑骚扰订阅者
        if ("PUBLISHED".equals(saved.getStatus())) {
            notificationService.notifyDocUpdate(saved.getId(), saved.getTitle(), editorId, usernameOf(editorId));
        }
        return saved;
    }

    /** 普通用户编辑自己的帖子：校验归属，否则 403 */
    @Transactional
    public Document updateOwn(Long id, Document patch, Long ownerId) {
        Document d = get(id);
        if (!Objects.equals(d.getOwnerId(), ownerId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能修改自己发布的帖子");
        }
        snapshot(d, ownerId);   // 改前先存一版快照
        if (patch.getTitle() != null) d.setTitle(patch.getTitle());
        if (patch.getContent() != null) d.setContent(patch.getContent());
        if (patch.getCategory() != null) d.setCategory(patch.getCategory());
        if (patch.getTags() != null) d.setTags(patch.getTags());
        // 作者不能自己发布：只允许在草稿/待审核之间切换，不能设 PUBLISHED（防绕过审核）
        if (patch.getStatus() != null) {
            String st = patch.getStatus();
            if ("DRAFT".equals(st) || "PENDING_REVIEW".equals(st)) d.setStatus(st);
        }
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        auditDoc("UPDATE_DOC", ownerId, saved.getId(), saved.getTitle(), "更新文档《" + clip(saved.getTitle()) + "》");
        // 订阅推送：仅已发布文档的更新才推送，避免草稿反复编辑骚扰订阅者
        if ("PUBLISHED".equals(saved.getStatus())) {
            notificationService.notifyDocUpdate(saved.getId(), saved.getTitle(), ownerId, usernameOf(ownerId));
        }
        return saved;
    }

    /** 取用户展示名（订阅通知里显示"谁更新了文档"）；查不到返回 null */
    private String usernameOf(Long userId) {
        if (userId == null) return null;
        return userRepo.findById(userId)
                .map(u -> (u.getDisplayName() != null && !u.getDisplayName().isBlank())
                        ? u.getDisplayName() : u.getUsername())
                .orElse(null);
    }

    /** 版本历史：列出某文档的全部历史版本（倒序） */
    public List<DocumentVersion> listVersions(Long docId) {
        return versionRepo.findByDocIdOrderByCreatedAtDesc(docId);
    }

    // ============================================================
    // 知识空间文档树：树 / 面包屑 / 移动
    // ============================================================

    /**
     * 知识空间文档树：返回该空间下「已发布」文档组成的树（children 递归）。
     * 只带 id / title / sortOrder，不返回 content 大字段，避免整树查询拖垮接口。
     */
    /** 全局文档树（仅已发布文档）：用于知识库文档层级浏览与「父文档」选择器。
     * 只带 id / title / sortOrder，不返回 content 大字段，避免整树查询拖垮接口。 */
    public List<Map<String, Object>> docTree() {
        List<Document> all = repo.findByStatusOrderBySortOrderAscIdAsc("PUBLISHED");
        Map<Long, List<Document>> byParent = new HashMap<>();
        for (Document d : all) {
            Long p = d.getParentId() == null ? 0L : d.getParentId();
            byParent.computeIfAbsent(p, k -> new ArrayList<>()).add(d);
        }
        return buildTreeNodes(byParent, 0L, new HashSet<>());
    }

    private List<Map<String, Object>> buildTreeNodes(Map<Long, List<Document>> byParent,
                                                     Long parentId, Set<Long> path) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document d : byParent.getOrDefault(parentId, List.of())) {
            if (!path.add(d.getId())) continue;   // 脏数据成环时的兜底，防止无限递归
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", d.getId());
            n.put("title", d.getTitle());
            n.put("sortOrder", d.getSortOrder());
            n.put("children", buildTreeNodes(byParent, d.getId(), path));
            out.add(n);
            path.remove(d.getId());
        }
        return out;
    }

    /** 文档面包屑：从最顶层祖先到该文档自身（含自身），供详情页展示「上级 / 当前」链路 */
    public List<Map<String, Object>> breadcrumb(Long id) {
        List<Document> chain = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Document cur = repo.findById(id).orElse(null);
        while (cur != null && seen.add(cur.getId())) {
            chain.add(cur);
            if (cur.getParentId() == null) break;
            cur = repo.findById(cur.getParentId()).orElse(null);
        }
        Collections.reverse(chain);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Document x : chain) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", x.getId());
            m.put("title", x.getTitle());
            out.add(m);
        }
        return out;
    }

    /** 移动文档：换父节点（null = 移到该空间根级）+ 可选同级排序号。禁止移到自己或自己的子孙下 */
    @Transactional
    public Document moveDoc(Long id, Long newParentId, Integer sortOrder, TokenClaims c) {
        Document d = get(id);
        boolean admin = c != null && "ADMIN".equals(c.role());
        boolean canOrganize = c != null && c.hasPerm("TAXONOMY_MANAGE");
        boolean owner = c != null && d.getOwnerId() != null && d.getOwnerId().equals(c.userId());
        if (!admin && !canOrganize && !owner) {
            throw new ResponseStatusException(FORBIDDEN, "无权移动该文档（需为作者本人，或拥有 TAXONOMY_MANAGE 权限）");
        }
        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new ResponseStatusException(BAD_REQUEST, "不能把文档移动到它自己下面");
            }
            if (isDescendant(newParentId, id)) {
                throw new ResponseStatusException(BAD_REQUEST, "不能把文档移动到它自己的子文档下面");
            }
            // 父节点合法性已在上面 isDescendant 中校验（不能挂到自己或子孙下）
        }
        d.setParentId(newParentId);
        d.setSortOrder(sortOrder != null ? sortOrder : nextSortOrder(newParentId));
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        auditDoc("MOVE_DOC", c == null ? null : c.userId(), saved.getId(), saved.getTitle(),
                "移动文档《" + clip(saved.getTitle()) + "》");
        return saved;
    }

    /** candidateId 是否位于 ancestorId 的子树中（用于移动时的环检测） */
    private boolean isDescendant(Long candidateId, Long ancestorId) {
        Set<Long> seen = new HashSet<>();
        Long cur = candidateId;
        while (cur != null && seen.add(cur)) {
            if (cur.equals(ancestorId)) return true;
            Document p = repo.findById(cur).orElse(null);
            cur = (p == null) ? null : p.getParentId();
        }
        return false;
    }

    private int nextSortOrder(Long parentId) {
        return repo.findByStatusOrderBySortOrderAscIdAsc("PUBLISHED").stream()
                .filter(x -> Objects.equals(x.getParentId(), parentId))
                .mapToInt(Document::getSortOrder)
                .max().orElse(0) + 1;
    }

    /** 版本历史：取单个版本 */
    public DocumentVersion getVersion(Long vid) {
        return versionRepo.findById(vid).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "版本不存在"));
    }

    /** 版本历史：回滚到指定版本（回滚前先存当前快照，避免覆盖丢失） */
    @Transactional
    public Document restoreVersion(Long docId, Long vid, Long editorId) {
        Document d = get(docId);
        DocumentVersion v = getVersion(vid);
        if (!v.getDocId().equals(docId)) {
            throw new ResponseStatusException(BAD_REQUEST, "该版本不属于此文档");
        }
        snapshot(d, editorId);   // 回滚前保存当前状态
        if (v.getTitle() != null) d.setTitle(v.getTitle());
        if (v.getContent() != null) d.setContent(v.getContent());
        if (v.getCategory() != null) d.setCategory(v.getCategory());
        if (v.getTags() != null) d.setTags(v.getTags());
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        auditDoc("RESTORE_VERSION", editorId, docId, saved.getTitle(), "回滚文档《" + clip(saved.getTitle()) + "》到历史版本 #" + vid);
        return saved;
    }

    /** 把当前文档状态存为一份历史版本快照 */
    private void snapshot(Document d, Long editorId) {
        DocumentVersion v = new DocumentVersion();
        v.setDocId(d.getId());
        v.setTitle(d.getTitle());
        v.setContent(d.getContent());
        v.setCategory(d.getCategory());
        v.setTags(d.getTags());
        v.setEditorId(editorId);
        v.setCreatedAt(LocalDateTime.now());
        versionRepo.save(v);
    }

    /** 普通用户删除自己的帖子：校验归属，否则 403。软删除（移入回收站），保留恢复与彻底删除能力 */
    @Transactional
    public void deleteOwn(Long id, Long ownerId) {
        Document d = get(id);
        if (!Objects.equals(d.getOwnerId(), ownerId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能删除自己发布的帖子");
        }
        moveToRecycle(d);
        auditDoc("DELETE_DOC", ownerId, d.getId(), d.getTitle(), "将文档《" + clip(d.getTitle()) + "》移入回收站");
    }

    @Transactional
    public void block(Long id) {
        Document d = get(id);
        d.setStatus("BLOCKED");
        Document saved = repo.save(d);
        meili.sync(saved);
    }

    @Transactional
    public void unblock(Long id) {
        Document d = get(id);
        d.setStatus("PUBLISHED");
        Document saved = repo.save(d);
        meili.sync(saved);
    }

    /** 作者提交审核：草稿 → 待审核（校验归属） */
    @Transactional
    public Document submitForReview(Long id, Long ownerId) {
        Document d = get(id);
        if (!Objects.equals(d.getOwnerId(), ownerId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能提交自己帖子的审核");
        }
        if (!"DRAFT".equals(d.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST, "仅草稿可提交审核");
        }
        d.setStatus("PENDING_REVIEW");
        d.setRejectReason(null);   // 重新提交审核，清除上次的驳回理由
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);         // 待审核不可被搜到，从索引移除
        // 通知全体管理员有新的待审核文章（提交者本人若是管理员则不重复通知自己）
        String submitter = userRepo.findById(ownerId).map(u -> u.getUsername()).orElse("用户");
        notificationService.notifyReviewSubmit(saved.getId(), saved.getTitle(), submitter, ownerId);
        return saved;
    }

    /** 作者撤回：待审核 → 草稿（审核前可拉回）；已发布 → 草稿（下架自己的文章重新修改）。
     *  仅作者本人或管理员可操作。已发布撤回时扣回发布积分，避免「撤回→重新提交→再通过」反复刷分。 */
    @Transactional
    public Document withdraw(Long id, Long ownerId, boolean isAdmin) {
        Document d = get(id);
        String st = d.getStatus();
        boolean wasPublished = "PUBLISHED".equals(st);
        if (!"PENDING_REVIEW".equals(st) && !wasPublished) {
            throw new ResponseStatusException(BAD_REQUEST, "仅待审核或已发布的帖子可撤回到草稿");
        }
        if (!isAdmin && !Objects.equals(d.getOwnerId(), ownerId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能撤回自己的帖子");
        }
        d.setStatus("DRAFT");
        d.setRejectReason(null);   // 主动撤回不是被驳回，清掉旧的驳回理由
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);         // 撤回下架不可被搜到，从索引移除
        if (wasPublished) {
            // 发布时发过 PUBLISH_POINTS，撤回下架时原路扣回（approve 再次通过会重新发放）
            authService.awardPoints(saved.getOwnerId(), -AuthService.PUBLISH_POINTS, "PUBLISH",
                    "撤回已发布文章《" + clip(saved.getTitle()) + "》");
        }
        return saved;
    }

    /** 管理员通过审核：待审核 → 已发布，并首次发放积分 */
    @Transactional
    public Document approve(Long id) {
        Document d = get(id);
        boolean wasPublished = "PUBLISHED".equals(d.getStatus());
        d.setStatus("PUBLISHED");
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        if (!wasPublished) {
            authService.awardPoints(saved.getOwnerId(), AuthService.PUBLISH_POINTS, "PUBLISH",
                    "发布文章《" + clip(saved.getTitle()) + "》");
        }
        notificationService.notifyReview(saved.getId(), saved.getTitle(), saved.getOwnerId(), true, null);
        return saved;
    }

    /** 管理员驳回：待审核 → 退回草稿，并记录驳回理由（作者可见）。理由必填，否则 400 */
    @Transactional
    public Document reject(Long id, String reason) {
        Document d = get(id);
        if (!"PENDING_REVIEW".equals(d.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST, "仅待审核帖子可被驳回");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "驳回必须填写理由");
        }
        d.setStatus("DRAFT");
        d.setRejectReason(reason);
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);         // 退回草稿不可被搜到，从索引移除
        notificationService.notifyReview(saved.getId(), saved.getTitle(), saved.getOwnerId(), false, reason);
        return saved;
    }

    /** 管理员删除：软删除（移入回收站）。审计由 AdminDocController 负责，避免重复 */
    @Transactional
    public void delete(Long id) {
        Document d = get(id);
        moveToRecycle(d);
    }

    public List<String> categories() {
        // 只取 category 列（SQL DISTINCT），不再 findAll 拉全行 TEXT
        return repo.findDistinctCategories().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.toList());
    }

    /** 公开：标签聚合（去重 + 计数），按计数倒序，用于标签云/筛选 */
    public List<Map<String, Object>> allTags() {
        Map<String, Integer> count = new LinkedHashMap<>();
        // 只取 tags 列，不再 findAll 拉全行 TEXT
        for (String tags : repo.findAllTagsRaw()) {
            if (tags == null) continue;
            for (String t : tags.split(",")) {
                t = t.trim();
                if (!t.isEmpty()) count.merge(t, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> res = new ArrayList<>();
        count.entrySet().stream()
                .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("tag", e.getKey());
                    m.put("count", e.getValue());
                    res.add(m);
                });
        return res;
    }

    /** 公开：按标签筛选（tags 逗号分隔字段的边界匹配），支持最新/最热排序 */
    public Page<Document> listByTag(Pageable pageable, String sort, String tag) {
        if ("hot".equals(sort)) return repo.findByTagHot(tag, pageable);
        return repo.findByTag(tag, pageable);
    }

    // ---------- 相关文档推荐 ----------

    /** 标签拆分：逗号分隔字符串 → 去空白去空的标签集合 */
    private static Set<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String t : tags.split(",")) {
            t = t.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * 相关文档推荐：同分类 + 标签重叠加权打分，得分低的补位高浏览文档，避免内容变成孤岛。
     *
     * 打分规则：同分类 +2；每个共同标签 +3。只回列表展示需要的轻量字段（不含正文）。
     * 只用 MySQL，无外部依赖——MeiliSearch 未启用时同样可用。
     *
     * @param docId 源文档 id
     * @param limit 返回条数（<=0 时取默认 6，上限 20）
     */
    public List<Document> relatedDocs(Long docId, int limit) {
        if (limit <= 0) limit = 6;
        if (limit > 20) limit = 20;
        if (docId == null) return List.of();
        Document src = repo.findById(docId).orElse(null);
        if (src == null) return List.of();

        String cat = (src.getCategory() == null || src.getCategory().isBlank()) ? null : src.getCategory();
        final Set<String> srcTags = parseTags(src.getTags());

        // 候选集：同分类（或源文档无分类时的全站）按浏览量取前 300 条，避免全表扫描后全量打分
        List<Object[]> rows = repo.findRelatedCandidates(docId, cat, PageRequest.of(0, 300));

        record Cand(long id, String title, String category, String tags, int views, int score) {}
        List<Cand> scored = new ArrayList<>();
        for (Object[] r : rows) {
            if (r == null || r.length < 5 || r[0] == null) continue;
            long id = ((Number) r[0]).longValue();
            String title = r[1] == null ? "" : String.valueOf(r[1]);
            String category = r[2] == null ? null : String.valueOf(r[2]);
            String tags = r[3] == null ? null : String.valueOf(r[3]);
            int views = r[4] == null ? 0 : ((Number) r[4]).intValue();
            int score = 0;
            if (cat != null && cat.equals(category)) score += 2;
            Set<String> common = new java.util.LinkedHashSet<>(parseTags(tags));
            common.retainAll(srcTags);          // 标签交集
            score += common.size() * 3;
            if (score > 0) scored.add(new Cand(id, title, category, tags, views, score));
        }
        // 相关度优先，同分看浏览量
        scored.sort(java.util.Comparator
                .comparingInt(Cand::score).reversed()
                .thenComparingInt(Cand::views).reversed());

        List<Document> res = new ArrayList<>();
        List<Long> picked = new ArrayList<>();
        for (Cand c : scored) {
            if (res.size() >= limit) break;
            res.add(lightDoc(c.id(), c.title(), c.category(), c.tags(), c.views()));
            picked.add(c.id());
        }

        // 相关文档不足（新站/冷门分类）时，用高浏览文档补位，避免底部空白
        if (res.size() < limit) {
            List<Long> excluded = new ArrayList<>(picked);
            excluded.add(docId);                // 保证非空且排除自身
            for (Object[] r : repo.findPopularExcept(docId, excluded, PageRequest.of(0, limit - res.size()))) {
                if (r == null || r.length < 5 || r[0] == null) continue;
                long id = ((Number) r[0]).longValue();
                res.add(lightDoc(id,
                        r[1] == null ? "" : String.valueOf(r[1]),
                        r[2] == null ? null : String.valueOf(r[2]),
                        r[3] == null ? null : String.valueOf(r[3]),
                        r[4] == null ? 0 : ((Number) r[4]).intValue()));
            }
        }
        return res;
    }

    /** 构造只含列表展示字段的轻量 Document（不暴露正文） */
    private static Document lightDoc(long id, String title, String category, String tags, int views) {
        Document d = new Document();
        d.setId(id);
        d.setTitle(title);
        d.setCategory(category);
        d.setTags(tags);
        d.setViews(views);
        return d;
    }

    /** 截断标题用于积分明细说明，避免过长 */
    private String clip(String t) {
        if (t == null) return "";
        t = t.trim();
        return t.length() > 20 ? t.substring(0, 20) + "…" : t;
    }

    // ---------- 回收站 / 软删除 ----------

    /** 将文档移入回收站（软删除）：标记 deleted + 从 Meili 索引移除 */
    private void moveToRecycle(Document d) {
        d.setDeleted(true);
        d.setDeletedAt(LocalDateTime.now());
        repo.save(d);
        meili.delete(d.getId());
    }

    /** 回收站列表：管理员看全部，普通用户只看自己的 */
    public List<Document> listRecycleBin(Long ownerId, boolean isAdmin) {
        List<Document> list = isAdmin ? repo.findRecycled() : repo.findRecycledByOwner(ownerId);
        fillOwners(list);
        return list;
    }

    /** 从回收站恢复：清除软删除标记并重新进 Meili 索引（仅当已发布） */
    @Transactional
    public Document restore(Long id, Long actorId, boolean isAdmin) {
        Document d = repo.findIncludingDeletedById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "文档不存在"));
        if (!isAdmin && !Objects.equals(d.getOwnerId(), actorId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能恢复自己移入回收站的文档");
        }
        d.setDeleted(false);
        d.setDeletedAt(null);
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        auditDoc("RESTORE_DOC", actorId, id, d.getTitle(), "从回收站恢复文档《" + clip(d.getTitle()) + "》");
        return saved;
    }

    /** 彻底删除（物理删除），绕过软删除过滤 */
    @Transactional
    public void purge(Long id, Long actorId, boolean isAdmin) {
        Document d = repo.findIncludingDeletedById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "文档不存在"));
        if (!isAdmin && !Objects.equals(d.getOwnerId(), actorId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能彻底删除自己移入回收站的文档");
        }
        String title = d.getTitle();
        repo.delete(d);   // 走 JPA 删除（若配置了级联可一并清理关联数据）
        subRepo.deleteAllByDocId(id);   // 清理该文档的订阅，避免「我的订阅」里留下永久孤儿记录
        meili.delete(id);
        auditDoc("PURGE_DOC", actorId, id, title, "彻底删除文档《" + clip(title) + "》");
    }

    /** 取含软删除的文档（回收站相关操作使用，普通 get 会被 @SQLRestriction 过滤） */
    public Document getIncludeDeleted(Long id) {
        return repo.findIncludingDeletedById(id).orElse(null);
    }

    /** 导出为 Word：HTML 封装的 .doc（Word/WPS 均可打开，中文无乱码） */
    public String exportWord(Long id, Long actorId) {
        Document d = get(id);
        String safeTitle = HtmlSanitizer.escapeHtml(d.getTitle());
        String content = d.getContent() == null ? "" : d.getContent();
        String html = "<html xmlns:o='urn:schemas-microsoft-com:office:office' " +
                "xmlns:w='urn:schemas-microsoft-com:office:word' xmlns='http://www.w3.org/TR/REC-html40'>" +
                "<head><meta charset='utf-8'><title>" + safeTitle + "</title>" +
                "<style>body{font-family:'Microsoft YaHei','SimSun',sans-serif;line-height:1.6;font-size:14px;}" +
                "img{max-width:100%} table{border-collapse:collapse} td,th{border:1px solid #ccc;padding:4px 8px}</style>" +
                "</head><body><h1>" + safeTitle + "</h1>" + content + "</body></html>";
        auditDoc("EXPORT_DOC", actorId, id, d.getTitle(), "导出文档《" + clip(d.getTitle()) + "》为 Word");
        return html;
    }

    /** 文档操作审计（best-effort，异常由 AuditService 内部吞掉） */
    private void auditDoc(String action, Long actorId, Long docId, String title, String detail) {
        if (actorId == null) return;
        String name = userRepo.findById(actorId)
                .map(u -> (u.getDisplayName() != null && !u.getDisplayName().isBlank()) ? u.getDisplayName() : u.getUsername())
                .orElse(String.valueOf(actorId));
        auditService.log(actorId, name, action, "DOCUMENT", docId, title, detail);
    }
}
