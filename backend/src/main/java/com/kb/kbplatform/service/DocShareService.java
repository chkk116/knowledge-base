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
import com.kb.kbplatform.repository.UploadedFileRepository;
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
    private final UploadedFileRepository fileRepo;
    private final TransactionTemplate tx;
    private final RelayNotifier relayNotifier;
    private final NotificationService notificationService;

    public DocShareService(DocShareRepository shareRepo, DocSharePermissionRepository permRepo,
                           SharedDocumentRepository docRepo, SharedDocPermissionRepository docPermRepo,
                           UserRepository userRepo, SharedDocVersionRepository sharedDocVerRepo,
                           DocAnnotationRepository annoRepo, UploadedFileRepository fileRepo,
                           TransactionTemplate tx, RelayNotifier relayNotifier,
                           NotificationService notificationService) {
        this.relayNotifier = relayNotifier;
        this.notificationService = notificationService;
        this.shareRepo = shareRepo;
        this.permRepo = permRepo;
        this.docRepo = docRepo;
        this.docPermRepo = docPermRepo;
        this.userRepo = userRepo;
        this.sharedDocVerRepo = sharedDocVerRepo;
        this.annoRepo = annoRepo;
        this.fileRepo = fileRepo;
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
        // 批量取用户：此前循环里逐个 findById，成员多时是 N+1（外部复审 2026-11-07）
        java.util.Set<Long> memberIds = new java.util.HashSet<>();
        for (DocSharePermission sp : spaceMembers) memberIds.add(sp.getUserId());
        for (SharedDocPermission ov : docOverrides) memberIds.add(ov.getUserId());
        Map<Long, User> users = userRepo.findAllById(memberIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        List<Map<String, Object>> result = new ArrayList<>();
        // 空间成员
        for (DocSharePermission sp : spaceMembers) {
            User u = users.get(sp.getUserId());
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
            User u = users.get(ov.getUserId());
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
        // 目标用户必须存在且启用：原先不校验，随便填个 id 也能建出一条权限记录，
        // 界面上看不到、数据里却留着（外部白盒审计点）
        com.kb.kbplatform.domain.User target = targetUserId == null ? null
                : userRepo.findById(targetUserId).orElse(null);
        if (target == null || !target.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标用户不存在或已停用");
        }
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
        if (list.isEmpty()) return;
        Map<Long, User> users = userRepo.findAllById(list.stream().map(DocSharePermission::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a));
        for (DocSharePermission p : list) {
            User u = users.get(p.getUserId());
            if (u == null) continue;
            p.setUsername(u.getUsername());
            p.setDisplayName(u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
            p.setAvatar(u.getAvatar());
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
        // 个人存储 = 共享文档正文/yjs 快照 + 「我的文件」上传件。
        // 只算前者会让文件中心顶部的「已用」永远是几 KB（文件明明占了 300MB 也不动）。
        return docRepo.sumSizeByOwnerId(userId) + fileRepo.sumSizeByOwnerId(userId);
    }

    /** 上传前配额闸门：incoming 字节放不下就 413（否则「已用/配额」只是摆设，磁盘能被传满） */
    public void assertUserStorage(Long userId, long incoming) {
        if (incoming <= 0) return;
        long used = storageUsage(userId);
        long quota = effectiveUserQuota(userId);
        if (used + incoming > quota) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "个人存储空间不足（已用 " + formatSize(used) + " / 配额 " + formatSize(quota)
                            + "），如需扩容请联系管理员");
        }
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
                : userRepo.searchEnabledUsers(kw, PageRequest.of(0, 20));
        List<Map<String, Object>> result = new ArrayList<>();
        // 两条查询路径都只返回 enabled=true 用户，无需再判 isEnabled
        for (User u : users) {
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
            // C3：管理员此前走 findAll()，把全站每个账号的个人空间都塞进「我的空间」，
            // 注销账号不清个人空间 → 卡片越攒越多（实测 248 张）。
            // 管理员也只能额外看到「团队空间」（便于集中管理），他人的个人空间一律不出现。
            list = new ArrayList<>(shareRepo.findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(userId));
            Set<Long> seen = list.stream().map(DocShare::getId).collect(Collectors.toSet());
            for (DocShare s : shareRepo.findAll()) {
                if ("TEAM".equals(s.getType()) && seen.add(s.getId())) list.add(s);
            }
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

    /**
     * 正文 @提及：点选空间成员即发通知。
     *
     * 为什么不去解析正文：共享文档的正文靠「保存快照」才落库，用户可能一直不点保存，
     * 解析正文根本不会触发；而「我叫他来看这篇」这个动作本身是明确的，点选即通知更可靠。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> mentionDoc(Long shareId, Long docId, Long targetUserId, Long userId, boolean sysAdmin) {
        DocShare s = get(shareId, userId, sysAdmin);
        if (targetUserId == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要提及的成员");
        if (Objects.equals(targetUserId, userId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能提及自己");
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        if (!canReadDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权在该文档中提及他人");

        // 被提及者必须本来就能打开这篇文档，否则通知过去也是死链
        boolean reachable = Objects.equals(s.getOwnerId(), targetUserId)
                || permRepo.findByShareIdAndUserId(shareId, targetUserId).isPresent()
                || docPermRepo.findByDocIdAndUserId(docId, targetUserId).isPresent();
        if (!reachable)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该用户不在这个空间里，无法接收提及");

        User target = userRepo.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        String actor = userRepo.findById(userId).map(User::getUsername).orElse("");
        notificationService.notifyDocMention(shareId, docId, d.getTitle(), userId, actor, targetUserId);

        String name = target.getDisplayName() == null || target.getDisplayName().trim().isEmpty()
                ? target.getUsername() : target.getDisplayName();
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("ok", true);
        ret.put("name", name);
        return ret;
    }

    @Transactional(rollbackFor = Exception.class)
    public DocSharePermission addMember(Long shareId, Long targetUserId, String permission, Long userId, boolean sysAdmin) {        DocShare s = get(shareId, userId, sysAdmin);
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

    /** 用户可读的跨空间文档列表（导出/全量正文场景），复用空间级与文档级权限判断，不返回待整理项。 */
    public List<SharedDocument> listAllDocs(Long userId, boolean sysAdmin, boolean withContent) {
        return buildLibraryDocs(userId, sysAdmin, withContent, false);
    }

    /**
     * 「全部文档」列表查询（带护栏）：摘要查询只取正文前缀，不拉全文；
     * 总量超 {@link #MAX_LIST_DOCS} 篇截断并告警，withContent 时只对前 {@link #MAX_CONTENT_DOCS} 篇
     * 批量取完整正文（其余 content=null，前端据此提示检索覆盖范围）。
     * 导出走 {@link #listAllDocs} 不经这里，保证导出内容完整。
     */
    public List<SharedDocument> listAllDocsForList(Long userId, boolean sysAdmin, boolean withContent) {
        return buildLibraryDocs(userId, sysAdmin, withContent, true);
    }

    private List<SharedDocument> buildLibraryDocs(Long userId, boolean sysAdmin, boolean withContent, boolean forList) {
        List<DocShare> spaces = listMine(userId, sysAdmin);
        if (spaces.isEmpty()) return List.of();
        Map<Long, DocShare> spaceById = spaces.stream().collect(Collectors.toMap(DocShare::getId, s -> s));
        List<Long> shareIds = spaces.stream().map(DocShare::getId).toList();
        List<SharedDocumentSummary> summaries = forList
                ? docRepo.findLibraryMetaByShareIdIn(shareIds)
                : docRepo.findLibrarySummariesByShareIdIn(shareIds);
        if (summaries.isEmpty()) return List.of();

        List<Long> docIds = summaries.stream().map(SharedDocumentSummary::getId).toList();
        Map<Long, SharedDocPermission> overrides = docPermRepo.findByDocIdInAndUserId(docIds, userId).stream()
                .collect(Collectors.toMap(SharedDocPermission::getDocId, p -> p, (a, b) -> a));
        List<SharedDocument> out = new ArrayList<>();
        List<SharedDocumentSummary> rows = new ArrayList<>();   // 与 out 一一对应，供全量正文回填
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
            // 列表路径 row.content 是 200 字前缀，足够判断 JSON/HTML/文本类型
            doc.setContentType(classifyContentType(row.getContent()));
            doc.setSpaceName(space.getName());
            doc.setSpaceType(space.getType());
            doc.setMyPerm(permission);
            out.add(doc);
            rows.add(row);
        }
        if (forList && out.size() > MAX_LIST_DOCS) {
            log.warn("全部文档列表超过 {} 篇（实际 {}），已截断；建议改服务端分页", MAX_LIST_DOCS, out.size());
            out = new ArrayList<>(out.subList(0, MAX_LIST_DOCS));
            rows = new ArrayList<>(rows.subList(0, MAX_LIST_DOCS));
        }
        // 正文检索（2026-10-18）：按需下发，截断到 20000 字符；列表路径只给前 MAX_CONTENT_DOCS 篇，
        // 其余保持 null —— 前端可据此告诉用户「正文检索覆盖范围」，而不是静默少搜。
        if (withContent) {
            if (forList) {
                int n = Math.min(MAX_CONTENT_DOCS, out.size());
                List<Long> ids = out.subList(0, n).stream().map(SharedDocument::getId).toList();
                Map<Long, String> bodies = ids.isEmpty() ? Map.of()
                        : docRepo.findContentByIdIn(ids).stream()
                            // 手写 put：Collectors.toMap 遇到 content=null 会抛 NPE（HashMap.merge 不接受 null 值）
                            .collect(java.util.HashMap::new,
                                    (m, r) -> m.put(r.getId(), r.getContent() == null ? "" : r.getContent()),
                                    java.util.HashMap::putAll);
                for (int i = 0; i < n; i++) {
                    out.get(i).setContent(truncateForSearch(bodies.get(out.get(i).getId()), 20000));
                }
            } else {
                for (int i = 0; i < out.size(); i++) {
                    out.get(i).setContent(truncateForSearch(rows.get(i).getContent(), 20000));
                }
            }
        }
        return out;
    }

    /** 为「正文检索」截断正文：null 安全，超长按字符截断（前端只做匹配，不需要完整内容） */
    private static String truncateForSearch(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 「全部文档」列表的正文条数上限：正文只用于页面检索，超出的文档不带正文 */
    private static final int MAX_CONTENT_DOCS = 300;
    /** 「全部文档」列表的条目硬上限：极端数据量下截断并告警（导出不受影响） */
    private static final int MAX_LIST_DOCS = 5000;

    /**
     * 整库导出：zip = 每篇一个 .html + 一份 index.md 清单。
     *
     * 复用 listAllDocs(…, true) 的逐条权限裁决，导出范围天然等于「用户可读范围」，
     * 不存在越权导出他人空间的问题。体积上限 200MB，超出直接拒绝，避免内存被打爆。
     */
    public byte[] buildExportZip(Long userId, boolean sysAdmin) {
        List<SharedDocument> docs = listAllDocs(userId, sysAdmin, true);
        if (docs.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可导出的文档");
        final long MAX = 200L * 1024 * 1024;

        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        StringBuilder manifest = new StringBuilder();
        manifest.append("# 知识库导出（").append(stamp).append("）\n\n")
                .append("- 文档数：").append(docs.size()).append("\n");

        java.util.Set<String> used = new java.util.HashSet<>();   // 同名（空间/标题）去重
        int n = 0;
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            for (SharedDocument d : docs) {
                String space = safeName(d.getSpaceName());
                String title = safeName(d.getTitle());
                String base = space + "/" + title;
                String entry = base;
                int i = 2;
                while (!used.add(entry.toLowerCase(java.util.Locale.US))) entry = base + "-" + (i++);
                entry = (++n) + "-" + entry + ".html";

                zos.putNextEntry(new java.util.zip.ZipEntry(entry));
                zos.write(wrapHtml(d.getTitle(), d.getContent()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zos.closeEntry();

                manifest.append("\n## ").append(n).append(". ").append(d.getTitle() == null ? "" : d.getTitle()).append('\n')
                        .append("- 空间：").append(d.getSpaceName() == null ? "" : d.getSpaceName()).append('\n')
                        .append("- 文件：`").append(entry).append("`\n");
                String tags = d.getTags();
                if (tags != null && !tags.trim().isEmpty()) manifest.append("- 标签：").append(tags).append('\n');

                if (bos.size() > MAX) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "导出体积超过 200MB 上限");
            }
            zos.putNextEntry(new java.util.zip.ZipEntry("index.md"));
            zos.write(manifest.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "导出失败：" + e.getMessage());
        }
        return bos.toByteArray();
    }

    /** 导出条目名安全化：去路径分隔符与 Windows 保留字符、压缩空白、限长 60 */
    private static String safeName(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\r\n\t]", "_").replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) t = "untitled";
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    /** 把正文包成能独立打开的 HTML（图片/链接保留原样，通常已是绝对地址） */
    private static String wrapHtml(String title, String body) {
        String t = title == null ? "" : title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\"><title>" + t
                + "</title></head><body><h1>" + t + "</h1>" + (body == null ? "" : body) + "</body></html>";
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

    /** 把逗号分隔的标签串拆成列表 */
    private static List<String> splitTags(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static Long asLong(Object o) {
        if (o == null) return null;
        try { return Long.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * 批量改标签（「全部文档」多选后用）。
     *
     * 逐条做写权限校验：没有写权限或文档已删除的**跳过并计入 skipped**，
     * 不让一条失败把整批回滚掉——批量操作里最烦的就是「99 条成功、1 条失败全没了」。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> batchUpdateTags(List<Map<String, Object>> items, List<String> add, List<String> remove,
                                               Long userId, boolean sysAdmin) {
        List<String> addList = new ArrayList<>();
        if (add != null) for (String s : add) { String t = s == null ? "" : s.trim(); if (!t.isEmpty()) addList.add(t); }
        List<String> rmList = new ArrayList<>();
        if (remove != null) for (String s : remove) { String t = s == null ? "" : s.trim(); if (!t.isEmpty()) rmList.add(t); }
        if (addList.isEmpty() && rmList.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请至少指定一个要添加或移除的标签");

        int ok = 0;
        List<String> skipped = new ArrayList<>();
        if (items != null) {
            for (Map<String, Object> it : items) {
                Long sid = asLong(it.get("shareId"));
                Long did = asLong(it.get("docId"));
                if (sid == null || did == null) continue;
                SharedDocument d = docRepo.findByIdAndShareId(did, sid).orElse(null);
                if (d == null) { skipped.add("文档已删除：" + did); continue; }
                if (!canWriteDoc(sid, did, userId, sysAdmin)) { skipped.add("无写入权限：" + d.getTitle()); continue; }
                LinkedHashSet<String> next = new LinkedHashSet<>(splitTags(d.getTags()));
                for (String r : rmList) next.removeIf((t) -> t.equalsIgnoreCase(r));
                next.addAll(addList);
                d.setTags(normalizeDocTags(new ArrayList<>(next)));
                d.setUpdatedAt(LocalDateTime.now());
                docRepo.save(d);
                ok++;
            }
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("ok", ok);
        ret.put("skipped", skipped);
        return ret;
    }

    /** 正文里的 [[文档名]] 引用，限长 1~120，不跨行 */
    private static final java.util.regex.Pattern WIKI_LINK =
            java.util.regex.Pattern.compile("\\[\\[([^\\[\\]\\r\\n]{1,120})\\]\\]");

    /** 抽出正文里所有 [[...]] 的标题（去重，保持出现顺序） */
    private static List<String> extractLinkTitles(String content) {
        List<String> out = new ArrayList<>();
        if (content == null || content.isEmpty()) return out;
        java.util.regex.Matcher m = WIKI_LINK.matcher(content);
        Set<String> seen = new HashSet<>();
        while (m.find()) {
            String t = m.group(1) == null ? "" : m.group(1).trim();
            if (t.isEmpty()) continue;
            if (seen.add(t.toLowerCase(Locale.ROOT))) out.add(t);
        }
        return out;
    }

    /**
     * 双向链接：当前文档「引用了谁」+「被谁引用」。
     *
     * 实时解析正文，不落链接表——文档数量在个人/小团队量级时，
     * 建表维护的成本（写入时同步、改标题要级联）远高于扫描成本。
     *
     * 解析范围 = listAllDocs 的**可读**文档，天然不会泄露无权访问的文档标题。
     */
    public Map<String, Object> docLinks(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        get(shareId, userId, sysAdmin);   // 空间级校验
        SharedDocument target = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        if (!canReadDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该文档");

        List<SharedDocument> all = listAllDocs(userId, sysAdmin, true);

        // 标题 → 文档（同名取第一篇，够用；不区分大小写与首尾空白）
        Map<String, SharedDocument> byTitle = new HashMap<>();
        for (SharedDocument d : all) {
            if (d.getTitle() == null) continue;
            byTitle.putIfAbsent(d.getTitle().trim().toLowerCase(Locale.ROOT), d);
        }

        List<Map<String, Object>> outList = new ArrayList<>();
        for (String t : extractLinkTitles(target.getContent())) {
            SharedDocument hit = byTitle.get(t.toLowerCase(Locale.ROOT));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("title", t);
            row.put("resolved", hit != null);
            row.put("shareId", hit == null ? null : hit.getShareId());
            row.put("docId", hit == null ? null : hit.getId());
            row.put("spaceName", hit == null ? null : hit.getSpaceName());
            outList.add(row);
        }

        List<Map<String, Object>> backList = new ArrayList<>();
        String selfTitle = target.getTitle() == null ? "" : target.getTitle().trim().toLowerCase(Locale.ROOT);
        if (!selfTitle.isEmpty()) {
            for (SharedDocument d : all) {
                if (Objects.equals(d.getId(), target.getId())) continue;
                boolean ref = extractLinkTitles(d.getContent()).stream()
                        .anyMatch((t) -> t.trim().toLowerCase(Locale.ROOT).equals(selfTitle));
                if (!ref) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("title", d.getTitle());
                row.put("resolved", true);
                row.put("shareId", d.getShareId());
                row.put("docId", d.getId());
                row.put("spaceName", d.getSpaceName());
                backList.add(row);
            }
        }

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("out", outList);
        ret.put("back", backList);
        return ret;
    }

    /**
     * 知识图谱：当前用户可读的全部文档为节点，正文里 [[标题]] 能解析到文档的为有向边。
     * 与 docLinks 共用同一套解析规则（extractLinkTitles + 标题索引），保证面板与图谱一致。
     */
    public Map<String, Object> linkGraph(Long userId, boolean sysAdmin) {
        List<SharedDocument> all = listAllDocs(userId, sysAdmin, true);
        Map<String, SharedDocument> byTitle = new HashMap<>();
        for (SharedDocument d : all) {
            if (d.getTitle() == null) continue;
            byTitle.putIfAbsent(d.getTitle().trim().toLowerCase(Locale.ROOT), d);
        }
        Map<String, Integer> inDeg = new HashMap<>();
        Map<String, Integer> outDeg = new HashMap<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SharedDocument d : all) {
            String from = d.getShareId() + ":" + d.getId();
            for (String t : extractLinkTitles(d.getContent())) {
                SharedDocument hit = byTitle.get(t.trim().toLowerCase(Locale.ROOT));
                if (hit == null || Objects.equals(hit.getId(), d.getId())) continue;
                String to = hit.getShareId() + ":" + hit.getId();
                String key = from + ">" + to;
                if (!seen.add(key)) continue;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("id", key);
                e.put("source", from);
                e.put("target", to);
                edges.add(e);
                outDeg.merge(from, 1, Integer::sum);
                inDeg.merge(to, 1, Integer::sum);
            }
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (SharedDocument d : all) {
            String id = d.getShareId() + ":" + d.getId();
            int deg = inDeg.getOrDefault(id, 0) + outDeg.getOrDefault(id, 0);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", id);
            n.put("docId", d.getId());
            n.put("shareId", d.getShareId());
            n.put("title", d.getTitle());
            n.put("spaceName", d.getSpaceName());
            n.put("degree", deg);
            nodes.add(n);
        }
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("nodes", nodes);
        ret.put("edges", edges);
        return ret;
    }

    // ===================== 外部分享（公开只读链接） =====================

    /** 创建/更新公开链接：password 为空表示免密；expireDays<=0 表示永久 */
    public Map<String, Object> savePublicLink(Long shareId, Long docId, Long userId, boolean sysAdmin,
                                              String password, Integer expireDays, boolean noCopy) {
        get(shareId, userId, sysAdmin);   // 空间校验
        if (!canAdminDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权设置该文档的公开链接");
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        if (d.getShareToken() == null || d.getShareToken().isBlank()) {
            d.setShareToken(java.util.UUID.randomUUID().toString().replace("-", "")
                    + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        }
        d.setSharePassHash(password == null || password.isBlank()
                ? null : com.kb.kbplatform.security.PasswordUtil.encode(password.trim()));
        d.setShareExpireAt(expireDays != null && expireDays > 0
                ? LocalDateTime.now().plusDays(expireDays) : null);
        d.setShareNoCopy(noCopy);
        docRepo.save(d);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("token", d.getShareToken());
        ret.put("hasPassword", d.getSharePassHash() != null);
        ret.put("expireAt", d.getShareExpireAt());
        ret.put("noCopy", d.isShareNoCopy());
        ret.put("views", d.getShareViews());
        return ret;
    }

    /** 关闭公开链接（令牌作废，历史链接立即 404） */
    public void revokePublicLink(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        get(shareId, userId, sysAdmin);
        if (!canAdminDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权操作该文档的公开链接");
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        d.setShareToken(null);
        d.setSharePassHash(null);
        d.setShareExpireAt(null);
        docRepo.save(d);
    }

    /** 当前文档的公开链接状态（管理端回显，不返回令牌全文） */
    public Map<String, Object> publicLinkInfo(Long shareId, Long docId, Long userId, boolean sysAdmin) {
        get(shareId, userId, sysAdmin);
        if (!canAdminDoc(shareId, docId, userId, sysAdmin))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看该文档的公开链接");
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("enabled", d.getShareToken() != null);
        ret.put("token", d.getShareToken());
        ret.put("hasPassword", d.getSharePassHash() != null);
        ret.put("expireAt", d.getShareExpireAt());
        ret.put("noCopy", d.isShareNoCopy());
        ret.put("views", d.getShareViews());
        return ret;
    }

    /** 公开链接存在性检查：过期/未开启一律 404（不区分，避免探测令牌） */
    private SharedDocument requirePublicDoc(String token) {
        if (token == null || token.isBlank())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "链接不存在或已失效");
        SharedDocument d = docRepo.findByShareToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "链接不存在或已失效"));
        if (d.getShareExpireAt() != null && d.getShareExpireAt().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "链接已过期");
        }
        return d;
    }

    /** 免登录读取元信息：标题 / 是否需要密码 / 是否禁止复制（不返回正文） */
    public Map<String, Object> publicMeta(String token) {
        SharedDocument d = requirePublicDoc(token);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("title", d.getTitle());
        ret.put("needPassword", d.getSharePassHash() != null);
        ret.put("noCopy", d.isShareNoCopy());
        ret.put("updatedAt", d.getUpdatedAt());
        ret.put("spaceName", d.getSpaceName());
        return ret;
    }

    /** 免登录读取正文：有密码时校验，通过后计数 +1；正文统一消毒 */
    public Map<String, Object> publicView(String token, String password) {
        SharedDocument d = requirePublicDoc(token);
        if (d.getSharePassHash() != null) {
            if (password == null || !com.kb.kbplatform.security.PasswordUtil.matches(password.trim(), d.getSharePassHash()))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "密码不正确");
        }
        d.setShareViews(d.getShareViews() + 1);
        docRepo.save(d);
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("title", d.getTitle());
        ret.put("content", com.kb.kbplatform.security.HtmlSanitizer.sanitize(d.getContent()));
        ret.put("noCopy", d.isShareNoCopy());
        ret.put("updatedAt", d.getUpdatedAt());
        ret.put("spaceName", d.getSpaceName());
        return ret;
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
        return updateDoc(shareId, docId, title, content, userId, sysAdmin, null, null);
    }

    /**
     * 保存正文（带防覆盖护栏）。
     *
     * 平台实测过一条真实的丢数据路径：一端本地内容没同步完整（只有新追加段），点保存即整篇覆盖，
     * 另一端原文与其它段落同时消失、没有任何提示。这里加两道闸：
     *   1) baseRev：调用方带上读到的 yjsRev，与库里不一致直接 409（别默默盖掉别人的改动）；
     *   2) 骤减拦截：新正文不足现有正文一半（且原文不短）时拒绝，提示先刷新再看，
     *      确需删减走「保存当前版本 + 恢复」而不是直接覆盖。
     * baseRev 为 null 视为老客户端，只走第 2 道。
     *
     * 第 1 道原先用 yjsRev 当代次，但它只在「恢复历史版本」时自增（怕自增会让协同房间名变化、
     * 编辑器整体重建），日常保存根本不变，等于形同虚设——两端各存各的整篇内容，后存的静默盖掉
     * 先存的（N17）。这里补上 baseUpdatedAt：客户端带上「打开时看到的 updated_at」，与库里不一致
     * 说明期间有人保存过，直接 409 让它刷新，而不是把别人的修改整篇抹掉。同样，老客户端不带就跳过。
     */
    public SharedDocument updateDoc(Long shareId, Long docId, String title, String content,
                                    Long userId, boolean sysAdmin, Long baseRev, java.time.LocalDateTime baseUpdatedAt) {
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
        // 闸 1：代次校验（乐观锁）——别把别人刚写的版本静默盖掉
        if (baseRev != null && baseRev != d.getYjsRev()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "文档已在别处更新（版本 " + d.getYjsRev() + "），请刷新后再保存");
        }
        // 闸 1b：保存时间戳校验——真正挡住「A 拿着十分钟前的整篇内容按保存，把 B 刚写的一整段抹掉」。
        // 只比到秒：库里是微秒精度，客户端 JSON 往返的小数位数不一定一致，比到秒足够区分两次保存。
        if (baseUpdatedAt != null && d.getUpdatedAt() != null
                && !d.getUpdatedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                        .equals(baseUpdatedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "你打开这篇文档之后，已有人在别处保存过（最后保存 "
                            + d.getUpdatedAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                            + "）。请刷新确认最新内容后再修改，本次保存未写入，避免整篇覆盖别人的改动。");
        }
        // 闸 2：正文骤减拦截——客户端本地未同步完整时，内容会「只剩自己刚写的一段」
        if (content != null && d.getContent() != null) {
            int oldLen = d.getContent().length();
            int newLen = nextContent.length();
            if (oldLen > 200 && newLen * 2 < oldLen) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "提交的内容比现有正文少了一半以上（" + newLen + " / " + oldLen
                                + " 字），可能是本地未同步完整。请刷新后再修改；确需删减请先保存版本再恢复。");
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
        // 注意：这里刻意不递增 yjsRev —— 自动快照每几秒触发一次，递增会让父组件按 key 重建编辑器，
        // 用户会看到闪烁/光标丢失。内容骤减与代次失配已在上面两道闸拦下。
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
        // 先让中继把内存里的最新协同态落库，再读 yjs_state 存快照。
        // 中继是定时落库的，直接读会拿到几秒前的旧状态——回滚后才出现「正文列恢复了、协同内容还是旧的」（N23/N56）。
        // 必须排在 getDoc 之前：一旦实体进了持久化上下文，后面的查询只会返回旧值。
        relayNotifier.flush(shareId, docId);
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
        // 必须在**第一次**读实体之前催中继落库：实体一旦进了持久化上下文，后面再查回来的还是同一个旧实例，
        // 备份里存的 yjs_state 就永远是几秒前那份（回滚后的「回滚前备份」也就点不动回去了）。
        relayNotifier.flush(shareId, docId);
        SharedDocument d = docRepo.findByIdAndShareId(docId, shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));
        SharedDocVersion v = sharedDocVerRepo.findById(vid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在"));
        if (!v.getSharedDocId().equals(docId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该版本不属于此文档");
        if (!canAdminDoc(shareId, docId, editorId, sysAdmin) && !d.getOwnerId().equals(editorId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间/文档管理员权限或文档所有者身份");
        // 回滚前先把「当前内容」留一档，并标记 RESTORE：
        // 既不会出现「回滚后再也找不回刚才的内容」，也不会像早期实现那样把版本列表刷满
        // （前端对该类条目标注「回滚前备份」，不影响正常历史浏览）
        SharedDocVersion backup = new SharedDocVersion();
        backup.setSharedDocId(docId);
        backup.setTitle(d.getTitle());
        backup.setContent(d.getContent());
        backup.setYjsState(d.getYjsState());
        backup.setEditorId(editorId);
        backup.setKind("RESTORE");
        backup.setCreatedAt(LocalDateTime.now());
        sharedDocVerRepo.save(backup);

        d.setTitle(v.getTitle());
        d.setContent(v.getContent());
        // 只覆盖有协同快照的版本：老版本（未开协同时期的快照）yjs_state 为空，
        // 直接写 null 会让协同端加载到空文档、整篇内容凭空消失。宁可正文与协同态不同步，
        // 也不能把文档清空——文本层有 RESTORE 备份可回退。
        boolean hasYjs = v.getYjsState() != null && v.getYjsState().length > 0;
        if (hasYjs) {
            d.setYjsState(v.getYjsState());
        } else {
            log.warn("版本 {} 没有协同快照，仅恢复正文，协同态保持当前内容", vid);
        }
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
        return listAll(userId, sysAdmin, null);
    }

    /** 管理后台的空间列表。type=TEAM 只看团队空间：个人空间每人自动生成一个（全站 159 个、
     *  默认都叫「我的空间」），堆在管理页里既无信息量也把真正的团队空间挤没了（外部测试 C3）。 */
    public List<DocShare> listAll(Long userId, boolean sysAdmin, String type) {
        if (!sysAdmin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        List<DocShare> list = shareRepo.findAll();
        if (type != null && !type.isBlank()) {
            list = list.stream().filter((s0) -> type.equalsIgnoreCase(s0.getType())).collect(Collectors.toList());
        }
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
