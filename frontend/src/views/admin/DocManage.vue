<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Close, Lock, EditPen, Top } from '@element-plus/icons-vue'
import {
  listDocsAdmin, deleteDoc, blockDoc, unblockDoc, pinDoc, unpinDoc, approveDoc, rejectDoc,
} from '../../api/admin'

const route = useRoute()
const router = useRouter()
const docs = ref([])
const statusTab = ref('all')   // all | PUBLISHED | PENDING_REVIEW | DRAFT | BLOCKED

const keyword = ref('')
const sort = ref('updatedAt')
const dir = ref('desc')
const page = ref(1)
const size = ref(15)
const total = ref(0)

// 页码与筛选写进路由 query：文章详情页的「返回」是 router.back()，回来时本组件会重新挂载，
// 组件内 ref 会全部归位（page=1）。用 router.replace 同步 URL（不新增历史记录），
// 从文章返回即回到翻页前那条 URL，刷新/复制链接也一样能停在第 N 页。
const qInt = (v, fallback) => {
  const x = Number(v)
  return Number.isInteger(x) && x > 0 ? x : fallback
}
const VALID_TABS = ['all', 'PUBLISHED', 'PENDING_REVIEW', 'DRAFT', 'BLOCKED']
const VALID_SORTS = ['updatedAt', 'created', 'views']
if (route.query.page !== undefined) page.value = qInt(route.query.page, 1)
if (VALID_TABS.includes(route.query.status)) statusTab.value = route.query.status
if (typeof route.query.q === 'string') keyword.value = route.query.q.slice(0, 100)
if (VALID_SORTS.includes(route.query.sort)) sort.value = route.query.sort
if (route.query.dir === 'asc' || route.query.dir === 'desc') dir.value = route.query.dir
if ([5, 15, 30].includes(qInt(route.query.size, 0))) size.value = qInt(route.query.size, 15)

const QUERY_KEYS = ['page', 'status', 'q', 'sort', 'dir', 'size']
const syncQuery = () => {
  const next = { ...route.query }
  for (const k of QUERY_KEYS) delete next[k]
  if (page.value > 1) next.page = String(page.value)
  if (statusTab.value !== 'all') next.status = statusTab.value
  if (keyword.value.trim()) next.q = keyword.value.trim()
  if (sort.value !== 'updatedAt') next.sort = sort.value
  if (dir.value !== 'desc') next.dir = dir.value
  if (size.value !== 15) next.size = String(size.value)
  const pick = (o) => {
    const r = {}
    for (const k of QUERY_KEYS) if (o[k] !== undefined) r[k] = String(o[k])
    return r
  }
  if (JSON.stringify(pick(route.query)) === JSON.stringify(pick(next))) return
  router.replace({ query: next })
}
// keyword 由 v-model 逐字变化，不放进 watch 依赖（否则每敲一个字都改一次 URL）；
// 搜索回车/清空时在 onSearch 里显式同步一次。
watch([page, statusTab, sort, dir, size], syncQuery, { flush: 'post' })

const STATUS_META = {
  PUBLISHED:      { label: '正常',   icon: Check,  color: '#10b981', bg: '#ecfdf5' },
  PENDING_REVIEW: { label: '待审核', icon: Close,  color: '#f59e0b', bg: '#fffbeb' },
  DRAFT:          { label: '草稿',   icon: EditPen, color: '#64748b', bg: '#f1f5f9' },
  BLOCKED:        { label: '已封禁', icon: Lock,   color: '#ef4444', bg: '#fef2f2' },
}
const statusMeta = (s) => STATUS_META[s] || { label: s, icon: EditPen, color: '#64748b', bg: '#f1f5f9' }

const visibleDocs = computed(() =>
  statusTab.value === 'all' ? docs.value : docs.value.filter((d) => d.status === statusTab.value),
)

