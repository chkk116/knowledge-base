<template>
  <el-dialog
    :model-value="modelValue"
    :title="file?.name || '文件预览'"
    width="min(1080px, 94vw)"
    top="4vh"
    destroy-on-close
    class="fp-dlg"
    @update:model-value="(v) => emit('update:modelValue', v)"
  >
    <div class="fp-bar">
      <span class="fp-type">{{ kindLabel }}</span>
      <span v-if="pageInfo" class="fp-page">{{ pageInfo }}</span>
      <span class="fp-grow"></span>
      <el-button v-if="kind === 'pdf' && pages.length > 1" size="small" :disabled="pageIdx <= 0" @click="goto(-1)">上一页</el-button>
      <el-button v-if="kind === 'pdf' && pages.length > 1" size="small" :disabled="pageIdx >= pages.length - 1" @click="goto(1)">下一页</el-button>
      <el-button size="small" @click="download">下载</el-button>
    </div>

    <div v-loading="loading" class="fp-body" :class="'k-' + kind">
      <div v-if="error" class="fp-err">
        <p>{{ error }}</p>
        <el-button size="small" @click="download">下载后用本地软件打开</el-button>
      </div>

      <!-- 图片 -->
      <img v-else-if="kind === 'image' && srcUrl" :src="srcUrl" class="fp-img" :alt="file?.name" />

      <!-- PDF：逐页 canvas（不依赖浏览器内置 PDF 阅读器，移动端表现一致） -->
      <div v-else-if="kind === 'pdf'" class="fp-pdf">
        <canvas v-show="pageIdx === 0" ref="pdfCanvas" class="fp-canvas"></canvas>
      </div>

      <!-- Word：docx-preview 直接渲染成 HTML -->
      <div v-else-if="kind === 'docx'" ref="docxBox" class="fp-docx"></div>

      <!-- Excel：SheetJS 读成表格，按工作表切换 -->
      <div v-else-if="kind === 'xlsx'" class="fp-xlsx">
        <div class="fp-sheets">
          <button v-for="(s, i) in sheets" :key="i" class="fp-sheet" :class="{ on: sheetIdx === i }"
                  @click="sheetIdx = i">{{ s.name }}</button>
        </div>
        <div class="fp-sheet-body" v-html="sheetHtml"></div>
      </div>

      <!-- 文本 / 其它 -->
      <pre v-else-if="kind === 'text' && textContent" class="fp-text">{{ textContent }}</pre>
      <div v-else-if="!loading" class="fp-unsupported">
        <p>这类文件暂不支持在线预览（{{ ext || '未知类型' }}）。</p>
        <el-button size="small" @click="download">下载文件</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  file: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue'])

const loading = ref(false)
const error = ref('')
const srcUrl = ref('')
const textContent = ref('')
const pages = ref([])         // pdf 页数占位
const pageIdx = ref(0)
const pdfCanvas = ref(null)
const docxBox = ref(null)
const sheets = ref([])
const sheetIdx = ref(0)
const pageInfo = ref('')

const MAX_PREVIEW = 40 * 1024 * 1024   // 超过 40MB 不预览，直接引导下载

const ext = computed(() => {
  const n = String(props.file?.name || props.file?.url || '')
  const i = n.lastIndexOf('.')
  return i > 0 ? n.slice(i + 1).toLowerCase() : ''
})

const kind = computed(() => {
  const e = ext.value
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg'].includes(e)) return 'image'
  if (e === 'pdf') return 'pdf'
  if (e === 'docx') return 'docx'
  if (e === 'doc') return 'unsupported'
  if (['xlsx', 'xls', 'csv'].includes(e)) return 'xlsx'
  if (['txt', 'md', 'json', 'log', 'xml', 'yml', 'yaml', 'ini', 'conf'].includes(e)) return 'text'
  return 'unsupported'
})

const kindLabel = computed(() => ({
  image: '图片', pdf: 'PDF', docx: 'Word', xlsx: '表格', text: '文本', unsupported: '文件'
}[kind.value] || '文件'))

const sheetHtml = computed(() => (sheets.value[sheetIdx.value] || {}).html || '')

function goto(d) {
  pageIdx.value = Math.min(Math.max(0, pageIdx.value + d), pages.value.length - 1)
  renderPdfPage()
}

// pdf 文档对象缓存：翻页不重新解析
let pdfDoc = null

async function renderPdfPage() {
  if (!pdfDoc || !pdfCanvas.value) return
  const page = await pdfDoc.getPage(pageIdx.value + 1)
  const base = page.getViewport({ scale: 1 })
  const width = Math.min(900, base.width)
  const scale = width / base.width
  const viewport = page.getViewport({ scale: scale * (window.devicePixelRatio || 1) })
  const canvas = pdfCanvas.value
  canvas.width = Math.round(viewport.width)
  canvas.height = Math.round(viewport.height)
  canvas.style.width = width + 'px'
  canvas.style.height = 'auto'
  const ctx = canvas.getContext('2d')
  await page.render({ canvasContext: ctx, viewport }).promise
  pageInfo.value = `第 ${pageIdx.value + 1} / ${pages.value.length} 页`
}

