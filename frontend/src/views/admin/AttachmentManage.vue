<script setup>
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, Picture, Headset, Paperclip } from '@element-plus/icons-vue'
import PageBar from '../../components/PageBar.vue'
import { listOrphanFiles, deleteOrphanFile } from '../../api/admin'
import { errMsg } from '../../utils/errMsg'

const files = ref([])          // 当前页内容（服务端分页）
const total = ref(0)           // 过滤后的总条数（服务端算）
const imageCount = ref(0)      // 全量图片数（类型 Tab 徽标用）
const audioCount = ref(0)      // 全量音频数
const loading = ref(false)
const search = ref('')
const selected = ref([])
const kindTab = ref('all')     // all | image | audio

// 服务端分页参数
const currentPage = ref(1)
const pageSize = ref(20)

// 搜索防抖：输入停 350ms 再发请求
let debounceTimer = null
// 回第 1 页并重查（页码已是 1 时直接查，避免 watch 双触发重复请求）
const gotoFirstPage = () => {
  if (currentPage.value === 1) load()
  else currentPage.value = 1   // 触发 watch(currentPage) → load()
}
watch(search, () => {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(gotoFirstPage, 350)
})
// 切类型 Tab：回第 1 页重查
watch(kindTab, gotoFirstPage)
// 翻页 / 改页大小
watch(currentPage, () => load())
watch(pageSize, gotoFirstPage)
onBeforeUnmount(() => clearTimeout(debounceTimer))

// 后端仅返回 url，按扩展名判定类型（当前仅开放图片与音频上传）
const kindOf = (url) =>
  /\.(mp3|wav|ogg|webm|m4a|flac|aac)$/i.test(url || '') ? 'audio' : 'image'

const fmtSize = (b) => {
  if (b == null) return '-'
  if (b < 1024) return b + ' B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB'
  return (b / 1024 / 1024).toFixed(2) + ' MB'
}
const relTime = (ts) => {
  if (!ts) return '-'
  const t = new Date(ts)
  if (isNaN(t.getTime())) return '-'
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const hhmm = pad(t.getHours()) + ':' + pad(t.getMinutes())
  if (t.toDateString() === now.toDateString()) return '今天 ' + hhmm
  const y = new Date(now); y.setDate(now.getDate() - 1)
  if (t.toDateString() === y.toDateString()) return '昨天 ' + hhmm
  return t.toLocaleDateString('zh-CN', { hour12: false })
}

