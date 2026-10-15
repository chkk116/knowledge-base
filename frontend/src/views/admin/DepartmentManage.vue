<script setup>
/**
 * 部门管理（组织架构）
 *
 * 借鉴 HRMS 组织架构的几处实用设计（2026-10-15）：
 *  - 树节点可直接拖拽调整层级与排序（后端校验防成环 + 层级上限）
 *  - 关键字过滤树 + 全部展开 / 全部收起
 *  - 部门编码与启用状态；详情显示层级路径
 *  - 人数区分「直属」与「含下级」，便于看整个中心的人数
 *  - 成员列表默认只显示前 10 人，人多时不撑爆页面
 */
import { ref, onMounted, computed, nextTick, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, OfficeBuilding, User, Remove, Refresh, Search } from '@element-plus/icons-vue'
import {
  listDepartments, createDepartment, updateDepartment, deleteDepartment, moveDepartment,
  listUsers, assignDepartmentMembers, removeDepartmentMember,
} from '../../api/admin'
import { errMsg } from '../../utils/errMsg'

const departments = ref([])
const users = ref([])          // 全量用户（用于成员数 / 成员列表 / 选人）；用户量大时以 1000 为上限
const loading = ref(false)
const dialog = ref(false)
const editing = ref(null)      // 正在编辑的部门 id
const saving = ref(false)
const form = ref({ name: '', code: '', parentId: null, leaderId: null, description: '', sortOrder: 0, status: 'ACTIVE' })

const selectedId = ref(null)
const selectedDept = computed(() => departments.value.find(d => d.id === selectedId.value) || null)

const filterText = ref('')
const treeRef = ref(null)
const MEMBER_PREVIEW = 10
const memberExpanded = ref(false)

const userName = (u) => (u.displayName && u.displayName !== u.username ? u.displayName + ' (@' + u.username + ')' : '@' + u.username)
const userMap = computed(() => { const m = {}; (users.value || []).forEach(u => { m[u.id] = u }); return m })
const leaderName = (id) => (id && userMap.value[id]) ? userName(userMap.value[id]) : (id ? '用户 #' + id : '—')

// 部门树：按 parentId 组装层级，同级按 sortOrder 升序
const tree = computed(() => {
  const list = departments.value || []
  const byParent = {}
  list.forEach(d => { (byParent[d.parentId || 0] ||= []).push(d) })
  Object.values(byParent).forEach(arr => arr.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0)))
  const build = (parentId) => (byParent[parentId] || []).map(d => ({
    id: d.id, name: d.name, code: d.code, status: d.status || 'ACTIVE',
    directCount: d.directCount || 0, totalCount: d.totalCount || 0,
    children: build(d.id),
  }))
  return build(0)
})

const membersOf = (deptId) => (users.value || []).filter(u => u.departmentId === deptId)
const memberCount = (deptId) => membersOf(deptId).length
const shownMembers = computed(() => {
  const list = selectedDept.value ? membersOf(selectedDept.value.id) : []
  return memberExpanded.value ? list : list.slice(0, MEMBER_PREVIEW)
})
const hiddenMemberCount = computed(() => {
  if (!selectedDept.value) return 0
  return Math.max(0, memberCount(selectedDept.value.id) - MEMBER_PREVIEW)
})

// 下拉可选父部门（排除自身及其子孙，避免成环），带层级缩进
const parentOptions = computed(() => {
  const list = departments.value || []
  const selfId = editing.value
  const forbidden = new Set()
  if (selfId) {
    forbidden.add(selfId)
    const byParent = {}
    list.forEach(d => { (byParent[d.parentId || 0] ||= []).push(d) })
    const walk = (id) => (byParent[id] || []).forEach(c => { forbidden.add(c.id); walk(c.id) })
    walk(selfId)
  }
  const byParent = {}
  list.forEach(d => { (byParent[d.parentId || 0] ||= []).push(d) })
  const out = []
  const walk2 = (parentId, depth) => (byParent[parentId] || []).forEach(d => {
    if (!forbidden.has(d.id)) out.push({ id: d.id, label: '　'.repeat(depth) + d.name })
    walk2(d.id, depth + 1)
  })
  walk2(0, 0)
  return out
})