async function loadPdf(arrayBuffer) {
  const pdfjs = await import('pdfjs-dist')
  pdfjs.GlobalWorkerOptions.workerSrc = `${import.meta.env.BASE_URL}vendor/pdf.worker.min.js`
  pdfDoc = await pdfjs.getDocument({ data: arrayBuffer }).promise
  pages.value = new Array(pdfDoc.numPages).fill(1)
  pageIdx.value = 0
  pageInfo.value = `第 1 / ${pdfDoc.numPages} 页`
  await nextTick()
  await renderPdfPage()
}

async function loadDocx(arrayBuffer) {
  const { renderAsync } = await import('docx-preview')
  await nextTick()
  if (!docxBox.value) return
  docxBox.value.innerHTML = ''
  await renderAsync(arrayBuffer, docxBox.value, null, {
    className: 'fp-docx-inner',
    inWrapper: true,
    ignoreLastRenderedPageBreak: true,
    breakPages: true
  })
  pageInfo.value = ''
}

async function loadXlsx(arrayBuffer) {
  const XLSX = await import('xlsx')
  const wb = XLSX.read(arrayBuffer, { type: 'array' })
  sheets.value = wb.SheetNames.map((name) => ({
    name,
    // 只读渲染：把前 500 行转成 HTML 表格，避免超大表把 DOM 撑爆
    html: XLSX.utils.sheet_to_html(wb.Sheets[name], { id: 'fp-sheet', editable: false })
  }))
  sheetIdx.value = 0
  pageInfo.value = `${wb.SheetNames.length} 个工作表`
}

async function open() {
  error.value = ''
  srcUrl.value = ''
  textContent.value = ''
  sheets.value = []
  pages.value = []
  pageInfo.value = ''
  pdfDoc = null
  const f = props.file
  if (!f || !f.url) return
  if (f.size && f.size > MAX_PREVIEW) {
    error.value = '文件较大（> 40MB），在线预览可能很慢，建议下载后查看。'
    return
  }
  if (kind.value === 'image') { srcUrl.value = f.url; return }
  if (kind.value === 'unsupported') return

  loading.value = true
  try {
    const resp = await fetch(f.url)
    if (!resp.ok) throw new Error('文件读取失败（HTTP ' + resp.status + '）')
    const buf = await resp.arrayBuffer()
    if (kind.value === 'pdf') await loadPdf(buf)
    else if (kind.value === 'docx') await loadDocx(buf)
    else if (kind.value === 'xlsx') await loadXlsx(buf)
    else if (kind.value === 'text') textContent.value = new TextDecoder().decode(buf).slice(0, 200000)
  } catch (e) {
    error.value = (e && e.message) || '预览失败，请下载后查看'
  } finally {
    loading.value = false
  }
}

function download() {
  const f = props.file
  if (!f || !f.url) return
  const a = document.createElement('a')
  a.href = f.url
  a.download = f.name || 'download'
  a.target = '_blank'
  a.click()
  ElMessage.success('已开始下载')
}

watch(() => props.modelValue, (v) => { if (v) open() })
watch(() => props.file, () => { if (props.modelValue) open() })
</script>

<style scoped>
.fp-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; font-size: 12.5px; color: var(--el-text-color-secondary); }
.fp-type { background: var(--el-fill-color-light); padding: 2px 8px; border-radius: 6px; }
.fp-page { color: var(--el-text-color-secondary); }
.fp-grow { flex: 1 1 auto; }
.fp-body { min-height: 320px; max-height: 74vh; overflow: auto; background: var(--el-fill-color-lighter); border-radius: 10px; padding: 14px; }
.fp-body.k-image { display: flex; align-items: center; justify-content: center; }
.fp-img { max-width: 100%; max-height: 70vh; object-fit: contain; }
.fp-pdf, .fp-docx { background: #fff; padding: 10px; border-radius: 8px; }
.fp-canvas { display: block; margin: 0 auto 10px; box-shadow: 0 2px 10px rgba(0,0,0,.08); background: #fff; }
.fp-xlsx { background: #fff; border-radius: 8px; padding: 8px; }
.fp-sheets { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 8px; }
.fp-sheet { border: 1px solid var(--el-border-color); background: #fff; border-radius: 6px; padding: 3px 10px; font-size: 12.5px; cursor: pointer; }
.fp-sheet.on { background: var(--el-color-primary); border-color: var(--el-color-primary); color: #fff; }
.fp-sheet-body :deep(table) { border-collapse: collapse; font-size: 12.5px; }
.fp-sheet-body :deep(td) { border: 1px solid var(--el-border-color-lighter); padding: 3px 8px; white-space: nowrap; }
.fp-text { margin: 0; white-space: pre-wrap; word-break: break-word; font-size: 13px; line-height: 1.7; }
.fp-err, .fp-unsupported { text-align: center; color: var(--el-text-color-secondary); padding: 60px 10px; font-size: 13.5px; }
.fp-docx :deep(.fp-docx-inner) { box-shadow: none; }
@media (max-width: 640px) {
  .fp-body { max-height: 68vh; padding: 8px; }
}
</style>
