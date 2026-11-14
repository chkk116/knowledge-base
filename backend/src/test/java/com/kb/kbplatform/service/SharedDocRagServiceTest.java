/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.DocShare;
import com.kb.kbplatform.repository.DocShareRepository;
import com.kb.kbplatform.repository.SharedDocumentRagRow;
import com.kb.kbplatform.repository.SharedDocumentRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 共享文档 RAG 检索的权限隔离回归：任何一条来源进入模型前都必须经过数据库当前权限复核。
 * 纯 Mockito，不依赖数据库与 Spring 上下文。
 */
class SharedDocRagServiceTest {

    private static final long USER = 7L;
    private DocShareRepository shares;
    private SharedDocumentRepository docs;
    private DocShareService docShareService;
    private SharedDocRagService svc;

    @BeforeEach
    void setUp() {
        shares = mock(DocShareRepository.class);
        docs = mock(SharedDocumentRepository.class);
        docShareService = mock(DocShareService.class);
        svc = new SharedDocRagService(shares, docs, docShareService);
    }

    private DocShare space(long id, String status) {
        DocShare s = new DocShare();
        s.setId(id);
        s.setStatus(status);
        s.setName("空间" + id);
        return s;
    }

    /** 用匿名实现而不是 mock：mock 创建嵌套在 when(...).thenReturn(...) 里会触发 Mockito 未完成打桩异常 */
    private SharedDocumentRagRow row(long shareId, long id, String title, String content) {
        return new SharedDocumentRagRow() {
            public Long getId() { return id; }
            public Long getShareId() { return shareId; }
            public String getTitle() { return title; }
            public String getContent() { return content; }
            public byte[] getYjsState() { return null; }
        };
    }

    @Test
    @DisplayName("无用户上下文一律返回空，绝不读取私有文档")
    void noUserReturnsEmpty() {
        assertTrue(svc.search("报销", null, List.of("报销"), 2).isEmpty());
        verifyNoInteractions(shares, docs, docShareService);
    }

    @Test
    @DisplayName("空间不可读时不查任何文档")
    void unreadableSpaceIsSkipped() {
        when(shares.findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(USER))
                .thenReturn(List.of(space(1, "ACTIVE")));
        when(docShareService.canReadSpace(1L, USER, false)).thenReturn(false);

        assertTrue(svc.search("报销", USER, List.of("报销"), 2).isEmpty());
        verifyNoInteractions(docs);
    }

