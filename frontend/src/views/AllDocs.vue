<script setup>
/**
 * 全部文档（桌面端，2026-10-15）
 *
 * 把个人空间 + 参与空间里可读的文档汇总成一页，支持多标签管理与组合筛选：
 * 搜索（标题/标签）× 标签 × 空间 × 类型 × 更新时间。
 * 数据来自 /api/doc-shares/docs/all（服务端已按空间级/文档级权限逐条裁决）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Collection, Download } from '@element-plus/icons-vue'
import { contentMatches, snippetOf, clearDocSearchCache } from '../utils/docSearch'
import { listAllShareDocs, listMyShares, setShareDocTags, exportAllShareDocs, batchSetDocTags } from '../api/docShare'
import { errMsg } from '../utils/errMsg'
import { saveBinaryFile } from '../utils/shareOut'

const router = useRouter()
const loading = ref(false)
const docs = ref([])
const spaces = ref([])
const keyword = ref('')
const tagFilter = ref('')
const spaceFilter = ref(null)
const typeFilter = ref('')
const timeFilter = ref('')

const TYPES = [
  { value: 'TEXT', label: '文字' },
  { value: 'IMAGE', label: '图片' },
  { value: 'VIDEO', label: '视频' },
  { value: 'AUDIO', label: '音频' },
  { value: 'FILE', label: '附件' }
]
const TIMES = [
  { value: 7, label: '近 7 天' },
  { value: 30, label: '近 30 天' },
  { value: 90, label: '近 90 天' }
]

function tagsOf(d) {
  if (Array.isArray(d?.tags)) return d.tags.map((x) => String(x).trim()).filter(Boolean)
  return String(d?.tags || '').split(',').map((x) => x.trim()).filter(Boolean)
}
const typeLabel = (t) => TYPES.find((x) => x.value === t)?.label || '文字'

function fmtTime(s) {
  if (!s) return '—'
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return '—'
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const allTags = computed(() => {
  const map = new Map()
  for (const d of docs.value) for (const t of tagsOf(d)) {
    const k = t.toLowerCase()
    if (!map.has(k)) map.set(k, t)
  }
  return [...map.values()].sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const shown = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  const days = Number(timeFilter.value || 0)
  const from = days ? Date.now() - days * 86400000 : 0
  return docs.value.filter((d) => {
    const tags = tagsOf(d)
    // 标题 / 标签 / 正文 三处任一命中即可（正文检索见 utils/docSearch.js）
    if (q
      && !String(d.title || '').toLowerCase().includes(q)
      && !tags.some((t) => t.toLowerCase().includes(q))
      && !contentMatches(d, q)) return false
    if (tagFilter.value && !tags.some((t) => t.toLowerCase() === tagFilter.value.toLowerCase())) return false
    if (spaceFilter.value && Number(d.shareId) !== Number(spaceFilter.value)) return false
    if (typeFilter.value && d.contentType !== typeFilter.value) return false
    if (from) {
      const t = new Date(d.updatedAt || d.createdAt || 0).getTime()
      if (!t || t < from) return false
    }
    return true
  })
})

const filtersActive = computed(() => !!(keyword.value.trim() || tagFilter.value || spaceFilter.value || typeFilter.value || timeFilter.value))

/**
 * 客户端分页：「全部文档」接口是一次性全量下发（文档级权限过滤在服务端内存中裁决），
 * 这里只把渲染切成每页 N 条，避免上千行表格一次渲染卡顿；
 * 文档量继续增长（万级）时应改服务端分页，权限过滤需下推 SQL。
 */
