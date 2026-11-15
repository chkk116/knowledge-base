/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Document;
import com.kb.kbplatform.repository.DocumentRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 从 DocumentService 拆出的发现能力回归：行为与原实现保持一致。纯 Mockito，不依赖数据库。 */
class DocumentDiscoveryServiceTest {

    private DocumentRepository repo;
    private DocumentDiscoveryService svc;

    @BeforeEach
    void setUp() {
        repo = mock(DocumentRepository.class);
        svc = new DocumentDiscoveryService(repo);
    }

    private Document doc(long id, String title, String category, String tags) {
        Document d = new Document();
        d.setId(id);
        d.setTitle(title);
        d.setCategory(category);
        d.setTags(tags);
        return d;
    }

    private Object[] row(long id, String title, String category, String tags, int views) {
        return new Object[]{id, title, category, tags, views};
    }

    @Test
    @DisplayName("分类去重、去空白、去空串且保序")
    void categoriesNormalized() {
        when(repo.findDistinctCategories()).thenReturn(List.of(" 技术 ", "", "技术", " 产品 ", "  "));
        assertEquals(List.of("技术", "产品"), svc.categories());
    }

    @Test
    @DisplayName("标签聚合去重计数并按计数倒序")
    void allTagsCountsAndSorts() {
        when(repo.findAllTagsRaw()).thenReturn(java.util.Arrays.asList("a,b", " a ,", null, "b", "c,b"));
        List<Map<String, Object>> tags = svc.allTags();
        assertEquals("b", tags.get(0).get("tag"));
        assertEquals(3, tags.get(0).get("count"));
        assertEquals("a", tags.get(1).get("tag"));
        assertEquals(2, tags.get(1).get("count"));
        assertEquals("c", tags.get(2).get("tag"));
        assertEquals(1, tags.get(2).get("count"));
    }

    @Test
    @DisplayName("按标签筛选：hot 走热门查询，其余走普通查询")
    void listByTagRoutes() {
        Pageable p = PageRequest.of(0, 10);
        svc.listByTag(p, "hot", "java");
        svc.listByTag(p, "latest", "java");
        verify(repo).findByTagHot("java", p);
        verify(repo).findByTag("java", p);
    }

    @Test
    @DisplayName("相关文档按同分类 + 标签重叠打分排序，且只回轻量字段")
    void relatedDocsScoresAndClips() {
        Document src = doc(1L, "源文档", "技术", "java,vue");
        when(repo.findById(1L)).thenReturn(Optional.of(src));
        when(repo.findRelatedCandidates(eq(1L), eq("技术"), any(Pageable.class)))
                .thenReturn(java.util.List.<Object[]>of(
                        row(2L, "只同分类", "技术", "go", 10),
                        row(3L, "同分类两标签", "技术", "java,vue", 5),
                        row(4L, "无相关", "生活", "美食", 999)));
        when(repo.findPopularExcept(eq(1L), anyList(), any(Pageable.class))).thenReturn(java.util.List.<Object[]>of());

        List<Document> got = svc.relatedDocs(1L, 6);
        assertEquals(2, got.size(), "零分的候选不入选");
        assertEquals(3L, got.get(0).getId(), "两标签重叠（2+6=8）排第一");
        assertEquals(2L, got.get(1).getId(), "只同分类（+2）排第二");
        assertNull(got.get(0).getContent(), "只回列表字段，不带正文");
    }

    @Test
    @DisplayName("相关文档不足时用高浏览文档补位，且不混入已选与自身")
    void relatedDocsFillsWithPopular() {
        Document src = doc(1L, "源文档", "技术", "java");
        when(repo.findById(1L)).thenReturn(Optional.of(src));
        when(repo.findRelatedCandidates(eq(1L), eq("技术"), any(Pageable.class)))
                .thenReturn(java.util.List.<Object[]>of(row(2L, "相关", "技术", "java", 10)));
        when(repo.findPopularExcept(eq(1L), anyList(), any(Pageable.class)))
                .thenReturn(java.util.List.<Object[]>of(row(9L, "热门补位", "生活", "x", 500)));

        List<Document> got = svc.relatedDocs(1L, 3);
        assertEquals(2, got.size());
        assertEquals(9L, got.get(1).getId());
        org.mockito.ArgumentCaptor<List<Long>> excluded = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(repo).findPopularExcept(eq(1L), excluded.capture(), any(Pageable.class));
        assertTrue(excluded.getValue().contains(1L));
        assertTrue(excluded.getValue().contains(2L));
    }

    @Test
    @DisplayName("源文档不存在或 id 为空返回空；无分类无标签时不产生得分")
    void relatedDocsEdgeCases() {
        assertTrue(svc.relatedDocs(null, 6).isEmpty());
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertTrue(svc.relatedDocs(99L, 6).isEmpty());
        verify(repo, never()).findRelatedCandidates(any(), any(), any());

        Document src = doc(1L, "源文档", null, "");
        when(repo.findById(1L)).thenReturn(Optional.of(src));
        when(repo.findRelatedCandidates(eq(1L), isNull(), any(Pageable.class)))
                .thenReturn(java.util.List.<Object[]>of(row(2L, "无分类全站", null, "", 1)));
        when(repo.findPopularExcept(eq(1L), anyList(), any(Pageable.class))).thenReturn(java.util.List.<Object[]>of());
        assertEquals(0, svc.relatedDocs(1L, 100).size(), "无分类且无标签时不产生得分");
    }
}
