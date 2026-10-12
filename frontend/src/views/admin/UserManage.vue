<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Medal, User, UserFilled, Search, Lock, Message } from '@element-plus/icons-vue'
import { listUsers, createUser, updateUser, deleteUser, toggleUser, adjustPoints, resetPassword, adminUserStorage, getUserDetail, listRoles, listDepartments } from '../../api/admin'
import PageBar from '../../components/PageBar.vue'
import { errMsg } from '../../utils/errMsg'
import { isFullAdminPerms, unionRolePerms } from '../../utils/permCodes'

// 角色/状态筛选 Tab（胶囊按钮）
const TABS = [
  { name: 'all', label: '全部' },
  { name: 'ADMIN', label: '管理员' },
  { name: 'USER', label: '普通用户' },
  { name: 'disabled', label: '已禁用' },
]
const activeTabLabel = computed(() => TABS.find(t => t.name === roleTab.value)?.label || '全部')

const users = ref([])
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)
const form = ref({ username: '', displayName: '', email: '', gender: '', role: 'USER', password: '', enabled: true, storageQuotaGb: null, roleIds: [], departmentId: null })
const storageInfo = ref(null)   // 编辑时拉取的当前用量 { used, quota }

// 角色 / 部门（编辑表单下拉用；部门树由 parentId 自组装）
const roles = ref([])
const departments = ref([])
const roleMap = computed(() => { const m = {}; roles.value.forEach(r => m[r.id] = r); return m })
const deptMap = computed(() => { const m = {}; departments.value.forEach(d => m[d.id] = d); return m })
const deptName = (id) => (id && deptMap.value[id]) ? deptMap.value[id].name : '—'
const roleNames = (ids) => (ids || []).map(id => roleMap.value[id]?.name).filter(Boolean).join('、') || '—'

// 部门下拉：展平为带缩进的列表（按 parentId 组装层级，便于一眼看清所属上级部门）
const departmentTree = computed(() => {
  const list = departments.value || []
  const byParent = {}
  list.forEach(d => { (byParent[d.parentId || 0] ||= []).push(d) })
  const out = []
  const walk = (parentId, depth) => {
    (byParent[parentId] || []).forEach(d => {
      out.push({ ...d, name: (depth ? '　'.repeat(depth) + '└ ' : '') + d.name })
      walk(d.id, depth + 1)
    })
  }
  walk(0, 0)
  return out
})
// 部门嵌套树：el-tree-select 需要 { value, label, children } 结构（原 departmentTree 是带缩进的扁平列表，供 el-select 用）
const deptTreeData = computed(() => {
  const list = departments.value || []
  const byParent = {}
  list.forEach(d => { (byParent[d.parentId || 0] ||= []).push(d) })
  const build = (parentId) => (byParent[parentId] || []).map(d => {
    const children = build(d.id)
    return { value: d.id, label: d.name, ...(children.length ? { children } : {}) }
  })
  return build(0)
})
const filterDept = (value, node) => !value || String(node.label || '').includes(value)

const GB = 1024 * 1024 * 1024
const formatGb = (b) => ((b || 0) / GB).toFixed(2) + ' GB'
const roleTab = ref('all')   // all | ADMIN | USER | disabled

// 权限统一由「角色管理」定义：用户最终权限 = 所分配角色的权限并集（ADMIN 隐式全权）。
// 用户管理只负责挂角色 / 部门，不再直接勾选细粒度权限，避免两处管理同一权限的冲突。

// 检索 / 排序 / 分页（角色 / 状态 Tab 走后端过滤，对全量生效，不再前端按当前页过滤）
const keyword = ref('')
const sort = ref('newest')
const page = ref(1)
const size = ref(15)
const total = ref(0)

// 积分调整弹窗
const pointsDialog = ref(false)
const pointsTarget = ref(null)
const pointsSaving = ref(false)
const pointsForm = ref({ delta: 0, reason: '' })