const load = async () => {
  loading.value = true
  try {
    const [d, u] = await Promise.all([listDepartments(), listUsers({ page: 0, size: 1000 })])
    departments.value = d.data || []
    users.value = u.data?.content || []
    // 选中的部门可能已被删除
    if (selectedId.value && !departments.value.some(x => x.id === selectedId.value)) selectedId.value = null
    await nextTick()
    applyFilter()
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

// ---- 树过滤 / 展开收起 ----
const filterNode = (value, data) => {
  if (!value) return true
  const kw = String(value).toLowerCase()
  return String(data.name || '').toLowerCase().includes(kw) || String(data.code || '').toLowerCase().includes(kw)
}
function applyFilter() {
  treeRef.value?.filter(filterText.value)
}
// 用 watch 而不是 el-input 的 @input：v-model 与 @input 的触发顺序不保证，
// 直接在 @input 里读 filterText 可能拿到旧值（表现为「输入了但树没过滤」）。
watch(filterText, () => nextTick(applyFilter))
function expandAll() {
  const nodes = treeRef.value?.store?.nodesMap || {}
  Object.values(nodes).forEach(n => { n.expanded = true })
}
function collapseAll() {
  const nodes = treeRef.value?.store?.nodesMap || {}
  Object.values(nodes).forEach(n => { n.expanded = false })
}

// ---- 拖拽改层级 / 排序 ----
const draggingId = ref(null)
function isDescendantNode(dragData, dropData) {
  if (!dropData.children || !dropData.children.length) return false
  for (const c of dropData.children) {
    if (c.id === dragData.id) return true
    if (isDescendantNode(dragData, c)) return true
  }
  return false
}
function subtreeDepth(data) {
  if (!data.children || !data.children.length) return 1
  return 1 + Math.max(...data.children.map(subtreeDepth))
}
function depthOfNode(data, all = tree.value, depth = 1) {
  for (const n of all) {
    if (n.id === data.id) return depth
    const found = depthOfNode(data, n.children || [], depth + 1)
    if (found) return found
  }
  return 0
}
/** 与后端一致：最多 5 层，且不允许拖到自己的子孙下 */
function allowDrop(dragNode, dropNode, type) {
  if (dragNode.data.id === dropNode.data.id) return false
  if (isDescendantNode(dragNode.data, dropNode.data)) return false
  const dragDepth = subtreeDepth(dragNode.data)
  const dropDepth = depthOfNode(dropNode.data)
  const newDepth = type === 'inner' ? dropDepth + 1 : dropDepth
  return newDepth + dragDepth - 1 <= 5
}
function onDragStart(node) { draggingId.value = node.data.id }
function onDragEnd() { draggingId.value = null }

async function onNodeDrop(dragNode, dropNode, dropType) {
  const drag = dragNode.data
  const target = dropNode.data
  let parentId
  let sortOrder
  if (dropType === 'inner') {
    parentId = target.id
    sortOrder = (target.children || []).length
  } else {
    const targetDept = departments.value.find(d => d.id === target.id)
    parentId = targetDept?.parentId ?? null
    const siblings = departments.value
      .filter(d => (d.parentId ?? null) === (parentId ?? null) && d.id !== drag.id)
      .sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
    const idx = siblings.findIndex(s => s.id === target.id)
    sortOrder = dropType === 'before' ? Math.max(0, idx) : idx + 1
  }
  const targetName = parentId ? (departments.value.find(d => d.id === parentId)?.name || '') : '顶级'
  try {
    await ElMessageBox.confirm(
      `确定把「${drag.name}」移动到「${targetName}」${dropType === 'inner' ? '下' : '同级'}吗？`,
      '调整部门层级', { type: 'warning', confirmButtonText: '确认移动', cancelButtonText: '取消' })
  } catch {
    await load()   // 取消 → 重新拉取还原树形
    return
  }
  try {
    await moveDepartment(drag.id, parentId, sortOrder)
    ElMessage.success('已移动')
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '移动失败'))
    await load()
  }
}