const page = ref(1)
const pageSize = ref(50)
const paged = computed(() => shown.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
watch([keyword, tagFilter, spaceFilter, typeFilter, timeFilter], () => { page.value = 1 })

/** 关键词命中正文时，在标题下方显示一小段上下文 */
function hitSnippet(row) {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return ''
  if (String(row.title || '').toLowerCase().includes(q)) return ''
  if (tagsOf(row).some((t) => t.toLowerCase().includes(q))) return ''
  return snippetOf(row, q)
}

async function load() {
  loading.value = true
  try {
    const [a, s] = await Promise.all([listAllShareDocs(true), listMyShares()])
    docs.value = Array.isArray(a.data) ? a.data : []
    spaces.value = Array.isArray(s.data) ? s.data : []
    clearDocSearchCache()   // 列表刷新后丢弃旧的正文缓存
  } catch (e) {
    ElMessage.error('加载失败：' + errMsg(e))
  } finally {
    loading.value = false
  }
}

function clearFilters() {
  keyword.value = ''
  tagFilter.value = ''
  spaceFilter.value = null
  typeFilter.value = ''
  timeFilter.value = ''
}

function openDoc(row) {
  router.push(`/doc-shares/${row.shareId}?doc=${row.id}`)
}
const canTag = (row) => ['OWNER', 'ADMIN', 'WRITE'].includes(String(row.myPerm || '').toUpperCase())

// ---- 标签管理 ----
const tagDialog = ref(false)
const tagTarget = ref(null)
const tagDraft = ref([])
const tagSaving = ref(false)

function openTagDialog(row) {
  if (!canTag(row)) { ElMessage.warning('你只有阅读权限，不能修改此文档的标签'); return }
  tagTarget.value = row
  tagDraft.value = tagsOf(row)
  tagDialog.value = true
}

async function saveTags() {
  const row = tagTarget.value
  if (!row) return
  const cleaned = [...new Map(
    tagDraft.value.map((t) => String(t).trim()).filter(Boolean).map((t) => [t.toLowerCase(), t])
  ).values()]
  if (cleaned.length > 12) { ElMessage.warning('每篇文档最多 12 个标签'); return }
  if (cleaned.some((t) => t.length > 24)) { ElMessage.warning('单个标签不超过 24 个字'); return }
  tagSaving.value = true
  try {
    const { data } = await setShareDocTags(row.shareId, row.id, cleaned)
    const i = docs.value.findIndex((x) => x.id === row.id && x.shareId === row.shareId)
    if (i >= 0) docs.value[i] = { ...docs.value[i], tags: data?.tags || '' }
    tagDialog.value = false
    ElMessage.success(cleaned.length ? '标签已保存' : '已清除标签')
  } catch (e) {
    ElMessage.error('保存失败：' + errMsg(e))
  } finally {
    tagSaving.value = false
  }
}

async function removeTag(row, tag) {
  try {
    await ElMessageBox.confirm(`从「${row.title || '未命名文档'}」移除标签「${tag}」？`, '移除标签', {
      type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消'
    })
  } catch { return }
  const next = tagsOf(row).filter((t) => t.toLowerCase() !== tag.toLowerCase())
  try {
    const { data } = await setShareDocTags(row.shareId, row.id, next)
    const i = docs.value.findIndex((x) => x.id === row.id && x.shareId === row.shareId)
    if (i >= 0) docs.value[i] = { ...docs.value[i], tags: data?.tags || '' }
  } catch (e) {
    ElMessage.error('移除失败：' + errMsg(e))
  }
}

onMounted(load)

// ---------- 整库导出（需 DOC_EXPORT 权限，ADMIN 隐式拥有） ----------
const exporting = ref(false)
/**
 * 导出范围由后端按空间级/文档级权限逐条裁决，只能是「我能看的」那一部分。
 * 403 时后端返回的是 JSON，但 responseType=blob 会让错误体也变成 Blob，
 * 所以这里小体积时把它读出来，给出真实原因而不是笼统的「导出失败」。
 */
async function doExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    const { data } = await exportAllShareDocs()
    const name = `知识库导出-${new Date().toISOString().slice(0, 10)}.zip`
    const r = await saveBinaryFile({ filename: name, blob: data, mime: 'application/zip' })
    if (r === 'download') ElMessage.success('已开始下载')
    else if (!r) ElMessage.error('导出失败，请重试')
  } catch (e) {
    let msg = ''
    const d = e?.response?.data
    if (d instanceof Blob && d.size && d.size < 4096) {
      try { msg = JSON.parse(await d.text())?.error || '' } catch { /* 不是 JSON 就用默认文案 */ }
    } else if (typeof d?.error === 'string') msg = d.error
    ElMessage.error(msg || errMsg(e) || '导出失败（需要 DOC_EXPORT 权限）')
  } finally {
    exporting.value = false
  }
}

// ---------- 多选与批量操作 ----------
const tableRef = ref(null)
const selected = ref([])
const batchOpen = ref(false)
const batchAdd = ref([])
const batchRemove = ref([])
const batching = ref(false)

function onSelectionChange(rows) { selected.value = rows }
function clearSelection() {
  tableRef.value?.clearSelection()
  selected.value = []
}
/** 点勾选框那一列不应该顺带打开文档 */
function onRowClick(row, column) {
  if (column?.type === 'selection') return
  openDoc(row)
}
function openBatch() {
  batchAdd.value = []
  batchRemove.value = []
  batchOpen.value = true
}