const load = async () => {
  try {
    const params = { keyword: keyword.value, sort: sort.value, page: page.value - 1, size: size.value }
    // 角色 / 状态 Tab → 后端过滤（对全量生效，解决大数据量下前端只筛当前页的缺陷）
    if (roleTab.value === 'ADMIN') params.role = 'ADMIN'
    else if (roleTab.value === 'USER') params.role = 'USER'
    else if (roleTab.value === 'disabled') params.enabled = false
    const { data } = await listUsers(params)
    users.value = data.content || []
    total.value = data.totalElements || 0
  } catch (e) {
    ElMessage.error('加载失败')
  }
}

const onSearch = () => { page.value = 1; load() }
const onSort = () => { page.value = 1; load() }
const onPage = (p) => { page.value = p; load() }
const onSize = (s) => { size.value = s; page.value = 1; load() }
const onTab = () => { page.value = 1; load() }

const openAdd = () => {
  editing.value = null
  form.value = { username: '', displayName: '', role: 'USER', password: '', enabled: true, storageQuotaGb: null, roleIds: [], departmentId: null }
  storageInfo.value = null
  dialog.value = true
}

const openEdit = (u) => {
  editing.value = u.id
  form.value = {
    username: u.username,
    displayName: u.displayName || '',
    email: u.email || '',
    gender: u.gender || '',
    role: u.role,
    password: '',
    enabled: u.enabled,
    storageQuotaGb: u.storageQuota ? Number((u.storageQuota / GB).toFixed(2)) : null,
    roleIds: [],
    departmentId: u.departmentId || null,
  }
  storageInfo.value = null
  // 拉取该用户当前存储用量，便于在弹窗内对照
  adminUserStorage(u.id).then(({ data }) => { storageInfo.value = data }).catch(() => {})
  // 拉取已分配角色（安全视图，不含密码）
  getUserDetail(u.id).then(({ data }) => {
    form.value.roleIds = Array.isArray(data.roleIds) ? data.roleIds : []
    form.value.departmentId = data.departmentId || null
  }).catch(() => {})
  dialog.value = true
}

const save = async () => {
  saving.value = true
  try {
    // 权限统一走角色：不提交 permissions（编辑时后端保留原直接权限，不再从 UI 修改）
    const roleIds = (form.value.roleIds || []).map(Number).filter(Boolean)
    const departmentId = form.value.departmentId ? Number(form.value.departmentId) : null
    if (editing.value) {
      const r = { displayName: form.value.displayName, email: form.value.email || '', gender: form.value.gender || '', role: form.value.role, enabled: form.value.enabled, roleIds, departmentId }
      if (form.value.password) r.password = form.value.password
      // 存储配额：填了 GB 转字节；0/空表示恢复系统默认 200MB
      r.storageQuota = form.value.storageQuotaGb ? Math.round(form.value.storageQuotaGb * GB) : 0
      await updateUser(editing.value, r)
      // 完全管理员判定（语义=覆盖）：所选角色权限并集覆盖全量白名单 → 该用户视同 ADMIN
      const full = isFullAdminPerms([...unionRolePerms(roleIds.map((id) => roleMap.value[id]))])
      ElMessage.success(full ? '已更新（所选角色合计为完全管理员权限，该用户视同 ADMIN）' : '已更新')
    } else {
      await createUser({ username: form.value.username, displayName: form.value.displayName, email: form.value.email || '', gender: form.value.gender || '', role: form.value.role, password: form.value.password, enabled: form.value.enabled, roleIds, departmentId })
      const full2 = isFullAdminPerms([...unionRolePerms(roleIds.map((id) => roleMap.value[id]))])
      ElMessage.success(full2 ? '已创建（所选角色合计为完全管理员权限，该用户视同 ADMIN）' : '已创建')
    }
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  } finally {
    saving.value = false
  }
}

