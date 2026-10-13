<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, UserFilled } from '@element-plus/icons-vue'
import { listRoles, createRole, updateRole, deleteRole, roleMembers } from '../../api/admin'
import PageBar from '../../components/PageBar.vue'

const roles = ref([])
const loading = ref(false)
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)
const form = ref({ name: '', code: '', description: '', permissions: [], sortOrder: 0 })
// 权限码清单已抽到 utils/permCodes.js（与后端 Perm.WHITELIST 同步，含 BLACKLIST_MANAGE 与演示权限 DEMO_MODE）
import { PERM_GROUPS, PERM_OPTIONS, isFullAdminPerms } from '../../utils/permCodes'
import { errMsg } from '../../utils/errMsg'
const permLabel = (v) => (PERM_OPTIONS.find(o => o.value === v)?.label || (v === 'DEMO_MODE' ? '演示权限（只读）' : v))
// 完全管理员判定（语义=覆盖）：角色权限码覆盖全量白名单 → 持有者视同 ADMIN
const isFullAdmin = computed(() => isFullAdminPerms(form.value.permissions))
// 是否含演示权限：该角色持有者将只能查看、不能改动数据
const isDemoRole = computed(() => form.value.permissions.includes('DEMO_MODE'))

// 角色成员统计：roleId -> 人数（仅展示已加载页内的用户，粗略计数）
const memberCount = ref({})
const load = async () => {
  loading.value = true
  try {
    const { data } = await listRoles()
    roles.value = data || []
  } catch (e) {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}
const loadMembers = async () => {
  try {
    const { data } = await roleMembers()
    // 后端返回 { roleId: 人数 }；统一成字符串键，避免数字/字符串键不匹配显示 0
    const cnt = {}
    Object.entries(data || {}).forEach(([rid, n]) => { cnt[String(rid)] = Number(n) || 0 })
    memberCount.value = cnt
  } catch { memberCount.value = {} }
}

const openAdd = () => {
  editing.value = null
  form.value = { name: '', code: '', description: '', permissions: [], sortOrder: 0 }
  dialog.value = true
}
const openEdit = (r) => {
  editing.value = r.id
  form.value = {
    name: r.name,
    code: r.code,
    description: r.description || '',
    permissions: (r.permissions && r.permissions.trim()) ? r.permissions.split(',').map(s => s.trim()).filter(Boolean) : [],
    sortOrder: r.sortOrder || 0,
  }
  dialog.value = true
}
const save = async () => {
  saving.value = true
  try {
    const payload = {
      name: form.value.name.trim(),
      code: form.value.code.trim().toUpperCase(),
      description: form.value.description.trim(),
      permissions: form.value.permissions.join(','),
      sortOrder: Number(form.value.sortOrder) || 0,
    }
    if (editing.value) {
      await updateRole(editing.value, payload)
      ElMessage.success(isFullAdmin.value ? '已更新角色（该角色为完全管理员权限，持有者视同 ADMIN）' : '已更新角色')
    } else {
      await createRole(payload)
      ElMessage.success(isFullAdmin.value ? '已创建角色（该角色为完全管理员权限，持有者视同 ADMIN）' : '已创建角色')
    }
    dialog.value = false
    await load(); await loadMembers()
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  } finally {
    saving.value = false
  }
}
// 「一键演示权限」模板：勾上全部真实管理权限 + 演示只读标记。
// 效果 = 后台所有页面都能打开查看，但任何保存/删除/发布操作都会被服务端拒绝。
const selectAllPerms = () => {
  form.value.permissions = PERM_OPTIONS.map(o => o.value)
}
const selectDemoTemplate = () => {
  // 全量真实权限 + DEMO_MODE（只读约束）
  form.value.permissions = [...PERM_OPTIONS.map(o => o.value), 'DEMO_MODE']
}
const remove = async (r) => {
  try {
    await ElMessageBox.confirm('确定删除角色「' + r.name + '」？仍有用户使用的角色无法删除。', '删除角色', { type: 'warning' })
  } catch { return }
  try {
    await deleteRole(r.id)
    ElMessage.success('已删除')
    await load(); await loadMembers()
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}

onMounted(async () => { await load(); await loadMembers() })
</script>

<template>
  <div class="rm-page">
    <div class="page-head">
      <div class="head-icon"><el-icon :size="22"><UserFilled /></el-icon></div>
      <div class="head-text">
        <h2>角色管理</h2>
        <p>权限统一在这里定义：角色是权限的集合，用户挂上角色即获得对应权限（管理员隐式全权）</p>
      </div>
      <div class="head-action">
        <el-button type="primary" :icon="Plus" @click="openAdd">新建角色</el-button>
      </div>
    </div>

    <el-card shadow="never" class="panel">
      <el-table v-stack-table :data="roles" v-loading="loading" class="role-table">
        <el-table-column prop="name" label="角色名称" min-width="140">
          <template #default="{ row }">
            <span class="role-name">{{ row.name }}</span>
            <el-tag v-if="row.builtin" size="small" type="info" effect="plain" class="builtin-tag">内置</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="编码" width="150" />
        <el-table-column label="权限" min-width="280">
          <template #default="{ row }">
            <el-tag v-for="p in (row.permissions || '').split(',').filter(Boolean)" :key="p"
                    class="perm-tag" type="success" size="small" effect="light" round>{{ permLabel(p) }}</el-tag>
            <span v-if="!row.permissions" class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="成员" width="90" align="center">
          <template #default="{ row }">{{ memberCount[String(row.id)] || 0 }} 人</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" :icon="Edit" :disabled="row.builtin" @click="openEdit(row)">编辑</el-button>
            <el-button text type="danger" :icon="Delete" :disabled="row.builtin" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" :title="editing ? '编辑角色' : '新建角色'" width="660px" class="role-dialog" :style="{ maxWidth: 'calc(100vw - 32px)' }">
      <el-form label-width="80px">
        <el-form-item label="角色名称">
          <el-input v-model="form.name" placeholder="如：内容审核员" />
        </el-form-item>
        <el-form-item label="角色编码">
          <el-input v-model="form.code" :disabled="!!editing" placeholder="如 CONTENT_AUDITOR（唯一，创建后不可改）" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
        <el-form-item label="权限">
          <div class="pgroups">
            <div v-for="g in PERM_GROUPS" :key="g.title" class="pgroup">
              <div class="pgroup-title">{{ g.title }}<span class="pgroup-count">{{ g.perms.length }} 项</span></div>
              <el-checkbox-group v-model="form.permissions" class="perm-grid">
                <el-checkbox v-for="o in g.perms" :key="o.value" :value="o.value" border>{{ o.label }}</el-checkbox>
              </el-checkbox-group>
            </div>
          </div>
          <el-alert v-if="isFullAdmin" type="warning" :closable="false" show-icon style="margin-top: 8px"
            title="该角色已覆盖全部管理员权限：持有此角色的用户将视同完全管理员（ADMIN），包括仅管理员可用的接口" />
          <el-alert v-if="isDemoRole" type="info" :closable="false" show-icon style="margin-top: 8px"
            title="已勾选「演示权限」：持有此角色的用户可查看后台各页面，但所有保存/删除/发布等改动数据的操作都会被拒绝并提示「演示账户不能…」" />
          <div class="perm-hint">
            <el-button text type="primary" size="small" @click="selectAllPerms">全选管理员权限</el-button>
            <el-button text size="small" @click="selectDemoTemplate">一键「演示权限」模板</el-button>
            <el-button text size="small" @click="form.permissions = []">清空</el-button>
          </div>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="999" :step="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; flex-wrap: wrap; }
.head-icon {
  width: 46px; height: 46px; border-radius: 12px; display: flex; align-items: center; justify-content: center;
  background: #e0e7ff; color: #4f46e5; flex-shrink: 0;
}
.head-text { flex: 1; min-width: 220px; }
.head-text h2 { margin: 0; font-size: 20px; font-weight: 700; color: var(--el-text-color-primary); }
.head-text p { margin: 4px 0 0; font-size: 13px; color: var(--el-text-color-secondary); }
.role-name { font-weight: 600; color: var(--el-text-color-primary); }
.builtin-tag { margin-left: 6px; }
.perm-tag { margin: 2px 4px 2px 0; }
.muted { color: var(--el-text-color-secondary); font-size: 13px; }
.panel { border-radius: 14px; }
/* 权限勾选：按业务域分组，同组内的勾选共享同一个 checkbox-group（v-model 相同）。
   ⚠️ 必须用 minmax(0,1fr) 而非 1fr —— 1fr 等价 minmax(auto,1fr)，网格项默认 min-width:auto
   会被内部 checkbox 标签的固有内容宽度撑开，两列一起膨胀溢出弹窗（2026-10-05 实测溢出 52px）。 */
.pgroups { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 4px 18px; align-items: start; }
.pgroup { min-width: 0; overflow: hidden; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; padding: 10px 12px 12px; background: var(--el-fill-color-extra-light, #fafbfd); }
.pgroup-title { display: flex; align-items: center; gap: 6px; font-size: 12.5px; font-weight: 600; color: var(--el-text-color-primary); margin-bottom: 8px; }
.pgroup-title::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: var(--el-color-primary); }
.pgroup-count { margin-left: auto; font-size: 11px; font-weight: 400; color: var(--el-text-color-secondary); }
.perm-grid { display: flex; flex-wrap: wrap; gap: 8px; min-width: 0; }
.perm-grid :deep(.el-checkbox.is-bordered) { margin-right: 0; max-width: 100%; }
.perm-grid :deep(.el-checkbox__label) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.perm-hint { display: flex; flex-wrap: wrap; gap: 2px; margin-top: 6px; }
html.dark .pgroup { border-color: #2a3858; background: #131c33; }
/* 窄屏分组单列 */
@media (max-width: 1024px) { .pgroups { grid-template-columns: minmax(0, 1fr); } }
html.dark .head-icon { background: rgb(79 70 229 / .2); color: #a5b4fc; }
</style>
