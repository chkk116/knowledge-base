<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UploadFilled, FolderOpened, Plus, DataLine, Document, Files, Camera } from '@element-plus/icons-vue'
import { listMyShares, importShareDoc, createShareDoc, getStorage, ocrImportShareDoc } from '../api/docShare'
import { useAuthStore } from '../store/auth'
import { errMsg } from '../utils/errMsg'

const router = useRouter()
const auth = useAuthStore()

// ---- 数据 ----
const shares = ref([])                 // 我参与的空间（仅取可写入的）
const storage = ref({ used: 0, quota: 200 * 1024 * 1024 })
const loading = ref(false)
const shareId = ref(null)              // 上传目标空间
const fileInput = ref(null)

const WRITABLE = new Set(['OWNER', 'ADMIN', 'WRITE'])
const writableShares = computed(() => shares.value.filter(s => WRITABLE.has(s.myPermission)))

function formatSize(n) {
  if (n == null) return '-'
  if (n < 1024) return n + ' B'
  const u = ['KB', 'MB', 'GB', 'TB']; let i = -1, v = n
  do { v /= 1024; i++ } while (v >= 1024 && i < u.length - 1)
  return v.toFixed(2) + ' ' + u[i]
}
const storageText = computed(() => `${formatSize(storage.value.used)} / ${formatSize(storage.value.quota)}`)
const storagePercent = computed(() => Math.min(100, Math.round((storage.value.used / storage.value.quota) * 100)))

const currentShare = computed(() => writableShares.value.find(s => s.id === shareId.value))

const load = async () => {
  loading.value = true
  try {
    const [{ data: list }, { data: st }] = await Promise.all([listMyShares(), getStorage()])
    shares.value = list || []
    if (st) storage.value = st
    // 默认选中第一个可写空间（优先个人空间）
    const first = writableShares.value.find(s => s.type === 'PERSONAL') || writableShares.value[0]
    if (first) shareId.value = first.id
  } catch (e) {
    ElMessage.error(errMsg(e, '加载空间列表失败'))
  } finally {
    loading.value = false
  }
}
onMounted(load)

// ---- 上传（点击 / 拖拽） ----
const ACCEPT = '.md,.txt,.html,.htm,.docx,.pdf,image/png,image/jpeg,image/webp,image/gif,image/bmp'
const uploading = ref(false)
const dragOver = ref(false)
// 本次导入结果卡片：带「打开文档」直达按钮，避免导入后用户不知道文档去哪了
const doneList = ref([])   // { shareId, shareName, docId, title, ocr }
const pushDone = (data, fallbackName, ocr) => {
  const shareName = currentShare.value?.name || '选定空间'
  doneList.value.unshift({ shareId: shareId.value, shareName, docId: data.id, title: data.title || fallbackName, ocr })
  ElMessage.success(`「${data.title || fallbackName}」已${ocr ? '识别并' : ''}导入到「${shareName}」，可在下方打开`)
}
const openDoc = (d) => router.push({ path: `/doc-shares/${d.shareId}`, query: { doc: d.docId } })

const pick = () => fileInput.value && fileInput.value.click()

const onFileChange = (e) => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  files.forEach(uploadOne)
}

const onDrop = (e) => {
  dragOver.value = false
  const files = Array.from(e.dataTransfer?.files || [])
  files.forEach(uploadOne)
}

const uploadOne = async (file) => {
  if (!shareId.value) { ElMessage.warning('请先选择目标空间'); return }
  // 图片自动分流到 OCR 识别导入（jpg/png/webp/gif/bmp）
  if (file.type && file.type.startsWith('image/')) return ocrOne(file)
  uploading.value = true
  try {
    const { data } = await importShareDoc(shareId.value, file)
    pushDone(data, file.name, false)
  } catch (e) {
    ElMessage.error(e.response?.data?.error || `导入「${file.name}」失败`)
  } finally {
    uploading.value = false
  }
}

const createBlank = async () => {
  if (!shareId.value) { ElMessage.warning('请先选择目标空间'); return }
  uploading.value = true
  try {
    await createShareDoc(shareId.value, { title: '未命名文档', content: '' })
    ElMessage.success('已创建，正在打开空间…')
    router.push(`/doc-shares/${shareId.value}`)
  } catch (e) {
    ElMessage.error(errMsg(e, '创建失败'))
  } finally { uploading.value = false }
}

const goWorkspace = () => {
  if (shareId.value) router.push(`/doc-shares/${shareId.value}`)
}

