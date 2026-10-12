<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { View, Hide, Top, Bell } from '@element-plus/icons-vue'
import { errMsg } from '../../utils/errMsg'
import {
  listAnnouncementsAdmin,
  createAnnouncement,
  updateAnnouncement,
  deleteAnnouncement,
  toggleAnnouncementPin,
  toggleAnnouncementEnabled,
} from '../../api/kb'

const items = ref([])
const dialog = ref(false)
const saving = ref(false)
const loading = ref(false)
const page = ref(1)
const size = ref(15)
const total = ref(0)
const form = ref({ id: null, title: '', content: '', pinned: false, enabled: true, sortOrder: 0 })
const isEdit = computed(() => !!form.value.id)
const statusTab = ref('all')   // all | enabled | disabled

const visibleItems = computed(() =>
  statusTab.value === 'all'
    ? items.value
    : items.value.filter((r) => (statusTab.value === 'enabled') === r.enabled),
)

const relTime = (iso) => {
  if (!iso) return '-'
  const t = new Date(iso)
  if (isNaN(t.getTime())) return iso
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const hhmm = pad(t.getHours()) + ':' + pad(t.getMinutes())
  if (t.toDateString() === now.toDateString()) return '今天 ' + hhmm
  const y = new Date(now); y.setDate(now.getDate() - 1)
  if (t.toDateString() === y.toDateString()) return '昨天 ' + hhmm
  return iso.slice(0, 10) + ' ' + hhmm
}
const rowClass = ({ row }) => (row.pinned ? 'pinned-row' : '')

const load = async () => {
  loading.value = true
  try {
    const { data } = await listAnnouncementsAdmin({ page: page.value - 1, size: size.value })
    items.value = data.content || []
    total.value = data.totalElements || 0
  } catch (e) {
    ElMessage.error('加载公告失败')
  } finally {
    loading.value = false
  }
}

const onPage = (p) => { page.value = p; load() }
const onSize = (s) => { size.value = s; page.value = 1; load() }
const onTab = () => { page.value = 1; load() }

const openCreate = () => {
  form.value = { id: null, title: '', content: '', pinned: false, enabled: true, sortOrder: 0 }
  dialog.value = true
}

const openEdit = (row) => {
  form.value = {
    id: row.id,
    title: row.title,
    content: row.content,
    pinned: row.pinned,
    enabled: row.enabled,
    sortOrder: row.sortOrder,
  }
  dialog.value = true
}

