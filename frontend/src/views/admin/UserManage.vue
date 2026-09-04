<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Medal, User, UserFilled, Search } from '@element-plus/icons-vue'
import { listUsers, createUser, updateUser, deleteUser, toggleUser, adjustPoints, resetPassword, adminUserStorage } from '../../api/admin'
import PageBar from '../../components/PageBar.vue'

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
const form = ref({ username: '', displayName: '', role: 'USER', password: '', enabled: true, permissions: [], storageQuotaGb: null })
const storageInfo = ref(null)   // 编辑时拉取的当前用量 { used, quota }
const GB = 1024 * 1024 * 1024
const formatGb = (b) => ((b || 0) / GB).toFixed(2) + ' GB'
const roleTab = ref('all')   // all | ADMIN | USER | disabled

// 可选细粒度权限（对应后端 TokenClaims.hasPerm 校验；黑名单管理暂未开放细粒度授权，仅 ADMIN 可访问，故不在选项内）
// 注意：需与后端 AdminService.PERM_WHITELIST 保持一致，新增权限码要同步加进这里，否则 UI 上无法勾选。
const PERM_OPTIONS = [
  { value: 'USER_MANAGE', label: '用户管理' },
  { value: 'DOC_REVIEW', label: '文章审核' },
  { value: 'STATS_VIEW', label: '数据看板' },
  { value: 'TAXONOMY_MANAGE', label: '分类标签管理' },
  { value: 'ANNOUNCEMENT_MANAGE', label: '公告管理' },
  { value: 'COURSE_MANAGE', label: '培训管理' },
  { value: 'EXAM_MANAGE', label: '考试管理' },
  { value: 'SHARE_CREATE', label: '创建团队空间' },
  { value: 'SHARE_VIEW', label: '共享文档管理（查看）' },
  { value: 'SHARE_DELETE', label: '共享文档管理（删除空间）' },
  { value: 'SHARE_CAPACITY', label: '共享文档管理（容量分配）' },
  { value: 'CORE_MAINTAIN', label: '核心维护（功能开关）' },
  { value: 'DOC_EXPORT', label: '文档导出' },
  { value: 'BANNED_WORDS_MANAGE', label: '违禁词管理' },
  { value: 'DOC_SHARE', label: '共享文档管理（旧权限，已拆分）' },
]
const permLabel = (v) => (PERM_OPTIONS.find(o => o.value === v)?.label || v)

// 弹窗内按业务域分组展示（仅影响渲染顺序/分组，不改动权限码本身；与 PERM_OPTIONS 一一对应）
const PERM_GROUPS = [
  { title: '用户与权限', options: ['USER_MANAGE'] },
  { title: '内容管理', options: ['DOC_REVIEW', 'TAXONOMY_MANAGE', 'ANNOUNCEMENT_MANAGE', 'DOC_EXPORT', 'BANNED_WORDS_MANAGE'] },
  { title: '共享文档', options: ['SHARE_CREATE', 'SHARE_VIEW', 'SHARE_DELETE', 'SHARE_CAPACITY', 'DOC_SHARE'] },
  { title: '培训与考试', options: ['COURSE_MANAGE', 'EXAM_MANAGE'] },
  { title: '系统与数据', options: ['STATS_VIEW', 'CORE_MAINTAIN'] },
]

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
  form.value = { username: '', displayName: '', role: 'USER', password: '', enabled: true, permissions: [], storageQuotaGb: null }
  storageInfo.value = null
  dialog.value = true
}

const openEdit = (u) => {
  editing.value = u.id
  form.value = {
    username: u.username,
    displayName: u.displayName || '',
    role: u.role,
    password: '',
    enabled: u.enabled,
    permissions: (u.permissions && typeof u.permissions === 'string' && u.permissions.trim())
      ? u.permissions.split(',').map(s => s.trim()).filter(Boolean) : [],
    storageQuotaGb: u.storageQuota ? Number((u.storageQuota / GB).toFixed(2)) : null,
  }
  storageInfo.value = null
  // 拉取该用户当前存储用量，便于在弹窗内对照
  adminUserStorage(u.id).then(({ data }) => { storageInfo.value = data }).catch(() => {})
  dialog.value = true
}

