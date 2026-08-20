<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Loading, Picture, Headset } from '@element-plus/icons-vue'
import { listOrphanFiles, deleteOrphanFile } from '../../api/admin'

const files = ref([])
const loading = ref(false)
const search = ref('')
const selected = ref([])
const kindTab = ref('all')   // all | image | audio

// 前端分页：后端一次性返回全部孤儿文件，按页切片渲染，避免一次性渲染上千行 + 全量图片占用内存
const currentPage = ref(1)
const pageSize = ref(20)
const paged = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})
watch([search, kindTab], () => { currentPage.value = 1 })

// 后端仅返回 url，按扩展名判定类型（当前仅开放图片与音频上传）
const kindOf = (url) =>
  /\.(mp3|wav|ogg|webm|m4a|flac|aac)$/i.test(url || '') ? 'audio' : 'image'

const filtered = computed(() => {
  const k = search.value.trim().toLowerCase()
  let r = files.value
  if (k) r = r.filter((f) => (f.url || '').toLowerCase().includes(k))
  if (kindTab.value !== 'all') r = r.filter((f) => kindOf(f.url) === kindTab.value)
  return r
})
const imageCount = computed(() => files.value.filter((f) => kindOf(f.url) === 'image').length)
const audioCount = computed(() => files.value.filter((f) => kindOf(f.url) === 'audio').length)

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

const load = async () => {
  loading.value = true
  try {
    const { data } = await listOrphanFiles()
    files.value = data || []
    selected.value = []
    currentPage.value = 1
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

const onSelectionChange = (rows) => { selected.value = rows }

const remove = async (f) => {
  try {
    await ElMessageBox.confirm('确定删除文件 ' + f.url + ' ？删除后不可恢复。', '删除附件', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteOrphanFile(f.url)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '删除失败')
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
  await load()
}

const preview = (url) => window.open(url, '_blank')

onMounted(load)
</script>

<template>
  <div class="attach">
    <div class="attach-header">
      <h2>附件管理</h2>
      <span class="tip">未被任何文档引用的上传文件（孤儿文件）。建议定期清理以节省磁盘。</span>
      <el-button type="primary" :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-tabs v-model="kindTab" class="seg">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane :label="`图片 (${imageCount})`" name="image" />
      <el-tab-pane :label="`音频 (${audioCount})`" name="audio" />
    </el-tabs>

    <div class="toolbar">
      <el-input v-model="search" placeholder="按路径搜索，如 /uploads/2026" clearable style="width: 280px;" />
      <span class="count">共 {{ files.length }} 个，筛选后 {{ filtered.length }} 个</span>
      <el-button type="danger" :disabled="!selected.length" @click="batchRemove">
        批量删除（{{ selected.length }}）
      </el-button>
    </div>

    <el-alert v-if="!loading && files.length === 0" type="success" :closable="false" style="margin-bottom: 14px;">
      暂无孤儿文件，所有上传文件均被文档引用。
    </el-alert>
    <div v-else-if="loading && files.length === 0" class="attach-loading">
      <el-icon class="spin"><Loading /></el-icon> 附件加载中…
    </div>

    <template v-else>
      <el-table
        :data="paged"
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
        <el-table-column prop="url" label="路径" min-width="240" show-overflow-tooltip />
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
      <el-pagination
        v-if="filtered.length > pageSize"
        class="pager" background
        layout="total, sizes, prev, pager, next, jumper"
        :total="filtered.length"
        :page-size="pageSize" :page-sizes="[10, 20, 50, 100]"
        :current-page="currentPage"
        @current-change="currentPage = $event"
        @size-change="pageSize = $event"
      />
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
</style>
