<script setup>
/**
 * 网页端「我的文件」文件中心
 *
 * 与移动端 MobileFiles 共用同一套接口（api/file.js 的 listMyFiles / uploadManagedFile / deleteMyFile），
 * 两端行为一致：上传即登记归属、可预览、复制链接、删除。
 *
 * 设计：
 *  - 拖拽 / 点选上传，支持多选，逐个上传并显示进度（大文件 + 弱网，超时放宽到 120s）
 *  - 列表按时间倒序；图片显示缩略图，其余显示类型图标
 *  - 删除前二次确认（后端还会在文档正文引用时只解除登记，避免文档裂图）
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMyFiles, uploadManagedFile, deleteMyFile } from '../api/file'
import { getStorage } from '../api/docShare'

const files = ref([])
const loading = ref(false)
let uploadSeq = 0
const uploading = ref([])      // [{ id, name, percent }]（reactive 条目，进度才能触发重渲染）
const dragging = ref(false)
const inputRef = ref(null)

// 存储用量（让页面有信息量：知道自己用了多少 / 还剩多少）
const storage = reactive({ used: 0, quota: 0, has: false })
// 筛选与搜索
const filter = ref('all')      // all | image | media | doc
const keyword = ref('')

const FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'image', label: '图片' },
  { key: 'media', label: '音视频' },
  { key: 'doc', label: '文档' }
]

function fmtSize(n) {
  const v = Number(n || 0)
  if (!v) return '—'
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0, x = v
  while (x >= 1024 && i < u.length - 1) { x /= 1024; i++ }
  return (i === 0 ? x : x.toFixed(1)) + u[i]
}

function fmtTime(s) {
  if (!s) return ''
  return String(s).replace('T', ' ').slice(0, 16)
}

function isImage(f) {
  return /^image\//i.test(f.contentType || '') || /\.(png|jpe?g|gif|webp|bmp)$/i.test(f.url || '')
}
function isMedia(f) {
  return /^(audio|video)\//i.test(f.contentType || '') || /\.(mp4|webm|mov|mp3|wav|m4a|ogg)$/i.test(f.url || '')
}
function isDoc(f) {
  return /(pdf|word|excel|ppt|text|json|csv)/i.test(f.contentType || '') || /\.(pdf|docx?|xlsx?|pptx?|txt|md|csv)$/i.test(f.url || '')
}

const shown = computed(() => {
  let list = files.value
  if (filter.value === 'image') list = list.filter(isImage)
  else if (filter.value === 'media') list = list.filter(isMedia)
  else if (filter.value === 'doc') list = list.filter(isDoc)
  const k = keyword.value.trim().toLowerCase()
  if (k) list = list.filter((f) => String(f.name || '').toLowerCase().includes(k))
  return list
})

const usedPercent = computed(() => {
  if (!storage.quota) return 0
  return Math.min(100, Math.round((storage.used / storage.quota) * 100))
})

async function load() {
  loading.value = true
  try {
    const { data } = await listMyFiles()
    files.value = Array.isArray(data) ? data : []
  } catch {
    ElMessage.error('加载文件列表失败')
  } finally {
    loading.value = false
  }
  // 用量单独取，失败不影响列表
  try {
    const { data } = await getStorage()
    if (data && (data.used != null || data.quota != null)) {
      storage.used = Number(data.used || 0)
      storage.quota = Number(data.quota || 0)
      storage.has = true
    }
  } catch { /* 忽略 */ }
}

async function uploadAll(list) {
  for (const f of list) {
    const item = reactive({ id: ++uploadSeq, name: f.name || '文件', percent: 0 })
    uploading.value.push(item)
    try {
      await uploadManagedFile(f, (p) => { item.percent = p })
      ElMessage.success(`${item.name} 上传成功`)
    } catch (e) {
      const msg = (e && e.response && e.response.data && (e.response.data.message || e.response.data.error)) || (e && e.message) || '上传失败'
      ElMessage.error(`${item.name}：${msg}`)
    } finally {
      uploading.value = uploading.value.filter((x) => x !== item)
      await load()
    }
  }
}

function onPick(e) {
  const list = Array.from(e.target.files || [])
  e.target.value = ''
  if (list.length) uploadAll(list)
}

