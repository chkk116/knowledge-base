/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 文档树一致性回归测试（F16）：父文档被软删除 / 彻底删除后，直接子文档必须提升到根级。
 * 软删除的父文档会被 @SQLRestriction 从常规查询里排除，文档树拿不到该节点；
 * 子文档若仍挂在它下面，就会整棵从树视图消失（正文还在，但没有任何入口能找到）。
 * 纯 Mockito 单元测试：不依赖数据库 / Meili / 中继。
 * 运行：mvn test -Dtest=DocumentTreeRulesTest
 */
class DocumentTreeRulesTest {

    private DocumentRepository repo;
    private UserRepository userRepo;
    private DocumentVersionRepository versionRepo;
    private DocumentService svc;
    private Document child;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(DocumentRepository.class);
        userRepo = Mockito.mock(UserRepository.class);
        versionRepo = Mockito.mock(DocumentVersionRepository.class);
        svc = new DocumentService(repo, Mockito.mock(AuthService.class), Mockito.mock(NotificationService.class),
                userRepo, Mockito.mock(MeiliSearchClient.class), versionRepo, Mockito.mock(AuditService.class),
                Mockito.mock(DocSubscriptionRepository.class), Mockito.mock(CommentRepository.class),
                Mockito.mock(LikeRepository.class), Mockito.mock(FavoriteRepository.class));

        child = new Document();
        child.setId(41L);
        child.setTitle("子文档");
        child.setParentId(40L);
        child.setStatus("PUBLISHED");
        when(repo.findByParentId(40L)).thenReturn(List.of(child));
    }

    private Document parent() {
        Document d = new Document();
        d.setId(40L);
        d.setOwnerId(2L);
        d.setStatus("PUBLISHED");
        d.setTitle("父文档");
        return d;
    }

    @Test
    @DisplayName("F16：作者自己删帖（软删除）时，子文档提升到根级，否则整棵子树从文档树消失")
    void deleteOwn_promotesChildren() {
        Document d = parent();
        when(repo.findById(40L)).thenReturn(Optional.of(d));

        svc.deleteOwn(40L, 2L);

        assertTrue(d.isDeleted(), "父文档应进入回收站");
        assertNull(child.getParentId(), "子文档必须提升到根级");
        verify(repo).saveAll(List.of(child));
    }

    @Test
    @DisplayName("F16：彻底删除（purge）前同样提升子文档，避免留下指向不存在父节点的孤儿")
    void purge_promotesChildren() {
        Document d = parent();
        when(repo.findIncludingDeletedById(40L)).thenReturn(Optional.of(d));

        svc.purge(40L, 2L, false);

        assertNull(child.getParentId(), "真删前必须先把子文档提升到根级");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Iterable<Document>> cap = ArgumentCaptor.forClass((Class) Iterable.class);
        verify(repo, atLeastOnce()).saveAll(cap.capture());
        assertTrue(cap.getAllValues().stream().flatMap(it -> java.util.stream.StreamSupport.stream(it.spliterator(), false))
                .anyMatch(x -> x.getId() == 41L), "提升动作必须包含子文档");
        verify(repo).delete(d);
    }

    @Test
    @DisplayName("非作者删除自己以外的文档应 403，且不得改动子文档归属")
    void deleteOwn_otherOwner_forbidden() {
        Document d = parent();
        when(repo.findById(40L)).thenReturn(Optional.of(d));

        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> svc.deleteOwn(40L, 999L));
        assertEquals(40L, child.getParentId(), "越权删除被拒时不得改动树结构");
    }
}
