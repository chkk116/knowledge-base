/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

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
import static org.springframework.http.HttpStatus.CONFLICT;
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
    private final com.kb.kbplatform.repository.CommentRepository commentRepo;
    private final com.kb.kbplatform.repository.LikeRepository likeRepo;
    private final com.kb.kbplatform.repository.FavoriteRepository favoriteRepo;

    public DocumentService(DocumentRepository repo, AuthService authService, NotificationService notificationService, UserRepository userRepo, MeiliSearchClient meili, DocumentVersionRepository versionRepo, AuditService auditService, DocSubscriptionRepository subRepo,
                           com.kb.kbplatform.repository.CommentRepository commentRepo,
                           com.kb.kbplatform.repository.LikeRepository likeRepo,
                           com.kb.kbplatform.repository.FavoriteRepository favoriteRepo) {
        this.repo = repo;
        this.authService = authService;
        this.notificationService = notificationService;
        this.userRepo = userRepo;
        this.meili = meili;
        this.versionRepo = versionRepo;
        this.auditService = auditService;
        this.subRepo = subRepo;
        this.commentRepo = commentRepo;
        this.likeRepo = likeRepo;
        this.favoriteRepo = favoriteRepo;
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
        return listPublicFiltered(pageable, sort, null, null, null);
    }

    /** 公开列表（分页 + 排序 + 分类 + 作者 + 标签）统一入口（F11）：
     *  之前分类分支用实体属性 comments/favorites 排序（实体并无这两个字段）→ 查询解析失败；
     *  标签分支还会吞掉分类与作者条件。这里统一走组合查询，计数类排序改用子查询。 */
    public Page<Document> listPublicFiltered(Pageable pageable, String sort, String category, Long ownerId, String tag) {
        String cat = (category == null || category.isBlank()) ? null : category.trim();
        String tg = (tag == null || tag.isBlank()) ? null : tag.trim();
        Pageable pg = PageRequest.of(clampPage(pageable.getPageNumber()), clampSize(pageable.getPageSize()));
        Page<Document> page;
        if ("comments".equals(sort)) {
            page = repo.findPublicFilteredByComments("PUBLISHED", cat, ownerId, tg, pg);
        } else if ("favorites".equals(sort)) {
            page = repo.findPublicFilteredByFavorites("PUBLISHED", cat, ownerId, tg, pg);
        } else {
            Sort s = Sort.by(Sort.Direction.DESC, "pinned")
                    .and(Sort.by(Sort.Direction.DESC, "hot".equals(sort) ? "views" : "createdAt"));
            page = repo.findPublicFiltered("PUBLISHED", cat, ownerId, tg, PageRequest.of(pg.getPageNumber(), pg.getPageSize(), s));
        }
        fillOwners(page.getContent());
        return page;
    }

    /** 公开列表（分页 + 排序 + 分类筛选）：分类为空时退化为无筛选版本 */
    public Page<Document> listPublic(Pageable pageable, String sort, String category) {
        return listPublicFiltered(pageable, sort, category, null, null);
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

    /** 新建时允许客户端指定的状态（其余状态一律不允许由创建接口写入） */
    private static final Set<String> CREATE_STATUS = Set.of("DRAFT", "PENDING_REVIEW", "PUBLISHED");

    /**
     * 新建实体字段白名单：主键与内部统计／审核／删除字段全部由服务端生成。
     * 关键点是 set 主键为 null —— 实体无版本字段，主键非空时 JPA 会走 merge 路径，
     * 客户端传入他人的文档 id 就能覆盖其内容与归属（F01）。
     */
    private void applyCreateWhitelist(Document d, String fallbackStatus) {
        d.setId(null);
        d.setOwnerName(null);
        d.setViews(0);
        d.setHot(false);
        d.setPinned(false);
        d.setLikes(0);
        d.setDeleted(false);
        d.setDeletedAt(null);
        d.setRejectReason(null);
        if (d.getStatus() == null || !CREATE_STATUS.contains(d.getStatus())) d.setStatus(fallbackStatus);
        validateParent(d.getParentId());
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
    }

    /** 父节点必须存在且处于可展示状态，否则文档会挂在树外（F12） */
    private void validateParent(Long parentId) {
        if (parentId == null) return;
        Document parent = repo.findById(parentId)
                .orElseThrow(() -> new ResponseStatusException(BAD_REQUEST, "父文档不存在"));
        if (parent.isDeleted() || !"PUBLISHED".equals(parent.getStatus())) {
            throw new ResponseStatusException(BAD_REQUEST, "父文档不可用（未发布或已下架）");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Document create(Document d) {
        applyCreateWhitelist(d, "PUBLISHED");
        Document saved = repo.save(d);
        meili.sync(saved);
        if ("PUBLISHED".equals(saved.getStatus())) {
            authService.awardPoints(saved.getOwnerId(), AuthService.PUBLISH_POINTS, "PUBLISH",
                    "发布文章《" + clip(saved.getTitle()) + "》");
        }
        return saved;
    }

    /** 普通用户写帖：作者固定为当前用户。
     *  非法/缺失状态回退为 PENDING_REVIEW（而非 PUBLISHED）——否则客户端伪造
     *  status=BLOCKED 之类会被白名单回退成已发布，绕过审核（F01 补丁）。 */
    @Transactional(rollbackFor = Exception.class)
    public Document createAs(Document d, Long ownerId) {
        applyCreateWhitelist(d, "PENDING_REVIEW");
        d.setOwnerId(ownerId);
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
    @Transactional(rollbackFor = Exception.class)
    public Document pin(Long id) {
        Document d = get(id);
        d.setPinned(true);
        d.setUpdatedAt(LocalDateTime.now());
        return repo.save(d);
    }

    /** 管理员：取消置顶 */
    @Transactional(rollbackFor = Exception.class)
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
        return listPublicFiltered(pageable, sort, category, ownerId, null);
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
     *  - 非发布（草稿/待审核/封禁）：作者本人、完全管理员可预览；
     *    持 DOC_REVIEW 的审核人只能预览待审核文档，不能查看他人的草稿或封禁文档；
     *    其余人访问按“不存在/已下架”处理。
     *  预览不做任何写入（不累计浏览量、不发热帖奖励）。 */
    @Transactional(rollbackFor = Exception.class)
    public Document view(Long id, TokenClaims c) {
        Document d = get(id);
        boolean published = "PUBLISHED".equals(d.getStatus());
        if (!published) {
            boolean canPreview = c != null && (
                    c.isAdmin()
                    || ("PENDING_REVIEW".equals(d.getStatus()) && c.hasPerm("DOC_REVIEW"))
                    || (d.getOwnerId() != null && d.getOwnerId().equals(c.userId())));
            if (!canPreview) throw new ResponseStatusException(NOT_FOUND, "文档不存在或已下架");
            return d; // 预览：不累计浏览量、不发热帖奖励
        }
        repo.incrementViews(id);                          // 浏览量原子自增，避免并发丢更新
        int views = repo.findViewsById(id);               // 标量查询取最新浏览量（绕过一级缓存）
        d.setViews(views);
        if (!d.isHot() && views >= HOT_THRESHOLD) {
            // 外部审查 2026-10-11：不再 repo.save(d) 整体写回——那会把 get(id) 读到的旧实体
            // 盖掉并发编辑（标题/内容丢失）。hot 用原子 UPDATE 翻转，只有翻成功的请求发奖励。
            if (repo.markHotIfNot(id) > 0) {
                d.setHot(true);
                authService.awardPoints(d.getOwnerId(), AuthService.HOT_POINTS, "HOT",
                        "热帖奖励（浏览达 " + HOT_THRESHOLD + "）");
            }
        }
        return d;
    }

    /** 管理员用：不校验归属，直接改 */
    @Transactional(rollbackFor = Exception.class)
    public Document update(Long id, Document patch, Long editorId) {
        Document d = get(id);
        String previousStatus = d.getStatus();
        snapshot(d, editorId);   // 改前先存一版快照
        if (patch.getTitle() != null) d.setTitle(patch.getTitle());
        if (patch.getContent() != null) d.setContent(patch.getContent());
        if (patch.getCategory() != null) d.setCategory(patch.getCategory());
        if (patch.getTags() != null) d.setTags(patch.getTags());
        if (patch.getStatus() != null) d.setStatus(patch.getStatus());
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        promoteChildrenIfHidden(saved, previousStatus);
        auditDoc("UPDATE_DOC", editorId, saved.getId(), saved.getTitle(), "更新文档《" + clip(saved.getTitle()) + "》");
        // 订阅推送：仅已发布文档的更新才推送，避免草稿反复编辑骚扰订阅者。
        // 走异步入口（M6）：热门文档上千订阅者的通知写入不阻塞本次保存请求。
        if ("PUBLISHED".equals(saved.getStatus())) {
            notificationService.notifyDocUpdateAsync(saved.getId(), saved.getTitle(), editorId, usernameOf(editorId));
        }
        return saved;
    }

    /** 普通用户编辑自己的帖子：校验归属，否则 403。
     *  业务规则（2026-09-11 拍板）：已发布文章一经修改必须重新审核——
     *  作者编辑已发布文档 → 状态回到 PENDING_REVIEW 并通知管理员；发布积分按修订版结算，
     *  离开已发布状态时原路扣回，审核通过再次发放，保证「每个过审修订版恰好对应一次积分」（F06 补充口径）。 */
    @Transactional(rollbackFor = Exception.class)
    public Document updateOwn(Long id, Document patch, Long ownerId) {
        Document d = get(id);
        if (!Objects.equals(d.getOwnerId(), ownerId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能修改自己发布的帖子");
        }
        String previousStatus = d.getStatus();
        boolean wasPublished = "PUBLISHED".equals(previousStatus);
        snapshot(d, ownerId);   // 改前先存一版快照
        if (patch.getTitle() != null) d.setTitle(patch.getTitle());
        if (patch.getContent() != null) d.setContent(patch.getContent());
        if (patch.getCategory() != null) d.setCategory(patch.getCategory());
        if (patch.getTags() != null) d.setTags(patch.getTags());
        // 作者不能自己发布；已发布修改默认重审，但显式保存草稿仍保留 DRAFT。
        if (wasPublished) d.setStatus("PENDING_REVIEW");
        if ("DRAFT".equals(patch.getStatus()) || "PENDING_REVIEW".equals(patch.getStatus())) {
            d.setStatus(patch.getStatus());
        }
        boolean enteredReview = !"PENDING_REVIEW".equals(previousStatus)
                && "PENDING_REVIEW".equals(d.getStatus());
        if (wasPublished || "PENDING_REVIEW".equals(d.getStatus())) d.setRejectReason(null);
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        promoteChildrenIfHidden(saved, previousStatus);
        if (wasPublished && !"PUBLISHED".equals(saved.getStatus())) {
            // 规则 B：离开已发布状态扣回发布积分，审核通过时随新修订版再次发放
            authService.awardPoints(saved.getOwnerId(), -AuthService.PUBLISH_POINTS, "PUBLISH",
                    "修改已发布文章《" + clip(saved.getTitle()) + "》，"
                            + ("DRAFT".equals(saved.getStatus()) ? "转为草稿" : "转入重新审核"));
        }
        if (enteredReview) notifyReviewSubmit(saved, ownerId);
        auditDoc("UPDATE_DOC", ownerId, saved.getId(), saved.getTitle(), "更新文档《" + clip(saved.getTitle()) + "》");
        // 订阅推送：仅仍处于已发布状态的更新才推送（重审中的不再推）。
        // 走异步入口（M6）：通知写入移到线程池，不阻塞保存请求。
        if ("PUBLISHED".equals(saved.getStatus())) {
            notificationService.notifyDocUpdateAsync(saved.getId(), saved.getTitle(), ownerId, usernameOf(ownerId));
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
    @Transactional(rollbackFor = Exception.class)
    public Document moveDoc(Long id, Long newParentId, Integer sortOrder, TokenClaims c) {
        Document d = get(id);
        boolean admin = c != null && c.isAdmin();   // 与后台门禁同口径
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
            // 父节点必须真实存在且可见（F12）：原实现只做环检测，父 id 不存在时
            // isDescendant 返回 false 反而"校验通过"，文档会挂到不存在的父节点上，
            // 既不在树里出现，也无法通过界面找回。
            validateParent(newParentId);
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
        return repo.maxSortOrderByParent(parentId) + 1;   // 外部审查 2026-10-11：DB 聚合替代全表加载
    }

    /** 版本历史：取单个版本 */
    public DocumentVersion getVersion(Long vid) {
        return versionRepo.findById(vid).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "版本不存在"));
    }

    /** 版本历史：回滚到指定版本（回滚前先存当前快照，避免覆盖丢失）。
     *  业务规则（规则 A/B）：非管理员把「已发布」文档回滚 → 视同修改，转入重新审核并按修订版结算积分 */
    @Transactional(rollbackFor = Exception.class)
    public Document restoreVersion(Long docId, Long vid, Long editorId) {
        Document d = get(docId);
        DocumentVersion v = getVersion(vid);
        if (!v.getDocId().equals(docId)) {
            throw new ResponseStatusException(BAD_REQUEST, "该版本不属于此文档");
        }
        String previousStatus = d.getStatus();
        boolean wasPublished = "PUBLISHED".equals(previousStatus);
        snapshot(d, editorId);   // 回滚前保存当前状态
        if (v.getTitle() != null) d.setTitle(v.getTitle());
        if (v.getContent() != null) d.setContent(v.getContent());
        if (v.getCategory() != null) d.setCategory(v.getCategory());
        if (v.getTags() != null) d.setTags(v.getTags());
        boolean editorAdmin = userRepo.findById(editorId).map(u -> "ADMIN".equals(u.getRole())).orElse(false);
        if (wasPublished && !editorAdmin) {
            d.setStatus("PENDING_REVIEW");
            d.setRejectReason(null);
        }
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        promoteChildrenIfHidden(saved, previousStatus);
        if (wasPublished && !editorAdmin) {
            authService.awardPoints(saved.getOwnerId(), -AuthService.PUBLISH_POINTS, "PUBLISH",
                    "回滚已发布文章《" + clip(saved.getTitle()) + "》，转入重新审核");
            notifyReviewSubmit(saved, saved.getOwnerId());
        }
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
    @Transactional(rollbackFor = Exception.class)
    public void deleteOwn(Long id, Long ownerId) {
        Document d = get(id);
        if (!Objects.equals(d.getOwnerId(), ownerId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能删除自己发布的帖子");
        }
        moveToRecycle(d);
        auditDoc("DELETE_DOC", ownerId, d.getId(), d.getTitle(), "将文档《" + clip(d.getTitle()) + "》移入回收站");
    }

    @Transactional(rollbackFor = Exception.class)
    public void block(Long id) {
        Document d = get(id);
        String previousStatus = d.getStatus();
        d.setStatus("BLOCKED");
        Document saved = repo.save(d);
        meili.sync(saved);
        promoteChildrenIfHidden(saved, previousStatus);
    }

    /** 仅已发布父文档实际下架时改树，避免草稿/待审反复保存时批量改动子节点。 */
    private void promoteChildrenIfHidden(Document d, String previousStatus) {
        if ("PUBLISHED".equals(previousStatus) && (d.isDeleted() || !"PUBLISHED".equals(d.getStatus()))) {
            promoteOrphanChildren(d.getId());
        }
    }

    /**
     * 父文档变为不可见（下架/封禁/撤回）时，把它的直接子文档提升到根级。
     * 文档树只从 PUBLISHED 根节点递归，若不处理，已发布的子文档会整棵从树视图里消失
     * （正文仍在，只是找不到入口）。提升到根级是最不破坏可发现性的做法。
     */
    private void promoteOrphanChildren(Long parentId) {
        if (parentId == null) return;
        List<Document> children = repo.findByParentId(parentId);
        if (children.isEmpty()) return;
        for (Document child : children) {
            child.setParentId(null);
            child.setUpdatedAt(LocalDateTime.now());
        }
        repo.saveAll(children);
    }

    @Transactional(rollbackFor = Exception.class)
    public void unblock(Long id) {
        Document d = get(id);
        d.setStatus("PUBLISHED");
        Document saved = repo.save(d);
        meili.sync(saved);
    }

    /** 作者提交审核：草稿 → 待审核（校验归属） */
    @Transactional(rollbackFor = Exception.class)
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
        notifyReviewSubmit(saved, ownerId);
        return saved;
    }

    /**
     * 新建即进入待审核（普通用户发文章被直接置为 PENDING_REVIEW）时通知管理员。
     * 与 submitForReview 共用同一通知逻辑，避免「写文章」绕过审核通知。
     */
    @Transactional(rollbackFor = Exception.class)
    public void notifyReviewSubmitIfPending(Document d, Long ownerId) {
        if (d != null && "PENDING_REVIEW".equals(d.getStatus())) {
            notifyReviewSubmit(d, ownerId);
        }
    }

    private void notifyReviewSubmit(Document saved, Long ownerId) {
        // 通知全体管理员有新的待审核文章（提交者本人若是管理员则不重复通知自己）
        String submitter = userRepo.findById(ownerId).map(User::getUsername).orElse("用户");
        notificationService.notifyReviewSubmit(saved.getId(), saved.getTitle(), submitter, ownerId);
    }

    /** 作者撤回：待审核 → 草稿（审核前可拉回）；已发布 → 草稿（下架自己的文章重新修改）。
     *  仅作者本人或管理员可操作。已发布撤回时扣回发布积分，避免「撤回→重新提交→再通过」反复刷分。 */
    @Transactional(rollbackFor = Exception.class)
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
        promoteOrphanChildren(saved.getId());   // 下架后子文档提升到根级，避免在树里消失（F12）
        if (wasPublished) {
            // 发布时发过 PUBLISH_POINTS，撤回下架时原路扣回（approve 再次通过会重新发放）
            authService.awardPoints(saved.getOwnerId(), -AuthService.PUBLISH_POINTS, "PUBLISH",
                    "撤回已发布文章《" + clip(saved.getTitle()) + "》");
        }
        return saved;
    }

    /** 管理员通过审核：待审核 → 已发布，并首次发放积分。
     *  必须校验当前状态：审核人页面停留期间作者可能已撤回为草稿，此时旧审核动作不得把草稿发布出去（F06） */
    @Transactional(rollbackFor = Exception.class)
    public Document approve(Long id) {
        Document d = get(id);
        if (!"PENDING_REVIEW".equals(d.getStatus())) {
            throw new ResponseStatusException(CONFLICT,
                    "该文档当前不是待审核状态（可能已被作者撤回或已处理），请刷新后重试");
        }
        d.setStatus("PUBLISHED");
        d.setUpdatedAt(LocalDateTime.now());
        Document saved = repo.save(d);
        meili.sync(saved);
        authService.awardPoints(saved.getOwnerId(), AuthService.PUBLISH_POINTS, "PUBLISH",
                "发布文章《" + clip(saved.getTitle()) + "》");
        notificationService.notifyReview(saved.getId(), saved.getTitle(), saved.getOwnerId(), true, null);
        return saved;
    }

    /** 管理员驳回：待审核 → 退回草稿，并记录驳回理由（作者可见）。理由必填，否则 400 */
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Document d = get(id);
        moveToRecycle(d);   // 内部统一负责提升子文档，避免两条删除路径行为不一致
    }

    /** 截断标题用于积分明细说明，避免过长 */
    private String clip(String t) {
        if (t == null) return "";
        t = t.trim();
        return t.length() > 20 ? t.substring(0, 20) + "…" : t;
    }

    // ---------- 回收站 / 软删除 ----------

    /**
     * 将文档移入回收站（软删除）：标记 deleted + 从 Meili 索引移除。
     * 回收前先把直接子文档提升到根级——软删除的父文档会被 @SQLRestriction 从所有常规查询里排除，
     * 文档树因此拿不到这个父节点，挂在它下面的已发布子文档会整棵从树视图消失（正文仍在，只是找不到入口）。
     * 之前只有管理员 delete() 做了提升，作者自己删帖（deleteOwn）走的同一方法却漏了这一步（F16）。
     */
    private void moveToRecycle(Document d) {
        promoteOrphanChildren(d.getId());
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
    @Transactional(rollbackFor = Exception.class)
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

    /**
     * 彻底删除（物理删除），绕过软删除过滤。
     * 业务规则（2026-09-11 拍板）：「彻底删除」就是真删——作者/管理员可直接彻底删除，
     * 不强制先进回收站；同时统一清理全部关联记录，不留孤儿数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public void purge(Long id, Long actorId, boolean isAdmin) {
        Document d = repo.findIncludingDeletedById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "文档不存在"));
        if (!isAdmin && !Objects.equals(d.getOwnerId(), actorId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能彻底删除自己移入回收站的文档");
        }
        String title = d.getTitle();
        // 真删会留下指向不存在父节点的子文档（树视图里永久消失），删除前先提升到根级（F16）
        promoteOrphanChildren(id);
        // 关联记录统一清理（部分为 @Modifying 批量删除，需事务包裹）
        commentRepo.deleteByDocId(id);
        likeRepo.deleteByDocId(id);
        favoriteRepo.deleteByDocId(id);
        versionRepo.deleteByDocId(id);
        subRepo.deleteAllByDocId(id);   // 清理该文档的订阅，避免「我的订阅」里留下永久孤儿记录
        repo.delete(d);   // 走 JPA 删除
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
