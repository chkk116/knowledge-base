/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */
package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Department;
import com.kb.kbplatform.repository.DepartmentRepository;
import com.kb.kbplatform.repository.RoleRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.repository.UserRoleRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 批量导入里的部门索引回归：名称/编码归一、同名歧义、停用过滤。 */
class OrgServiceTest {

    private DepartmentRepository deps;
    private OrgService svc;

    @BeforeEach
    void setUp() {
        deps = mock(DepartmentRepository.class);
        svc = new OrgService(mock(UserRoleRepository.class), mock(RoleRepository.class),
                deps, mock(UserRepository.class));
    }

    private Department dept(long id, String name, String code, String status) {
        Department d = new Department();
        d.setId(id);
        d.setName(name);
        d.setCode(code);
        d.setStatus(status);
        return d;
    }

    @Test
    @DisplayName("部门名称与编码都可命中，忽略大小写与首尾空白")
    void nameAndCodeLookup() {
        when(deps.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                dept(1L, "研发部", "RD", "ACTIVE"),
                dept(2L, "市场部", "MKT", "ACTIVE")));

        Map<String, Long> idx = svc.departmentLookup();
        assertEquals(1L, idx.get("研发部"));
        assertEquals(1L, idx.get("rd"));
        assertEquals(1L, idx.get(OrgService.lookupKey("  RD  ")), "导入侧也按同一规则归一化后再查索引");
        assertEquals(2L, idx.get("市场部"));
        assertEquals(4, idx.size());
    }

    @Test
    @DisplayName("同名部门不进名称索引，但各自的编码仍可精确定位")
    void ambiguousNameSkippedButCodeWorks() {
        when(deps.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                dept(1L, "财务部", "FIN-A", "ACTIVE"),
                dept(2L, "财务部", "FIN-B", "ACTIVE")));

        Map<String, Long> idx = svc.departmentLookup();
        assertNull(idx.get("财务部"), "同名时不能随便选一个");
        assertEquals(1L, idx.get("fin-a"));
        assertEquals(2L, idx.get("fin-b"));
    }

    @Test
    @DisplayName("停用部门不参与，空表返回空索引")
    void inactiveAndEmpty() {
        when(deps.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                dept(1L, "已撤销", "OLD", "DISABLED")));
        assertTrue(svc.departmentLookup().isEmpty());

        when(deps.findAllByOrderBySortOrderAsc()).thenReturn(List.of());
        assertTrue(svc.departmentLookup().isEmpty());
    }

    @Test
    @DisplayName("编码无唯一约束，重复编码也剔除，导入会明确报错而不是静默错分部门")
    void duplicateCodeIsDropped() {
        when(deps.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                dept(1L, "研发一部", "RD", "ACTIVE"),
                dept(2L, "研发二部", "rd", "ACTIVE")));

        Map<String, Long> idx = svc.departmentLookup();
        assertNull(idx.get("rd"), "两个部门共用编码时不能随便选一个");
        assertEquals(1L, idx.get("研发一部"));
        assertEquals(2L, idx.get("研发二部"));
    }

    @Test
    @DisplayName("编码与另一部门名称冲突时以编码为准")
    void codeTakesPrecedence() {
        when(deps.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                dept(1L, "RD", "TEAM-A", "ACTIVE"),
                dept(2L, "研发部", "rd", "ACTIVE")));

        Map<String, Long> idx = svc.departmentLookup();
        assertEquals(2L, idx.get("rd"), "编码优先于同名 key");
    }

    @Test
    @DisplayName("导入值的归一化与索引一致")
    void lookupKeyNormalizes() {
        assertEquals("研发部", OrgService.lookupKey("  研发部  "));
        assertEquals("rd", OrgService.lookupKey(" RD "));
        assertEquals("", OrgService.lookupKey(null));
    }
}