async function doBatch() {
  const add = (batchAdd.value || []).map((x) => String(x).trim()).filter(Boolean)
  const remove = (batchRemove.value || []).map((x) => String(x).trim()).filter(Boolean)
  if (!add.length && !remove.length) return ElMessage.warning('请填写要添加或移除的标签')
  if (!selected.value.length) return ElMessage.warning('请先选择文档')
  batching.value = true
  try {
    const { data } = await batchSetDocTags(
      selected.value.map((r) => ({ shareId: r.shareId, docId: r.id })), add, remove)
    const ok = data?.ok || 0
    const skipped = data?.skipped || []
    // 部分跳过是正常结果（无写权限/文档已删），要说清楚而不是笼统报「成功」
    if (skipped.length) ElMessage.warning(`成功 ${ok} 篇，跳过 ${skipped.length} 篇（${skipped.slice(0, 2).join('；')}）`)
    else ElMessage.success(`已更新 ${ok} 篇文档的标签`)
    batchOpen.value = false
    await load()
    clearSelection()
  } catch (e) {
    ElMessage.error('批量更新失败：' + errMsg(e))
  } finally {
    batching.value = false
  }
}
</script>

<template>
  <div class="all-docs">
    <div class="bar">
      <h3 class="title">全部文档</h3>
      <span class="sec-desc">跨空间汇总你参与的所有文档</span>
      <span class="spacer" />
      <el-input v-model="keyword" placeholder="搜索标题、标签或正文" clearable style="width: 240px">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button :loading="exporting" style="margin-left: 8px" @click="doExport">
        <el-icon><Download /></el-icon>
        <span style="margin-left: 4px">导出全部</span>
      </el-button>
      <el-select v-model="tagFilter" placeholder="全部标签" clearable style="width: 140px">
        <el-option v-for="t in allTags" :key="t" :label="t" :value="t" />
      </el-select>
      <el-select v-model="spaceFilter" placeholder="全部空间" clearable style="width: 150px">
        <el-option v-for="s in spaces" :key="s.id" :label="s.name" :value="s.id" />
      </el-select>
      <el-select v-model="typeFilter" placeholder="全部类型" clearable style="width: 120px">
        <el-option v-for="t in TYPES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-select v-model="timeFilter" placeholder="全部时间" clearable style="width: 120px">
        <el-option v-for="t in TIMES" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>
      <el-button v-if="filtersActive" @click="clearFilters">清除条件</el-button>
      <el-button type="primary" @click="load">刷新</el-button>
    </div>

    <div class="summary">
      共 {{ shown.length }} 篇<template v-if="filtersActive">（已筛选，全部 {{ docs.length }} 篇）</template>
    </div>

    <!-- 多选后出现的批量操作条 -->
    <div v-if="selected.length" class="batch-bar">
      <span class="bb-count">已选 {{ selected.length }} 篇</span>
      <el-button size="small" type="primary" plain @click="openBatch">批量打标签</el-button>
      <el-button size="small" text @click="clearSelection">取消选择</el-button>
    </div>

    <el-table ref="tableRef" :data="paged" v-loading="loading" row-key="id" @row-click="onRowClick"
              @selection-change="onSelectionChange" style="width: 100%">
      <el-table-column type="selection" width="44" />
      <el-table-column label="标题" min-width="280">
        <template #default="{ row }">
          <div class="t-title">{{ row.title || '未命名文档' }}</div>
          <!-- 正文命中时给一小段上下文，说明「为什么这条会被搜到」 -->
          <div v-if="hitSnippet(row)" class="t-hit">{{ hitSnippet(row) }}</div>
          <div v-if="tagsOf(row).length" class="t-tags">
            <el-tag v-for="t in tagsOf(row)" :key="t" size="small" type="info" closable
                    @close.stop="removeTag(row, t)" @click.stop="tagFilter = t">{{ t }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="空间" width="170">
        <template #default="{ row }">
          <span class="t-space">{{ row.spaceName || '—' }}</span>
          <el-tag v-if="row.spaceType === 'PERSONAL'" size="small" effect="plain" style="margin-left:6px">个人</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ typeLabel(row.contentType) }}</template>
      </el-table-column>
      <el-table-column label="权限" width="90">
        <template #default="{ row }">{{ row.myPerm || '—' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ fmtTime(row.updatedAt || row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="openDoc(row)">打开</el-button>
          <el-button link type="primary" :disabled="!canTag(row)" @click.stop="openTagDialog(row)">
            <el-icon style="margin-right:3px"><Collection /></el-icon>标签
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="filtersActive ? '没有符合条件的文档' : '还没有可显示的文档'" />
      </template>
    </el-table>

    <!-- 分页：每页条数可调，筛选条件变化时自动回到第 1 页 -->
    <div class="pager" v-if="shown.length > 20">
      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        :total="shown.length"
        :page-sizes="[20, 50, 100, 200]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        small
      />
    </div>

    <el-dialog v-model="tagDialog" title="管理标签" width="440px">
      <div class="dlg-sub">「{{ tagTarget?.title || '未命名文档' }}」</div>
      <el-select v-model="tagDraft" multiple filterable allow-create default-first-option
                 placeholder="输入标签后回车，如：项目资料" style="width: 100%">
        <el-option v-for="t in allTags" :key="t" :label="t" :value="t" />
      </el-select>
      <div class="dlg-hint">每篇最多 12 个标签，单个标签不超过 24 个字；留空保存即清除全部标签。</div>
      <template #footer>
        <el-button @click="tagDialog = false">取消</el-button>
        <el-button type="primary" :loading="tagSaving" @click="saveTags">保存</el-button>
      </template>
    </el-dialog>

    <!-- 批量打标签 -->
    <el-dialog v-model="batchOpen" title="批量打标签" width="460px">
      <div class="dlg-sub">将对已选的 {{ selected.length }} 篇文档生效</div>
      <div style="margin-bottom:10px">
        <div class="dlg-label">添加标签</div>
        <el-select v-model="batchAdd" multiple filterable allow-create default-first-option
                   placeholder="输入后回车，可多选" style="width: 100%">
          <el-option v-for="t in allTags" :key="'a' + t" :label="t" :value="t" />
        </el-select>
      </div>
      <div>
        <div class="dlg-label">移除标签</div>
        <el-select v-model="batchRemove" multiple filterable allow-create default-first-option
                   placeholder="选中的标签会从这些文档上移除" style="width: 100%">
          <el-option v-for="t in allTags" :key="'r' + t" :label="t" :value="t" />
        </el-select>
      </div>
      <div class="dlg-hint">添加和移除可以只填一边。没有写入权限的文档会自动跳过，不会导致整批失败。</div>
      <template #footer>
        <el-button @click="batchOpen = false">取消</el-button>
        <el-button type="primary" :loading="batching" @click="doBatch">应用到 {{ selected.length }} 篇</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.pager { display: flex; justify-content: flex-end; margin-top: 10px; }
