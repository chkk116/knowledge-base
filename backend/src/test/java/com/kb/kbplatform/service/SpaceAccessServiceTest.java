/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DocShare;
import com.kb.kbplatform.domain.DocSharePermission;
import com.kb.kbplatform.domain.SpaceInvite;
import com.kb.kbplatform.domain.SysSetting;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.repository.AuditLogRepository;
import com.kb.kbplatform.repository.DocSharePermissionRepository;
import com.kb.kbplatform.repository.DocShareRepository;
import com.kb.kbplatform.repository.SpaceInviteRepository;
import com.kb.kbplatform.repository.SysSettingRepository;
import com.kb.kbplatform.repository.UserRepository;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「进空间」两种入口的负面测试：拿到链接不等于能进，链接也不能用来提权。
 *
 * 不用 Mockito（本机 JDK 下 MockMaker 初始化失败），repository 用 JDK 动态代理手写 stub。
 */
class SpaceAccessServiceTest {

    private static final Long SPACE_ID = 7L;
    private static final Long OWNER_ID = 1L;
    private static final Long GUEST_ID = 9L;
    private static final String TOKEN = "A".repeat(43);   // 32B Base64URL 的形状

    private String packJson = "{\"key\":\"general\",\"defaultSpaceId\":7}";
    private Map<String, SpaceInvite> byToken;
    private Map<Long, SpaceInvite> byId;
    private Map<String, DocSharePermission> members;   // key = spaceId + ":" + userId
    private DocShare space;
    private SpaceAccessService svc;

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler h) {
        return (T) Proxy.newProxyInstance(SpaceAccessServiceTest.class.getClassLoader(), new Class<?>[]{type}, h);
    }

    @BeforeEach
    void setUp() {
        byToken = new HashMap<>();
        byId = new HashMap<>();
        members = new HashMap<>();

        space = new DocShare();
        space.setId(SPACE_ID);
        space.setName("新人培训");
        space.setOwnerId(OWNER_ID);
        space.setType("TEAM");
        space.setStatus("ACTIVE");

        SysSettingRepository settings = stub(SysSettingRepository.class, (p, m, a) ->
                switch (m.getName()) {
                    case "findByKey" -> Optional.of(new SysSetting("scenario_pack", packJson));
                    case "toString" -> "stub-settings";
                    default -> null;
                });
        DocShareRepository shares = stub(DocShareRepository.class, (p, m, a) ->
                switch (m.getName()) {
                    case "findById" -> SPACE_ID.equals(a[0]) ? Optional.of(space) : Optional.empty();
                    case "toString" -> "stub-shares";
                    default -> null;
                });
        DocSharePermissionRepository perms = stub(DocSharePermissionRepository.class, (p, m, a) -> {
            return switch (m.getName()) {
                case "findByShareIdAndUserId" -> Optional.ofNullable(members.get(a[0] + ":" + a[1]));
                case "save" -> {
                    DocSharePermission v = (DocSharePermission) a[0];
                    members.put(v.getShareId() + ":" + v.getUserId(), v);
                    yield v;
                }
                case "toString" -> "stub-perms";
                default -> null;
            };
        });
        SpaceInviteRepository invites = stub(SpaceInviteRepository.class, (p, m, a) ->
                switch (m.getName()) {
                    case "findByToken" -> Optional.ofNullable(byToken.get(a[0]));
                    case "findByShareIdAndRevokedFalse" -> byId.values().stream()
                            .filter(i -> i.getShareId().equals(a[0]) && !i.isRevoked()).toList();
                    case "save" -> {
                        SpaceInvite v = (SpaceInvite) a[0];
                        if (v.getId() == null) v.setId((long) (byId.size() + 1));
                        byId.put(v.getId(), v);
                        byToken.put(v.getToken(), v);
                        yield v;
                    }
                    case "saveAll" -> a[0];
                    case "consume" -> {
                        SpaceInvite v = byId.get((Long) a[0]);
                        if (v == null || (v.getMaxUses() > 0 && v.getUsedCount() >= v.getMaxUses())) yield 0;
                        v.setUsedCount(v.getUsedCount() + 1);
                        yield 1;
                    }
                    case "toString" -> "stub-invites";
                    default -> null;
                });
        User testUser = new User();
        testUser.setId(OWNER_ID);
        testUser.setUsername("admin");
        testUser.setDisplayName("管理员");
        UserRepository users = stub(UserRepository.class, (p, m, a) ->
                switch (m.getName()) {
                    case "findById" -> OWNER_ID.equals(a[0]) ? Optional.of(testUser) : Optional.empty();
                    case "toString" -> "stub-users";
                    default -> null;
                });
        AuditLogRepository auditRepo = stub(AuditLogRepository.class, (p, m, a) ->
                "save".equals(m.getName()) ? a[0] : null);

        svc = new SpaceAccessService(settings, shares, perms, invites, users, new AuditService(auditRepo));
    }

    private SpaceInvite activeInvite() {
        SpaceInvite i = new SpaceInvite();
        i.setId(1L);
        i.setShareId(SPACE_ID);
        i.setToken(TOKEN);
        i.setCreatorId(OWNER_ID);
        i.setPermission("READ");
        i.setExpiresAt(LocalDateTime.now().plusDays(7));
        i.setMaxUses(0);
        byId.put(1L, i);
        byToken.put(TOKEN, i);
        return i;
    }

    // ---- A：场景配置收敛 ----

    @Test
    void defaultSpaceIdOnlyAcceptsPositiveInteger() {
        assertEquals(7L, svc.defaultSpaceId());
        packJson = "{\"defaultSpaceId\":-3}";
        assertNull(svc.defaultSpaceId());
        packJson = "{\"defaultSpaceId\":\"javascript:alert(1)\"}";
        assertNull(svc.defaultSpaceId());
        packJson = "not json at all";
        assertNull(svc.defaultSpaceId());
    }

    @Test
    void newUserJoinsDefaultSpaceAsReadOnly() {
        svc.joinDefaultSpace(GUEST_ID, "newbie");
        assertEquals("READ", members.get(SPACE_ID + ":" + GUEST_ID).getPermission());
        // 幂等：重复调用不会写出第二条
        svc.joinDefaultSpace(GUEST_ID, "newbie");
        assertEquals(1, members.size());
    }

    // ---- B：邀请链接 ----

    @Test
    void outsiderCannotCreateInvite() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.createInvite(SPACE_ID, GUEST_ID, "guest", false, 7, 0));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void malformedRevokedExpiredInvitesRejected() {
        ResponseStatusException bad = assertThrows(ResponseStatusException.class, () -> svc.preview("../etc/passwd", GUEST_ID));
        assertEquals(404, bad.getStatusCode().value());

        SpaceInvite i = activeInvite();
        i.setRevoked(true);
        assertThrows(ResponseStatusException.class, () -> svc.preview(TOKEN, GUEST_ID));

        i.setRevoked(false);
        i.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThrows(ResponseStatusException.class, () -> svc.preview(TOKEN, GUEST_ID));
    }

    @Test
    void redeemGrantsReadOnlyAndIsIdempotent() {
        activeInvite();
        Map<String, Object> r = svc.redeem(TOKEN, GUEST_ID, "guest");
        assertEquals(true, r.get("joined"));
        assertEquals("READ", members.get(SPACE_ID + ":" + GUEST_ID).getPermission());

        Map<String, Object> again = svc.redeem(TOKEN, GUEST_ID, "guest");
        assertEquals(false, again.get("joined"));
        assertEquals(true, again.get("alreadyMember"));
        assertEquals(1, members.size());   // 没有重复成员
        assertEquals(1, byId.get(1L).getUsedCount());   // 幂等不消耗次数
    }

    @Test
    void maxUsesCannotBeExceeded() {
        SpaceInvite i = activeInvite();
        i.setMaxUses(1);
        svc.redeem(TOKEN, GUEST_ID, "guest");
        members.clear();   // 假装第二个不同的人来兑换
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> svc.redeem(TOKEN, 10L, "guest2"));
        assertEquals(404, ex.getStatusCode().value());
    }

    @Test
    void regeneratingRevokesOldLink() {
        activeInvite();
        Map<String, Object> created = svc.createInvite(SPACE_ID, OWNER_ID, "admin", false, 7, 0);
        assertNotEquals(TOKEN, created.get("token"));
        assertTrue(byId.get(1L).isRevoked(), "重新生成后旧链接必须立刻失效");
        assertThrows(ResponseStatusException.class, () -> svc.preview(TOKEN, GUEST_ID));
    }
}