const save = async () => {
  saving.value = true
  try {
    // ADMIN 角色隐式拥有全部权限，无需存储细粒度权限；普通用户才保存勾选的权限
    const perms = form.value.role === 'ADMIN' ? '' : form.value.permissions.join(',')
    if (editing.value) {
      const r = { displayName: form.value.displayName, role: form.value.role, enabled: form.value.enabled, permissions: perms }
      if (form.value.password) r.password = form.value.password
      // 存储配额：填了 GB 转字节；0/空表示恢复系统默认 200MB
      r.storageQuota = form.value.storageQuotaGb ? Math.round(form.value.storageQuotaGb * GB) : 0
      await updateUser(editing.value, r)
      ElMessage.success('已更新')
    } else {
      await createUser({ username: form.value.username, displayName: form.value.displayName, role: form.value.role, password: form.value.password, enabled: form.value.enabled, permissions: perms })
      ElMessage.success('已创建')
    }
    dialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '操作失败')
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
    ElMessage.error(e.response?.data?.error || '删除失败')
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
    ElMessage.error(e.response?.data?.error || '重置失败')
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
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '调整失败')
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

onMounted(load)
</script>

<template>
  <div class="um-page">
    <!-- 页头 -->
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><UserFilled /></el-icon></div>
      <div class="head-text">
        <h2>用户管理</h2>
        <p>账号、角色与细粒度权限、积分与存储配额的统一管理</p>
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
        <el-table-column label="角色 / 权限" min-width="220">
          <template #default="{ row }">
            <span class="role" :class="row.role === 'ADMIN' ? 'admin' : 'user'">
              <el-icon><component :is="row.role === 'ADMIN' ? Medal : User" /></el-icon>
              {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </span>
            <template v-if="row.role !== 'ADMIN' && row.permissions">
              <el-tag v-for="p in row.permissions.split(',').filter(Boolean)" :key="p" class="perm-tag" type="success" size="small" effect="light" round>{{ permLabel(p) }}</el-tag>
            </template>
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

    <el-dialog v-model="dialog" :title="editing ? '编辑用户' : '新增用户'" width="540px" class="user-dialog">
      <el-form label-width="80px">
        <el-form-item label="用户名">
          <el-input v-model="form.username" :disabled="!!editing" />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item label="角色权限">
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="管理员 ADMIN（拥有全部权限）" value="ADMIN" />
            <el-option label="普通用户 USER" value="USER" />
          </el-select>
        </el-form-item>
        <el-form-item label="细粒度权限" v-if="form.role !== 'ADMIN'" label-width="120px" class="perm-item">
          <el-checkbox-group v-model="form.permissions" class="perm-groups">
            <div v-for="g in PERM_GROUPS" :key="g.title" class="perm-group">
              <div class="perm-group-title">{{ g.title }}</div>
              <div class="perm-grid">
                <el-checkbox v-for="code in g.options" :key="code" :value="code" border>{{ permLabel(code) }}</el-checkbox>
              </div>
            </div>
          </el-checkbox-group>
          <div class="hint perm-hint">勾选后该用户可进行对应管理操作（无需成为管理员）；管理员角色无需勾选</div>
        </el-form-item>
        <el-form-item :label="editing ? '重置密码' : '密码'">
          <el-input v-model="form.password" type="password" show-password
                    :placeholder="editing ? '留空则不修改' : '至少 6 位'" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="禁用" />
        </el-form-item>
        <el-form-item label="存储配额">
          <el-input-number v-model="form.storageQuotaGb" :min="0" :max="1024" :step="1" :precision="1" style="width: 160px" />
          <span class="hint">GB；0 或不填 = 系统默认 200MB。当前已用 {{ storageInfo ? formatGb(storageInfo.used) : '—' }}</span>
        </el-form-item>
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
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  box-shadow: 0 4px 12px rgba(79, 70, 229, .3);
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

/* ===== 面板 ===== */
.panel { border-radius: 12px; border-color: #e2e8f0; }

/* 工具栏 */
.toolbar {
  display: flex; align-items: center; gap: 12px;
  flex-wrap: wrap;
  padding-bottom: 14px; margin-bottom: 6px;
  border-bottom: 1px dashed #e2e8f0;
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
</style>