function onDrop(e) {
  dragging.value = false
  const list = Array.from((e.dataTransfer && e.dataTransfer.files) || [])
  if (list.length) uploadAll(list)
}

async function copyLink(f) {
  const url = location.origin + f.url
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('链接已复制')
  } catch {
    ElMessage.info(url)
  }
}

async function remove(f) {
  try {
    await ElMessageBox.confirm(`确定删除「${f.name || '该文件'}」吗？若文档正文仍在引用，只会从文件中心移除，不会删掉原文件。`, '删除文件', { type: 'warning' })
  } catch { return }
  try {
    await deleteMyFile(f.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    const msg = (e && e.response && e.response.data && (e.response.data.message || e.response.data.error)) || '删除失败'
    ElMessage.error(msg)
  }
}

onMounted(load)
</script>

<template>
  <div class="fc">
    <header class="fc-head">
      <h2>我的文件</h2>
      <p class="fc-sub">统一管理你上传过的图片 / 音频 / 视频 / PDF：复制链接可插进文档或分享，也能查看占用、清理无用文件（单个 ≤ 50MB）</p>
    </header>

    <!-- 存储用量 -->
    <div v-if="storage.has" class="fc-store">
      <div class="fc-store-row">
        <span class="fc-store-t">已用 {{ fmtSize(storage.used) }}<template v-if="storage.quota"> / {{ fmtSize(storage.quota) }}</template></span>
        <span class="fc-store-n">共 {{ files.length }} 个文件</span>
      </div>
      <div class="fc-store-bar"><i :style="{ width: usedPercent + '%' }"></i></div>
    </div>

    <!-- 上传区 -->
    <div
      class="fc-drop"
      :class="{ drag: dragging }"
      @click="inputRef?.click()"
      @dragover.prevent="dragging = true"
      @dragleave.prevent="dragging = false"
      @drop.prevent="onDrop"
    >
      <div class="fc-drop-ico">＋</div>
      <div class="fc-drop-txt">点击选择文件，或拖拽到此处</div>
      <input ref="inputRef" type="file" multiple hidden @change="onPick" />
    </div>

    <!-- 上传进度 -->
    <div v-if="uploading.length" class="fc-prog">
      <div v-for="u in uploading" :key="u.id" class="fc-prog-row">
        <span class="fc-prog-name">{{ u.name }}</span>
        <span class="fc-prog-bar"><i :style="{ width: u.percent + '%' }"></i></span>
        <span class="fc-prog-num">{{ u.percent }}%</span>
      </div>
    </div>

    <!-- 工具条：类型筛选 + 搜索 -->
    <div v-if="files.length" class="fc-bar">
      <div class="fc-tabs">
        <button
          v-for="t in FILTERS" :key="t.key"
          class="fc-tab" :class="{ on: filter === t.key }"
          @click="filter = t.key"
        >{{ t.label }}</button>
      </div>
      <input v-model="keyword" class="fc-search" placeholder="搜索文件名…" />
    </div>

    <!-- 列表 -->
    <div v-if="loading && !files.length" class="fc-empty">加载中…</div>
    <div v-else-if="!files.length" class="fc-empty">
      <div class="fc-empty-t">还没有文件</div>
      <div class="fc-empty-d">上传后可复制链接插进文档；在编辑器里直接粘贴图片也会自动登记到这里。</div>
    </div>
    <div v-else-if="!shown.length" class="fc-empty">没有匹配的文件</div>
    <div v-else class="fc-grid">
      <div v-for="f in shown" :key="f.id" class="fc-item">
        <div class="fc-thumb">
          <img v-if="isImage(f)" :src="f.url" :alt="f.name" />
          <span v-else class="fc-thumb-txt">{{ (f.name || '').split('.').pop()?.toUpperCase() || 'FILE' }}</span>
        </div>
        <div class="fc-meta">
          <div class="fc-name" :title="f.name">{{ f.name || '未命名' }}</div>
          <div class="fc-dim">{{ fmtSize(f.size) }} · {{ fmtTime(f.createdAt) }}</div>
        </div>
        <div class="fc-acts">
          <el-button size="small" @click="copyLink(f)">复制链接</el-button>
          <el-button size="small" type="danger" plain @click="remove(f)">删除</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.fc { max-width: 1080px; margin: 0 auto; padding: 22px 20px 40px; }
.fc-head h2 { margin: 0 0 6px; font-size: 21px; font-weight: 700; color: var(--el-text-color-primary); }
.fc-sub { margin: 0 0 18px; font-size: 13px; color: var(--el-text-color-secondary); }

.fc-drop {
  border: 1.5px dashed var(--el-border-color);
  border-radius: 12px;
  background: var(--el-bg-color);
  padding: 30px 16px;
  text-align: center;
  cursor: pointer;
  transition: border-color .18s, background-color .18s;
}
.fc-drop:hover, .fc-drop.drag { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.fc-drop-ico { font-size: 26px; line-height: 1; color: var(--el-color-primary); }
.fc-drop-txt { margin-top: 8px; font-size: 13.5px; color: var(--el-text-color-secondary); }

.fc-prog { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.fc-prog-row { display: flex; align-items: center; gap: 10px; font-size: 12.5px; }
.fc-prog-name { flex: 0 0 34%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fc-prog-bar { flex: 1 1 auto; height: 6px; border-radius: 3px; background: var(--el-border-color-lighter); overflow: hidden; }
.fc-prog-bar i { display: block; height: 100%; background: var(--el-color-primary); transition: width .2s; }
.fc-prog-num { flex: none; width: 44px; text-align: right; color: var(--el-text-color-secondary); }

.fc-empty { margin-top: 26px; text-align: center; color: var(--el-text-color-secondary); font-size: 13.5px; }
.fc-empty-t { font-size: 15px; font-weight: 600; color: var(--el-text-color-regular); margin-bottom: 6px; }
.fc-empty-d { max-width: 460px; margin: 0 auto; line-height: 1.7; }

/* 存储用量 */
.fc-store { margin-bottom: 16px; }
.fc-store-row { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 6px; }
.fc-store-t { font-size: 13px; font-weight: 600; color: var(--el-text-color-regular); }
.fc-store-n { font-size: 12px; color: var(--el-text-color-secondary); }
.fc-store-bar { height: 6px; border-radius: 3px; background: var(--el-border-color-lighter); overflow: hidden; }
.fc-store-bar i { display: block; height: 100%; background: var(--el-color-primary); transition: width .3s; }

/* 筛选 + 搜索 */
.fc-bar { margin-top: 18px; display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.fc-tabs { display: flex; gap: 6px; }
.fc-tab {
  border: 1px solid var(--el-border-color-lighter); background: var(--el-bg-color);
  color: var(--el-text-color-regular); font-size: 13px; padding: 5px 14px;
  border-radius: 999px; cursor: pointer; transition: all .16s;
}
.fc-tab:hover { border-color: var(--el-color-primary-light-5); color: var(--el-color-primary); }
.fc-tab.on { background: var(--el-color-primary); border-color: var(--el-color-primary); color: #fff; }
.fc-search {
  margin-left: auto; width: 200px; max-width: 46vw;
  border: 1px solid var(--el-border-color-lighter); border-radius: 999px;
  background: var(--el-bg-color); color: var(--el-text-color-primary);
  font-size: 13px; padding: 6px 14px; outline: none;
}
.fc-search:focus { border-color: var(--el-color-primary); }

.fc-grid {
  margin-top: 18px;
  display: grid; gap: 14px;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
}
.fc-item {
  display: flex; flex-direction: column;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 12px; overflow: hidden;
  transition: box-shadow .18s;
}
.fc-item:hover { box-shadow: 0 4px 16px rgba(0, 0, 0, .06); }
.fc-thumb {
  height: 132px; background: var(--el-fill-color-lighter);
  display: flex; align-items: center; justify-content: center; overflow: hidden;
}
.fc-thumb img { width: 100%; height: 100%; object-fit: cover; }
.fc-thumb-txt { font-size: 13px; font-weight: 700; color: var(--el-text-color-secondary); }
.fc-meta { padding: 10px 12px 4px; }
.fc-name { font-size: 13.5px; font-weight: 600; color: var(--el-text-color-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fc-dim { margin-top: 3px; font-size: 12px; color: var(--el-text-color-secondary); }
.fc-acts { display: flex; gap: 8px; padding: 8px 12px 12px; }
</style>