// ---- 图片 OCR 识别导入：图片经后端 GLM-4V-Flash 识别文字，直接生成协作文档 ----
const imgInput = ref(null)
const pickOcr = () => imgInput.value && imgInput.value.click()
const onImgChange = (e) => {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  files.forEach(ocrOne)
}
const ocrOne = async (file) => {
  if (!shareId.value) { ElMessage.warning('请先选择目标空间'); return }
  uploading.value = true
  try {
    const { data } = await ocrImportShareDoc(shareId.value, file)
    pushDone(data, file.name, true)
  } catch (e) {
    ElMessage.error(e.response?.data?.error || `识别「${file.name}」失败`)
  } finally {
    uploading.value = false
  }
}

</script>

<template>
  <div class="dsi" v-loading="loading">
    <!-- 顶部功能卡片行：白底简洁卡 + 右侧彩色渐变图标 -->
    <div class="fn-grid">
      <div class="fn-card c-blue" @click="pick">
        <div class="fn-txt">
          <div class="fn-title">文档导入</div>
          <div class="fn-sub">支持 PDF、Word、Markdown 等格式转在线协作文档</div>
        </div>
        <span class="fn-badge"><el-icon><Files /></el-icon></span>
      </div>
      <div class="fn-card c-orange" @click="createBlank">
        <div class="fn-txt">
          <div class="fn-title">新建空白文档</div>
          <div class="fn-sub">在选定空间中直接新建并开始协作</div>
        </div>
        <span class="fn-badge"><el-icon><Plus /></el-icon></span>
      </div>
      <div class="fn-card c-green" @click="goWorkspace">
        <div class="fn-txt">
          <div class="fn-title">打开空间工作台</div>
          <div class="fn-sub">前往选定空间管理文档与成员</div>
        </div>
        <span class="fn-badge"><el-icon><FolderOpened /></el-icon></span>
      </div>
      <div class="fn-card c-purple" @click="pickOcr">
        <div class="fn-txt">
          <div class="fn-title">图片识别导入</div>
          <div class="fn-sub">上传图片自动识别文字（OCR）转在线协作文档</div>
        </div>
        <span class="fn-badge b-round"><el-icon><Camera /></el-icon></span>
      </div>
      <div class="fn-card c-cyan">
        <div class="fn-txt">
          <div class="fn-title">存储用量</div>
          <div class="fn-sub">{{ storageText }}</div>
          <el-progress class="fn-bar" :percentage="storagePercent" :stroke-width="6" />
        </div>
        <span class="fn-badge"><el-icon><DataLine /></el-icon></span>
      </div>
    </div>

    <!-- 目标空间选择 -->
    <div class="target-bar">
      <span class="target-label">导入到空间：</span>
      <el-select v-model="shareId" class="target-select" placeholder="选择共享空间" size="default">
        <el-option v-for="s in writableShares" :key="s.id" :value="s.id"
                   :label="(s.type === 'PERSONAL' ? '[个人] ' : '') + s.name" />
      </el-select>
      <span v-if="currentShare" class="target-sub">{{ currentShare.description || '无描述' }}</span>
    </div>

    <!-- 拖拽上传大区：保持白底虚线，不做玻璃 -->
    <div class="drop-zone" :class="{ over: dragOver }"
         @click="pick"
         @dragover.prevent="dragOver = true"
         @dragleave.prevent="dragOver = false"
         @drop.prevent="onDrop">
      <div class="dz-icon">
        <el-icon :size="64" class="dz-main"><UploadFilled /></el-icon>
        <el-icon :size="26" class="dz-doc d1"><Document /></el-icon>
        <el-icon :size="22" class="dz-doc d2"><Files /></el-icon>
        <el-icon :size="18" class="dz-doc d3"><Document /></el-icon>
      </div>
      <div class="dz-tip">点击上传文档 / 文件夹或直接拖拽到此处</div>
      <div class="dz-btns">
        <el-button type="primary" :loading="uploading" @click.stop="pick">上传文档</el-button>
        <el-button class="ghost-btn" @click.stop="createBlank">新建文档</el-button>
      </div>
      <input ref="imgInput" type="file" accept="image/png,image/jpeg,image/webp,image/gif,image/bmp" multiple hidden @change="onImgChange" />
    <input ref="fileInput" type="file" :accept="ACCEPT" multiple hidden @change="onFileChange" />
    </div>

    <!-- 本次导入结果：带一键打开，导入不再"失踪" -->
    <div v-if="doneList.length" class="done-panel">
      <div class="done-head">
        <span class="done-title">本次导入成功（{{ doneList.length }}）</span>
        <el-button text size="small" class="done-clear" @click="doneList = []">清空记录</el-button>
      </div>
      <div v-for="d in doneList" :key="d.docId" class="done-item">
        <el-icon class="done-ico"><Document /></el-icon>
        <div class="done-info">
          <div class="done-name">{{ d.title }}</div>
          <div class="done-meta">已导入到「{{ d.shareName }}」{{ d.ocr ? ' · 图片文字已识别为可编辑内容' : ' · 已转为在线协作文档' }}</div>
        </div>
        <div class="done-acts">
          <el-button type="primary" size="small" @click="openDoc(d)">打开文档</el-button>
          <el-button size="small" @click="router.push(`/doc-shares/${d.shareId}`)">进入空间</el-button>
        </div>
      </div>
    </div>

    <!-- 文件上传说明 -->
    <div class="notes">
      <div class="notes-title">文件上传说明：</div>
      <ul>
        <li>除上传本地文件以外，还可以直接把文件拖拽到上方区域（支持一次多选）</li>
        <li><b>PDF 文件</b>：解析正文与结构后转为在线协作文档</li>
        <li><b>Word 文件</b>：支持 .docx，保留标题、段落、列表与表格等基本结构</li>
        <li><b>Markdown / TXT / HTML</b>：直接转为在线文档内容</li>
        <li>单个文件最大 <b>20MB</b>；导入后文档落入选定空间，有写入权限的成员可实时协同编辑</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.dsi { padding: 20px; max-width: 1200px; margin: 0 auto; }