.bar { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.title { margin: 0; font-size: 18px; color: #0f172a; }
html.dark .title { color: #e2e8f0; }
.sec-desc { font-size: 12.5px; color: #94a3b8; }
.spacer { flex: 1; }
.summary { margin: 0 0 10px 2px; font-size: 12.5px; color: #64748b; }
html.dark .summary { color: #94a3b8; }

/* 多选后出现的批量操作条 */
.batch-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; padding: 8px 12px; border-radius: 8px; background: #eff6ff; border: 1px solid #bfdbfe; }
.batch-bar .bb-count { font-size: 13px; color: #1d4ed8; font-weight: 500; }
html.dark .batch-bar { background: #1e293b; border-color: #334155; }
html.dark .batch-bar .bb-count { color: #93c5fd; }
.dlg-label { font-size: 12.5px; color: #64748b; margin-bottom: 6px; }
html.dark .dlg-label { color: #94a3b8; }

.t-title { font-size: 14px; font-weight: 500; color: #1e293b; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
html.dark .t-title { color: #e2e8f0; }
/* 正文命中的上下文片段 */
.t-hit { margin-top: 2px; font-size: 12px; line-height: 1.5; color: #64748b; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; word-break: break-word; }
html.dark .t-hit { color: #94a3b8; }
.t-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 5px; }
.t-tags :deep(.el-tag) { cursor: pointer; }
.t-space { font-size: 13px; color: #475569; }
html.dark .t-space { color: #cbd5e1; }

.dlg-sub { font-size: 13px; color: #64748b; margin-bottom: 10px; }
.dlg-hint { margin-top: 8px; font-size: 12px; color: #94a3b8; line-height: 1.6; }
</style>
