package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.domain.DocumentVersion;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.DocumentRepository;
import com.kb.kbplatform.repository.DocumentVersionRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.SensitiveWordFilter;
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
    private final SensitiveWordFilter sensitiveWordFilter;
    private final NotificationService notificationService;
    private final UserRepository userRepo;
    private final MeiliSearchClient meili;
    private final DocumentVersionRepository versionRepo;

    public DocumentService(DocumentRepository repo, AuthService authService, SensitiveWordFilter sensitiveWordFilter, NotificationService notificationService, UserRepository userRepo, MeiliSearchClient meili, DocumentVersionRepository versionRepo) {
        this.repo = repo;
        this.authService = authService;
        this.sensitiveWordFilter = sensitiveWordFilter;
        this.notificationService = notificationService;
        this.userRepo = userRepo;
        this.meili = meili;
        this.versionRepo = versionRepo;
    }

    /** 公开列表：仅返回已发布文档（草稿/待审核/封禁均不对外可见） */
    public List<Document> listPublic() {
        return repo.findAll().stream()
                .filter(d -> "PUBLISHED".equals(d.getStatus()))
                .collect(Collectors.toList());
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
        Pageable pageable = PageRequest.of(page, size, s);
        String kw = (keyword == null) ? "" : keyword.trim();
        return repo.adminSearch(kw, pageable);
    }

    /** 管理员：仅封禁文档（黑名单）——按状态直接查，不再全表内存过滤 */
    public List<Document> listBlocked() {
        return repo.findByStatusOrderByUpdatedAtDesc("BLOCKED");
    }

    /** 管理员：封禁文档（黑名单）分页 */
    public Page<Document> listBlocked(int page, int size) {
        return repo.findByStatusOrderByUpdatedAtDesc("BLOCKED", PageRequest.of(page, size));
    }

    /** 管理员：待审核队列 */
    public List<Document> listPending() {
        return repo.findByStatusOrderByUpdatedAtDesc("PENDING_REVIEW");
    }

    @Transactional
    public Document create(Document d) {
        checkSensitive(d);
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
        checkSensitive(d);
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
        d.setViews(d.getViews() + 1);
        if (!d.isHot() && d.getViews() >= HOT_THRESHOLD) {
            d.setHot(true);
            authService.awardPoints(d.getOwnerId(), AuthService.HOT_POINTS, "HOT",
                    "热帖奖励（浏览达 " + HOT_THRESHOLD + "）");
        }
        return repo.save(d);
    }

    /** 管理员用：不校验归属，直接改 */
    @Transactional
    public Document update(Long id, Document patch, Long editorId) {
        checkSensitive(patch);
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
        return saved;
    }

    /** 普通用户编辑自己的帖子：校验归属，否则 403 */
    @Transactional
    public Document updateOwn(Long id, Document patch, Long ownerId) {
        checkSensitive(patch);
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
        return saved;
    }

    /** 版本历史：列出某文档的全部历史版本（倒序） */
    public List<DocumentVersion> listVersions(Long docId) {
        return versionRepo.findByDocIdOrderByCreatedAtDesc(docId);
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

    /** 普通用户删除自己的帖子：校验归属，否则 403 */
    @Transactional
    public void deleteOwn(Long id, Long ownerId) {
        Document d = get(id);
        if (!Objects.equals(d.getOwnerId(), ownerId)) {
            throw new ResponseStatusException(FORBIDDEN, "只能删除自己发布的帖子");
        }
        repo.deleteById(id);
        meili.delete(id);
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

    public void delete(Long id) {
        repo.deleteById(id);
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

    /** 截断标题用于积分明细说明，避免过长 */
    private String clip(String t) {
        if (t == null) return "";
        t = t.trim();
        return t.length() > 20 ? t.substring(0, 20) + "…" : t;
    }

    /** 敏感词（和谐词）检查：标题或正文命中则拒绝提交，返回 400 */
    private void checkSensitive(Document d) {
        StringBuilder sb = new StringBuilder();
        if (d.getTitle() != null) sb.append(d.getTitle());
        if (d.getContent() != null) sb.append(d.getContent());
        if (sensitiveWordFilter.contains(sb.toString())) {
            throw new IllegalArgumentException("内容包含敏感词：" + String.join("、", sensitiveWordFilter.find(sb.toString())));
        }
    }
}