const openAdd = (parentId = null) => {
  editing.value = null
  form.value = { name: '', code: '', parentId, leaderId: null, description: '', sortOrder: 0, status: 'ACTIVE' }
  dialog.value = true
}
const openEdit = (d) => {
  const full = departments.value.find(x => x.id === d.id) || d
  editing.value = full.id
  form.value = {
    name: full.name,
    code: full.code || '',
    parentId: full.parentId || null,
    leaderId: full.leaderId || null,
    description: full.description || '',
    sortOrder: full.sortOrder || 0,
    status: full.status || 'ACTIVE',
  }
  dialog.value = true
}
const save = async () => {
  if (!form.value.name.trim()) { ElMessage.warning('请填写部门名称'); return }
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      code: form.value.code.trim(),
      parentId: form.value.parentId || null,
      leaderId: form.value.leaderId || null,
      description: form.value.description.trim(),
      sortOrder: Number(form.value.sortOrder) || 0,
      status: form.value.status,
    }
    if (editing.value) {
      await updateDepartment(editing.value, payload)
      ElMessage.success('已更新部门')
    } else {
      await createDepartment(payload)
      ElMessage.success('已创建部门')
    }
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  } finally {
    saving.value = false
  }
}
const remove = async (d) => {
  try {
    await ElMessageBox.confirm('确定删除部门「' + d.name + '」？存在子部门或仍有成员时无法删除（请先移除子部门 / 成员）。', '删除部门', { type: 'warning' })
  } catch { return }
  try {
    await deleteDepartment(d.id)
    ElMessage.success('已删除')
    if (selectedId.value === d.id) selectedId.value = null
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}

// ===== 成员管理 =====
const addDialog = ref(false)
const addIds = ref([])
const adding = ref(false)
const candidateUsers = computed(() =>
  (users.value || []).filter(u => u.departmentId !== selectedId.value))
const openAddMembers = () => {
  addIds.value = []
  addDialog.value = true
}
const confirmAddMembers = async () => {
  if (!addIds.value.length) { addDialog.value = false; return }
  adding.value = true
  try {
    const { data } = await assignDepartmentMembers(selectedId.value, addIds.value)
    ElMessage.success('已加入 ' + (data?.count ?? addIds.value.length) + ' 名成员')
    addDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '加入失败'))
  } finally {
    adding.value = false
  }
}
const removeOne = async (u) => {
  try {
    await ElMessageBox.confirm('把「' + userName(u) + '」移出本部门？（其所属部门将变为无部门）', '移出成员', { type: 'warning' })
  } catch { return }
  try {
    await removeDepartmentMember(selectedId.value, u.id)
    ElMessage.success('已移出')
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '移出失败'))
  }
}

const onNodeClick = (data) => { selectedId.value = data.id; memberExpanded.value = false }

onMounted(load)
</script>