// 请求代次守卫：快速切换类型 tab / 搜索 / 翻页时，慢响应不得覆盖新数据
let loadSeq = 0
const load = async () => {
  const seq = ++loadSeq
  loading.value = true
  try {
    const { data } = await listOrphanFiles({
      page: currentPage.value,
      size: pageSize.value,
      kind: kindTab.value,
      q: search.value.trim() || undefined,
    })
    if (seq !== loadSeq) return   // 已有更新的请求，丢弃本次响应
    files.value = data?.content || []
    total.value = data?.total || 0
    imageCount.value = data?.imageCount || 0
    audioCount.value = data?.audioCount || 0
    selected.value = []
  } catch (e) {
    if (seq === loadSeq) ElMessage.error(errMsg(e, '加载失败'))
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

const onSelectionChange = (rows) => { selected.value = rows }

// 删除后若当前页已被删空，回退一页
const stepBackIfEmpty = () => {
  if (files.value.length === 0 && currentPage.value > 1) currentPage.value--
  else load()
}

const remove = async (f) => {
  try {
    await ElMessageBox.confirm('确定删除文件 ' + f.url + ' ？删除后不可恢复。', '删除附件', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteOrphanFile(f.url)
    ElMessage.success('已删除')
    stepBackIfEmpty()
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}

const batchRemove = async () => {
  if (!selected.value.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${selected.value.length} 个文件？删除后不可恢复。`,
      '批量删除', { type: 'warning' },
    )
  } catch {
    return
  }
  loading.value = true
  let ok = 0, fail = 0
  for (const f of selected.value) {
    try { await deleteOrphanFile(f.url); ok++ }
    catch { fail++ }
  }
  loading.value = false
  ElMessage.success(`已删除 ${ok} 个${fail ? '，失败 ' + fail + ' 个' : ''}`)
  selected.value = []
  stepBackIfEmpty()
}

const preview = (url) => window.open(url, '_blank')

onMounted(load)
</script>

<template>
  <div class="attach">
    <div class="attach-header">
      <div class="head-left">
        <div class="head-icon"><el-icon :size="22"><Paperclip /></el-icon></div>
        <h2>附件管理</h2>
      <span class="tip">未被任何文档引用的上传文件（孤儿文件）。建议定期清理以节省磁盘。</span>
      </div>
      <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-tabs v-model="kindTab" class="seg">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane :label="`图片 (${imageCount})`" name="image" />
      <el-tab-pane :label="`音频 (${audioCount})`" name="audio" />
    </el-tabs>

    <div class="toolbar">
      <el-input v-model="search" placeholder="按路径搜索，如 /uploads/2026" clearable style="width: 280px;" />
      <span class="count">共 {{ total }} 个</span>
      <el-select v-model="pageSize" style="width: 110px">
        <el-option v-for="n in [10, 20, 50, 100]" :key="n" :label="n + ' 条/页'" :value="n" />
      </el-select>
      <el-button type="danger" :disabled="!selected.length" @click="batchRemove">
        批量删除（{{ selected.length }}）
      </el-button>
    </div>

    <el-alert v-if="!loading && total === 0" type="success" :closable="false" style="margin-bottom: 14px;">
      暂无孤儿文件，所有上传文件均被文档引用。
    </el-alert>
    <div v-else-if="loading && files.length === 0" class="attach-loading">
      <el-icon class="spin"><Loading /></el-icon> 附件加载中…
    </div>

    <template v-else>
      <el-table v-stack-table
        :data="files"
        border
        stripe
        v-loading="loading"
        style="width: 100%"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="48" />
        <el-table-column label="类型" width="80" align="center">
          <template #default="{ row }">
            <el-icon v-if="kindOf(row.url) === 'audio'" class="kind-ic audio"><Headset /></el-icon>
            <el-icon v-else class="kind-ic image"><Picture /></el-icon>
          </template>
        </el-table-column>
        <el-table-column label="预览" width="80" align="center">
          <template #default="{ row }">
            <img v-if="kindOf(row.url) === 'image'" :src="row.url" class="thumb" alt="" loading="lazy" />
            <span v-else class="audio-tag">音频</span>
          </template>
        </el-table-column>
        <!-- 路径：手机卡片里隐藏（长 URL 会把卡片撑成一团，删除/预览靠操作列） -->
        <el-table-column prop="url" label="路径" min-width="240" show-overflow-tooltip class-name="stack-hide" />
        <el-table-column label="大小" width="120">
          <template #default="{ row }">{{ fmtSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="修改时间" width="150">
          <template #default="{ row }"><span class="time">{{ relTime(row.lastModified) }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="160">
          <template #default="{ row }">
            <el-button size="small" @click="preview(row.url)">预览</el-button>
            <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <PageBar v-if="total > 0" v-model="currentPage" :total="total" :page-size="pageSize" class="pager" />
    </template>
  </div>
</template>

<style scoped>
.attach { padding: 20px; max-width: 1200px; margin: 0 auto; }
.attach-header { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; }
.attach-header h2 { margin: 0; font-size: 20px; color: #111827; }
.tip { color: #94a3b8; font-size: 13px; flex: 1; }
.seg { margin-bottom: 6px; }
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.count { color: #94a3b8; font-size: 13px; }
.attach-loading { padding: 60px 20px; text-align: center; color: #94a3b8; display: flex; align-items: center; justify-content: center; gap: 10px; }
.spin { animation: attach-spin .8s linear infinite; }
@keyframes attach-spin { to { transform: rotate(360deg); } }
.thumb { width: 56px; height: 56px; object-fit: cover; border-radius: 6px; border: 1px solid #e2e8f0; }
.kind-ic { font-size: 22px; }
.kind-ic.image { color: #3b82f6; }
.kind-ic.audio { color: #10b981; }
.audio-tag { color: #10b981; font-size: 12px; }
.time { color: #64748b; font-size: 13px; }
.pager { margin-top: 14px; justify-content: flex-end; }

/* 暗色模式适配：本页 .tip / .time 已由全局 theme.css 覆盖，图标色 #3b82f6 / #10b981 在暗底对比度达标，均不改 */
html.dark .attach-header h2 { color: var(--el-text-color-primary); }
html.dark .thumb { border-color: var(--el-border-color); }

/* ============================================================
   响应式布局（断点统一：≤1024 平板 / ≤640 手机）
   只写 width / flex / padding / margin / font-size
   ============================================================ */
@media (max-width: 1024px) {
  .attach { padding: 14px; }
  /* 页头「标题 + 说明 + 刷新」：允许换行 */
  .attach-header { flex-wrap: wrap; gap: 10px; }
  /* 工具栏：搜索框 + 计数 + 批量删除，挤不下时换行 */
  .toolbar { flex-wrap: wrap; gap: 10px; }
  .pager { margin-top: 12px; }
}

@media (max-width: 640px) {
  .attach { padding: 12px; }
  .attach-header h2 { font-size: 18px; }
  /* 说明文字独占一行，标题与刷新按钮留在首行 */
  .tip { flex: 1 1 100%; font-size: 12px; }
  /* 搜索框占整行（宽度写在内联 style 上，需 !important 才能覆盖） */
  .toolbar :deep(.el-input) { width: 100% !important; }
  .thumb { width: 44px; height: 44px; }
  .attach-loading { padding: 40px 12px; font-size: 13px; }
  :deep(.el-table) { font-size: 13px; }
}

/* 页头彩色图标徽章（与用户管理页统一） */
.head-left { display: flex; align-items: center; gap: 14px; min-width: 0; }
.head-icon { width: 46px; height: 46px; border-radius: 12px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: #e0f2fe; color: #0284c7; }
html.dark .head-icon { background: rgb(2 132 199 / .18); color: #7dd3fc; }
</style>
