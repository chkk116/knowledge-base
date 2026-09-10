<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, UserFilled } from '@element-plus/icons-vue'
import { listRoles, createRole, updateRole, deleteRole, listUsers } from '../../api/admin'
import PageBar from '../../components/PageBar.vue'

const roles = ref([])
const loading = ref(false)
const dialog = ref(false)
const editing = ref(null)
const saving = ref(false)
const form = ref({ name: '', code: '', description: '', permissions: [], sortOrder: 0 })
const PERM_OPTIONS = [
  { value: 'USER_MANAGE', label: '用户管理' },
  { value: 'DOC_REVIEW', label: '文章审核' },
  { value: 'POST_MANAGE', label: '帖子管理' },
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
  { value: 'ASSET_MANAGE', label: '资产管理' },
  { value: 'AVATAR_REVIEW', label: '头像审核' },
]
const permLabel = (v) => (PERM_OPTIONS.find(o => o.value === v)?.label || v)

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
    const { data } = await listUsers({ size: 200 })
    const rows = data.content || []
    const cnt = {}
    rows.forEach(u => { (u.roleIds || []).forEach(rid => { cnt[rid] = (cnt[rid] || 0) + 1 }) })
    memberCount.value = cnt
  } catch {}
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
      ElMessage.success('已更新角色')
    } else {
      await createRole(payload)
      ElMessage.success('已创建角色')
    }
    dialog.value = false
    await load(); await loadMembers()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '操作失败')
  } finally {
    saving.value = false
  }
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
    ElMessage.error(e.response?.data?.error || '删除失败')
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
      <el-table :data="roles" v-loading="loading" class="role-table">
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
          <template #default="{ row }">{{ memberCount[row.id] || 0 }} 人</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button text type="primary" :icon="Edit" @click="openEdit(row)">编辑</el-button>
            <el-button text type="danger" :icon="Delete" :disabled="row.builtin" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialog" :title="editing ? '编辑角色' : '新建角色'" width="560px" class="role-dialog">
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
          <el-checkbox-group v-model="form.permissions" class="perm-grid">
            <el-checkbox v-for="o in PERM_OPTIONS" :key="o.value" :value="o.value" border>{{ o.label }}</el-checkbox>
          </el-checkbox-group>
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
  background: linear-gradient(135deg, #6366f1, #4f46e5); color: #fff; box-shadow: 0 4px 12px rgba(79,70,229,.3); flex-shrink: 0;
}
.head-text { flex: 1; min-width: 220px; }
.head-text h2 { margin: 0; font-size: 20px; font-weight: 700; color: #1e293b; }
.head-text p { margin: 4px 0 0; font-size: 13px; color: #94a3b8; }
.role-name { font-weight: 600; color: #1e293b; }
.builtin-tag { margin-left: 6px; }
.perm-tag { margin: 2px 4px 2px 0; }
.muted { color: #94a3b8; font-size: 13px; }
.panel { border-radius: 14px; }
.perm-grid { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
