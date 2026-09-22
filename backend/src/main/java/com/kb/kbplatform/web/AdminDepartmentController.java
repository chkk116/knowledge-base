/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.web;

import com.kb.kbplatform.domain.Department;
import com.kb.kbplatform.security.TokenClaims;
import com.kb.kbplatform.service.OrgService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@RequestMapping("/api/admin/departments")
public class AdminDepartmentController {

    private final OrgService orgService;

    public AdminDepartmentController(OrgService orgService) {
        this.orgService = orgService;
    }

    private void requirePerm(TokenClaims c, String code) {
        if (c == null || !c.hasPerm(code)) {
            throw new ResponseStatusException(FORBIDDEN, "需要「" + code + "」权限");
        }
    }

    /** 部门列表（按排序号升序，前端按 parentId 自组装成树） */
    @GetMapping
    public List<Department> list(@RequestAttribute("kbUser") TokenClaims c) {
        requirePerm(c, "USER_MANAGE");
        return orgService.listDepartments();
    }

    /** 新建部门 */
    @PostMapping
    public Department create(@RequestAttribute("kbUser") TokenClaims c,
                             @RequestBody OrgService.DeptReq r) {
        requirePerm(c, "USER_MANAGE");
        return orgService.createDepartment(r);
    }

    /** 修改部门 */
    @PutMapping("/{id}")
    public Department update(@RequestAttribute("kbUser") TokenClaims c,
                            @PathVariable Long id,
                            @RequestBody OrgService.DeptReq r) {
        requirePerm(c, "USER_MANAGE");
        return orgService.updateDepartment(id, r);
    }

    /** 删除部门（存在子部门或仍有成员时不可删） */
    @DeleteMapping("/{id}")
    public java.util.Map<String, Object> delete(@RequestAttribute("kbUser") TokenClaims c,
                                                @PathVariable Long id) {
        requirePerm(c, "USER_MANAGE");
        orgService.deleteDepartment(id);
        return java.util.Map.of("ok", true);
    }

    /** 批量加入成员：{ userIds: [..] }（原属其他部门的用户会被移动过来） */
    @PostMapping("/{id}/members")
    public java.util.Map<String, Object> addMembers(@RequestAttribute("kbUser") TokenClaims c,
                                                    @PathVariable Long id,
                                                    @RequestBody java.util.Map<String, List<Long>> body) {
        requirePerm(c, "USER_MANAGE");
        int n = orgService.assignMembers(id, body == null ? null : body.get("userIds"));
        return java.util.Map.of("ok", true, "count", n);
    }

    /** 移出单个成员 */
    @DeleteMapping("/{id}/members/{userId}")
    public java.util.Map<String, Object> removeMember(@RequestAttribute("kbUser") TokenClaims c,
                                                      @PathVariable Long id,
                                                      @PathVariable Long userId) {
        requirePerm(c, "USER_MANAGE");
        orgService.removeMember(id, userId);
        return java.util.Map.of("ok", true);
    }
}