/* ---- 顶部功能卡片：白底简洁卡 + 右侧彩色渐变图标 ---- */
.fn-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 14px; margin-bottom: 18px; }
.fn-card {
  --accent: 96 165 250;
  display: flex; align-items: center; justify-content: space-between;
  padding: 18px 16px; cursor: pointer;
  background: linear-gradient(180deg, #ffffff 38%, rgb(var(--accent) / .07));
  border: 1px solid #eef2f7; border-radius: 14px;
  box-shadow: 0 2px 10px -6px rgb(var(--accent) / .30);
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
}
.fn-card:hover { transform: translateY(-2px); border-color: rgb(var(--accent) / .40); box-shadow: 0 10px 20px -10px rgb(var(--accent) / .45); }

/* 每卡图标色 */
.c-blue   { --accent:  96 165 250; }
.c-orange { --accent: 251 146  60; }
.c-green  { --accent:  52 211 153; }
.c-cyan   { --accent:  34 211 238; }
.c-purple { --accent: 167 139 250; }
.c-purple .fn-badge { border-radius: 50%; }

.fn-txt { min-width: 0; }
.fn-title { font-size: 15px; font-weight: 600; color: #1e293b; }
.fn-sub { margin-top: 4px; font-size: 12px; color: #64748b; line-height: 1.5; }
.fn-bar { margin-top: 8px; width: 140px; }

/* 彩色渐变图标徽章：立体渐变正面 + 背后浅色错位形状（呼应参考图的层次感） */
.fn-badge {
  position: relative; isolation: isolate;
  flex: none; margin-left: 12px;
  width: 46px; height: 46px; border-radius: 14px; display: grid; place-items: center;
  font-size: 24px; color: #fff;
  background: linear-gradient(160deg,
              color-mix(in srgb, rgb(var(--accent)), #fff 36%),
              rgb(var(--accent)) 52%,
              color-mix(in srgb, rgb(var(--accent)), #000 14%));
  box-shadow:
    0 8px 16px -6px rgb(var(--accent) / .55),
    inset 0 1.5px 1px rgb(255 255 255 / .55),
    inset 0 -3px 6px rgb(0 0 0 / .12);
}
.fn-badge::before {
  content: ''; position: absolute; z-index: -1; right: -7px; top: -7px;
  width: 24px; height: 24px; border-radius: 8px;
  background: rgb(var(--accent) / .35);
  transform: rotate(14deg);
}
.fn-badge::after {
  content: ''; position: absolute; z-index: -1; left: -6px; bottom: -5px;
  width: 13px; height: 13px; border-radius: 50%;
  background: rgb(var(--accent) / .22);
}
/* 形状变化：绿=圆形、青=气泡形（多形状呼应参考图） */
.c-green .fn-badge { border-radius: 50%; }
.c-green .fn-badge::before { border-radius: 50%; transform: none; right: -6px; }
.c-cyan .fn-badge { border-radius: 50% 50% 8px 50%; }

/* ---- 目标空间选择 ---- */
.target-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.target-label { font-size: 14px; color: #475569; font-weight: 500; }
.target-select { width: 260px; }
.target-sub { font-size: 12px; color: #94a3b8; }

/* ---- 拖拽上传区：白底虚线，不做玻璃 ---- */
.drop-zone {
  border: 1.5px dashed #bfd3f0; border-radius: 12px;
  background: linear-gradient(180deg, #fbfdff, #f4f8fd);
  padding: 46px 20px 40px; text-align: center; cursor: pointer;
  transition: border-color .15s, background .15s;
}
.drop-zone.over { border-color: #3b82f6; background: #eff6ff; }
.drop-zone:hover { border-color: #93c5fd; }
.dz-icon { position: relative; width: 96px; height: 72px; margin: 0 auto 14px; }
.dz-main { position: absolute; left: 14px; top: 6px; color: #60a5fa; }
.dz-doc { position: absolute; color: #f59e0b; }
.dz-doc.d1 { right: 10px; top: 0; transform: rotate(14deg); color: #f97316; }
.dz-doc.d2 { right: 0; top: 28px; transform: rotate(24deg); color: #10b981; }
.dz-doc.d3 { right: 30px; top: -8px; transform: rotate(-12deg); color: #8b5cf6; }
.dz-tip { font-size: 14px; color: #64748b; margin-bottom: 16px; }
.dz-btns { display: flex; justify-content: center; gap: 12px; }
.ghost-btn { color: #3b82f6; background: #eaf2fe; border-color: transparent; }
.ghost-btn:hover { background: #dbeafe; }

/* ---- 导入结果：白底结果卡，每条带「打开文档」直达 ---- */
.done-panel {
  margin-top: 14px; padding: 12px 14px;
  background: #fff; border: 1px solid #eef2f7; border-radius: 12px;
  box-shadow: 0 2px 10px -6px rgba(59, 130, 246, .25);
}
.done-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.done-title { font-size: 13px; font-weight: 600; color: #16a34a; }
.done-clear { color: #94a3b8; }
.done-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 2px; border-top: 1px solid #f1f5f9;
}
.done-ico { flex: none; font-size: 20px; color: #3b82f6; }
.done-info { flex: 1; min-width: 0; }
.done-name { font-size: 14px; font-weight: 500; color: #1e293b; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.done-meta { margin-top: 2px; font-size: 12px; color: #94a3b8; }
.done-acts { flex: none; display: flex; gap: 6px; }

/* ---- 说明 ---- */
.notes { margin-top: 18px; }
.notes-title { font-size: 14px; font-weight: 600; color: #334155; margin-bottom: 8px; }
.notes ul { margin: 0; padding-left: 18px; }
.notes li { font-size: 13px; color: #64748b; line-height: 2; }
.notes li b { color: #475569; }

/* ---- 暗色模式 ---- */
html.dark .fn-card {
  background: linear-gradient(180deg, #1e293b, #172033);
  border-color: rgba(255, 255, 255, .10);
  box-shadow: 0 2px 8px -4px rgba(0, 0, 0, .40);
}
html.dark .fn-card:hover { border-color: rgba(96, 165, 250, .45); }
html.dark .fn-title { color: var(--el-text-color-primary); }
html.dark .fn-sub { color: var(--el-text-color-secondary); }
html.dark .target-label, html.dark .notes-title { color: var(--el-text-color-regular); }
html.dark .notes li, html.dark .notes li b { color: var(--el-text-color-secondary); }
html.dark .drop-zone { background: var(--el-bg-color); border-color: var(--el-border-color); }
html.dark .drop-zone.over { background: rgba(59, 130, 246, .1); border-color: #3b82f6; }
html.dark .dz-tip { color: var(--el-text-color-secondary); }
html.dark .done-title { color: #4ade80; }
html.dark .done-panel { background: #1e293b; border-color: rgba(255, 255, 255, .10); box-shadow: 0 2px 8px -4px rgba(0, 0, 0, .40); }
html.dark .done-item { border-top-color: rgba(255, 255, 255, .07); }
html.dark .done-name { color: var(--el-text-color-primary); }
html.dark .done-meta { color: var(--el-text-color-secondary); }
html.dark .done-ico { color: #93c5fd; }
html.dark .ghost-btn { background: rgba(59, 130, 246, .16); color: #93c5fd; }

/* 尊重系统的"减弱动态效果" */
@media (prefers-reduced-motion: reduce) {
  .fn-card { transition: none; }
  .fn-card:hover, .fn-card:active { transform: none; }
}

/* ============================================================
   响应式布局（断点统一：≤1024 平板 / ≤640 手机）
   ============================================================ */
@media (max-width: 1024px) {
  .dsi { padding: 14px; }
  .fn-grid { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .target-select { width: 220px; }
  .drop-zone { padding: 34px 16px 30px; }
}

@media (max-width: 640px) {
  .dsi { padding: 12px; }
  .fn-grid { grid-template-columns: 1fr; }
  .target-bar { flex-wrap: wrap; }
  .target-select { width: 100%; }
  .drop-zone { padding: 26px 12px 24px; }
  .dz-tip { font-size: 13px; }
  :deep(.dz-btns .el-button + .el-button) { margin-left: 0; }
}
</style>