const load = async () => {
  try {
    const { data } = await listDocsAdmin({ keyword: keyword.value, sort: sort.value, dir: dir.value, page: page.value - 1, size: size.value })
    docs.value = data.content || []
    total.value = data.totalElements || 0
  } catch (e) {
    ElMessage.error('加载失败')
  }
}
const onSearch = () => { page.value = 1; syncQuery(); load() }
const onSort = () => { dir.value = 'desc'; page.value = 1; load() }
const onSortChange = ({ prop, order }) => {
  if (!prop || !order) return
  sort.value = prop
  dir.value = order === 'ascending' ? 'asc' : 'desc'
  page.value = 1
  load()
}
const onPage = (p) => { page.value = p; load() }
const onSize = (s) => { size.value = s; page.value = 1; load() }
const onTab = () => { page.value = 1 }

const edit = (d) => router.push('/write/edit/' + d.id)
const goDetail = (d) => router.push('/post/' + d.id)

// 删除/封禁/审核等操作会让条目离开当前列表（含前端 tab 筛选后这页为空的情况），
// 重载后若当前页没内容就回退一页再拉一次，避免停在空页码。
const reloadAfterRemoval = async () => {
  await load()
  if (visibleDocs.value.length === 0 && page.value > 1) {
    page.value--
    await load()
  }
}