    @Test
    @DisplayName("归档空间不参与检索，只按可读空间 id 查询")
    void archivedSpaceExcluded() {
        when(shares.findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(USER))
                .thenReturn(List.of(space(1, "ACTIVE"), space(2, "ARCHIVED")));
        when(docShareService.canReadSpace(1L, USER, false)).thenReturn(true);
        when(docs.findRagRowsByShareIdIn(anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(row(1L, 11L, "报销制度", "<p>差旅报销流程</p>")));
        when(docShareService.docPermission(1L, 11L, USER, false)).thenReturn("READ");

        List<Map<String, Object>> got = svc.search("报销", USER, List.of("报销"), 2);
        assertEquals(1, got.size());
        assertEquals("share", got.get(0).get("docType"));
        assertEquals(1L, got.get(0).get("shareId"));
        assertEquals(11L, got.get(0).get("docId"));
        // ARCHIVED 的 2 号空间不能出现在 IN 列表里
        org.mockito.ArgumentCaptor<Collection<Long>> cap = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(docs).findRagRowsByShareIdIn(cap.capture(), any(Pageable.class));
        assertEquals(List.of(1L), List.copyOf(cap.getValue()));
        verify(docShareService, never()).canReadSpace(eq(2L), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("文档级权限收窄（docPermission 为 null）时该文档不得进入模型上下文")
    void docLevelDeniedExcluded() {
        when(shares.findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(USER))
                .thenReturn(List.of(space(1, "ACTIVE")));
        when(docShareService.canReadSpace(1L, USER, false)).thenReturn(true);
        when(docs.findRagRowsByShareIdIn(anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(row(1L, 11L, "报销制度", "<p>差旅报销流程</p>")));
        when(docShareService.docPermission(1L, 11L, USER, false)).thenReturn(null);

        assertTrue(svc.search("报销", USER, List.of("报销"), 2).isEmpty());
    }

    @Test
    @DisplayName("跨空间的 docId 不在可读空间里时跳过，不串数据")
    void crossSpaceRowExcluded() {
        when(shares.findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(USER))
                .thenReturn(List.of(space(1, "ACTIVE")));
        when(docShareService.canReadSpace(1L, USER, false)).thenReturn(true);
        when(docs.findRagRowsByShareIdIn(anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(row(2L, 22L, "别组机密", "<p>报销流程</p>")));

        assertTrue(svc.search("报销", USER, List.of("报销"), 2).isEmpty());
        verify(docShareService, never()).docPermission(anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("关键词命中才返回，来源字段完整且不含内部分数字段")
    void keywordHitBuildsSource() {
        when(shares.findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(USER))
                .thenReturn(List.of(space(1, "ACTIVE")));
        when(docShareService.canReadSpace(1L, USER, false)).thenReturn(true);
        when(docs.findRagRowsByShareIdIn(anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(row(1L, 11L, "报销制度", "<p>差旅报销流程与发票要求</p>")));
        when(docShareService.docPermission(1L, 11L, USER, false)).thenReturn("READ");

        List<Map<String, Object>> got = svc.search("发票", USER, List.of("发票"), 2);
        assertEquals(1, got.size());
        Map<String, Object> s = got.get(0);
        assertTrue(String.valueOf(s.get("snippet")).contains("发票"));
        assertTrue(String.valueOf(s.get("fullText")).contains("发票"));
        assertEquals("空间1", s.get("spaceName"));
        assertFalse(s.containsKey("_score"));
        assertTrue(svc.search("无关词", USER, List.of("无关词"), 2).isEmpty());
    }

    @Test
    @DisplayName("未命中的候选不做文档级权限查询，避免扫描全表触发 N+1")
    void onlyPickedCandidatesArePermissionChecked() {
        when(shares.findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(USER))
                .thenReturn(List.of(space(1, "ACTIVE")));
        when(docShareService.canReadSpace(1L, USER, false)).thenReturn(true);
        when(docs.findRagRowsByShareIdIn(anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(
                        row(1L, 11L, "无关一", "<p>没有目标词</p>"),
                        row(1L, 12L, "无关二", "<p>也没有</p>"),
                        row(1L, 13L, "报销制度", "<p>报销流程</p>")));
        when(docShareService.docPermission(1L, 13L, USER, false)).thenReturn("READ");

        List<Map<String, Object>> got = svc.search("报销", USER, List.of("报销"), 2);
        assertEquals(1, got.size());
        verify(docShareService, times(1)).docPermission(anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("按命中词数排序并受 limit 约束")
    void sortedByScoreAndLimited() {
        when(shares.findByOwnerIdOrMemberUserIdOrderByUpdatedAtDesc(USER))
                .thenReturn(List.of(space(1, "ACTIVE")));
        when(docShareService.canReadSpace(1L, USER, false)).thenReturn(true);
        when(docs.findRagRowsByShareIdIn(anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(
                        row(1L, 11L, "报销", "<p>报销</p>"),
                        row(1L, 12L, "报销发票", "<p>报销与发票</p>"),
                        row(1L, 13L, "发票", "<p>发票</p>")));
        when(docShareService.docPermission(anyLong(), anyLong(), anyLong(), anyBoolean())).thenReturn("READ");

        List<Map<String, Object>> got = svc.search("报销 发票", USER, List.of("报销", "发票"), 2);
        assertEquals(2, got.size());
        assertEquals(12L, got.get(0).get("docId"), "命中两个词的文档应排第一");
    }
}