const remove = async (u) => {
  try {
    await ElMessageBox.confirm('确定删除用户「' + u.username + '」？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteUser(u.id)
    ElMessage.success('已删除')
    await load()
    // 末页删完最后一条会停在空页码（筛选走后端，重载为空即说明这页没了）
    if (users.value.length === 0 && page.value > 1) { page.value--; await load() }
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}

const toggle = async (u) => {
  try {
    await toggleUser(u.id, !u.enabled)
    ElMessage.success(u.enabled ? '已禁用' : '已启用')
    await load()
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const openPoints = (u) => {
  pointsTarget.value = u
  pointsForm.value = { delta: 0, reason: '' }
  pointsDialog.value = true
}

const onResetPwd = async (u) => {
  try {
    await ElMessageBox.confirm('确定重置「' + u.username + '」的密码？将生成随机新密码并立即生效。', '重置密码', { type: 'warning' })
  } catch {
    return
  }
  try {
    const { data } = await resetPassword(u.id)
    ElMessageBox.alert('已生成新密码：\n\n' + data.newPassword + '\n\n请通过安全渠道转交给该用户，并提醒其尽快登录修改。', '重置成功', { type: 'success' })
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '重置失败'))
  }
}

const savePoints = async () => {
  if (!pointsForm.value.delta) {
    ElMessage.warning('请输入增减积分')
    return
  }
  pointsSaving.value = true
  try {
    await adjustPoints(pointsTarget.value.id, Number(pointsForm.value.delta), pointsForm.value.reason.trim())
    ElMessage.success('积分已调整')
    pointsDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '调整失败'))
  } finally {
    pointsSaving.value = false
  }
}

const onCmd = (cmd, u) => {
  if (cmd === 'toggle') toggle(u)
  else if (cmd === 'points') openPoints(u)
  else if (cmd === 'reset') onResetPwd(u)
  else if (cmd === 'delete') remove(u)
}

onMounted(() => { load(); loadOrg() })

// 加载角色 / 部门列表（编辑表单下拉 + 表格展示用）
const loadOrg = async () => {
  try { const { data: r } = await listRoles(); roles.value = r || [] } catch {}
  try { const { data: d } = await listDepartments(); departments.value = d || [] } catch {}
}
</script>