const save = async () => {
  const f = form.value
  if (!f.title || !f.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  saving.value = true
  try {
    const payload = {
      title: f.title.trim(),
      content: (f.content || '').trim(),
      pinned: f.pinned,
      enabled: f.enabled,
      sortOrder: Number(f.sortOrder) || 0,
    }
    if (f.id) {
      await updateAnnouncement(f.id, payload)
      ElMessage.success('公告已更新')
    } else {
      await createAnnouncement(payload)
      ElMessage.success('公告已创建')
    }
    dialog.value = false
    if (!f.id) page.value = 1   // 新建后回到首页，确保看到新公告
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

const remove = async (row) => {
  try {
    await ElMessageBox.confirm(`删除公告「${row.title}」？`, '确认删除', { type: 'warning' })
    await deleteAnnouncement(row.id)
    ElMessage.success('已删除')
    await load()
    // 删的是当前页最后一条时回退一页，避免停在空页码（列表有前端 tab 筛选，按可见项判断）
    if (visibleItems.value.length === 0 && page.value > 1) { page.value--; await load() }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

const onTogglePin = async (row) => {
  try {
    await toggleAnnouncementPin(row.id)
    ElMessage.success(row.pinned ? '已取消置顶' : '已置顶')
    await load()
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const onToggleEnabled = async (row) => {
  try {
    await toggleAnnouncementEnabled(row.id)
    ElMessage.success(row.enabled ? '已禁用' : '已启用')
    await load()
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="ann-manage">
    <div class="ann-header">
      <div class="head-left">
        <div class="head-icon"><el-icon :size="22"><Bell /></el-icon></div>
        <h2>公告管理</h2>
      </div>
      <el-button type="primary" @click="openCreate">新建公告</el-button>
    </div>

    <el-tabs v-model="statusTab" class="seg" @tab-change="onTab">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="启用" name="enabled" />
      <el-tab-pane label="禁用" name="disabled" />
    </el-tabs>

    <el-table v-stack-table :data="visibleItems" border style="width: 100%" row-key="id" :row-class-name="rowClass" v-loading="loading">
      <el-table-column prop="id" label="ID" width="70"  class-name="stack-hide" />
      <el-table-column label="标题" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">
          <el-icon v-if="row.pinned" class="pin-ic"><Top /></el-icon>
          {{ row.title }}
        </template>
      </el-table-column>
      <el-table-column prop="content" label="内容" min-width="200" show-overflow-tooltip />
      <el-table-column label="状态" width="110" align="center">
        <template #default="{ row }">
          <span v-if="row.enabled" class="st ok"><el-icon><View /></el-icon> 启用</span>
          <span v-else class="st off"><el-icon><Hide /></el-icon> 禁用</span>
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="80" align="center" class-name="stack-hide" />
      <el-table-column label="更新时间" width="150">
        <template #default="{ row }"><span class="time">{{ relTime(row.updatedAt) }}</span></template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button size="small" @click="openEdit(row)">编辑</el-button>
            <el-button size="small" :type="row.pinned ? 'info' : 'warning'" @click="onTogglePin(row)">
              {{ row.pinned ? '取消置顶' : '置顶' }}
            </el-button>
            <el-button size="small" :type="row.enabled ? 'info' : 'success'" @click="onToggleEnabled(row)">
              {{ row.enabled ? '禁用' : '启用' }}
            </el-button>
            <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="total > 0" class="pager"
                   layout="total, sizes, prev, pager, next, jumper"
                   :total="total" :page-size="size" :current-page="page"
                   :page-sizes="[10, 15, 30, 50]"
                   @current-change="onPage" @size-change="onSize" />

    <el-dialog v-model="dialog" :title="isEdit ? '编辑公告' : '新建公告'" width="560px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" placeholder="公告标题" maxlength="120" show-word-limit />
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="form.content" type="textarea" :rows="4" placeholder="公告正文（可选）" maxlength="500" show-word-limit />
        </el-form-item>
        <el-form-item label="置顶">
          <el-switch v-model="form.pinned" active-text="置顶" inactive-text="普通" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="禁用" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :max="9999" controls-position="right" />
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
.ann-manage { padding: 20px; max-width: 1200px; margin: 0 auto; }
.ann-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.ann-header h2 { margin: 0; font-size: 20px; color: var(--el-text-color-primary); }
.seg { margin-bottom: 6px; }
.pin-ic { color: #f59e0b; margin-right: 4px; vertical-align: middle; }
.st { display: inline-flex; align-items: center; gap: 4px; padding: 2px 9px; border-radius: 999px; font-size: 13px; font-weight: 500; }
.st.ok { color: #10b981; background: #ecfdf5; }
.st.off { color: #94a3b8; background: #f1f5f9; }
.st .el-icon { font-size: 14px; }
.time { color: var(--el-text-color-secondary); font-size: 13px; }
.row-actions { display: flex; flex-wrap: wrap; gap: 6px; justify-content: center; }
.pager { margin-top: 14px; justify-content: flex-end; }
:deep(.pinned-row) { background: #fffbeb; }
html.dark :deep(.pinned-row) { background: #2a2410; }
html.dark .st.ok { background: #14291f; }
html.dark .st.off { background: #23232e; }

/* ============================================================
   响应式布局（断点统一：≤1024 平板 / ≤640 手机）
   只写 width / flex / padding / margin / font-size / gap
   ============================================================ */
@media (max-width: 1024px) {
  .ann-manage { padding: 14px; }
  /* 页头「标题 + 新建按钮」：允许换行 */
  .ann-header { flex-wrap: wrap; gap: 10px; }
  .pager { margin-top: 12px; }
}

@media (max-width: 640px) {
  .ann-manage { padding: 12px; }
  .ann-header h2 { font-size: 18px; }
  /* 操作列四个按钮已自带换行，这里再收紧间距与字号 */
  .row-actions { gap: 4px; }
  .st { font-size: 12px; padding: 2px 8px; }
  :deep(.el-table) { font-size: 13px; }
}

/* 页头彩色图标徽章（与用户管理页统一） */
.head-left { display: flex; align-items: center; gap: 14px; min-width: 0; }
.head-icon { width: 46px; height: 46px; border-radius: 12px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: #fef3c7; color: #d97706; }
html.dark .head-icon { background: rgb(217 119 6 / .2); color: #fcd34d; }
</style>
