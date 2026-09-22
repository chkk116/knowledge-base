/*
 * Copyright (c) 2026 北寻·浅光
 * 本源代码基于 AGPL-3.0 许可证开源，详见仓库根目录 LICENSE 文件。
 */

package com.kb.kbplatform.service;

import com.kb.kbplatform.domain.Department;
import com.kb.kbplatform.domain.Role;
import com.kb.kbplatform.domain.User;
import com.kb.kbplatform.domain.UserRole;
import com.kb.kbplatform.repository.DepartmentRepository;
import com.kb.kbplatform.repository.RoleRepository;
import com.kb.kbplatform.repository.UserRepository;
import com.kb.kbplatform.repository.UserRoleRepository;
import com.kb.kbplatform.security.Perm;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组织管理（角色 / 部门 / 用户-角色关联）。
 *
 * 设计要点：鉴权链完全读取 JWT 内的 permissions 快照（AdminService 已说明），
 * 因此本服务不改动 TokenClaims / AuthFilter，只负责在「发令牌」时把
 * 用户的最终有效权限（直接权限 ∪ 所有角色权限）展开进 permissions 串。
 *
 * 任何会改变用户身份的写操作（分配角色 / 改部门 / 改直接权限 / 改角色）都必须使旧 token 失效，
 * 由调用方（AdminService）统一递增 tokenVersion 完成；本服务内的 setUserRoles / setUserDepartment
 * 只做数据落库、不自行 bump tokenVersion，避免重复保存。
 */
@Service
public class OrgService {

    private final UserRoleRepository userRoleRepo;
    private final RoleRepository roleRepo;
    private final DepartmentRepository departmentRepo;
    private final UserRepository userRepo;

    public OrgService(UserRoleRepository userRoleRepo, RoleRepository roleRepo,
                      DepartmentRepository departmentRepo, UserRepository userRepo) {
        this.userRoleRepo = userRoleRepo;
        this.roleRepo = roleRepo;
        this.departmentRepo = departmentRepo;
        this.userRepo = userRepo;
    }

    // ==================== 角色 ====================

    public List<Role> listRoles() {
        return roleRepo.findAllByOrderBySortOrderAsc();
    }