const remove = async (d) => {
  try {
    await ElMessageBox.confirm('确定删除《' + d.title + '》？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteDoc(d.id)
    ElMessage.success('已删除')
    await reloadAfterRemoval()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}
const block = async (d) => {
  try { await blockDoc(d.id); ElMessage.success('已加入黑名单'); await reloadAfterRemoval() }
  catch { ElMessage.error('操作失败') }
}
const unblock = async (d) => {
  try { await unblockDoc(d.id); ElMessage.success('已解封'); await reloadAfterRemoval() }
  catch { ElMessage.error('操作失败') }
}
const togglePin = async (d) => {
  try {
    if (d.pinned) { await unpinDoc(d.id); ElMessage.success('已取消置顶') }
    else { await pinDoc(d.id); ElMessage.success('已置顶') }
    await load()
  } catch { ElMessage.error('操作失败') }
}
const approve = async (d) => {
  try { await approveDoc(d.id); ElMessage.success('已通过，帖子已公开'); await reloadAfterRemoval() }
  catch { ElMessage.error('操作失败') }
}
const reject = async (d) => {
  let reason = ''
  try {
    const r = await ElMessageBox.prompt('请填写驳回理由（将退回作者草稿，作者可见，必填）', '驳回审核',
      { confirmButtonText: '确定驳回', cancelButtonText: '取消', inputType: 'textarea',
        inputPlaceholder: '例如：内容不完整 / 含敏感词 / 需补充来源',
        inputValidator: (v) => (v && v.trim()) ? true : '请填写驳回理由后再提交' })
    reason = (r && r.value) ? r.value.trim() : ''
  } catch { return }
  try { await rejectDoc(d.id, reason); ElMessage.success('已驳回，退回作者草稿'); await reloadAfterRemoval() }
  catch { ElMessage.error('操作失败') }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <el-button type="primary" @click="router.push('/write')">写文章</el-button>
    </div>

    <el-tabs v-model="statusTab" class="seg" @tab-change="onTab">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="正常" name="PUBLISHED" />
      <el-tab-pane label="待审核" name="PENDING_REVIEW" />
      <el-tab-pane label="草稿" name="DRAFT" />
      <el-tab-pane label="已封禁" name="BLOCKED" />
    </el-tabs>

    <div class="filters">
      <el-input v-model="keyword" placeholder="搜索标题 / 正文 / 分类" clearable
                style="width: 240px" @keyup.enter="onSearch" @clear="onSearch" />
      <el-select v-model="sort" style="width: 130px" @change="onSort">
        <el-option label="最新更新" value="updatedAt" />
        <el-option label="发布时间" value="created" />
        <el-option label="浏览最多" value="views" />
      </el-select>
    </div>

    <el-table v-stack-table :data="visibleDocs" border stripe @sort-change="onSortChange">
      <!-- ID：手机卡片里隐藏（次要字段，标题/分类/状态才是筛选依据） -->
      <el-table-column prop="id" label="ID" width="70" sortable="custom" :sort-orders="['ascending','descending']" class-name="stack-hide" />
      <el-table-column label="标题">
        <template #default="{ row }">
          <span class="link" @click="goDetail(row)">{{ row.title }}</span>
          <el-icon v-if="row.pinned" class="pin"><Top /></el-icon>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="110" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <span class="st" :style="{ color: statusMeta(row.status).color, background: statusMeta(row.status).bg }">
            <el-icon><component :is="statusMeta(row.status).icon" /></el-icon>
            {{ statusMeta(row.status).label }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="190" sortable="custom" :sort-orders="['ascending','descending']" />
      <el-table-column label="操作" width="330">
        <template #default="{ row }">
          <el-button size="small" @click="edit(row)">编辑</el-button>
          <el-button size="small" :type="row.pinned ? 'primary' : 'info'" plain @click="togglePin(row)">{{ row.pinned ? '取消置顶' : '置顶' }}</el-button>
          <el-button size="small" type="warning" v-if="row.status !== 'BLOCKED'" @click="block(row)">封禁</el-button>
          <el-button size="small" type="success" v-else @click="unblock(row)">解封</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="total > size" class="pager"
                   layout="total, sizes, prev, pager, next"
                   :total="total" :page-size="size" :current-page="page"
                   :page-sizes="[5, 15, 30]"
                   @current-change="onPage" @size-change="onSize" />
  </div>
</template>

<style scoped>
.bar { margin-bottom: 12px; }
.seg { margin-bottom: 4px; }
.filters { display: flex; gap: 10px; margin-bottom: 12px; }
.pager { margin-top: 14px; justify-content: flex-end; }
.link { color: #4f46e5; cursor: pointer; }
.link:hover { text-decoration: underline; }
.pin { color: #f59e0b; vertical-align: middle; margin-left: 4px; }
.st { display: inline-flex; align-items: center; gap: 4px; padding: 2px 9px; border-radius: 999px; font-size: 13px; font-weight: 500; }
.st .el-icon { font-size: 14px; }

/* 暗色模式适配（标题链接、置顶图标、状态 chip） */
html.dark .link { color: #a5b4fc; }
html.dark .pin { color: #fbbf24; }
/* .st 的 color / background 由 STATUS_META 通过 :style 内联写入（<script> 不可改），常规选择器压不过内联样式，只能用 !important 去掉浅色底 */
html.dark .st { background: rgba(255,255,255,.06) !important; }
/* 草稿态的 #64748b 灰字在暗底上偏暗，单独提亮（其余绿/橙/红在暗底上对比度足够，保留原色相） */
html.dark .st[style*="100, 116, 139"] { color: #94a3b8 !important; }

/* ============================================================
   响应式布局（断点统一：≤1024 平板 / ≤640 手机）
   只写 width / flex / padding / margin / font-size
   ============================================================ */
@media (max-width: 1024px) {
  /* 筛选条：搜索 + 排序挤不下时换行 */
  .filters { flex-wrap: wrap; gap: 8px; }
  .pager { margin-top: 12px; }
}

@media (max-width: 640px) {
  /* 搜索框 / 排序下拉各占整行（宽度写在内联 style 上，需 !important 才能覆盖） */
  .filters :deep(.el-input),
  .filters :deep(.el-select) { width: 100% !important; }
  /* ≤640 表格由 v-stack-table 转卡片，这里只收状态 chip 内距 */
  :deep(.el-table) { font-size: 13px; }
  .st { font-size: 12px; padding: 2px 8px; }
  .pager { margin-top: 10px; }
}
</style>
