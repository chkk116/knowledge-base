/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Role;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.OrgService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/roles")
public class AdminRoleController {

    private final OrgService orgService;

    public AdminRoleController(OrgService orgService) {
        this.orgService = orgService;
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「" + code + "」权限");
        }
    }

    /** 角色列表（按排序号升序） */
    @GetMapping
    public List<Role> list(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "USER_MANAGE");
        return orgService.listRoles();
    }

    /** 各角色成员数：{ roleId: 人数 }。角色管理列表「成员」列用。
     *  注意必须走 kb_user_role 关联表统计——用户列表接口返回的实体不含 roleIds，
     *  前端拿列表数据自己数会恒为 0（2026-10-06 修的 bug）。 */
    @GetMapping("/members")
    public java.util.Map<String, Object> members(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "USER_MANAGE");
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        for (Object[] row : orgService.roleMemberCounts()) {
            out.put(String.valueOf(row[0]), row[1]);
        }
        return out;
    }

    /** 新建角色 */
    @PostMapping
    public Role create(@RequestAttribute("kbUser") TokenClaims c,
                       @RequestBody OrgService.RoleReq r) {
        requirePerm(c, "USER_MANAGE");
        return orgService.createRole(r);
    }

    /** 修改角色（系统内置角色除外） */
    @PutMapping("/{id}")
    public Role update(@RequestAttribute("kbUser") TokenClaims c,
                       @PathVariable Long id,
                       @RequestBody OrgService.RoleReq r) {
        requirePerm(c, "USER_MANAGE");
        return orgService.updateRole(id, r);
    }

    /** 删除角色（系统内置角色、仍有用户使用的角色不可删） */
    @DeleteMapping("/{id}")
    public java.util.Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c,
                                                @PathVariable Long id) {
        requirePerm(c, "USER_MANAGE");
        orgService.deleteRole(id);
        return java.util.Map.of("ok", true);
    }
}