    public Role getRole(Long id) {
        return roleRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("角色不存在"));
    }

    @Transactional
    public Role createRole(RoleReq r) {
        if (r.name == null || r.name.isBlank()) throw new IllegalArgumentException("角色名称不能为空");
        if (r.code == null || r.code.isBlank()) throw new IllegalArgumentException("角色编码不能为空");
        String code = r.code.trim().toUpperCase();
        if (roleRepo.findByCode(code).isPresent()) throw new IllegalArgumentException("角色编码已存在");
        if (roleRepo.findByName(r.name.trim()).isPresent()) throw new IllegalArgumentException("角色名称已存在");
        Role role = new Role();
        role.setName(r.name.trim());
        role.setCode(code);
        role.setDescription(r.description == null ? "" : r.description.trim());
        role.setPermissions(normalizePerms(r.permissions));
        role.setSortOrder(r.sortOrder == null ? 0 : r.sortOrder);
        role.setBuiltin(false);
        return roleRepo.save(role);
    }

    @Transactional
    public Role updateRole(Long id, RoleReq r) {
        Role role = getRole(id);
        if (role.isBuiltin()) throw new IllegalArgumentException("系统内置角色不可修改");
        if (r.name != null && !r.name.isBlank() && !r.name.trim().equals(role.getName())) {
            if (roleRepo.findByName(r.name.trim()).isPresent()) throw new IllegalArgumentException("角色名称已存在");
            role.setName(r.name.trim());
        }
        if (r.description != null) role.setDescription(r.description.trim());
        boolean permsChanged = false;
        if (r.permissions != null) {
            String next = normalizePerms(r.permissions);
            permsChanged = !next.equals(role.getPermissions() == null ? "" : role.getPermissions());
            role.setPermissions(next);
        }
        if (r.sortOrder != null) role.setSortOrder(r.sortOrder);
        Role saved = roleRepo.save(role);
        // 角色权限清单变化 → 该角色下所有用户的旧令牌立即失效（F03-B）。
        // 鉴权链完全信任令牌内的 permissions 快照，不 bump 的话撤权后旧令牌仍可执行已撤销操作。
        // 注意：这里是「角色级」变更，与 AdminService 处理「用户级」分配互不重复。
        if (permsChanged) {
            List<Long> affected = userRoleRepo.findUserIdsByRoleId(id);
            if (!affected.isEmpty()) userRepo.bumpTokenVersionByIds(affected);
        }
        return saved;
    }

    @Transactional
    public void deleteRole(Long id) {
        Role role = getRole(id);
        if (role.isBuiltin()) throw new IllegalArgumentException("系统内置角色不可删除");
        if (userRoleRepo.countByRoleId(id) > 0) throw new IllegalArgumentException("仍有用户使用该角色，无法删除");
        roleRepo.deleteById(id);
    }

    // ==================== 部门 ====================

    public List<Department> listDepartments() {
        return departmentRepo.findAllByOrderBySortOrderAsc();
    }

    public Department getDepartment(Long id) {
        return departmentRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("部门不存在"));
    }

    @Transactional
    public Department createDepartment(DeptReq r) {
        if (r.name == null || r.name.isBlank()) throw new IllegalArgumentException("部门名称不能为空");
        Department d = new Department();
        d.setName(r.name.trim());
        d.setParentId(r.parentId);
        d.setLeaderId(r.leaderId);
        d.setDescription(r.description == null ? "" : r.description.trim());
        d.setSortOrder(r.sortOrder == null ? 0 : r.sortOrder);
        return departmentRepo.save(d);
    }

    @Transactional
    public Department updateDepartment(Long id, DeptReq r) {
        Department d = getDepartment(id);
        if (r.name != null && !r.name.isBlank()) d.setName(r.name.trim());
        if (r.parentId != null) {
            if (id.equals(r.parentId)) throw new IllegalArgumentException("不能将部门挂到自身");
            if (isDescendant(r.parentId, id)) throw new IllegalArgumentException("不能将部门挂到其下属部门下");
            d.setParentId(r.parentId);
        }
        if (r.leaderId != null) d.setLeaderId(r.leaderId);
        if (r.description != null) d.setDescription(r.description.trim());
        if (r.sortOrder != null) d.setSortOrder(r.sortOrder);
        return departmentRepo.save(d);
    }

    @Transactional
    public void deleteDepartment(Long id) {
        if (!departmentRepo.findByParentIdOrderBySortOrderAsc(id).isEmpty()) {
            throw new IllegalArgumentException("该部门下存在子部门，无法删除（请先移除子部门）");
        }
        List<User> members = userRepo.findByDepartmentId(id);
        for (User u : members) u.setDepartmentId(null);
        userRepo.saveAll(members);
        departmentRepo.deleteById(id);
    }

    /** 判断 candidateId 是否为 ancestorId 的子孙（防止部门树出现环） */
    private boolean isDescendant(Long candidateId, Long ancestorId) {
        Department cur = departmentRepo.findById(candidateId).orElse(null);
        Set<Long> visited = new HashSet<>();
        while (cur != null && cur.getParentId() != null) {
            if (ancestorId.equals(cur.getParentId())) return true;
            if (!visited.add(cur.getId())) break;   // 防御性：理论上无环
            cur = departmentRepo.findById(cur.getParentId()).orElse(null);
        }
        return false;
    }

    /** 部门名称（登录 / me / profile 回显用）；无部门返回空串 */
    public String departmentNameOf(Long deptId) {
        if (deptId == null) return "";
        return departmentRepo.findById(deptId).map(Department::getName).orElse("");
    }

    // ==================== 部门成员 ====================

    /** 批量把用户加入部门（若原属其他部门则移动过去）；返回实际分配人数 */
    @Transactional
    public int assignMembers(Long deptId, List<Long> userIds) {
        getDepartment(deptId);
        int n = 0;
        if (userIds == null) return 0;
        for (Long uid : userIds) {
            if (uid == null) continue;
            User u = userRepo.findById(uid).orElse(null);
            if (u == null) continue;
            if (deptId.equals(u.getDepartmentId())) continue;
            u.setDepartmentId(deptId);
            userRepo.save(u);
            n++;
        }
        return n;
    }

    /** 把用户移出部门（departmentId 置空） */
    @Transactional
    public void removeMember(Long deptId, Long userId) {
        User u = userRepo.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (u.getDepartmentId() == null || !deptId.equals(u.getDepartmentId())) {
            throw new IllegalArgumentException("该用户不属于此部门");
        }
        u.setDepartmentId(null);
        userRepo.save(u);
    }

    // ==================== 用户 - 角色 / 部门 ====================

    /** 取用户已分配的角色 id 列表 */
    public List<Long> getUserRoleIds(Long userId) {
        List<Long> ids = new ArrayList<>();
        for (UserRole ur : userRoleRepo.findByUserId(userId)) ids.add(ur.getRoleId());
        return ids;
    }

    /** 整体替换用户角色（仅落库，不 bump tokenVersion，由调用方统一处理失效） */
    @Transactional
    public void setUserRoles(Long userId, List<Long> roleIds) {
        userRoleRepo.deleteByUserId(userId);
        if (roleIds == null) return;
        for (Long rid : roleIds) {
            if (rid == null) continue;
            if (roleRepo.existsById(rid)) {
                UserRole ur = new UserRole();
                ur.setUserId(userId);
                ur.setRoleId(rid);
                userRoleRepo.save(ur);
            }
        }
    }

    /** 设置用户所属部门（仅落库，不 bump tokenVersion；deptId 为 null 表示清除部门） */
    @Transactional
    public void setUserDepartment(Long userId, Long deptId) {
        User u = userRepo.findById(userId).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (deptId != null && !departmentRepo.existsById(deptId)) {
            throw new IllegalArgumentException("部门不存在");
        }
        u.setDepartmentId(deptId);
        userRepo.save(u);
    }

    // ==================== 有效权限展开 ====================

    /** 用户最终有效权限 = 直接权限 ∪ 所有已分配角色的权限（ADMIN 角色在 TokenClaims 中隐式全权，无需展开）。 */
    public Set<String> effectivePermissions(User u) {
        Set<String> set = new LinkedHashSet<>();
        if (u.getPermissions() != null && !u.getPermissions().isBlank()) {
            for (String p : u.getPermissions().split(",")) {
                String t = p.trim().toUpperCase();
                if (!t.isEmpty() && Perm.valid(t)) set.add(t);
            }
        }
        for (UserRole ur : userRoleRepo.findByUserId(u.getId())) {
            Role role = roleRepo.findById(ur.getRoleId()).orElse(null);
            if (role == null || role.getPermissions() == null || role.getPermissions().isBlank()) continue;
            for (String p : role.getPermissions().split(",")) {
                String t = p.trim().toUpperCase();
                if (!t.isEmpty() && Perm.valid(t)) set.add(t);
            }
        }
        return set;
    }

    public String effectivePermissionsString(User u) {
        return String.join(",", effectivePermissions(u));
    }

    /** 仅保留白名单内的权限码 */
    private String normalizePerms(String perms) {
        if (perms == null || perms.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : perms.split(",")) {
            String t = p.trim().toUpperCase();
            if (Perm.valid(t) && sb.indexOf(t) < 0) {
                if (sb.length() > 0) sb.append(",");
                sb.append(t);
            }
        }
        return sb.toString();
    }

    // ---- 请求 DTO ----
    public static class RoleReq {
        public String name;
        public String code;
        public String description;
        public String permissions;   // 逗号分隔的权限码
        public Integer sortOrder;
    }

    public static class DeptReq {
        public String name;
        public Long parentId;
        public Long leaderId;
        public String description;
        public Integer sortOrder;
    }
}