<template>
  <div class="dm-page">
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><OfficeBuilding /></el-icon></div>
      <div class="head-text">
        <h2>部门管理</h2>
        <p>左侧点击部门查看 / 管理成员；<b>直接拖拽节点</b>即可调整层级与排序（最多 5 层）</p>
      </div>
      <div class="head-action">
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openAdd(null)">新建顶级部门</el-button>
      </div>
    </div>

    <div class="dm-body" v-loading="loading">
      <!-- 左：组织树 -->
      <el-card shadow="never" class="panel tree-panel">
        <div class="tree-toolbar">
          <el-input v-model="filterText" placeholder="搜索部门名称 / 编码" clearable size="small"
                    :prefix-icon="Search" />
          <div class="tree-tools">
            <el-button link size="small" @click="expandAll">全部展开</el-button>
            <span class="divider">|</span>
            <el-button link size="small" @click="collapseAll">全部收起</el-button>
          </div>
        </div>
        <el-tree
          ref="treeRef"
          :data="tree"
          node-key="id"
          :props="{ label: 'name', children: 'children' }"
          :expand-on-click-node="false"
          :filter-node-method="filterNode"
          default-expand-all
          highlight-current
          :current-node-key="selectedId || undefined"
          draggable
          :allow-drop="allowDrop"
          @node-click="onNodeClick"
          @node-drag-start="onDragStart"
          @node-drag-end="onDragEnd"
          @node-drop="onNodeDrop"
        >
          <template #default="{ data }">
            <div class="tree-node" :class="{ dragging: draggingId === data.id }">
              <span class="status-dot" :class="data.status === 'DISABLED' ? 'off' : 'on'"
                    :title="data.status === 'DISABLED' ? '已停用' : '启用中'"></span>
              <span class="node-name" :class="{ off: data.status === 'DISABLED' }">{{ data.name }}</span>
              <span class="node-count" :title="`直属 ${data.directCount} 人，含下级 ${data.totalCount} 人`">
                {{ data.directCount }}<template v-if="data.totalCount !== data.directCount">/{{ data.totalCount }}</template>
              </span>
              <span class="node-ops" @click.stop>
                <el-tooltip content="添加子部门" placement="top"><el-button text size="small" :icon="Plus" @click="openAdd(data.id)" /></el-tooltip>
                <el-tooltip content="编辑" placement="top"><el-button text size="small" :icon="Edit" @click="openEdit(data)" /></el-tooltip>
                <el-tooltip content="删除" placement="top"><el-button text size="small" type="danger" :icon="Delete" @click="remove(data)" /></el-tooltip>
              </span>
            </div>
          </template>
        </el-tree>
        <el-empty v-if="!departments.length" description="暂无部门，点击右上角新建" :image-size="70" />
        <div v-else class="tree-hint">人数：直属/含下级（鼠标悬停看明细）</div>
      </el-card>

      <!-- 右：部门详情 + 成员 -->
      <el-card shadow="never" class="panel member-panel">
        <template v-if="selectedDept">
          <div class="dept-head">
            <div class="dept-title">
              <h3>{{ selectedDept.name }}</h3>
              <el-tag size="small" effect="plain" round>{{ selectedDept.directCount || 0 }} 人直属</el-tag>
              <el-tag v-if="(selectedDept.totalCount || 0) !== (selectedDept.directCount || 0)" size="small" effect="plain" type="success" round>
                含下级 {{ selectedDept.totalCount }} 人
              </el-tag>
              <el-tag v-if="selectedDept.status === 'DISABLED'" size="small" type="danger" effect="plain" round>已停用</el-tag>
            </div>
            <div class="dept-sub">
              <span v-if="selectedDept.code" class="dept-code">编码 {{ selectedDept.code }}</span>
              <span v-if="selectedDept.path">路径：{{ selectedDept.path }}</span>
              <span>负责人：{{ leaderName(selectedDept.leaderId) }}</span>
              <span v-if="selectedDept.description">{{ selectedDept.description }}</span>
            </div>
            <div class="dept-actions">
              <el-button type="primary" size="small" :icon="Plus" @click="openAddMembers">添加成员</el-button>
              <el-button size="small" :icon="Edit" @click="openEdit(selectedDept)">编辑部门</el-button>
            </div>
          </div>

          <el-table v-stack-table :data="shownMembers" class="member-table" empty-text="该部门还没有成员">
            <el-table-column label="成员" min-width="200">
              <template #default="{ row }">
                <div class="member-cell">
                  <el-icon class="member-icon"><User /></el-icon>
                  <span class="member-name">{{ row.displayName || row.username }}</span>
                  <span class="member-uid">@{{ row.username }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="角色" width="110">
              <template #default="{ row }">
                <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'info'" size="small">
                  {{ row.role === 'ADMIN' ? '管理员' : '用户' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="right">
              <template #default="{ row }">
                <el-button text type="danger" size="small" :icon="Remove" @click="removeOne(row)">移出</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="hiddenMemberCount" class="more-members">
            <el-button link type="primary" size="small" @click="memberExpanded = !memberExpanded">
              {{ memberExpanded ? '收起' : `展开其余 ${hiddenMemberCount} 人` }}
            </el-button>
          </div>
        </template>
        <el-empty v-else description="点击左侧部门节点，查看和管理该部门成员" :image-size="90" class="member-empty" />
      </el-card>
    </div>

    <!-- 新建 / 编辑部门 -->
    <el-dialog v-model="dialog" :title="editing ? '编辑部门' : '新建部门'" width="520px" class="dept-dialog">
      <el-form label-width="80px">
        <el-form-item label="部门名称">
          <el-input v-model="form.name" placeholder="如：技术部" />
        </el-form-item>
        <el-form-item label="部门编码">
          <el-input v-model="form.code" maxlength="50" placeholder="可选，如 TECH-BE" />
        </el-form-item>
        <el-form-item label="上级部门">
          <el-select v-model="form.parentId" clearable placeholder="无（顶级部门）" style="width: 100%">
            <el-option v-for="d in parentOptions" :key="d.id" :value="d.id" :label="d.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人">
          <el-select v-model="form.leaderId" clearable filterable placeholder="选择用户（可搜索）" style="width: 100%">
            <el-option v-for="u in users" :key="u.id" :value="u.id" :label="userName(u)" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="form.status">
            <el-radio value="ACTIVE">启用</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="999" :step="1" />
          <span class="form-hint">也可直接在左侧拖拽调整</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 添加成员 -->
    <el-dialog v-model="addDialog" :title="'添加成员到「' + (selectedDept?.name || '') + '」'" width="520px">
      <el-select v-model="addIds" multiple filterable placeholder="搜索并选择用户（可多选）" style="width: 100%">
        <el-option v-for="u in candidateUsers" :key="u.id" :value="u.id" :label="userName(u)">
          <span>{{ userName(u) }}</span>
          <span class="opt-dept">{{ u.departmentId && departments.find(d => d.id === u.departmentId) ? '现属：' + departments.find(d => d.id === u.departmentId).name : '无部门' }}</span>
        </el-option>
      </el-select>
      <div class="hint">已在其他部门的用户会被移动到本部门</div>
      <template #footer>
        <el-button @click="addDialog = false">取消</el-button>
        <el-button type="primary" :loading="adding" @click="confirmAddMembers">加入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; flex-wrap: wrap; }
.head-icon {
  width: 46px; height: 46px; border-radius: 12px; display: flex; align-items: center; justify-content: center;
  background: #e0f2fe; color: #0284c7; flex-shrink: 0;
}
.head-text { flex: 1; min-width: 220px; }
.head-text h2 { margin: 0; font-size: 20px; font-weight: 700; color: #1e293b; }
.head-text p { margin: 4px 0 0; font-size: 13px; color: #94a3b8; }
.head-action { display: flex; gap: 8px; }

.dm-body { display: flex; gap: 16px; align-items: stretch; }
.tree-panel { width: 400px; flex-shrink: 0; border-radius: 14px; }
.member-panel { flex: 1; min-width: 0; border-radius: 14px; }

/* 树工具栏：搜索 + 展开收起 */
.tree-toolbar { display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px; }
.tree-tools { display: flex; align-items: center; gap: 4px; padding-left: 2px; }
.tree-tools .divider { color: #e2e8f0; font-size: 12px; }
.tree-hint { margin-top: 10px; font-size: 11.5px; color: #cbd5e1; text-align: center; }

/* 树节点：状态点 + 名称 + 人数 + 悬停操作 */
.tree-node { flex: 1; min-width: 0; display: flex; align-items: center; gap: 8px; padding-right: 4px; }
.tree-node.dragging { opacity: .5; }
.status-dot { width: 7px; height: 7px; border-radius: 50%; flex-shrink: 0; }
.status-dot.on { background: #22c55e; }
.status-dot.off { background: #cbd5e1; }
.node-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; color: #1e293b; }
.node-name.off { color: #94a3b8; text-decoration: line-through; }
.node-count { font-size: 12px; color: #0369a1; background: rgba(14,165,233,.12); border-radius: 999px; padding: 1px 8px; flex-shrink: 0; }
.node-ops { display: none; align-items: center; flex-shrink: 0; }
.tree-node:hover .node-ops { display: inline-flex; }
.tree-node:hover .node-count { display: none; }
:deep(.el-tree-node__content) { height: 34px; border-radius: 8px; }

/* 右侧部门详情 */
.dept-head { margin-bottom: 14px; }
.dept-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.dept-title h3 { margin: 0; font-size: 17px; font-weight: 700; color: #1e293b; }
.dept-sub { margin-top: 6px; font-size: 13px; color: #94a3b8; display: flex; flex-wrap: wrap; gap: 6px 14px; }
.dept-code { font-family: ui-monospace, Menlo, Consolas, monospace; color: #64748b; }
.dept-actions { margin-top: 12px; display: flex; gap: 8px; }
.member-cell { display: flex; align-items: center; gap: 8px; }
.member-icon { color: #94a3b8; }
.member-name { font-weight: 600; color: #334155; }
.member-uid { font-size: 12px; color: #cbd5e1; }
.more-members { text-align: center; padding: 8px 0 2px; }
.member-empty { padding: 60px 0; }
.hint { font-size: 12px; color: #94a3b8; margin-top: 8px; }
.opt-dept { float: right; font-size: 12px; color: #cbd5e1; }
.form-hint { margin-left: 10px; font-size: 12px; color: #94a3b8; }

html.dark .node-name, html.dark .dept-title h3, html.dark .member-name { color: var(--el-text-color-primary); }
html.dark .head-text h2 { color: var(--el-text-color-primary); }
html.dark .head-icon { background: rgb(2 132 199 / .18); color: #7dd3fc; }
html.dark .tree-tools .divider { color: #334155; }
html.dark .node-count { background: rgb(14 165 233 / .18); color: #7dd3fc; }
</style>
