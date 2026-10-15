/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DocAnnotation;
import com.kb.kbplatform.domain.DocShare;
import com.kb.kbplatform.domain.DocSharePermission;
import com.kb.kbplatform.domain.SharedDocument;
import com.kb.kbplatform.domain.SharedDocPermission;
import com.kb.kbplatform.domain.SharedDocVersion;
import com.kb.kbplatform.domain.User;
import jakarta.annotation.PostConstruct;
import com.kb.kbplatform.repository.DocAnnotationRepository;
import com.kb.kbplatform.repository.DocSharePermissionRepository;
import com.kb.kbplatform.repository.DocShareRepository;
import com.kb.kbplatform.repository.SharedDocPermissionRepository;
import com.kb.kbplatform.repository.SharedDocumentRepository;
import com.kb.kbplatform.repository.SharedDocumentSummary;
import com.kb.kbplatform.repository.SharedDocVersionRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.security.HtmlSanitizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocShareService {

    private static final Logger log = LoggerFactory.getLogger(DocShareService.class);

    private final DocShareRepository shareRepo;
    private final DocSharePermissionRepository permRepo;
    private final SharedDocumentRepository docRepo;
    private final SharedDocPermissionRepository docPermRepo;
    private final UserRepository userRepo;
    private final SharedDocVersionRepository sharedDocVerRepo;
    private final DocAnnotationRepository annoRepo;
    private final TransactionTemplate tx;
    private final RelayNotifier relayNotifier;

    public DocShareService(DocShareRepository shareRepo, DocSharePermissionRepository permRepo,
                           SharedDocumentRepository docRepo, SharedDocPermissionRepository docPermRepo,
                           UserRepository userRepo, SharedDocVersionRepository sharedDocVerRepo,
                           DocAnnotationRepository annoRepo, TransactionTemplate tx, RelayNotifier relayNotifier) {
        this.relayNotifier = relayNotifier;
        this.shareRepo = shareRepo;
        this.permRepo = permRepo;
        this.docRepo = docRepo;
        this.docPermRepo = docPermRepo;
        this.userRepo = userRepo;
        this.sharedDocVerRepo = sharedDocVerRepo;
        this.annoRepo = annoRepo;
        this.tx = tx;
    }

    /** 启动幂等迁移：存量团队空间若未设容量上限（null=不限），统一补默认 2GB。
     *  已显式设置过上限（含 0=取消上限）的记录不受影响。 */
    @PostConstruct
    public void backfillSpaceCapacity() {
        // 原来这里直接自调用 protected + @Transactional 方法，双重失效：
        //   ① @PostConstruct 内同类自调用走不到 AOP 代理；
        //   ② Spring 的事务属性源默认只认 public 方法，protected 上的 @Transactional 被忽略。
        // 改为显式 TransactionTemplate，保证迁移真在事务里跑；失败只告警不阻断启动。
        try {
            tx.executeWithoutResult(status -> migrateTeamCapacity());
        } catch (Exception e) {
            log.warn("[capacity-migrate] 存量团队空间容量回填失败，不影响启动", e);
        }
    }

    private void migrateTeamCapacity() {
        List<DocShare> legacy = shareRepo.findByTypeAndCapacityIsNull("TEAM");
        if (legacy.isEmpty()) return;
        for (DocShare s : legacy) s.setCapacity(DEFAULT_SPACE_CAPACITY_BYTES);
        shareRepo.saveAll(legacy);
        log.info("[capacity-migrate] 已为 {} 个存量团队空间补默认容量 2GB", legacy.size());
    }

    // ---------- 权限帮助方法 ----------

    /** 取我在某空间的实际权限：OWNER / ADMIN / WRITE / READ / null */
    public String myPermission(Long shareId, Long userId, boolean sysAdmin) {
        if (sysAdmin) return "OWNER";
        DocShare share = shareRepo.findById(shareId).orElse(null);
        if (share == null) return null;
        if (share.getOwnerId().equals(userId)) return "OWNER";
        return permRepo.findByShareIdAndUserId(shareId, userId).map(DocSharePermission::getPermission).orElse(null);
    }

    private boolean canRead(String perm) { return perm != null; }
    private boolean canWrite(String perm) { return "WRITE".equals(perm) || "ADMIN".equals(perm) || "OWNER".equals(perm); }
    private boolean canAdmin(String perm) { return "ADMIN".equals(perm) || "OWNER".equals(perm); }

    /** 公开帮助方法：当前用户对某空间是否拥有管理权限（OWNER/ADMIN） */
    public boolean canAdminSpace(Long shareId, Long userId, boolean sysAdmin) {
        return canAdmin(myPermission(shareId, userId, sysAdmin));
    }
    /** 公开帮助方法：当前用户对某空间是否可读 */
    public boolean canReadSpace(Long shareId, Long userId, boolean sysAdmin) {
        return canRead(myPermission(shareId, userId, sysAdmin));
    }

    // ---------- 文档级权限（空间权限 ∪ 文档单独权限，文档级优先） ----------

    /** 某用户对某文档的有效权限：OWNER 始终 OWNER；否则若文档级有专门记录则用之，否则继承空间权限。 */
    public String docPermission(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        if (sysAdmin) return "OWNER";
        DocShare share = shareRepo.findById(shareId).orElse(null);
        if (share != null && share.getOwnerId().equals(userId)) return "OWNER";
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId).orElse(null);
        if (d != null && d.getOwnerId().equals(userId)) return "OWNER";
        // 文档级单独设置优先
        SharedDocPermission dp = docPermRepo.findByDocIdAndUserId(docId, userId).orElse(null);
        if (dp != null) return dp.getPermission();
        // 否则继承空间权限
        return myPermission(shareId, userId, sysAdmin);
    }

    private boolean canReadDoc(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        return canRead(docPermission(shareId, docId, userId, sysAdmin));
    }
    private boolean canWriteDoc(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        return canWrite(docPermission(shareId, docId, userId, sysAdmin));
    }
    private boolean canAdminDoc(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        // 文档管理员：空间管理员/所有者，或文档级 ADMIN，或文档所有者
        return canAdmin(docPermission(shareId, docId, userId, sysAdmin));
    }

    /** 列出某文档的权限成员（空间全部成员 + 各自在该文档的有效权限与是否单独设置） */
    public List<Map<String, Object>> listDocMembers(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        // 校验：可管理该文档 或 可读
        if (!canAdminDoc(shareId, docId, userId, sysAdmin) && !canReadDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该文档");
        List<DocSharePermission> spaceMembers = permRepo.findByShareIdOrderByCreatedAtAsc(shareId);
        List<SharedDocPermission> docOverrides = docPermRepo.findByDocId(docId);
        List<Map<String, Object>> result = new ArrayList<>();
        // 空间成员
        for (DocSharePermission sp : spaceMembers) {
            User u = userRepo.findById(sp.getUserId()).orElse(null);
            if (u == null) continue;
            SharedDocPermission ov = docOverrides.stream().filter(o -> o.getUserId().equals(sp.getUserId())).findFirst().orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
            m.put("avatar", u.getAvatar());
            m.put("spacePermission", sp.getPermission());                 // 空间级
            m.put("permission", ov != null ? ov.getPermission() : sp.getPermission()); // 有效（文档级优先）
            m.put("override", ov != null);                               // 是否单独设置
            result.add(m);
        }
        // 单独设置但非空间成员的用户（理论上不应出现，但防御性列出）
        for (SharedDocPermission ov : docOverrides) {
            if (result.stream().anyMatch(r -> r.get("userId").equals(ov.getUserId()))) continue;
            User u = userRepo.findById(ov.getUserId()).orElse(null);
            if (u == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
            m.put("avatar", u.getAvatar());
            m.put("spacePermission", null);
            m.put("permission", ov.getPermission());
            m.put("override", true);
            result.add(m);
        }
        return result;
    }

    /** 设置某文档对某个空间成员的权限（READ/WRITE/ADMIN）；传与空间权限相同的值即视为清除单独设置（回退继承） */
    @Transactional(rollbackFor = Exception.class)
    public SharedDocPermission setDocMember(Long shareId, Long docId, Long targetUserId, String permission,
                                           Long userId, boolean sysAdmin) {
        if (!canAdminDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要文档管理员权限");
        if (shareRepo.findById(shareId).orElse(null) == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "空间不存在");
        if (docRepo.findByIdAndShareId(docId, shareId).orElse(null) == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        DocSharePermission spacePerm = permRepo.findByShareIdAndUserId(shareId, targetUserId).orElse(null);
        String perm = normalizeDocPerm(permission);
        SharedDocPermission existing = docPermRepo.findByDocIdAndUserId(docId, targetUserId).orElse(null);
        // 若等于空间权限则清除单独设置，回退继承（保持简洁）
        if (spacePerm != null && spacePerm.getPermission().equals(perm)) {
            if (existing != null) docPermRepo.delete(existing);
            relayNotifier.documentPermissionChanged(shareId, docId, targetUserId);
            return null;
        }
        if (existing == null) {
            existing = new SharedDocPermission();
            existing.setShareId(shareId);
            existing.setDocId(docId);
            existing.setUserId(targetUserId);
        }
        existing.setPermission(perm);
        SharedDocPermission saved = docPermRepo.save(existing);
        relayNotifier.documentPermissionChanged(shareId, docId, targetUserId);
        // 注入展示字段
        userRepo.findById(targetUserId).ifPresent(u -> {
            saved.setUsername(u.getUsername());
            saved.setDisplayName(u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
            saved.setAvatar(u.getAvatar());
        });
        return saved;
    }

    /** 清除某文档对某个成员的单独权限（回退继承空间权限） */
    @Transactional(rollbackFor = Exception.class)
    public void clearDocMember(Long shareId, Long docId, Long targetUserId, Long userId, boolean sysAdmin) {
        if (!canAdminDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要文档管理员权限");
        docPermRepo.deleteByDocIdAndUserId(docId, targetUserId);
        relayNotifier.documentPermissionChanged(shareId, docId, targetUserId);
    }

    private String normalizeDocPerm(String p) {
        if (p == null) return "READ";
        String t = p.trim().toUpperCase();
        return switch (t) {
            case "WRITE" -> "WRITE";
            case "ADMIN" -> "ADMIN";
            default -> "READ";
        };
    }

    private User resolveUser(Long userId) {
        return userRepo.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private void fillOwnerName(DocShare share) {
        userRepo.findById(share.getOwnerId()).ifPresent(u -> share.setOwnerName(u.getDisplayName() != null ? u.getDisplayName() : u.getUsername()));
    }

    private void fillMemberNames(List<DocSharePermission> list) {
        for (DocSharePermission p : list) {
            userRepo.findById(p.getUserId()).ifPresent(u -> {
                p.setUsername(u.getUsername());
                p.setDisplayName(u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
                p.setAvatar(u.getAvatar());
            });
        }
    }

    private void fillDocOwnerName(SharedDocument doc) {
        userRepo.findById(doc.getOwnerId()).ifPresent(u -> {
            doc.setOwnerName(u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
            doc.setOwnerAvatar(u.getAvatar());
        });
    }

    // ---------- 个人存储配额（默认每人 200MB，管理员可调） ----------
    public static final long PER_USER_QUOTA_BYTES = 200L * 1024 * 1024;

    /** 团队空间创建时的默认容量上限（2GB），需要更多时由管理员在后台调大 */
    public static final long DEFAULT_SPACE_CAPACITY_BYTES = 2L * 1024 * 1024 * 1024;

    /** 每个有创建权限的用户最多可拥有的团队空间数量；超出须走「创建申请」审批流程 */
    public static final long MAX_TEAM_SPACE_PER_USER = 2L;

    /** 某用户作为所有者拥有的团队空间数量（含归档），用于创建上限判定 */
    public long countUserTeamSpaces(Long ownerId) {
        return shareRepo.countByOwnerIdAndType(ownerId, "TEAM");
    }

    /** 某用户作为文档所有者已用字节数（content + yjs_state 之和） */
    public long storageUsage(Long userId) {
        return docRepo.sumSizeByOwnerId(userId);
    }

    /** 用户有效总配额：管理员分配过则用之，否则系统默认 200MB（见 PER_USER_QUOTA_BYTES） */
    public long effectiveUserQuota(Long userId) {
        User u = userRepo.findById(userId).orElse(null);
        if (u != null && u.getStorageQuota() != null) return u.getStorageQuota();
        return PER_USER_QUOTA_BYTES;
    }

    // ---------- 共享空间 CRUD ----------

    @Transactional(rollbackFor = Exception.class)
    public DocShare create(String name, String description, Long ownerId, String type, Long capacity, String color,
                           List<Long> memberIds, String memberPermission) {
        if (name == null || name.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "空间名称不能为空");
        DocShare s = new DocShare();
        s.setName(name.trim());
        s.setDescription(description == null ? "" : description.trim());
        s.setOwnerId(ownerId);
        s.setStatus("ACTIVE");
        s.setType(type);
        // 容量一律默认 2GB，变更只走扩容审批（DocShareCapacityRequestService）：
        // 此前客户端可直接传 capacity 落库（0=无上限），配额审批流程被完全绕过（2026-10-13 审查 C1）
        s.setCapacity(DEFAULT_SPACE_CAPACITY_BYTES);
        s.setColor(color == null || color.isBlank() ? null : color.trim().toLowerCase());
        s.setUpdatedAt(LocalDateTime.now());
        DocShare saved = shareRepo.save(s);
        fillOwnerName(saved);
        saved.setMyPermission("OWNER");
        // 创建时直接带入成员（统一权限，创建后可在成员管理中单独调整）
        if (memberIds != null) {
            String perm = normalizePerm(memberPermission);
            for (Long mid : new LinkedHashSet<>(memberIds)) {
                if (mid == null || mid.equals(ownerId)) continue;
                User target = userRepo.findById(mid).orElse(null);
                if (target == null || !target.isEnabled()) continue;
                if (permRepo.findByShareIdAndUserId(saved.getId(), mid).isPresent()) continue;
                DocSharePermission p = new DocSharePermission();
                p.setShareId(saved.getId());
                p.setUserId(mid);
                p.setPermission(perm);
                permRepo.save(p);
            }
        }
        fillMemberSnapshot(saved);
        return saved;
    }

    /** 填充成员快照（前 12 个头像 + 总数），列表页头像组堆叠/hover 展开展示用 */
    private void fillMemberSnapshot(DocShare s) {
        List<DocSharePermission> perms = permRepo.findByShareIdOrderByCreatedAtAsc(s.getId());
        List<Map<String, Object>> snap = new ArrayList<>();
        for (DocSharePermission p : perms) {
            if (snap.size() >= 12) break;
            User u = userRepo.findById(p.getUserId()).orElse(null);
            if (u == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getId());
            m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
            m.put("avatar", u.getAvatar());
            snap.add(m);
        }
        s.setMembers(snap);
        s.setMemberCount((long) perms.size());
    }

    /** 选人搜索（创建/邀请成员用）：用户名或昵称模糊匹配，仅启用用户，排除自己，最多 20 条。
     *  空关键词时只取前 30 个启用用户（DB LIMIT），不全量加载用户表。 */
    public List<Map<String, Object>> searchInviteUsers(String keyword, Long excludeUserId) {
        String kw = keyword == null ? "" : keyword.trim();
        List<User> users = kw.isEmpty()
                ? userRepo.findByEnabledTrue(PageRequest.of(0, 30)).getContent()
                : userRepo.findByUsernameContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(kw, kw);
        List<Map<String, Object>> result = new ArrayList<>();
        for (User u : users) {
            if (!u.isEnabled()) continue;
            if (excludeUserId != null && excludeUserId.equals(u.getId())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
            m.put("avatar", u.getAvatar());
            result.add(m);
            if (result.size() >= 20) break;
        }
        return result;
    }

    public List<DocShare> listMine(Long userId, boolean sysAdmin) {
        List<DocShare> list;
        if (sysAdmin) {
            list = shareRepo.findAll();
        } else {
            ensurePersonalSpace(userId);   // 普通用户保证有专属个人空间
            list = shareRepo.findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(userId);
        }
        fillListInBatch(list, userId, sysAdmin);
        return list;
    }

    /** 批量注入列表字段（所有者名 / 我的权限 / 成员快照 / 已用容量）。
     *  旧实现在循环里逐空间查库：判权限 1 次 findById + 容量 1 次 sumSize + 成员最多 12 次 findById
     *  ≈ N×14 次查询，空间一多就拖垮列表页。现改为 4 条集合查询降到常数级。 */
    private void fillListInBatch(List<DocShare> list, Long userId, boolean sysAdmin) {
        if (list == null || list.isEmpty()) return;
        List<Long> shareIds = list.stream().map(DocShare::getId).collect(Collectors.toList());

        // 1) 所有空间的权限行（一次取回，按空间分组并保持原有创建时间升序）
        Map<Long, List<DocSharePermission>> permsByShare = permRepo.findByShareIdIn(shareIds).stream()
                .collect(Collectors.groupingBy(DocSharePermission::getShareId));
        permsByShare.values().forEach(ps -> ps.sort(
                java.util.Comparator.comparing(DocSharePermission::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))));

        // 2) 相关用户：所有者 + 成员（一次取回）
        Set<Long> userIds = new HashSet<>();
        for (DocShare s : list) if (s.getOwnerId() != null) userIds.add(s.getOwnerId());
        for (List<DocSharePermission> ps : permsByShare.values())
            for (DocSharePermission p : ps) if (p.getUserId() != null) userIds.add(p.getUserId());
        Map<Long, User> users = userIds.isEmpty() ? new HashMap<>()
                : userRepo.findAllById(userIds).stream().collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));

        // 3) 已用容量（一次聚合）
        Map<Long, Long> usedByShare = new HashMap<>();
        for (Object[] row : docRepo.sumSizeByShareIdIn(shareIds)) {
            if (row[0] == null) continue;
            usedByShare.put(((Number) row[0]).longValue(), row[1] == null ? 0L : ((Number) row[1]).longValue());
        }

        for (DocShare s : list) {
            User owner = users.get(s.getOwnerId());
            if (owner != null)
                s.setOwnerName(owner.getDisplayName() != null ? owner.getDisplayName() : owner.getUsername());
            List<DocSharePermission> ps = permsByShare.getOrDefault(s.getId(), List.of());
            s.setMyPermission(sysAdmin ? "OWNER" : resolvePermCached(s, ps, userId));
            fillMemberSnapshotCached(s, ps, users);
            s.setUsed(usedByShare.getOrDefault(s.getId(), 0L));
        }
    }

    /** 由已加载的权限行判定当前用户权限（OWNER/ADMIN/WRITE/READ/null），不再回查数据库 */
    private String resolvePermCached(DocShare s, List<DocSharePermission> ps, Long userId) {
        if (userId != null && s.getOwnerId() != null && s.getOwnerId().equals(userId)) return "OWNER";
        for (DocSharePermission p : ps) {
            if (p.getUserId() != null && p.getUserId().equals(userId)) return p.getPermission();
        }
        return null;
    }

    /** 成员快照：从已加载的用户缓存填充（前 12 个头像 + 总数），不再逐个 findById */
    private void fillMemberSnapshotCached(DocShare s, List<DocSharePermission> ps, Map<Long, User> users) {
        List<Map<String, Object>> snap = new ArrayList<>();
        for (DocSharePermission p : ps) {
            if (snap.size() >= 12) break;
            User u = p.getUserId() == null ? null : users.get(p.getUserId());
            if (u == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.getId());
            m.put("displayName", u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
            m.put("avatar", u.getAvatar());
            snap.add(m);
        }
        s.setMembers(snap);
        s.setMemberCount((long) ps.size());
    }

    /** 每个普通用户保证存在唯一个人空间（type=PERSONAL）；已存在则跳过。
     *  synchronized：查询-再插入是 TOCTOU，并发首请求会建出两份个人空间（单实例部署，进程内锁足够）。 */
    private synchronized void ensurePersonalSpace(Long userId) {
        List<DocShare> existing = shareRepo.findByOwnerIdAndTypeOrderByUpdatedAtDesc(userId, "PERSONAL");
        if (!existing.isEmpty()) return;
        DocShare s = new DocShare();
        s.setName("我的空间");
        s.setDescription("个人专属空间，仅你自己可见");
        s.setOwnerId(userId);
        s.setStatus("ACTIVE");
        s.setType("PERSONAL");
        s.setUpdatedAt(LocalDateTime.now());
        DocShare saved = shareRepo.save(s);
        fillOwnerName(saved);
    }

    public DocShare get(Long shareId, Long userId, boolean sysAdmin) {
        DocShare s = shareRepo.findById(shareId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "共享空间不存在"));
        String perm = myPermission(shareId, userId, sysAdmin);
        if (!canRead(perm)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该共享空间");
        fillOwnerName(s);
        s.setMyPermission(perm);
        return s;
    }

    @Transactional(rollbackFor = Exception.class)
    public DocShare update(Long shareId, String name, String description, String status, Long capacity, Long userId, boolean sysAdmin) {
        DocShare s = get(shareId, userId, sysAdmin);
        if (!canAdmin(s.getMyPermission())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间管理员权限");
        if (name != null && !name.isBlank()) s.setName(name.trim());
        if (description != null) s.setDescription(description.trim());
        if (status != null && ("ACTIVE".equals(status) || "ARCHIVED".equals(status))) s.setStatus(status);
        // 容量变更仅限系统管理员（普通空间管理员走扩容审批流程）；此前空间管理员可直接改容量（0=无上限）绕过配额（2026-10-13 审查 C1）
        if (capacity != null && sysAdmin) s.setCapacity(capacity == 0L ? null : capacity);  // 0 表示取消空间上限
        s.setUpdatedAt(LocalDateTime.now());
        return shareRepo.save(s);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long shareId, Long userId, boolean sysAdmin) {
        DocShare s = shareRepo.findById(shareId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "共享空间不存在"));
        if (!sysAdmin && !s.getOwnerId().equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有所有者或系统管理员可删除空间");
        List<SharedDocument> docs = docRepo.findByShareIdOrderByUpdatedAtDesc(shareId);
        // 版本历史按 sharedDocId 冗余关联，文档行删掉后仍会留下查不到的孤儿版本，需一并清理
        if (!docs.isEmpty()) {
            sharedDocVerRepo.deleteAllBySharedDocIdIn(docs.stream().map(SharedDocument::getId).collect(Collectors.toList()));
        }
        docRepo.deleteAll(docs);
        permRepo.deleteAll(permRepo.findByShareIdOrderByCreatedAtAsc(shareId));
        docPermRepo.deleteAllByShareId(shareId); // 清理空间下所有文档级权限
        shareRepo.delete(s);
    }

    // ---------- 成员权限管理 ----------

    public List<DocSharePermission> listMembers(Long shareId, Long userId, boolean sysAdmin) {
        get(shareId, userId, sysAdmin); // 校验可读
        List<DocSharePermission> list = permRepo.findByShareIdOrderByCreatedAtAsc(shareId);
        fillMemberNames(list);
        return list;
    }

    @Transactional(rollbackFor = Exception.class)
    public DocSharePermission addMember(Long shareId, Long targetUserId, String permission, Long userId, boolean sysAdmin) {
        DocShare s = get(shareId, userId, sysAdmin);
        if (!canAdmin(s.getMyPermission())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间管理员权限");
        if (s.getOwnerId().equals(targetUserId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所有者无需单独授权");
        User target = resolveUser(targetUserId);
        if (permRepo.findByShareIdAndUserId(shareId, targetUserId).isPresent())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该成员已存在");
        String perm = normalizePerm(permission);
        DocSharePermission p = new DocSharePermission();
        p.setShareId(shareId);
        p.setUserId(targetUserId);
        p.setPermission(perm);
        DocSharePermission saved = permRepo.save(p);
        saved.setUsername(target.getUsername());
        saved.setDisplayName(target.getDisplayName() != null ? target.getDisplayName() : target.getUsername());
        saved.setAvatar(target.getAvatar());
        s.setUpdatedAt(LocalDateTime.now());
        shareRepo.save(s);
        relayNotifier.permissionChanged(shareId, targetUserId, saved.getPermission());   // F04 即时通知
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public DocSharePermission updateMember(Long shareId, Long targetUserId, String permission, Long userId, boolean sysAdmin) {
        DocShare s = get(shareId, userId, sysAdmin);
        if (!canAdmin(s.getMyPermission())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间管理员权限");
        if (s.getOwnerId().equals(targetUserId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能修改所有者权限");
        DocSharePermission p = permRepo.findByShareIdAndUserId(shareId, targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "成员不存在"));
        p.setPermission(normalizePerm(permission));
        DocSharePermission saved = permRepo.save(p);
        fillMemberNames(List.of(saved));
        s.setUpdatedAt(LocalDateTime.now());
        shareRepo.save(s);
        relayNotifier.permissionChanged(shareId, targetUserId, saved.getPermission());   // F04 即时通知
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long shareId, Long targetUserId, Long userId, boolean sysAdmin) {
        DocShare s = get(shareId, userId, sysAdmin);
        if (!canAdmin(s.getMyPermission())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间管理员权限");
        if (s.getOwnerId().equals(targetUserId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能移除所有者");
        permRepo.deleteByShareIdAndUserId(shareId, targetUserId);
        s.setUpdatedAt(LocalDateTime.now());
        shareRepo.save(s);
        relayNotifier.permissionChanged(shareId, targetUserId, null);   // F04：移除成员立即踢下线
    }

    private String normalizePerm(String p) {
        if (p == null) return "READ";
        String t = p.trim().toUpperCase();
        return switch (t) {
            case "WRITE" -> "WRITE";
            case "ADMIN" -> "ADMIN";
            default -> "READ";
        };
    }

    // ---------- 共享文档 CRUD ----------

    public List<SharedDocument> listDocs(Long shareId, Long userId, boolean sysAdmin) {
        get(shareId, userId, sysAdmin); // 校验可读（空间级）
        List<SharedDocument> list = docRepo.findByShareIdOrderByUpdatedAtDesc(shareId);
        // 待整理箱的文档先不进空间列表，归档（补标题/选空间）后才出现
        list.removeIf(SharedDocument::isInbox);
        for (SharedDocument d : list) {
            fillDocOwnerName(d);
            String html = d.getContent();
            d.setContentType(classifyContentType(html));
            // 列表接口不返回大字段（content / yjs_state），仅注入展示用的字节数与头像
            d.setSize((html != null ? html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0)
                    + (d.getYjsState() != null ? d.getYjsState().length : 0));
            d.setContent(null);
            // 注入当前用户在该文档的有效权限（空间权限 ∪ 文档级，文档级优先）
            d.setMyPerm(docPermission(shareId, d.getId(), userId, sysAdmin));
        }
        return list;
    }

    /** 用户可读的跨空间文档列表，复用空间级与文档级权限判断，不返回待整理项。 */
    public List<SharedDocument> listAllDocs(Long userId, boolean sysAdmin) {
        List<DocShare> spaces = listMine(userId, sysAdmin);
        if (spaces.isEmpty()) return List.of();
        Map<Long, DocShare> spaceById = spaces.stream().collect(Collectors.toMap(DocShare::getId, s -> s));
        List<Long> shareIds = spaces.stream().map(DocShare::getId).toList();
        List<SharedDocumentSummary> summaries = docRepo.findLibrarySummariesByShareIdIn(shareIds);
        if (summaries.isEmpty()) return List.of();

        List<Long> docIds = summaries.stream().map(SharedDocumentSummary::getId).toList();
        Map<Long, SharedDocPermission> overrides = docPermRepo.findByDocIdInAndUserId(docIds, userId).stream()
                .collect(Collectors.toMap(SharedDocPermission::getDocId, p -> p, (a, b) -> a));
        List<SharedDocument> out = new ArrayList<>();
        for (SharedDocumentSummary row : summaries) {
            DocShare space = spaceById.get(row.getShareId());
            if (space == null) continue;
            String permission;
            if (sysAdmin || Objects.equals(row.getOwnerId(), userId)) permission = "OWNER";
            else {
                SharedDocPermission override = overrides.get(row.getId());
                permission = override == null ? space.getMyPermission() : override.getPermission();
            }
            if (!canRead(permission)) continue;

            SharedDocument doc = new SharedDocument();
            doc.setId(row.getId());
            doc.setShareId(row.getShareId());
            doc.setTitle(row.getTitle());
            doc.setTags(row.getTags());
            doc.setOwnerId(row.getOwnerId());
            doc.setCreatedAt(row.getCreatedAt());
            doc.setUpdatedAt(row.getUpdatedAt());
            doc.setContentType(classifyContentType(row.getContent()));
            doc.setSpaceName(space.getName());
            doc.setSpaceType(space.getType());
            doc.setMyPerm(permission);
            out.add(doc);
        }
        return out;
    }

    /** 仅可写入者可更新标签；标签是元数据，不生成正文版本快照。 */
    @Transactional(rollbackFor = Exception.class)
    public SharedDocument updateDocTags(Long shareId, Long docId, List<String> tags, Long userId, boolean sysAdmin) {
        get(shareId, userId, sysAdmin);
        if (!canWriteDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "对该文档无写入权限");
        SharedDocument doc = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        doc.setTags(normalizeDocTags(tags));
        doc.setUpdatedAt(LocalDateTime.now());
        SharedDocument saved = docRepo.save(doc);
        saved.setMyPerm(docPermission(shareId, docId, userId, sysAdmin));
        return saved;
    }

    static String normalizeDocTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "";
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String value : tags) {
            if (value == null) continue;
            String tag = value.trim();
            if (tag.isEmpty()) continue;
            if (tag.length() > 24 || tag.contains(","))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每个标签最多 24 个字符，且不能包含逗号");
            unique.putIfAbsent(tag.toLowerCase(Locale.ROOT), tag);
            if (unique.size() > 12)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每篇文档最多添加 12 个标签");
        }
        return String.join(",", unique.values());
    }

    // ---------- 文档批注 / 摘录（2026-10-15） ----------

    /** 某文档下「我的」批注；批注是私人笔记，同空间其他人看不到 */
    public List<DocAnnotation> listAnnotations(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        get(shareId, userId, sysAdmin);
        if (!canReadDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该文档");
        return annoRepo.findByShareIdAndDocIdAndUserIdOrderByCreatedAtDesc(shareId, docId, userId);
    }

    /** 新增批注：能读该文档即可批注（不改正文，不校验写权限） */
    @Transactional(rollbackFor = Exception.class)
    public DocAnnotation createAnnotation(Long shareId, Long docId, String quote, String note,
                                          String prefix, String suffix, Long userId, boolean sysAdmin) {
        get(shareId, userId, sysAdmin);
        if (!canReadDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该文档");
        if (docRepo.findByIdAndShareId(docId, shareId).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        String q = quote == null ? "" : quote.trim();
        if (q.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "摘录内容不能为空");
        if (q.length() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "摘录最多 500 字");
        String n = note == null ? "" : note.trim();
        if (n.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "笔记最多 2000 字");
        DocAnnotation a = new DocAnnotation();
        a.setShareId(shareId);
        a.setDocId(docId);
        a.setUserId(userId);
        a.setQuote(q);
        a.setNote(n);
        a.setPrefix(truncate(prefix, 80));
        a.setSuffix(truncate(suffix, 80));
        return annoRepo.save(a);
    }

    /** 修改笔记内容：只能改自己的批注 */
    @Transactional(rollbackFor = Exception.class)
    public DocAnnotation updateAnnotation(Long shareId, Long docId, Long annoId, String note, Long userId) {
        DocAnnotation a = requireOwnAnnotation(shareId, docId, annoId, userId);
        String n = note == null ? "" : note.trim();
        if (n.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "笔记最多 2000 字");
        a.setNote(n);
        a.setUpdatedAt(LocalDateTime.now());
        return annoRepo.save(a);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteAnnotation(Long shareId, Long docId, Long annoId, Long userId) {
        annoRepo.delete(requireOwnAnnotation(shareId, docId, annoId, userId));
    }

    private DocAnnotation requireOwnAnnotation(Long shareId, Long docId, Long annoId, Long userId) {
        DocAnnotation a = annoRepo.findByIdAndUserId(annoId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "批注不存在或不属于你"));
        if (!a.getShareId().equals(shareId) || !a.getDocId().equals(docId))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批注不属于该文档");
        return a;
    }

    /** 「我的摘录」聚合：跨文档取我的批注，注入文档标题与空间名；文档已删除的条目自动跳过 */
    public List<DocAnnotation> myAnnotations(Long userId, int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        List<DocAnnotation> list = new ArrayList<>(annoRepo.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, size)));
        if (list.isEmpty()) return list;
        Map<Long, SharedDocument> docs = docRepo.findAllById(list.stream().map(DocAnnotation::getDocId).distinct().toList())
                .stream().collect(Collectors.toMap(SharedDocument::getId, d -> d, (a, b) -> a));
        Set<Long> shareIds = docs.values().stream().map(SharedDocument::getShareId).collect(Collectors.toSet());
        Map<Long, DocShare> spaces = shareIds.isEmpty() ? Map.of()
                : shareRepo.findAllById(shareIds).stream().collect(Collectors.toMap(DocShare::getId, s -> s, (a, b) -> a));
        list.removeIf(a -> {
            SharedDocument d = docs.get(a.getDocId());
            if (d == null || d.isInbox()) return true;   // 文档已删 / 还在待整理箱
            DocShare s = spaces.get(d.getShareId());
            if (s == null) return true;
            a.setDocTitle(d.getTitle());
            a.setSpaceName(s.getName());
            return false;
        });
        return list;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        String v = value.trim();
        return v.length() <= max ? v : v.substring(v.length() - max);
    }

    static String classifyContentType(String html) {
        String body = html == null ? "" : html.toLowerCase(Locale.ROOT);
        if (body.contains("<video") || containsAny(body, ".mp4", ".webm", ".mov")) return "VIDEO";
        if (body.contains("<audio") || containsAny(body, ".mp3", ".wav", ".ogg", ".m4a")) return "AUDIO";
        if (body.contains("<img") || containsAny(body, ".png", ".jpg", ".jpeg", ".gif", ".webp")) return "IMAGE";
        if (containsAny(body, ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx")) return "FILE";
        return "TEXT";
    }

    private static boolean containsAny(String body, String... needles) {
        for (String needle : needles) if (body.contains(needle)) return true;
        return false;
    }

    public SharedDocument getDoc(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        // 用文档级裁决：空间可读 且 文档可读
        get(shareId, userId, sysAdmin);
        if (!canReadDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问该文档");
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        fillDocOwnerName(d);
        d.setMyPerm(docPermission(shareId, docId, userId, sysAdmin));
        return d;
    }

    @Transactional(rollbackFor = Exception.class)
    public SharedDocument createDoc(Long shareId, String title, String content, Long userId, boolean sysAdmin) {
        return createDoc(shareId, title, content, userId, sysAdmin, false);
    }

    /** inbox=true：收进待整理箱（快速收纳，先落个人空间并打标记，不出现在空间文档列表） */
    @Transactional(rollbackFor = Exception.class)
    public SharedDocument createDoc(Long shareId, String title, String content, Long userId, boolean sysAdmin, boolean inbox) {
        DocShare s = get(shareId, userId, sysAdmin);
        if (!canWrite(s.getMyPermission())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要写入权限");
        long incoming = (content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                + (title == null ? 0 : title.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        // ① 用户总配额（管理员分配过或系统默认 200MB）
        long userQuota = effectiveUserQuota(userId);
        long userUsed = storageUsage(userId);
        if (userUsed + incoming > userQuota)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "个人存储空间不足（已用 " + formatSize(userUsed) + " / 配额 " + formatSize(userQuota) + "），如需扩容请联系管理员");
        // ② 空间容量上限（默认 2GB，管理员可调；0/null 表示不限）
        if (s.getCapacity() != null) {
            long spaceUsed = docRepo.sumSizeByShareId(shareId);
            if (spaceUsed + incoming > s.getCapacity())
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "空间「" + s.getName() + "」容量不足（已用 " + formatSize(spaceUsed) + " / 上限 " + formatSize(s.getCapacity()) + "），如需扩容请联系管理员");
        }
        SharedDocument d = new SharedDocument();
        d.setShareId(shareId);
        d.setTitle(title == null || title.isBlank() ? "未命名文档" : title.trim());
        d.setContent(content == null ? "" : HtmlSanitizer.sanitize(content));
        d.setOwnerId(userId);
        if (inbox) d.setInbox(true);
        SharedDocument saved = docRepo.save(d);
        fillDocOwnerName(saved);
        s.setUpdatedAt(LocalDateTime.now());
        shareRepo.save(s);
        return saved;
    }

    // ---------- 待整理箱（2026-10-15） ----------

    /** 我的待整理项：跨空间、按更新时间倒序，不含大字段 */
    public List<SharedDocument> listInbox(Long userId) {
        List<SharedDocument> list = docRepo.findByOwnerIdAndInboxTrueOrderByUpdatedAtDesc(userId);
        for (SharedDocument d : list) {
            fillDocOwnerName(d);
            d.setSize((d.getContent() != null ? d.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0)
                    + (d.getYjsState() != null ? d.getYjsState().length : 0));
            d.setContent(null);
            d.setMyPerm("ADMIN");   // 自己的待整理项：可整理、可删
        }
        return list;
    }

    /** 未整理数量（角标） */
    public long inboxCount(Long userId) {
        return docRepo.countByOwnerIdAndInboxTrue(userId);
    }

    /** 整理一条待整理项：补标题（可选）+ 挪到目标空间 + 摘掉 inbox 标记。
     *  仅文档所有者（或 sysAdmin）可整理；目标空间要求写入权限（moveDoc 内校验）。 */
    @Transactional(rollbackFor = Exception.class)
    public SharedDocument archiveInbox(Long docId, Long targetShareId, String title, Long userId, boolean sysAdmin) {
        SharedDocument d = docRepo.findById(docId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        if (!d.isInbox()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该文档不在待整理箱中");
        if (!d.getOwnerId().equals(userId) && !sysAdmin)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能整理自己的待整理项");
        if (title != null && !title.isBlank()) d.setTitle(title.trim());
        docRepo.save(d);
        // 复用 moveDoc：内部校验目标空间写入权限，并清理（本就不存在的）文档级权限
        SharedDocument saved = moveDoc(d.getShareId(), docId, targetShareId, userId, sysAdmin);
        saved.setInbox(false);
        saved.setUpdatedAt(LocalDateTime.now());
        SharedDocument out = docRepo.save(saved);
        fillDocOwnerName(out);
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public SharedDocument updateDoc(Long shareId, Long docId, String title, String content, Long userId, boolean sysAdmin) {
        DocShare s = get(shareId, userId, sysAdmin);
        if (!canWriteDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "对该文档无写入权限");
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        // 配额检查（与 createDoc 同规则，按本次增量校验）：此前更新完全绕过配额，可先建小文档再更新成超大体量（2026-10-13 审查 C2）。
        // 存储用量归属文档所有者，故配额按 ownerId 计而非当前编辑者。
        long oldSize = (d.getTitle() == null ? 0 : d.getTitle().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                + (d.getContent() == null ? 0 : d.getContent().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        String nextTitle = title == null ? d.getTitle() : (title.isBlank() ? "未命名文档" : title.trim());
        String nextContent = content == null ? d.getContent() : HtmlSanitizer.sanitize(content);
        long delta = (nextTitle.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                + nextContent.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) - oldSize;
        if (delta > 0) {
            Long ownerId = d.getOwnerId();
            long userQuota = effectiveUserQuota(ownerId);
            long userUsed = storageUsage(ownerId);
            if (userUsed + delta > userQuota)
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "个人存储空间不足（已用 " + formatSize(userUsed) + " / 配额 " + formatSize(userQuota) + "），如需扩容请联系管理员");
            if (s.getCapacity() != null) {
                long spaceUsed = docRepo.sumSizeByShareId(shareId);
                if (spaceUsed + delta > s.getCapacity())
                    throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                            "空间「" + s.getName() + "」容量不足（已用 " + formatSize(spaceUsed) + " / 上限 " + formatSize(s.getCapacity()) + "），如需扩容请联系管理员");
            }
        }
        // 改前先留一版快照（版本历史）
        SharedDocVersion snap = new SharedDocVersion();
        snap.setSharedDocId(docId);
        snap.setTitle(d.getTitle());
        snap.setContent(d.getContent());
        snap.setYjsState(d.getYjsState());
        snap.setEditorId(userId);
        snap.setCreatedAt(LocalDateTime.now());
        sharedDocVerRepo.save(snap);
        if (title != null) d.setTitle(nextTitle);
        if (content != null) d.setContent(nextContent);
        // 注意：更新不得变更所有者。此前无条件 setOwnerId(userId) 会让仅有编辑权限者夺取所有权，
        // 进而在 deleteDoc/moveDoc（按 owner 判定）中删除或搬走他人文档。owner 仅在创建时设定。
        d.setUpdatedAt(LocalDateTime.now());
        SharedDocument saved = docRepo.save(d);
        fillDocOwnerName(saved);
        s.setUpdatedAt(LocalDateTime.now());
        shareRepo.save(s);
        return saved;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteDoc(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        DocShare s = get(shareId, userId, sysAdmin);
        // 空间管理员可删任意文档；文档所有者（创建者）可删自己的文档；文档级 ADMIN 也可管理该文档
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        boolean isOwnerOfDoc = d.getOwnerId().equals(userId);
        if (!canAdminDoc(shareId, docId, userId, sysAdmin) && !isOwnerOfDoc)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间/文档管理员权限或文档所有者身份");
        docRepo.delete(d);
        docPermRepo.deleteAllByDocId(docId); // 清理文档级权限
        sharedDocVerRepo.deleteAllBySharedDocId(docId); // 清理版本历史，避免孤儿版本行
        annoRepo.deleteByDocId(docId); // 清理批注/摘录，避免孤儿行
        s.setUpdatedAt(LocalDateTime.now());
        shareRepo.save(s);
    }

    @Transactional(rollbackFor = Exception.class)
    public SharedDocument moveDoc(Long shareId, Long docId, Long targetShareId, Long userId, boolean sysAdmin) {
        DocShare src = get(shareId, userId, sysAdmin);
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        boolean isOwnerOfDoc = d.getOwnerId().equals(userId);
        if (!canAdminDoc(shareId, docId, userId, sysAdmin) && !isOwnerOfDoc)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间/文档管理员权限或文档所有者身份");
        // 目标空间必须存在且本人可写（配额随原所有者，移动不转移归属）
        DocShare target = shareRepo.findById(targetShareId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "目标空间不存在"));
        String targetPerm = myPermission(targetShareId, userId, sysAdmin);
        if (!canWrite(targetPerm)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "目标空间无写入权限");
        d.setShareId(targetShareId);
        d.setUpdatedAt(LocalDateTime.now());
        SharedDocument saved = docRepo.save(d);
        fillDocOwnerName(saved);
        // 移动后文档级权限失效（归属空间变化），清理之
        docPermRepo.deleteAllByDocId(docId);
        src.setUpdatedAt(LocalDateTime.now());
        shareRepo.save(src);
        target.setUpdatedAt(LocalDateTime.now());
        shareRepo.save(target);
        return saved;
    }

    // ---------- 共享文档版本历史 ----------

    /** 手动保存当前文档状态为一份版本快照（标题 / 正文 / 协同态） */
    @Transactional(rollbackFor = Exception.class)
    public void snapshotSharedDoc(Long shareId, Long docId, Long editorId, boolean sysAdmin) {
        getDoc(shareId, docId, editorId, sysAdmin); // 校验可读
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        SharedDocVersion v = new SharedDocVersion();
        v.setSharedDocId(docId);
        v.setTitle(d.getTitle());
        v.setContent(d.getContent());
        v.setYjsState(d.getYjsState());
        v.setEditorId(editorId);
        v.setCreatedAt(LocalDateTime.now());
        sharedDocVerRepo.save(v);
    }

    /** 列出某共享文档的全部历史版本（倒序） */
    public List<SharedDocVersion> listSharedDocVersions(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        getDoc(shareId, docId, userId, sysAdmin); // 校验可读
        return sharedDocVerRepo.findBySharedDocIdOrderByCreatedAtDesc(docId);
    }

    /** 恢复到指定版本：写回 title/content/yjs_state；协同端下次加载（重连）即生效。
     *  **不再自动生成快照**：早期实现在回滚前无条件调用 snapshotSharedDoc，
     *  导致「每回滚一次就多出一条版本记录」，版本列表被回滚动作本身污染（用户反馈）。
     *  如需保留回滚前的内容，请先显式调用 {@link #snapshotSharedDoc}（前端「保存当前版本」按钮）。 */
    @Transactional(rollbackFor = Exception.class)
    public SharedDocument restoreSharedDocVersion(Long shareId, Long docId, Long vid, Long editorId, boolean sysAdmin) {
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        SharedDocVersion v = sharedDocVerRepo.findById(vid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在"));
        if (!v.getSharedDocId().equals(docId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该版本不属于此文档");
        if (!canAdminDoc(shareId, docId, editorId, sysAdmin) && !d.getOwnerId().equals(editorId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间/文档管理员权限或文档所有者身份");
        d.setTitle(v.getTitle());
        d.setContent(v.getContent());
        d.setYjsState(v.getYjsState());   // 写回 Yjs 状态；协同端重连后 onLoadDocument 会加载此状态
        // 代次自增（F05）：中继若仍持有旧内存快照，落库时会因代次失配而丢弃并强制重载，
        // 不会在「已提示恢复成功」之后又把历史内容覆盖回旧版本。
        d.setYjsRev(d.getYjsRev() + 1);
        d.setUpdatedAt(LocalDateTime.now());
        SharedDocument saved = docRepo.save(d);
        DocShare s = shareRepo.findById(shareId).orElse(null);
        if (s != null) { s.setUpdatedAt(LocalDateTime.now()); shareRepo.save(s); }
        fillDocOwnerName(saved);
        return saved;
    }

    /** 删除某个历史版本。版本只是快照，删除**不影响文档当前内容**，仅从版本列表移除。
     *  权限与回滚一致：需空间/文档管理员，或文档所有者。 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteSharedDocVersion(Long shareId, Long docId, Long vid, Long editorId, boolean sysAdmin) {
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        SharedDocVersion v = sharedDocVerRepo.findById(vid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在"));
        if (!v.getSharedDocId().equals(docId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该版本不属于此文档");
        if (!canAdminDoc(shareId, docId, editorId, sysAdmin) && !d.getOwnerId().equals(editorId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间/文档管理员权限或文档所有者身份");
        sharedDocVerRepo.delete(v);
    }

    /** 字节数转人类可读（用于配额报错文案），如 1.2 GB */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double v = bytes;
        String[] u = { "KB", "MB", "GB", "TB" };
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format("%.1f %s", v, u[i]);
    }

    // 空间用量（已用 / 上限 / 所有者总配额），供空间页与后台展示
    public Map<String, Object> spaceStorage(Long shareId, Long userId, boolean sysAdmin) {
        DocShare s = get(shareId, userId, sysAdmin); // 校验可读
        long used = docRepo.sumSizeByShareId(shareId);
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("used", used);
        m.put("capacity", s.getCapacity());          // 可能为 null（不限制）
        m.put("hasCapacity", s.getCapacity() != null);
        m.put("ownerQuota", effectiveUserQuota(s.getOwnerId()));
        m.put("ownerUsed", storageUsage(s.getOwnerId()));
        return m;
    }

    // 管理员：列出所有空间（含所有者名）
    public List<DocShare> listAll(Long userId, boolean sysAdmin) {
        if (!sysAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        List<DocShare> list = shareRepo.findAll();
        fillOwnerNamesInBatch(list);   // 旧实现是逐条 findById 查所有者名，N+1
        for (DocShare s : list) s.setMyPermission("OWNER");
        return list;
    }

    /** 批量注入所有者名：一次 findAllById 替代逐条 findById */
    private void fillOwnerNamesInBatch(List<DocShare> list) {
        if (list == null || list.isEmpty()) return;
        Set<Long> ids = list.stream().map(DocShare::getOwnerId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return;
        Map<Long, User> users = userRepo.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        for (DocShare s : list) {
            User u = users.get(s.getOwnerId());
            if (u != null) s.setOwnerName(u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
        }
    }
}