<template>
  <div class="um-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><UserFilled /></el-icon></div>
      <div class="head-text">
        <h2>用户管理</h2>
        <p>账号、部门与角色分配、积分与存储配额的统一管理（权限在「角色管理」中定义）</p>
      </div>
      <div class="head-stats">
        <div class="stat">
          <b>{{ total }}</b>
          <span>{{ activeTabLabel }}用户</span>
        </div>
      </div>
    </div>

    <el-card shadow="never" class="panel">
      <!-- 工具栏：胶囊筛选 + 搜索/排序/新增 -->
      <div class="toolbar">
        <div class="seg-row">
          <button v-for="t in TABS" :key="t.name"
                  class="seg-pill" :class="{ active: roleTab === t.name }"
                  @click="roleTab = t.name; onTab()">
            {{ t.label }}
          </button>
        </div>
        <div class="tool-right">
          <el-input v-model="keyword" placeholder="搜索用户名 / 昵称" clearable
                    :prefix-icon="Search" class="search-input"
                    @keyup.enter="onSearch" @clear="onSearch" />
          <el-select v-model="sort" class="sort-select" @change="onSort">
            <el-option label="最新注册" value="newest" />
            <el-option label="积分最高" value="points" />
            <el-option label="角色" value="role" />
          </el-select>
          <el-button type="primary" @click="openAdd">新增用户</el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="panel table-panel">
      <el-table v-stack-table :data="users" class="user-table">
        <!-- 用户：头像 + 用户名 + 昵称 合并展示（手机上信息密度更友好） -->
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div class="u-cell">
              <el-avatar :size="38" :src="row.avatar || undefined" class="u-avatar">
                {{ (row.displayName || row.username || '?').charAt(0) }}
              </el-avatar>
              <div class="u-meta">
                <div class="u-name">
                  {{ row.username }}
                  <span class="u-id">#{{ row.id }}</span>
                </div>
                <div class="u-nick">{{ row.displayName || '—' }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="身份 / 部门" min-width="200">
          <template #default="{ row }">
            <span class="role" :class="row.role === 'ADMIN' ? 'admin' : 'user'">
              <el-icon><component :is="row.role === 'ADMIN' ? Medal : User" /></el-icon>
              {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </span>
            <div class="u-dept u-dept-inline">{{ deptName(row.departmentId) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <span class="status" :class="row.enabled ? 'on' : 'off'">
              <i class="dot" />{{ row.enabled ? '启用' : '禁用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="points" label="积分" width="80" align="center">
          <template #default="{ row }">
            <span class="pts">{{ row.points }}</span>
          </template>
        </el-table-column>
        <el-table-column label="存储配额" width="120" class-name="stack-hide">
          <template #default="{ row }">
            <span>{{ row.storageQuota ? (row.storageQuota / GB).toFixed(1) + ' GB' : '默认 200MB' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-dropdown split-button type="primary" size="small"
                          @click="openEdit(row)" @command="(c) => onCmd(c, row)">
              编辑
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="toggle">
                    {{ row.enabled ? '禁用' : '启用' }}
                  </el-dropdown-item>
                  <el-dropdown-item command="points">调整积分</el-dropdown-item>
                  <el-dropdown-item command="reset">重置密码</el-dropdown-item>
                  <el-dropdown-item command="delete" divided style="color:#f56c6c">删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="total > 0" class="foot">
        <span class="foot-total">共 {{ total }} 人</span>
        <el-select :model-value="size" class="size-select" @change="onSize">
          <el-option v-for="s in [5, 15, 30, 50]" :key="s" :label="s + ' 条/页'" :value="s" />
        </el-select>
        <PageBar :model-value="page" :total="total" :page-size="size"
                 @update:model-value="onPage" />
      </div>
    </el-card>

    <el-dialog v-model="dialog" :title="editing ? '编辑用户信息' : '新增用户'"
               width="780px" class="user-dialog" :close-on-click-modal="false">
      <el-form label-position="top" class="uform">
        <!-- 分区一：基本信息（对齐后台 OA 风格的「上标签 + 多列网格」布局） -->
        <div class="sec">
          <div class="sec-hd"><span class="sec-bar"></span>基本信息</div>
          <div class="grid">
            <el-form-item label="姓名 / 昵称">
              <el-input v-model="form.displayName" placeholder="用于展示的名称" :prefix-icon="User" clearable />
            </el-form-item>
            <el-form-item label="用户名（登录名）">
              <el-input v-model="form.username" :disabled="!!editing" :prefix-icon="UserFilled"
                        :placeholder="editing ? '登录名不可修改' : '登录用，创建后不可改'" />
            </el-form-item>
            <el-form-item :label="editing ? '重置密码' : '密码'">
              <el-input v-model="form.password" type="password" show-password :prefix-icon="Lock"
                        :placeholder="editing ? '留空则不修改' : '至少 6 位'" />
            </el-form-item>
            <el-form-item label="性别">
              <el-radio-group v-model="form.gender" class="sex-group">
                <el-radio-button value="">保密</el-radio-button>
                <el-radio-button value="男">男</el-radio-button>
                <el-radio-button value="女">女</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="账号状态">
              <div class="status-cell">
                <el-switch v-model="form.enabled" :active-value="true" :inactive-value="false" />
                <span class="status-text" :class="{ off: !form.enabled }">
                  {{ form.enabled ? '当前为：启用' : '当前为：禁用' }}
                </span>
              </div>
            </el-form-item>
            <el-form-item label="邮箱">
              <el-input v-model="form.email" placeholder="选填，用于通知与找回" :prefix-icon="Message" clearable />
            </el-form-item>
          </div>
        </div>

        <!-- 分区二：权限与组织 -->
        <div class="sec">
          <div class="sec-hd"><span class="sec-bar"></span>权限与组织</div>
          <div class="grid">
            <el-form-item label="系统角色">
              <el-select v-model="form.role" style="width: 100%">
                <el-option label="普通用户 USER" value="USER" />
                <el-option label="管理员 ADMIN（拥有全部权限）" value="ADMIN" />
              </el-select>
            </el-form-item>
            <el-form-item label="所属部门">
              <el-tree-select v-model="form.departmentId" :data="deptTreeData" clearable check-strictly
                              :render-after-expand="false" default-expand-all
                              placeholder="未分配部门" filterable :filter-node-method="filterDept"
                              style="width: 100%" />
            </el-form-item>
            <el-form-item label="存储配额">
              <div class="quota-cell">
                <el-input-number v-model="form.storageQuotaGb" :min="0" :max="1024" :step="1" :precision="1"
                                 controls-position="right" style="width: 150px" />
                <span class="hint">GB · 0 或留空 = 默认 200MB；已用 {{ storageInfo ? formatGb(storageInfo.used) : '—' }}</span>
              </div>
            </el-form-item>
            <el-form-item label="分配角色（权限来源）" class="span-3">
              <el-select v-model="form.roleIds" multiple collapse-tags collapse-tags-tooltip
                         placeholder="选择角色，权限取所选角色的并集" style="width: 100%">
                <el-option v-for="r in roles" :key="r.id" :value="r.id" :label="r.name" />
              </el-select>
              <div class="hint block">权限统一在「角色管理」中定义；用户最终权限 = 所分配角色的权限并集（管理员隐式全权）</div>
            </el-form-item>
          </div>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 积分调整 -->
    <el-dialog v-model="pointsDialog" :title="'调整积分 · ' + (pointsTarget?.username || '')" width="400px">
      <el-form label-width="80px">
        <el-form-item label="当前积分">
          <span>{{ pointsTarget?.points ?? 0 }}</span>
        </el-form-item>
        <el-form-item label="增减">
          <el-input-number v-model="pointsForm.delta" :min="-9999" :max="9999" :step="5" />
          <span class="hint">正为加，负为扣</span>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="pointsForm.reason" placeholder="如：优秀内容奖励 / 违规扣分" maxlength="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pointsDialog = false">取消</el-button>
        <el-button type="primary" :loading="pointsSaving" @click="savePoints">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
/* ===== 部门 / 角色展示 ===== */
.u-dept { font-size: 13px; color: #334155; font-weight: 500; }
.u-dept-inline { margin-top: 4px; font-weight: 400; font-size: 12px; color: #94a3b8; }
.u-roles { margin-top: 4px; display: flex; flex-wrap: wrap; gap: 4px; align-items: center; }
.role-tag { font-weight: 500; }
.muted { font-size: 12px; color: #94a3b8; }

/* ===== 页头 ===== */
.page-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.head-icon {
  width: 46px; height: 46px;
  border-radius: 12px;
  display: flex; align-items: center; justify-content: center;
  background: #e0e7ff;
  color: #4f46e5;
  flex-shrink: 0;
}
.head-text { flex: 1; min-width: 220px; }
.head-text h2 { margin: 0; font-size: 20px; font-weight: 700; color: #1e293b; }
.head-text p { margin: 4px 0 0; font-size: 13px; color: #94a3b8; }
.head-stats { display: flex; gap: 22px; margin-left: auto; }
.stat { text-align: center; min-width: 48px; }
.stat b {
  display: block; font-size: 22px; line-height: 1.1;
  font-weight: 700; color: #4f46e5;
  font-variant-numeric: tabular-nums;
}
.stat span { font-size: 12px; color: #94a3b8; }

/* ===== 面板（分区圆角卡片：工具栏、表格各自独立一框） ===== */
.panel { border-radius: 12px; border-color: #eef1f6; margin-bottom: 16px; }
.panel :deep(.el-card__body) { padding: 16px 18px; }
.table-panel :deep(.el-card__body) { padding: 4px 4px 12px; }

/* 工具栏（独立卡片内，无需再画分隔虚线） */
.toolbar {
  display: flex; align-items: center; gap: 12px;
  flex-wrap: wrap;
}
.seg-row {
  display: inline-flex; gap: 4px;
  background: #f1f5f9;
  border-radius: 999px;
  padding: 3px;
}
.seg-pill {
  border: none; background: transparent;
  padding: 5px 16px;
  border-radius: 999px;
  font-size: 13px; color: #64748b;
  cursor: pointer;
  transition: .15s;
  white-space: nowrap;
}
.seg-pill:hover { color: #4f46e5; }
.seg-pill.active {
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(79, 70, 229, .3);
}
.tool-right { margin-left: auto; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.search-input { width: 210px; }
.sort-select { width: 118px; }

/* ===== 表格 ===== */
.user-table { --el-table-border-color: transparent; }
.u-cell { display: flex; align-items: center; gap: 10px; }
.u-meta { min-width: 0; }
.u-name {
  font-weight: 600; font-size: 14px; color: #1e293b;
  display: flex; align-items: baseline; gap: 6px;
}
.u-id { font-size: 11px; color: #94a3b8; font-weight: 400; font-variant-numeric: tabular-nums; }
.u-nick {
  font-size: 12px; color: #94a3b8;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.role { display: inline-flex; align-items: center; gap: 4px; font-weight: 500; }
.role.admin { color: #f59e0b; }
.role.user { color: #6366f1; }
.role .el-icon { font-size: 15px; }
.perm-tag { margin-left: 6px; }

.status { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; }
.status .dot { width: 7px; height: 7px; border-radius: 50%; }
.status.on { color: #16a34a; }
.status.on .dot { background: #22c55e; box-shadow: 0 0 0 3px rgba(34, 197, 94, .15); }
.status.off { color: #d97706; }
.status.off .dot { background: #f59e0b; box-shadow: 0 0 0 3px rgba(245, 158, 11, .15); }

.pts { font-weight: 600; font-variant-numeric: tabular-nums; color: #4f46e5; }

/* ===== 页脚分页 ===== */
.foot {
  display: flex; align-items: center; gap: 12px;
  margin-top: 14px; padding-top: 12px;
  border-top: 1px dashed #e2e8f0;
}
.foot-total { font-size: 12px; color: #94a3b8; flex-shrink: 0; }
.size-select { width: 110px; flex-shrink: 0; }

.hint { color: #94a3b8; font-size: 12px; margin-left: 8px; }

/* 细粒度权限：分组 + 两列网格对齐（弹窗内），防溢出 */
.user-dialog :deep(.el-dialog__body) { max-height: 72vh; overflow-y: auto; }
.perm-item :deep(.el-form-item__label) { white-space: nowrap; }
.perm-item :deep(.el-form-item__content) { flex: 1; min-width: 0; }
.perm-groups { display: block; width: 100%; }
.perm-group { margin-bottom: 2px; }
.perm-group-title { font-size: 12px; font-weight: 600; color: var(--el-text-color-secondary); margin: 8px 0 6px; }
.perm-group:first-child .perm-group-title { margin-top: 0; }
.perm-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 6px 10px; }
.perm-grid > * { min-width: 0; }
.perm-grid :deep(.el-checkbox.is-bordered) {
  width: 100%; margin-right: 0; box-sizing: border-box;
  display: flex; align-items: center; padding: 4px 10px;
  height: auto; min-height: 30px;
}
.perm-grid :deep(.el-checkbox__label) {
  font-size: 12px; flex: 1; min-width: 0;
  white-space: normal; overflow-wrap: anywhere; line-height: 1.25;
}
.perm-hint { margin-left: 0; margin-top: 6px; }

/* ===== 暗色模式 ===== */
html.dark .head-text h2 { color: #e7e9ee; }
html.dark .head-text p { color: #7c8290; }
html.dark .panel { border-color: #2c2c36; background: #1c1c26; }
html.dark .toolbar { border-bottom-color: #2c2c36; }
html.dark .seg-row { background: #232330; }
html.dark .seg-pill { color: #7c8290; }
html.dark .seg-pill:hover { color: #a5b4fc; }
html.dark .u-name { color: #e7e9ee; }
html.dark .foot { border-top-color: #2c2c36; }
html.dark .hint { color: var(--el-text-color-secondary); }
html.dark .role.admin { color: #fbbf24; }
html.dark .role.user { color: #a5b4fc; }

/* ============================================================
   响应式布局（断点统一：≤1024 平板 / ≤640 手机）
   ============================================================ */
@media (max-width: 1024px) {
  .toolbar { gap: 8px; }
  .search-input { width: 180px; }
}

@media (max-width: 640px) {
  /* 搜索框 / 排序下拉占满整行 */
  .search-input, .sort-select { width: 100% !important; }
  .tool-right { width: 100%; }
  .tool-right :deep(.el-select) { width: 100% !important; }
  /* 细粒度权限：两列 → 单列，避免勾选框文字被压成竖排 */
  .perm-grid { grid-template-columns: 1fr; }
  /* 表格列多：≤640 由全局 v-stack-table 转成卡片堆叠 */
  .perm-tag { margin: 2px 6px 2px 0; }
  .hint { margin-left: 0; margin-top: 6px; }
  .foot { flex-wrap: wrap; }
}

/* ================= 编辑用户弹窗：OA 风格表单（上标签 + 三列网格 + 分区标题） ================= */
.uform :deep(.el-form-item) { margin-bottom: 14px; }
.uform :deep(.el-form-item__label) {
  font-size: 12.5px; font-weight: 500; color: var(--el-text-color-secondary);
  padding: 0 0 5px; line-height: 1.4;
}
.grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0 18px; }
.grid > * { min-width: 0; }
.grid .span-3 { grid-column: span 3; }
.sec + .sec { margin-top: 4px; padding-top: 16px; border-top: 1px dashed var(--el-border-color-lighter); }
.sec-hd {
  display: flex; align-items: center; gap: 8px; margin: 2px 0 14px;
  font-size: 13px; font-weight: 600; color: var(--el-text-color-primary);
}
.sec-bar { width: 3px; height: 14px; border-radius: 2px; background: var(--el-color-primary); }
/* 输入类控件统一圆角，贴近参考图的柔和观感 */
.uform :deep(.el-input__wrapper),
.uform :deep(.el-select__wrapper),
.uform :deep(.el-textarea__inner),
.uform :deep(.el-input-number .el-input__wrapper) { border-radius: 8px; }
.uform :deep(.el-radio-button__inner) { padding: 8px 15px; }
.status-cell { display: flex; align-items: center; gap: 8px; height: 32px; }
.status-text { font-size: 12.5px; color: #0f6e56; }
.status-text.off { color: #c0392b; }
html.dark .status-text { color: #34d399; }
html.dark .status-text.off { color: #f87171; }
.quota-cell { display: flex; align-items: center; gap: 8px; width: 100%; }
.hint.block { display: block; margin: 6px 0 0; line-height: 1.6; }
@media (max-width: 1024px) {
  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .grid .span-3 { grid-column: span 2; }
  .user-dialog { width: 92% !important; }
}
@media (max-width: 640px) {
  .grid { grid-template-columns: 1fr; }
  .grid .span-3 { grid-column: span 1; }
  .user-dialog { width: 96% !important; }
}
html.dark .head-icon { background: rgb(79 70 229 / .2); color: #a5b4fc; }
</style>
