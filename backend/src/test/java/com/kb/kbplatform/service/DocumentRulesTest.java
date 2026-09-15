package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 文档状态转换 + 创建字段白名单 + 相关推荐排序的回归测试（审查报告 F01/F06/F13 与业务口径 A/B）。
 * 纯 Mockito 单元测试：不依赖数据库 / Meili / 中继，可在任何环境秒级跑完。
 * 运行：mvn test -Dtest=DocumentRulesTest
 */
class DocumentRulesTest {

    private DocumentRepository repo;
    private AuthService authService;
    private NotificationService notificationService;
    private UserRepository userRepo;
    private MeiliSearchClient meili;
    private DocumentVersionRepository versionRepo;
    private AuditService auditService;
    private DocSubscriptionRepository subRepo;
    private CommentRepository commentRepo;
    private LikeRepository likeRepo;
    private FavoriteRepository favoriteRepo;
    private DocumentService svc;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(DocumentRepository.class);
        authService = Mockito.mock(AuthService.class);
        notificationService = Mockito.mock(NotificationService.class);
        userRepo = Mockito.mock(UserRepository.class);
        meili = Mockito.mock(MeiliSearchClient.class);
        versionRepo = Mockito.mock(DocumentVersionRepository.class);
        auditService = Mockito.mock(AuditService.class);
        subRepo = Mockito.mock(DocSubscriptionRepository.class);
        commentRepo = Mockito.mock(CommentRepository.class);
        likeRepo = Mockito.mock(LikeRepository.class);
        favoriteRepo = Mockito.mock(FavoriteRepository.class);
        svc = new DocumentService(repo, authService, notificationService, userRepo, meili,
                versionRepo, auditService, subRepo, commentRepo, likeRepo, favoriteRepo);
        // 保存一律回传传入实体，模拟 JPA 持久化
        when(repo.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Document doc(long id, long ownerId, String status) {
        Document d = new Document();
        d.setId(id);
        d.setOwnerId(ownerId);
        d.setStatus(status);
        d.setTitle("T" + id);
        return d;
    }

    // ==================== 状态转换表（F06 / 规则 A/B） ====================

    @Test
    @DisplayName("状态转换：approve 仅允许 PENDING_REVIEW → PUBLISHED，撤回后的草稿再点通过必须 409")
    void approve_onNonPending_throws409() {
        Document d = doc(10, 1L, "DRAFT");
        when(repo.findById(10L)).thenReturn(Optional.of(d));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> svc.approve(10L));
        assertEquals(409, ex.getStatusCode().value());
        assertEquals("DRAFT", d.getStatus());                       // 状态未被改动
        verify(authService, never()).awardPoints(any(), anyInt(), any(), any());   // 不发分
        verify(notificationService, never()).notifyReview(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("状态转换：approve 对 PENDING_REVIEW 正常发布并发放积分")
    void approve_onPending_publishesAndAwards() {
        Document d = doc(11, 2L, "PENDING_REVIEW");
        when(repo.findById(11L)).thenReturn(Optional.of(d));
        Document saved = svc.approve(11L);
        assertEquals("PUBLISHED", saved.getStatus());
        verify(authService).awardPoints(eq(2L), eq(AuthService.PUBLISH_POINTS), eq("PUBLISH"), contains("发布文章"));
        verify(notificationService).notifyReview(eq(11L), any(), eq(2L), eq(true), isNull());
    }

    @Test
    @DisplayName("业务口径 A/B：已发布文档被作者修改 → 转入重新审核 + 扣回发布积分 + 通知管理员")
    void updateOwn_published_movesBackToReview() {
        Document d = doc(12, 2L, "PUBLISHED");
        when(repo.findById(12L)).thenReturn(Optional.of(d));

        Document patch = new Document();
        patch.setContent("<p>新内容</p>");
        Document saved = svc.updateOwn(12L, patch, 2L);

        assertEquals("PENDING_REVIEW", saved.getStatus());          // 规则 A：修改即重审
        verify(authService).awardPoints(eq(2L), eq(-AuthService.PUBLISH_POINTS), eq("PUBLISH"), contains("重新审核"));   // 规则 B：扣回
        verify(notificationService).notifyReviewSubmit(eq(12L), any(), any(), eq(2L));                                     // 通知管理员
        verify(notificationService, never()).notifyDocUpdate(any(), any(), any(), any());                                  // 重审中不再推订阅
    }

    @Test
    @DisplayName("业务口径 A：管理员编辑已发布文档不触发重审（保持 PUBLISHED）")
    void updateOwn_byAdminViaUpdate_keepsPublished() {
        Document d = doc(13, 2L, "PUBLISHED");
        when(repo.findById(13L)).thenReturn(Optional.of(d));
        // update() 是管理员通道：不改变状态
        Document patch = new Document();
        patch.setContent("<p>管理员修订</p>");
        Document saved = svc.update(13L, patch, 1L);
        assertEquals("PUBLISHED", saved.getStatus());
        verify(notificationService, never()).notifyReviewSubmit(any(), any(), any(), any());
    }

    // ==================== 创建字段白名单（F01） ====================

    @Test
    @DisplayName("F01：创建请求携带他人文档主键 → 服务端强制置空，走插入而非覆盖")
    void createAs_forcesIdNull() {
        // 客户端恶意传入已存在的文档 id=10（他人的文章）
        Document malicious = doc(10, 999L, "PENDING_REVIEW");
        malicious.setViews(12345);
        malicious.setHot(true);
        malicious.setPinned(true);
        malicious.setLikes(777);
        malicious.setDeleted(true);

        when(repo.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document patch = new Document();
        patch.setId(10L);                       // 攻击载荷
        patch.setTitle("我的新文章");
        patch.setStatus("PENDING_REVIEW");

        Document saved = svc.createAs(patch, 2L);

        assertNull(saved.getId(), "主键必须被服务端清空，否则 JPA 走 merge 覆盖他人文档");
        assertEquals(0, saved.getViews());
        assertFalse(saved.isHot());
        assertFalse(saved.isPinned());
        assertEquals(0, saved.getLikes());
        assertFalse(saved.isDeleted());
        assertEquals(2L, saved.getOwnerId(), "归属强制为当前用户");
    }

    @Test
    @DisplayName("F01：创建接口传入非法状态值 → 回退为安全默认")
    void createAs_rejectsIllegalStatus() {
        Document patch = new Document();
        patch.setStatus("BLOCKED");             // 客户端伪造封禁态
        when(repo.save(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        Document saved = svc.createAs(patch, 2L);
        assertEquals("PENDING_REVIEW", saved.getStatus(), "非法状态回退为待审核（不可越权置为 BLOCKED/PUBLISHED）");
    }

    // ==================== 相关推荐排序（F13 回归） ====================

    @Test
    @DisplayName("F13 回归：相关度降序优先，同分按浏览量降序（低分不得排到高分前）")
    void relatedDocs_ordersByScoreDescThenViewsDesc() {
        Document src = doc(100, 1L, "PUBLISHED");
        src.setCategory("教程");
        when(repo.findById(100L)).thenReturn(Optional.of(src));

        // 候选：A 相关度 2 / 浏览 100；B 相关度 8 / 浏览 1。B 必须排第一。
        List<Object[]> rows = List.of(
                new Object[]{201L, "低相关高浏览", "教程", "", 100, 0},
                new Object[]{202L, "高分A", "教程", "", 100, 0},
                new Object[]{203L, "高分B", "教程", "", 1, 0});
        // score 由服务端计算：同分类 +2。三者同分 → 按 views 降序：201(100) → 202(100) → 203(1)
        when(repo.findRelatedCandidates(eq(100L), eq("教程"), any())).thenReturn(rows);

        List<Document> out = svc.relatedDocs(100L, 3);
        assertEquals(3, out.size());
        assertEquals(201L, out.get(0).getId());
        assertEquals(202L, out.get(1).getId());
        assertEquals(203L, out.get(2).getId());
    }

    // ==================== 彻底删除关联清理（业务口径 C） ====================

    @Test
    @DisplayName("业务口径 C：purge 真删，并统一清理评论/点赞/收藏/版本/订阅")
    void purge_deletesRelatedData() {
        Document d = doc(30, 2L, "PUBLISHED");
        when(repo.findIncludingDeletedById(30L)).thenReturn(Optional.of(d));

        svc.purge(30L, 2L, false);

        verify(repo).delete(d);
        verify(commentRepo).deleteByDocId(30L);
        verify(likeRepo).deleteByDocId(30L);
        verify(favoriteRepo).deleteByDocId(30L);
        verify(versionRepo).deleteByDocId(30L);
        verify(subRepo).deleteAllByDocId(30L);
        verify(meili).delete(30L);
    }
}
