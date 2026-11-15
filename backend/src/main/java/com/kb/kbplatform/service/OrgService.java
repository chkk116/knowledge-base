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

    /** 各角色成员数（[roleId, count] 行）：角色管理列表展示用。
     *  必须走 kb_user_role 关联表——用户列表接口返回的实体不含 roleIds。 */
    public List<Object[]> roleMemberCounts() {
        return userRoleRepo.countGroupByRole();
    }

    public List<Role> listRoles() {
        return roleRepo.findAllByOrderBySortOrderAsc();
    }

    public Role getRole(Long id) {
        return roleRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("角色不存在"));
    }

    @Transactional(rollbackFor = Exception.class)
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

    @Transactional(rollbackFor = Exception.class)
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

    @Transactional(rollbackFor = Exception.class)
    public void deleteRole(Long id) {
        Role role = getRole(id);
        if (role.isBuiltin()) throw new IllegalArgumentException("系统内置角色不可删除");
        if (userRoleRepo.countByRoleId(id) > 0) throw new IllegalArgumentException("仍有用户使用该角色，无法删除");
        roleRepo.deleteById(id);
    }

    // ==================== 部门 ====================

    /** 部门树深度上限（含顶级）。参考 HRMS 的深度约束，避免无限嵌套导致展示与选择困难。 */
    private static final int MAX_DEPT_DEPTH = 5;

    /**
     * 批量导入用户用：一次查出全部启用部门，构建「名称 / 编码（归一化）→ 部门 id」索引。
     * 名称或编码出现重复时都不放进索引（避免误匹配、静默把用户分到任意一个部门），
     * 调用方会把这种输入报成「部门不存在或已停用」，由管理员改用唯一标识；停用部门不参与。
     * 调用方在循环外取一次即可，避免逐行查库。
     */
    public Map<String, Long> departmentLookup() {
        Map<String, Long> byName = new LinkedHashMap<>();
        Map<String, Long> byCode = new LinkedHashMap<>();
        Map<String, Long> out = new LinkedHashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        Set<String> ambiguousCodes = new HashSet<>();
        for (Department d : departmentRepo.findAllByOrderBySortOrderAsc()) {
            if (d.getId() == null || !"ACTIVE".equalsIgnoreCase(String.valueOf(d.getStatus()))) continue;
            String name = lookupKey(d.getName());
            String code = lookupKey(d.getCode());
            if (!name.isEmpty()) {
                if (byName.containsKey(name)) ambiguousNames.add(name);
                else byName.put(name, d.getId());
            }
            // Department.code 没有唯一约束，两个部门可能共用编码；重复时同样剔除，
            // 否则导入会静默选 sortOrder 最后一个，用户被错分部门且没有任何提示
            if (!code.isEmpty()) {
                if (byCode.containsKey(code)) ambiguousCodes.add(code);
                else byCode.put(code, d.getId());
            }
        }
        for (Map.Entry<String, Long> e : byCode.entrySet()) {   // 编码优先
            if (!ambiguousCodes.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Long> e : byName.entrySet()) {
            if (!ambiguousNames.contains(e.getKey())) out.putIfAbsent(e.getKey(), e.getValue());
        }
        return out;
    }

    /** 部门名/编码与导入文件里的写法归一化：去首尾空白、忽略大小写 */
    public static String lookupKey(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 全部部门（按排序号升序），并注入展示用派生字段：
     * directCount（直属人数）/ totalCount（含下级人数）/ path（层级路径）。
     * 人数用一条 GROUP BY 聚合取回，避免逐部门查库。
     */

    public List<Department> listDepartments() {
        List<Department> all = departmentRepo.findAllByOrderBySortOrderAsc();
        Map<Long, Integer> direct = new LinkedHashMap<>();
        for (Object[] row : userRepo.countGroupedByDepartmentId()) {
            if (row[0] == null) continue;
            direct.put(((Number) row[0]).longValue(), row[1] == null ? 0 : ((Number) row[1]).intValue());
        }
        Map<Long, Department> byId = new LinkedHashMap<>();
        for (Department d : all) byId.put(d.getId(), d);
        for (Department d : all) {
            d.setDirectCount(direct.getOrDefault(d.getId(), 0));
            d.setPath(buildPath(d, byId));
        }
        // 含下级人数：自底向上累加（parentId 邻接表，按 id 遍历一次即可，深度有限不会栈溢出）
        for (Department d : all) {
            int total = d.getDirectCount();
            for (Department child : all) {
                if (isDescendantOf(child, d.getId(), byId)) total += child.getDirectCount();
            }
            d.setTotalCount(total);
        }
        return all;
    }

    private String buildPath(Department d, Map<Long, Department> byId) {
        java.util.LinkedList<String> parts = new java.util.LinkedList<>();
        Department cur = d;
        Set<Long> guard = new HashSet<>();
        while (cur != null && guard.add(cur.getId())) {
            parts.addFirst(cur.getName());
            cur = cur.getParentId() == null ? null : byId.get(cur.getParentId());
        }
        return String.join(" / ", parts);
    }

    /** candidate 是否位于 ancestorId 之下（不含自身） */
    private boolean isDescendantOf(Department candidate, Long ancestorId, Map<Long, Department> byId) {
        Department cur = candidate.getParentId() == null ? null : byId.get(candidate.getParentId());
        Set<Long> guard = new HashSet<>();
        while (cur != null && guard.add(cur.getId())) {
            if (ancestorId.equals(cur.getId())) return true;
            cur = cur.getParentId() == null ? null : byId.get(cur.getParentId());
        }
        return false;
    }

    /** 某部门的层级深度（顶级 = 1） */
    private int depthOf(Long deptId, Map<Long, Department> byId) {
        int depth = 1;
        Department cur = byId.get(deptId);
        Set<Long> guard = new HashSet<>();
        while (cur != null && cur.getParentId() != null && guard.add(cur.getId())) {
            depth++;
            cur = byId.get(cur.getParentId());
        }
        return depth;
    }

    /** 以 deptId 为根的子树最大相对深度（自身 = 1） */
    private int subtreeDepth(Long deptId, List<Department> all) {
        int max = 1;
        for (Department d : all) {
            if (d.getParentId() != null && d.getParentId().equals(deptId)) {
                max = Math.max(max, 1 + subtreeDepth(d.getId(), all));
            }
        }
        return max;
    }

    public Department getDepartment(Long id) {
        return departmentRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("部门不存在"));
    }

    @Transactional(rollbackFor = Exception.class)
    public Department createDepartment(DeptReq r) {
        if (r.name == null || r.name.isBlank()) throw new IllegalArgumentException("部门名称不能为空");
        Department d = new Department();
        d.setName(r.name.trim());
        d.setParentId(r.parentId);
        d.setLeaderId(r.leaderId);
        d.setCode(normalizeCode(r.code));
        d.setStatus(normalizeStatus(r.status));
        d.setDescription(r.description == null ? "" : r.description.trim());
        d.setSortOrder(r.sortOrder == null ? 0 : r.sortOrder);
        return departmentRepo.save(d);
    }

    @Transactional(rollbackFor = Exception.class)
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
        // 编码与状态允许显式清空 / 切换（传空串即清空编码，状态仅接受 ACTIVE / DISABLED）
        if (r.code != null) d.setCode(normalizeCode(r.code));
        if (r.status != null) d.setStatus(normalizeStatus(r.status));
        return departmentRepo.save(d);
    }

    /**
     * 移动部门（拖拽改层级与排序）：设置新的上级与在同级中的位置。
     * 校验：不能挂到自身或其子孙下（防成环）、移动后子树不得超出深度上限。
     * 落库后把新父节点下的子部门 sortOrder 重排为 0..n-1，避免并列排序号带来的顺序抖动。
     */
    @Transactional(rollbackFor = Exception.class)
    public Department moveDepartment(Long id, Long newParentId, Integer newSortOrder) {
        Department d = getDepartment(id);
        if (newParentId != null) {
            if (id.equals(newParentId)) throw new IllegalArgumentException("不能将部门挂到自身");
            if (isDescendant(newParentId, id)) throw new IllegalArgumentException("不能将部门挂到其下属部门下");
            getDepartment(newParentId);   // 目标上级必须存在
        }
        List<Department> all = departmentRepo.findAllByOrderBySortOrderAsc();
        Map<Long, Department> byId = new LinkedHashMap<>();
        for (Department x : all) byId.put(x.getId(), x);

        int parentDepth = newParentId == null ? 0 : depthOf(newParentId, byId);
        int subtree = subtreeDepth(id, all);
        if (parentDepth + subtree > MAX_DEPT_DEPTH) {
            throw new IllegalArgumentException("移动后层级将超过 " + MAX_DEPT_DEPTH + " 层，请调整目标位置");
        }

        d.setParentId(newParentId);
        d.setSortOrder(newSortOrder == null ? 0 : newSortOrder);
        departmentRepo.save(d);

        // 重排新父节点下的顺序：把被移动的部门插到指定下标，其余顺延
        List<Department> siblings = new ArrayList<>(
                newParentId == null ? departmentRepo.findByParentIdIsNullOrderBySortOrderAsc()
                        : departmentRepo.findByParentIdOrderBySortOrderAsc(newParentId));
        siblings.removeIf(s -> s.getId().equals(id));
        int idx = Math.max(0, Math.min(newSortOrder == null ? siblings.size() : newSortOrder, siblings.size()));
        siblings.add(idx, d);
        for (int i = 0; i < siblings.size(); i++) siblings.get(i).setSortOrder(i);
        departmentRepo.saveAll(siblings);
        return d;
    }

    private String normalizeCode(String code) {
        if (code == null) return null;
        String c = code.trim();
        return c.isEmpty() ? null : (c.length() > 50 ? c.substring(0, 50) : c);
    }

    private String normalizeStatus(String status) {
        if (status == null) return "ACTIVE";
        return "DISABLED".equalsIgnoreCase(status.trim()) ? "DISABLED" : "ACTIVE";
    }

    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
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
        public String code;
        public String status;      // ACTIVE / DISABLED
    }
}
