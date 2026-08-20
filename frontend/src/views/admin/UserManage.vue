<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Medal, User } from '@element-plus/icons-vue'
import { listUsers, createUser, updateUser, deleteUser, toggleUser, adjustPoints, resetPassword } from '../../api/admin'

const users = ref([])
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)
const form = ref({ username: '', displayName: '', role: 'USER', password: '', enabled: true, permissions: [] })
const roleTab = ref('all')   // all | ADMIN | USER | disabled

// 可选细粒度权限（对应后端 TokenClaims.hasPerm 校验；黑名单管理暂未开放细粒度授权，仅 ADMIN 可访问，故不在选项内）
// 注意：需与后端 AdminService.PERM_WHITELIST 保持一致，新增权限码要同步加进这里，否则 UI 上无法勾选。
const PERM_OPTIONS = [
  { value: 'USER_MANAGE', label: '用户管理' },
  { value: 'DOC_REVIEW', label: '文章审核' },
  { value: 'STATS_VIEW', label: '数据看板' },
  { value: 'TAXONOMY_MANAGE', label: '分类标签管理' },
  { value: 'ANNOUNCEMENT_MANAGE', label: '公告管理' },
]
const permLabel = (v) => (PERM_OPTIONS.find(o => o.value === v)?.label || v)

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
  form.value = { username: '', displayName: '', role: 'USER', password: '', enabled: true, permissions: [] }
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
  }
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
  <div>
    <div class="bar">
      <el-button type="primary" @click="openAdd">新增用户</el-button>
      <span class="spacer" />
      <el-input v-model="keyword" placeholder="搜索用户名 / 昵称" clearable
                style="width: 220px" @keyup.enter="onSearch" @clear="onSearch" />
      <el-select v-model="sort" style="width: 130px" @change="onSort">
        <el-option label="最新注册" value="newest" />
        <el-option label="积分最高" value="points" />
        <el-option label="角色" value="role" />
      </el-select>
    </div>

    <el-tabs v-model="roleTab" class="seg" @tab-change="onTab">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="管理员" name="ADMIN" />
      <el-tab-pane label="普通用户" name="USER" />
      <el-tab-pane label="已禁用" name="disabled" />
    </el-tabs>

    <el-table :data="users" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column label="头像" width="72" align="center">
        <template #default="{ row }">
          <el-avatar :size="38" :src="row.avatar || undefined">{{ (row.displayName || row.username || '?').charAt(0) }}</el-avatar>
        </template>
      </el-table-column>
      <el-table-column prop="username" label="用户名" />
      <el-table-column prop="displayName" label="昵称" />
      <el-table-column label="角色 / 权限" min-width="200">
        <template #default="{ row }">
          <span class="role" :class="row.role === 'ADMIN' ? 'admin' : 'user'">
            <el-icon><component :is="row.role === 'ADMIN' ? Medal : User" /></el-icon>
            {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
          </span>
          <template v-if="row.role !== 'ADMIN' && row.permissions">
            <el-tag v-for="p in row.permissions.split(',').filter(Boolean)" :key="p" class="perm-tag" type="success" size="small" effect="light">{{ permLabel(p) }}</el-tag>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'warning'">{{ row.enabled ? '启用' : '禁用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="points" label="积分" width="80" />
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

    <el-pagination v-if="total > 0" class="pager"
                   layout="total, sizes, prev, pager, next, jumper"
                   :total="total" :page-size="size" :current-page="page"
                   :page-sizes="[5, 15, 30, 50]"
                   @current-change="onPage" @size-change="onSize" />

    <el-dialog v-model="dialog" :title="editing ? '编辑用户' : '新增用户'" width="420px">
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
        <el-form-item label="细粒度权限" v-if="form.role !== 'ADMIN'">
          <el-checkbox-group v-model="form.permissions">
            <el-checkbox v-for="o in PERM_OPTIONS" :key="o.value" :value="o.value" border>{{ o.label }}</el-checkbox>
          </el-checkbox-group>
          <div class="hint">勾选后该用户可进行对应管理操作（无需成为管理员）；管理员角色无需勾选</div>
        </el-form-item>
        <el-form-item :label="editing ? '重置密码' : '密码'">
          <el-input v-model="form.password" type="password" show-password
                    :placeholder="editing ? '留空则不修改' : '至少 6 位'" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="禁用" />
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
.bar { margin-bottom: 12px; display: flex; align-items: center; gap: 10px; }
.bar .spacer { flex: 1; }
.seg { margin-bottom: 4px; }
.pager { margin-top: 14px; justify-content: flex-end; }
.hint { color: #94a3b8; font-size: 12px; margin-left: 8px; }
.perm-tag { margin-left: 6px; }
.role { display: inline-flex; align-items: center; gap: 4px; font-weight: 500; }
.role.admin { color: #f59e0b; }
.role.user { color: #6366f1; }
.role .el-icon { font-size: 15px; }
:deep(.el-checkbox.is-bordered) { margin-right: 8px; }
</style>
