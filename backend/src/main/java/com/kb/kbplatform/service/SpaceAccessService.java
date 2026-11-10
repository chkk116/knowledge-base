/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DocShare;
import com.kb.kbplatform.domain.DocSharePermission;
import com.kb.kbplatform.domain.SpaceInvite;
import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.repository.DocSharePermissionRepository;
import com.kb.kbplatform.repository.DocShareRepository;
import com.kb.kbplatform.repository.SpaceInviteRepository;
import com.kb.kbplatform.repository.SysSettingRepository;
import com.kb.kbplatform.repository.UserRepository;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 「进空间」的两种入口，共用一套成员写入逻辑：
 *  A. 场景配置里的「新人默认空间」——账号一建好自动成为只读成员，不用一个个拉；
 *  B. 空间邀请链接——登录用户凭 token 兑换成员资格（企微式）。
 *
 * 安全口径：自动加入与链接兑换都只给 READ；链接必须登录才能兑换；
 * 生成/撤销要求空间管理员（owner / ADMIN 成员 / 完全管理员）；所有动作进审计。
 */
@Service
public class SpaceAccessService {

    private static final Logger log = LoggerFactory.getLogger(SpaceAccessService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SecureRandom RND = new SecureRandom();
    private static final String PACK_KEY = "scenario_pack";

    private final SysSettingRepository settings;
    private final DocShareRepository shares;
    private final DocSharePermissionRepository perms;
    private final SpaceInviteRepository invites;
    private final UserRepository users;
    private final AuditService audit;

    public SpaceAccessService(SysSettingRepository settings, DocShareRepository shares,
                              DocSharePermissionRepository perms, SpaceInviteRepository invites,
                              UserRepository users, AuditService audit) {
        this.settings = settings;
        this.shares = shares;
        this.perms = perms;
        this.invites = invites;
        this.users = users;
        this.audit = audit;
    }

    // ==================== A：新人默认空间 ====================

    /** 场景配置 pack 里的 defaultSpaceId。配置缺失/非法一律 null，绝不影响建号 */
    public Long defaultSpaceId() {
        try {
            SysSetting s = settings.findByKey(PACK_KEY).orElse(null);
            if (s == null || s.getValue() == null || s.getValue().isBlank()) return null;
            JsonNode n = JSON.readTree(s.getValue()).get("defaultSpaceId");
            if (n == null || n.isNull()) return null;
            long v = n.isNumber() ? n.longValue() : Long.parseLong(n.asText().trim());
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 新账号建成后调用：加入默认空间（只读）。任何失败只记日志，绝不阻断建号 */
    public void joinDefaultSpace(Long userId, String username) {
        Long spaceId = defaultSpaceId();
        if (spaceId == null || userId == null) return;
        try {
            DocShare s = shares.findById(spaceId).orElse(null);
            if (s == null || !"ACTIVE".equals(s.getStatus()) || !"TEAM".equals(s.getType())) return;
            if (s.getOwnerId().equals(userId) || perms.findByShareIdAndUserId(spaceId, userId).isPresent()) return;
            addMember(spaceId, userId, "READ");
            audit.log(userId, username, "AUTO_JOIN_SPACE", "SHARE_SPACE", spaceId, s.getName(),
                    "新人自动加入默认空间（只读）");
        } catch (Exception e) {
            log.warn("新人自动加入默认空间失败：userId={} spaceId={} ({})", userId, spaceId, e.getMessage());
        }
    }

    // ==================== B：空间邀请链接 ====================

    private DocShare requireSpace(Long spaceId) {
        DocShare s = shares.findById(spaceId).orElse(null);
        if (s == null || !"ACTIVE".equals(s.getStatus()) || !"TEAM".equals(s.getType())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "空间不存在或不可用");
        }
        return s;
    }

    private void requireManage(DocShare s, Long userId, boolean sysAdmin) {
        if (sysAdmin || s.getOwnerId().equals(userId)) return;
        boolean admin = perms.findByShareIdAndUserId(s.getId(), userId)
                .filter(p -> "ADMIN".equals(p.getPermission())).isPresent();
        if (!admin) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要空间管理员权限");
    }

    private SpaceInvite requireValidToken(String token) {
        // 32 字节 Base64URL = 43 字符；格式先掐死，再查库，避免任意字符串打全表
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请链接无效");
        }
        SpaceInvite i = invites.findByToken(token).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (i == null || i.isRevoked() || i.getExpiresAt() == null || !i.getExpiresAt().isAfter(now)
                || (i.getMaxUses() > 0 && i.getUsedCount() >= i.getMaxUses())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请链接无效或已失效");
        }
        requireSpace(i.getShareId());
        return i;
    }

    private Map<String, Object> view(SpaceInvite i, DocShare s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", i.getToken());
        m.put("url", "/join/" + i.getToken());
        m.put("spaceId", s.getId());
        m.put("spaceName", s.getName());
        m.put("expiresAt", i.getExpiresAt() == null ? null : i.getExpiresAt().toString());
        m.put("maxUses", i.getMaxUses());
        m.put("usedCount", i.getUsedCount());
        return m;
    }

    @Transactional
    public Map<String, Object> createInvite(Long spaceId, Long userId, String username,
                                            boolean sysAdmin, Integer expireDays, Integer maxUses) {
        DocShare s = requireSpace(spaceId);
        requireManage(s, userId, sysAdmin);
        // 重新生成即让旧链接立即失效，避免「以为换了新链接，旧的还在流传」
        List<SpaceInvite> olds = invites.findByShareIdAndRevokedFalse(spaceId);
        for (SpaceInvite old : olds) old.setRevoked(true);
        if (!olds.isEmpty()) invites.saveAll(olds);

        int days = expireDays == null ? 7 : Math.max(1, Math.min(90, expireDays));
        int uses = maxUses == null ? 0 : Math.max(0, Math.min(1000, maxUses));
        byte[] buf = new byte[32];
        RND.nextBytes(buf);
        SpaceInvite i = new SpaceInvite();
        i.setShareId(spaceId);
        i.setToken(Base64.getUrlEncoder().withoutPadding().encodeToString(buf));
        i.setCreatorId(userId);
        i.setPermission("READ");
        i.setExpiresAt(LocalDateTime.now().plusDays(days));
        i.setMaxUses(uses);
        invites.save(i);
        audit.log(userId, username, "SPACE_INVITE_CREATE", "SHARE_SPACE", spaceId, s.getName(),
                "生成空间邀请链接（只读，" + days + " 天，次数上限 " + (uses == 0 ? "不限" : uses) + "）");
        return view(i, s);
    }

    public Map<String, Object> inviteInfo(Long spaceId, Long userId, boolean sysAdmin) {
        DocShare s = requireSpace(spaceId);
        requireManage(s, userId, sysAdmin);
        LocalDateTime now = LocalDateTime.now();
        return invites.findByShareIdAndRevokedFalse(spaceId).stream()
                .filter(i -> i.getExpiresAt() != null && i.getExpiresAt().isAfter(now))
                .filter(i -> i.getMaxUses() == 0 || i.getUsedCount() < i.getMaxUses())
                .findFirst()
                .map(i -> view(i, s))
                .orElseGet(() -> Map.of("active", false));
    }

    @Transactional
    public void revokeInvite(Long spaceId, Long userId, String username, boolean sysAdmin) {
        DocShare s = requireSpace(spaceId);
        requireManage(s, userId, sysAdmin);
        List<SpaceInvite> list = invites.findByShareIdAndRevokedFalse(spaceId);
        for (SpaceInvite i : list) i.setRevoked(true);
        if (!list.isEmpty()) invites.saveAll(list);
        audit.log(userId, username, "SPACE_INVITE_REVOKE", "SHARE_SPACE", spaceId, s.getName(), "撤销空间邀请链接");
    }

    /** 链接预览（需登录）：只回显加入所需的最少信息，无效链接统一 404 不解释原因 */
    public Map<String, Object> preview(String token, Long userId) {
        SpaceInvite i = requireValidToken(token);
        DocShare s = requireSpace(i.getShareId());
        Map<String, Object> m = view(i, s);
        m.put("alreadyMember", s.getOwnerId().equals(userId)
                || perms.findByShareIdAndUserId(s.getId(), userId).isPresent());
        m.put("creatorName", users.findById(i.getCreatorId())
                .map(u -> u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName())
                .orElse("空间管理员"));
        return m;
    }

    /** 兑换（需登录）：条件递增用量 + 加 READ 成员。已是成员则幂等返回，不消耗次数 */
    @Transactional
    public Map<String, Object> redeem(String token, Long userId, String username) {
        SpaceInvite i = requireValidToken(token);
        DocShare s = requireSpace(i.getShareId());
        boolean member = s.getOwnerId().equals(userId)
                || perms.findByShareIdAndUserId(s.getId(), userId).isPresent();
        Map<String, Object> m = view(i, s);
        if (member) {
            m.put("joined", false);
            m.put("alreadyMember", true);
            return m;
        }
        if (invites.consume(i.getId()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "邀请链接次数已用尽");
        }
        // 权限恒为 READ：即使库里的 permission 被改过，链接也不能用来提权
        addMember(s.getId(), userId, "READ");
        audit.log(userId, username, "SPACE_INVITE_JOIN", "SHARE_SPACE", s.getId(), s.getName(),
                "通过邀请链接加入空间（只读）");
        m.put("joined", true);
        return m;
    }

    private void addMember(Long spaceId, Long userId, String permission) {
        DocSharePermission p = new DocSharePermission();
        p.setShareId(spaceId);
        p.setUserId(userId);
        p.setPermission(permission);
        perms.save(p);
    }
}
