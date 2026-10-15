<script setup>
/**
 * 拍照建文档（底部 TabBar 中间的 ＋）
 *
 * 完整流程（用户确认的方案）：
 *   1. 选择来源：直接拍照 / 从相册选
 *   2. 预览图片，点击「开始识别」
 *   3. OCR：默认走「本地识别」（Tesseract.js，纯前端、不传图、离线可用）
 *      可选「云端识别」（服务端 glm-4.6v，更准，需联网）
 *   4. 识别结果进入可编辑的文本框，用户可手动修正
 *   5. 选择存放空间（默认「我的文档」）
 *   6. 保存：有网 → 直接创建；无网 → 存本地队列，联网自动同步
 *
 * 为什么把图片 OCR 放在前端做：
 *   - 用户明确要求「离线也能用」，云端 OCR 在无网时完全不可用
 *   - Tesseract.js 中文包约 4MB，首次识别时才按需下载，之后浏览器缓存
 *   - 本地识别不上传图片，隐私更好
 */
import { computed, onMounted, onUnmounted, ref, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { createShareDoc, ocrImportShareDoc } from '../../api/docShare'
import { prepareImage, buildPdfBlob, revokePages } from '../../utils/scanPdf'
import MobileScanEditor from './MobileScanEditor.vue'
import { applyWatermark, buildLongImage, toCanvas, toJpegPage } from '../../utils/scanEnhance'
import { uploadFile } from '../../api/file'
import { enqueueDoc } from '../../utils/offlineStore'
import { mySpaces, resolveDefaultSpaceId, rememberSpace, personalSpace, lastSpaceId } from '../../utils/spaceResolver'
import { recognizeLocal, warmupLocalOcr } from '../../utils/localOcr'

const router = useRouter()

const fileInput = ref(null)
const cameraInput = ref(null)

const stage = ref('pick')        // pick | preview | review | scan
// ---- 多页扫描（导出 PDF）----
const scanPages = ref([])        // [{ file, url, w, h, bytes }]
// ---- 扫描增强（借鉴全能扫描王）----
const scanTitle = ref('')        // 扫描件名称（导出文件名 / 批量管理）
const editingIndex = ref(-1)     // 正在编辑的页下标（-1 = 未编辑）
const exportSheet = ref(false)   // 导出选项面板
const wmText = ref('')           // 水印文字（空 = 不加）
const pdfPwd = ref('')           // PDF 打开密码（空 = 不加密）
// ---- 证件模式 ----
const idRatio = ref(0)           // >0 = 证件模式，值为宽高比
const idType = ref('')           // 证件类型名（仅展示）
const idSheet = ref(false)       // 证件类型选择面板
// 常见证件尺寸（毫米换算的真实宽高比）
const ID_PRESETS = [
  { key: 'idcard', name: '身份证', ratio: 85.6 / 54 },
  { key: 'passport', name: '护照', ratio: 125 / 88 },
  { key: 'bank', name: '银行卡', ratio: 85.6 / 54 },
  { key: 'driver', name: '驾驶证', ratio: 88 / 62 },
  { key: 'household', name: '户口本', ratio: 1.4 },
  { key: 'license', name: '营业执照', ratio: 1.414 },
]
// ---- 空间选择面板用途（OCR 入库 / 图片归档）----
const sheetMode = ref('ocr')     // ocr | image
// ---- 页面拖拽排序 ----
const dragIdx = ref(-1)
const dragOver = ref(-1)
let dragTimer = null
let uidSeq = 0
let justDragged = false          // 拖拽刚结束 → 抑制一次 click，避免误进编辑器
const scanCamera = ref(null)
const scanAlbum = ref(null)
const exporting = ref(false)
const file = ref(null)
const previewUrl = ref('')
const busy = ref(false)
const engine = ref('local')      // local | cloud
const statusText = ref('')
const progress = ref(0)

const title = ref('')
const content = ref('')
const spaces = ref([])
const targetSpace = ref(null)
const sheetOpen = ref(false)
const online = ref(navigator.onLine)

const targetName = computed(() => {
  const s = spaces.value.find((x) => x.id === targetSpace.value)
  return s ? s.name : '我的文档'
})

function onOnline() { online.value = true }
function onOffline() { online.value = false }

onMounted(async () => {
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
  try {
    const list = await mySpaces()
    spaces.value = list
    const id = await resolveDefaultSpaceId()
    targetSpace.value = id
    if (!list.length) {
      const p = await personalSpace().catch(() => null)
      if (p) { spaces.value = [p]; targetSpace.value = p.id }
    }
  } catch { /* 离线：留空，保存时再解析 */ }
})

onUnmounted(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
  document.removeEventListener('pointerup', cancelDragTimer, true)
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  // 整个扫描流程的对象 URL 一起回收（长期驻留的 WebView 里反复拍照会累积内存）
  revokePages(scanPages.value)
})

/** 常驻的「抬手取消长按」：替代每次 pointerdown 挂 once 监听（pointercancel 结束的手势
 *  会让 once 监听残留累积，第四轮审查 Low） */
function cancelDragTimer() { clearTimeout(dragTimer) }
onMounted(() => document.addEventListener('pointerup', cancelDragTimer, true))

function pickCamera() { cameraInput.value?.click() }
function pickAlbum() { fileInput.value?.click() }

function onFile(e) {
  const f = (e.target.files || [])[0]
  // 允许重复选同一张图：清空 value，否则第二次 change 不触发
  e.target.value = ''
  if (!f) return
  if (!f.type.startsWith('image/')) { window.__kbMsg?.warning('请选择图片文件'); return }
  file.value = f
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = URL.createObjectURL(f)
  title.value = f.name ? f.name.replace(/\.[^.]+$/, '') : ('拍照文档 ' + fmtNow())
  content.value = ''
  stage.value = 'preview'
}

function fmtNow() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}月${d.getDate()}日 ${p(d.getHours())}:${p(d.getMinutes())}`
}

function back() {
  if (stage.value === 'review') { stage.value = 'preview'; return }
  if (stage.value === 'preview') { stage.value = 'pick'; return }
  router.back()
}

/** 开始识别 */
/* ---------- 多页扫描：拍照/选图 → 列表 → 导出 PDF ---------- */
function openScan() {
  stage.value = 'scan'
  if (!scanPages.value.length) scanAlbum.value?.click()
}
function pickScanCamera() { scanCamera.value?.click() }
function pickScanAlbum() { scanAlbum.value?.click() }

async function onScanFiles(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (!files.length) return
  busy.value = true
  statusText.value = `正在处理 ${files.length} 张…`
  try {
    for (const f of files) {
      if (!/^image\//i.test(f.type)) continue
      const page = await prepareImage(f)
      page.file = f
      page.uid = ++uidSeq            // 稳定 key：拖拽排序时避免 DOM 复用错位
      scanPages.value.push(page)
    }
  } catch (err) {
    window.__kbMsg?.error(err.message || '图片处理失败')
  } finally {
    busy.value = false
    statusText.value = ''
  }
}

function removePage(i) {
  const [p] = scanPages.value.splice(i, 1)
  if (p?.url) URL.revokeObjectURL(p.url)
}
function movePage(i, d) {
  const j = i + d
  if (j < 0 || j >= scanPages.value.length) return
  const arr = scanPages.value
  ;[arr[i], arr[j]] = [arr[j], arr[i]]
}

/** 扫描件文件名（优先用用户命名，否则时间戳） */
function scanFileName(ext) {
  const base = (scanTitle.value || '').trim().replace(/[\\/:*?"<>|]/g, '_')
  return (base || `scan-${Date.now()}`) + ext
}

/** 上传 → 拿链接 → 优先系统分享，否则新窗口打开 */
async function uploadAndShare(blob, filename) {
  statusText.value = '正在上传…'
  const f = new File([blob], filename, { type: blob.type })
  const { data } = await uploadFile(f)
  if (data?.errno !== 0 || !data?.data?.url) throw new Error(data?.message || '上传失败')
  const url = data.data.url
  const abs = url.startsWith('http') ? url : location.origin + url
  if (navigator.share) {
    try { await navigator.share({ title: scanTitle.value || '扫描件', url: abs }); return } catch (e) { /* 用户取消则继续兜底 */ }
  }
  window.open(abs, '_blank')
}

/** 按水印设置烘焙每一页（水印烧进图片，任何阅读器都能看到） */
async function bakePages() {
  const out = []
  for (const p of scanPages.value) {
    let cv = await toCanvas(p.url, 2000)
    const wm = wmText.value.trim()
    if (wm) cv = applyWatermark(cv, wm, { opacity: 0.16 })
    out.push(await toJpegPage(cv, 0.85))
  }
  return out
}

/** 生成 PDF → 上传取链接（App 内 blob 下载不便，走链接可直接用系统浏览器打开/分享） */
async function exportPdf() {
  if (!scanPages.value.length || exporting.value) return
  exporting.value = true
  statusText.value = '正在生成 PDF…'
  let baked = []
  try {
    baked = await bakePages()
    const blob = buildPdfBlob(baked, { password: pdfPwd.value.trim() })
    await uploadAndShare(blob, scanFileName('.pdf'))
    window.__kbMsg?.success(pdfPwd.value.trim() ? '加密 PDF 已生成' : 'PDF 已生成')
    exportSheet.value = false
  } catch (err) {
    window.__kbMsg?.error(err?.response?.data?.error || err.message || '导出失败')
  } finally {
    revokePages(baked)
    exporting.value = false
    statusText.value = ''
  }
}

/** 导出长图：多页纵向拼接成一张图（分享/存档都方便） */
async function exportLongImage() {
  if (!scanPages.value.length || exporting.value) return
  exporting.value = true
  statusText.value = '正在拼接长图…'
  try {
    const cvs = []
    for (const p of scanPages.value) {
      let cv = await toCanvas(p.url, 1600)
      const wm = wmText.value.trim()
      if (wm) cv = applyWatermark(cv, wm, { opacity: 0.16 })
      cvs.push(cv)
    }
    const longCv = buildLongImage(cvs, 16)
    const page = await toJpegPage(longCv, 0.9)
    statusText.value = '正在上传…'
    await uploadAndShare(new Blob([page.bytes], { type: 'image/jpeg' }), scanFileName('.jpg'))
    window.__kbMsg?.success('长图已生成')
    exportSheet.value = false
  } catch (err) {
    window.__kbMsg?.error(err?.response?.data?.error || err.message || '导出失败')
  } finally {
    exporting.value = false
    statusText.value = ''
  }
}

/* ---------- 单页编辑（裁剪 / 矫正 / 旋转 / 滤镜） ---------- */

function openEditor(i) {
  if (busy.value || exporting.value) return
  editingIndex.value = i
}

/** 编辑器回写：替换该页（旧 blob URL 释放） */
function onEditDone(page) {
  const i = editingIndex.value
  if (i < 0 || i >= scanPages.value.length) { editingIndex.value = -1; return }
  const old = scanPages.value[i]
  if (old?.url && String(old.url).startsWith('blob:')) URL.revokeObjectURL(old.url)
  scanPages.value[i] = { ...old, ...page }
  editingIndex.value = -1
  window.__kbMsg?.success('已保存本页')
}

/* ---------- 证件模式 ---------- */

function openIdSheet() { idSheet.value = true }

/** 选好证件类型 → 清空重来，进入证件扫描（编辑器会锁定比例并直接进裁剪） */
function pickIdType(p) {
  idRatio.value = p.ratio
  idType.value = p.name
  idSheet.value = false
  revokePages(scanPages.value)
  scanPages.value = []
  scanTitle.value = ''
  stage.value = 'scan'
  scanAlbum.value?.click()
}

function clearIdMode() {
  idRatio.value = 0
  idType.value = ''
}

/* ---------- 图片归档（扫描件存进知识库） ---------- */

function openImageArchive() {
  if (!scanPages.value.length || exporting.value || busy.value) return
  if (!online.value) { window.__kbMsg?.warning('离线状态无法归档，请联网后重试'); return }
  sheetMode.value = 'image'
  sheetOpen.value = true
}

/** 把当前扫描页上传后生成一篇「图片文档」存进指定空间 */
async function archiveImages(sid) {
  if (!scanPages.value.length || exporting.value) return
  exporting.value = true
  try {
    const urls = []
    const total = scanPages.value.length
    for (let i = 0; i < total; i++) {
      statusText.value = `正在上传第 ${i + 1}/${total} 张…`
      const p = scanPages.value[i]
      const f = new File([new Blob([p.bytes], { type: 'image/jpeg' })],
        `${scanFileName('')}-${i + 1}.jpg`, { type: 'image/jpeg' })
      const { data } = await uploadFile(f)
      if (data?.errno !== 0 || !data?.data?.url) throw new Error(data?.message || '上传失败')
      urls.push(data.data.url)
    }
    statusText.value = '正在生成文档…'
    // URL 过 escapeHtml 再进属性（第四轮审查）：虽然来自自家上传接口，但直拼 src 属性
    // 一旦值带引号/尖括号就会破坏属性边界（存储型 XSS 纵深防御）
    const html = urls.map((u) => `<p><img src="${escapeHtml(u)}" alt="扫描件"></p>`).join('')
    const name = scanTitle.value.trim() || `${idType.value || '扫描件'} ${new Date().toLocaleDateString()}`
    const { data } = await createShareDoc(sid, { title: name, content: html })
    window.__kbMsg?.success('已归档到「' + (spaces.value.find((x) => x.id === sid)?.name || '我的文档') + '」')
    router.replace(`/doc/${sid}/${data.id}`)
  } catch (e) {
    window.__kbMsg?.error('归档失败：' + (e?.response?.data?.error || e.message || '未知错误'))
  } finally {
    exporting.value = false
    statusText.value = ''
  }
}

/* ---------- 页面拖拽排序（长按 260ms 进入拖拽，保留 ↑↓ 兜底） ---------- */

/**
 * 长按 260ms 进入拖拽。移动/抬起一律挂在 document 上：
 * 手指移出元素后 item 上的 pointermove 就收不到了，只有文档级监听才可靠。
 */
function onPageDown(e, i) {
  if (busy.value || exporting.value || scanPages.value.length < 2) return
  clearTimeout(dragTimer)
  dragTimer = setTimeout(() => {
    dragIdx.value = i
    dragOver.value = i
    if (navigator.vibrate) { try { navigator.vibrate(12) } catch { /* ignore */ } }
    document.addEventListener('pointermove', onDragMove, { passive: false })
    document.addEventListener('pointerup', onDragEnd, true)
    document.addEventListener('pointercancel', onDragEnd, true)
  }, 260)
}
function onDragMove(e) {
  if (dragIdx.value < 0) return
  e.preventDefault()
  const el = document.elementFromPoint(e.clientX, e.clientY)
  const item = el && el.closest ? el.closest('.scan-item') : null
  if (item && item.dataset.idx != null) dragOver.value = Number(item.dataset.idx)
}
function onDragEnd() {
  document.removeEventListener('pointermove', onDragMove)
  document.removeEventListener('pointerup', onDragEnd, true)
  document.removeEventListener('pointercancel', onDragEnd, true)
  clearTimeout(dragTimer)
  const from = dragIdx.value
  const to = dragOver.value
  if (from >= 0) justDragged = true            // 抑制随后触发的 click
  if (from >= 0 && to >= 0 && from !== to) {
    const arr = scanPages.value
    const [moved] = arr.splice(from, 1)
    arr.splice(to, 0, moved)
  }
  dragIdx.value = -1
  dragOver.value = -1
}
/** 拖拽刚结束时抑制一次 click，避免误进编辑器 */
function onPageClick(i) {
  if (justDragged) { justDragged = false; return }
  openEditor(i)
}

/** 逐页识别后合成一篇文档（走 review 阶段让用户确认） */
async function recognizeAll() {
  if (!scanPages.value.length || busy.value) return
  busy.value = true
  try {
    const parts = []
    // 云端路径需要合法空间 id 拼 URL（persist=false 只识别不落库，但仍要走该空间的路由）
    let sid = null
    if (engine.value !== 'local') {
      sid = targetSpace.value || await resolveDefaultSpaceId() || await personalSpace().then((s) => s?.id)
      if (!sid) throw new Error('没有可写入的空间')
    }
    for (let i = 0; i < scanPages.value.length; i++) {
      statusText.value = `识别中 ${i + 1}/${scanPages.value.length}…`
      progress.value = Math.round(((i) / scanPages.value.length) * 100)
      try {
        if (engine.value === 'local') {
          const t = await recognizeLocal(scanPages.value[i].file, (p) => { progress.value = p }, { table: true })
          parts.push(t || '')
        } else {
          // persist=false：只识别不落库（第四轮审查 H6）——原先每页都在服务端建一篇文档，
          // review 后 save() 再建一篇合并的 → 每次多页识别遗留 N 篇孤儿文档
          const { data } = await ocrImportShareDoc(sid, scanPages.value[i].file, false)
          // 服务端返回的是段落 HTML：统一转回纯文本入 parts，与本地引擎路径同构，
          // 后面只做一次 textToHtml（第四轮审查 H5：原先把 HTML 再过 textToHtml 双重转义）
          parts.push(htmlToText(data?.content || ''))
        }
      } catch (e) {
        parts.push('')
      }
    }
    const text = parts.filter(Boolean).join('\n\n——————\n\n')
    content.value = text
    title.value = `扫描件 ${fmtNow()}`
    stage.value = 'review'
  } finally {
    busy.value = false
    statusText.value = ''
    progress.value = 0
  }
}

async function startRecognize() {
  if (!file.value || busy.value) return
  busy.value = true
  progress.value = 0
  statusText.value = '正在准备识别引擎…'
  try {
    let text = ''
    if (engine.value === 'local') {
      text = await recognizeLocal(file.value, (p, msg) => {
        progress.value = p
        statusText.value = msg || `识别中 ${Math.round(p * 100)}%`
      }, { table: true })
    } else {
      statusText.value = '正在上传并调用云端识别…'
      progress.value = 0.3
      const spaceId = targetSpace.value || await resolveDefaultSpaceId() || await personalSpace().then((s) => s?.id)
      if (!spaceId) throw new Error('没有可写入的空间')
      const { data } = await ocrImportShareDoc(spaceId, file.value)
      // 云端识别是「识别完直接建文档」，这里把结果取回来给用户预览/改标题
      progress.value = 1
      statusText.value = '识别完成'
      title.value = data.title || title.value
      content.value = htmlToText(data.content || '')
      stage.value = 'review'
      window.__kbMsg?.success('云端识别完成，已存入文档库')
      // 云端那条已经在服务端落库了，这里只做「查看/继续编辑」
      pendingCloudDoc.value = { spaceId, docId: data.id }
      return
    }

    text = (text || '').trim()
    if (!text) {
      window.__kbMsg?.warning('没识别到文字，换个角度或光线再试试')
      progress.value = 0
      return
    }
    content.value = text
    progress.value = 1
    statusText.value = '识别完成'
    stage.value = 'review'
    await nextTick()
  } catch (e) {
    console.error(e)
    window.__kbMsg?.error('识别失败：' + (e.message || '未知错误'))
  } finally {
    busy.value = false
  }
}

const pendingCloudDoc = ref(null)
// 收进待整理箱（2026-10-15）：勾选后保存走「个人空间 + inbox 标记」，免选空间
const toInbox = ref(false)

function htmlToText(html) {
  // DOMParser 替代 div.innerHTML（H9）
  return (new DOMParser().parseFromString(html || '', 'text/html').body.textContent || '').trim()
}

function textToHtml(text) {
  return (text || '')
    .split(/\n{2,}/)
    .map((para) => {
      const lines = para.split('\n').map((l) => escapeHtml(l)).join('<br>')
      return '<p>' + lines + '</p>'
    })
    .join('')
}
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}

/** 选择存放位置 */
function openSheet() { sheetMode.value = 'ocr'; sheetOpen.value = true }
function chooseSpaceSave(s) {
  targetSpace.value = s.id
  rememberSpace(s.id)
  sheetOpen.value = false
  if (sheetMode.value === 'image') archiveImages(s.id).catch(() => { /* 已内部提示 */ })
}

/** 保存到文档库（有网直传，无网进队列） */
async function save() {
  if (!title.value.trim() && !content.value.trim()) {
    window.__kbMsg?.warning('标题和内容不能都为空')
    return
  }
  if (pendingCloudDoc.value) {
    // 云端识别已落库：跳过去看
    const p = pendingCloudDoc.value
    router.replace(`/doc/${p.spaceId}/${p.docId}`)
    return
  }

  // 收进待整理箱（2026-10-15）：免选空间快速收纳，之后在待整理箱集中整理
  if (toInbox.value) {
    busy.value = true
    try {
      const p = await personalSpace()
      if (!p?.id) throw new Error('没有可用的个人空间')
      const html = textToHtml(content.value)
      await createShareDoc(p.id, { title: title.value.trim() || '未命名文档', content: html }, true)
      window.__kbMsg?.success('已收进待整理箱，稍后整理')
      router.replace('/inbox')
    } catch (e) {
      window.__kbMsg?.error(e.message || e.response?.data?.error || '收箱失败')
    } finally {
      busy.value = false
    }
    return
  }

  busy.value = true
  // 离线/降级入队时的空间兜底：targetSpace 可能还没解析（页面离线加载时列表拉不到）
  const offlineSid = targetSpace.value || lastSpaceId()
  try {
    const html = textToHtml(content.value)
    if (online.value) {
      let sid = targetSpace.value
      if (!sid) {
        sid = await resolveDefaultSpaceId()
        if (!sid) sid = await personalSpace().then((s) => s && s.id)
      }
      if (!sid) throw new Error('没有可写入的空间')
      const { data } = await createShareDoc(sid, { title: title.value.trim() || '未命名文档', content: html })
      rememberSpace(sid)
      window.__kbMsg?.success('已保存到「' + (spaces.value.find((s) => s.id === sid)?.name || '我的文档') + '」')
      router.replace(`/doc/${sid}/${data.id}`)
    } else {
      // 离线：进队列
      await enqueueDoc({ spaceId: offlineSid, title: title.value.trim() || '未命名文档', content: html })
      window.__kbMsg?.success('已存到本机，联网后自动同步')
      router.replace('/docs')
    }
  } catch (e) {
    console.error(e)
    // 只对「真网络类失败」降级离线队列（第四轮审查）：服务端已受理后响应丢失等情况
    // 降级入队会造出重复文档；业务报错（如校验失败）也不该静默转离线
    const networkFail = typeof navigator !== 'undefined' && navigator.onLine === false
    const noResponse = e && !e.response   // axios：无 response = 请求根本没到服务端
    if (networkFail || noResponse) {
      try {
        await enqueueDoc({ spaceId: offlineSid, title: title.value.trim() || '未命名文档', content: textToHtml(content.value) })
        window.__kbMsg?.warning('网络异常，已存到本机，联网后自动同步')
        router.replace('/docs')
        return
      } catch { /* 队列也失败 → 落到下面的错误提示 */ }
    }
    window.__kbMsg?.error('保存失败：' + (e.message || e.response?.data?.error || '未知错误'))
  } finally {
    busy.value = false
  }
}

/** 预热：进入页面就开始下载中文语言包，用户拍完照就不用等 */
function prewarm() {
  if (engine.value === 'local') warmupLocalOcr().catch(() => {})
}
onMounted(prewarm)
</script>

<template>
  <div class="cap">
    <!-- 顶部栏 -->
    <header class="cap-head">
      <button class="m-header-act" @click="back"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="cap-title">{{ stage === 'pick' ? '拍照建文档' : (stage === 'scan' ? '多页扫描' : (stage === 'preview' ? '识别' : '确认内容')) }}</div>
      <button class="m-header-act" style="visibility:hidden"><el-icon><Close /></el-icon></button>
    </header>

    <input ref="cameraInput" type="file" accept="image/*" capture="environment" hidden @change="onFile" />
    <input ref="fileInput" type="file" accept="image/*" hidden @change="onFile" />
    <input ref="scanCamera" type="file" accept="image/*" capture="environment" hidden @change="onScanFiles" />
    <input ref="scanAlbum" type="file" accept="image/*" multiple hidden @change="onScanFiles" />

    <!-- 阶段 1：选择来源 -->
    <div v-if="stage === 'pick'" class="stage">
      <div class="pick-hint">
        <div class="ph-title">拍下文档，自动变成可编辑的文字</div>
        <div class="ph-sub">支持纸质文件、书本、白板、名片，识别后可手动修正</div>
      </div>

      <div class="pick-actions">
        <button class="pick-btn primary" @click="pickCamera">
          <span class="pb-ico"><el-icon :size="30"><Camera /></el-icon></span>
          <span class="pb-txt">拍照</span>
          <span class="pb-desc">调用相机对准文档</span>
        </button>
        <button class="pick-btn" @click="pickAlbum">
          <span class="pb-ico gray"><el-icon :size="30"><Picture /></el-icon></span>
          <span class="pb-txt">从相册选择</span>
          <span class="pb-desc">选一张已有的图片</span>
        </button>
        <button class="pick-btn" @click="openScan">
          <span class="pb-ico orange"><el-icon :size="30"><Files /></el-icon></span>
          <span class="pb-txt">多页扫描</span>
          <span class="pb-desc">连拍多页 · 导出 PDF</span>
        </button>
        <button class="pick-btn" @click="openIdSheet">
          <span class="pb-ico blue"><el-icon :size="30"><Postcard /></el-icon></span>
          <span class="pb-txt">证件扫描</span>
          <span class="pb-desc">身份证 / 护照 · 自动锁定比例</span>
        </button>
      </div>

      <!-- 识别引擎选择 -->
      <div class="engine">
        <div class="m-sec-title">识别方式</div>
        <div class="m-card">
          <button class="m-row" type="button" @click="engine = 'local'">
            <span class="m-ico green"><el-icon><Lock /></el-icon></span>
            <span class="m-row-body">
              <span class="m-row-title">本地识别</span>
              <span class="m-row-desc">图片不离开手机 · 离线可用 · 适合纯文字；表格/清单建议用云端</span>
            </span>
            <span class="radio" :class="{ on: engine === 'local' }"><i></i></span>
          </button>
          <button class="m-row" type="button" @click="engine = 'cloud'">
            <span class="m-ico blue"><el-icon><Promotion /></el-icon></span>
            <span class="m-row-body">
              <span class="m-row-title">云端识别</span>
              <span class="m-row-desc">精度更高 · 能识别表格结构（IP/MAC 清单、报表等）· 需联网</span>
            </span>
            <span class="radio" :class="{ on: engine === 'cloud' }"><i></i></span>
          </button>
        </div>
        <div class="engine-tip">
          <el-icon><InfoFilled /></el-icon>
          <span>纯文字内容本地识别即可；表格、清单、复杂排版建议用云端（多模态模型理解结构更准）。</span>
        </div>
      </div>
    </div>

    <!-- 阶段：多页扫描（导出 PDF / 批量识别） -->
    <div v-if="stage === 'scan'" class="stage">
      <div class="pick-hint">
        <input v-model="scanTitle" class="scan-name" type="text" maxlength="40"
               placeholder="给这份扫描件起个名字（可选）" />
        <div v-if="idRatio" class="id-chip">
          <span>证件模式 · {{ idType }}（比例已锁定）</span>
          <button type="button" @click="clearIdMode">退出</button>
        </div>
        <div class="ph-sub">
          已添加 {{ scanPages.length }} 页 · 点页可裁剪/矫正/滤镜{{ scanPages.length > 1 ? ' · 长按可拖拽排序' : '' }}
        </div>
      </div>

      <div class="scan-grid">
        <div v-for="(p, i) in scanPages" :key="p.uid || i" class="scan-item"
             :class="{ 'drag-src': dragIdx === i, 'drag-over': dragOver === i && dragIdx >= 0 && dragIdx !== i }"
             :data-idx="i"
             @pointerdown="onPageDown($event, i)"
             @click="onPageClick(i)">
          <img :src="p.url" alt="" draggable="false" />
          <span class="scan-idx">{{ i + 1 }}</span>
          <span class="scan-edit">编辑</span>
          <div class="scan-ops" @click.stop @pointerdown.stop>
            <button type="button" :disabled="i === 0" @click="movePage(i, -1)">↑</button>
            <button type="button" :disabled="i === scanPages.length - 1" @click="movePage(i, 1)">↓</button>
            <button type="button" class="del" @click="removePage(i)">×</button>
          </div>
        </div>
        <button class="scan-add" type="button" @click="pickScanCamera">
          <el-icon :size="22"><Camera /></el-icon><span>拍一张</span>
        </button>
        <button class="scan-add" type="button" @click="pickScanAlbum">
          <el-icon :size="22"><Picture /></el-icon><span>相册</span>
        </button>
      </div>

      <div v-if="busy || exporting" class="scan-status">
        <el-icon class="rot"><Loading /></el-icon><span>{{ statusText || '处理中…' }}</span>
      </div>

      <div class="scan-actions">
        <button class="m-btn" :disabled="!scanPages.length || exporting || busy" @click="exportSheet = true">
          <el-icon><Download /></el-icon>{{ exporting ? '导出中…' : '导出（PDF / 长图）' }}
        </button>
        <button class="m-btn ghost" :disabled="!scanPages.length || exporting || busy" @click="openImageArchive">
          <el-icon><FolderOpened /></el-icon>存为图片文档
        </button>
        <button class="m-btn ghost" :disabled="!scanPages.length || busy" @click="recognizeAll">
          <el-icon><EditPen /></el-icon>识别全部并入库
        </button>
        <button class="m-btn ghost" :disabled="busy" @click="stage = 'pick'">返回</button>
      </div>
    </div>

    <!-- 阶段 2：图片预览 + 识别 -->
    <div v-else-if="stage === 'preview'" class="stage">
      <div class="preview-box">
        <img :src="previewUrl" alt="待识别图片" />
        <div v-if="busy" class="preview-mask">
          <div class="pm-ring"><el-icon :size="26"><Loading /></el-icon></div>
          <div class="pm-txt">{{ statusText }}</div>
          <div class="pm-bar"><i :style="{ width: Math.round(progress * 100) + '%' }"></i></div>
        </div>
      </div>

      <div class="preview-info">
        <div class="pi-row">
          <span class="pi-k">识别方式</span>
          <span class="pi-v">{{ engine === 'local' ? '本地识别（离线可用）' : '云端识别' }}</span>
        </div>
        <div class="pi-row">
          <span class="pi-k">存放位置</span>
          <button class="pi-pick" @click="openSheet">{{ targetName }} <el-icon><Right /></el-icon></button>
        </div>
      </div>

      <div class="stage-actions">
        <button class="m-btn ghost" @click="stage = 'pick'" :disabled="busy">重新选图</button>
        <button class="m-btn" @click="startRecognize" :disabled="busy">
          <el-icon v-if="!busy"><View /></el-icon>
          <el-icon v-else class="rot"><Loading /></el-icon>
          {{ busy ? '识别中…' : '开始识别' }}
        </button>
      </div>
    </div>

    <!-- 阶段 3：识别结果确认 + 编辑 -->
    <div v-else class="stage review">
      <div class="field">
        <label>标题</label>
        <el-input v-model="title" placeholder="给这篇文档起个名字" maxlength="80" />
      </div>

      <div class="field">
        <label>
          识别内容
          <span class="field-hint">可直接修改</span>
        </label>
        <textarea v-model="content" class="txt" rows="13" placeholder="识别出的文字会出现在这里"></textarea>
      </div>

      <div class="field">
        <div class="field-row" @click="openSheet">
          <span class="fr-k">存放位置</span>
          <span class="fr-v">{{ targetName }} <el-icon><Right /></el-icon></span>
        </div>
      </div>

      <div class="review-note">
        <el-icon><InfoFilled /></el-icon>
        <span v-if="toInbox">将先收进待整理箱，之后在文档库集中整理</span>
        <span v-else-if="pendingCloudDoc">云端识别已完成并存入库中，点下方按钮查看</span>
        <span v-else-if="online">保存后会直接进入「{{ targetName }}」</span>
        <span v-else>当前离线，保存后会在联网时自动同步</span>
      </div>

      <label v-if="!pendingCloudDoc" class="inbox-toggle">
        <input type="checkbox" v-model="toInbox" />
        <span>先收进待整理箱（免选空间，稍后整理）</span>
      </label>

      <div class="stage-actions">
        <button class="m-btn ghost" @click="stage = 'preview'" :disabled="busy">返回</button>
        <button class="m-btn" @click="save" :disabled="busy">
          <el-icon v-if="busy" class="rot"><Loading /></el-icon>
          {{ pendingCloudDoc ? '查看文档' : (toInbox ? '收进待整理箱' : (online ? '保存到文档库' : '存到本机')) }}
        </button>
      </div>
    </div>

    <!-- 存放位置选择弹层 -->
    <transition name="sheet-up">
      <div v-if="sheetOpen" class="m-sheet-mask" @click.self="sheetOpen = false">
        <div class="m-sheet">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">选择存放位置</div>
          <button v-for="s in spaces" :key="s.id" class="m-sheet-item" type="button" @click="chooseSpaceSave(s)">
            <span class="m-ico" :class="s.type === 'PERSONAL' ? '' : 'blue'">
              <el-icon><component :is="s.type === 'PERSONAL' ? 'User' : 'DocumentCopy'" /></el-icon>
            </span>
            <span style="flex:1 1 auto;text-align:left">
              {{ s.name }}
              <span style="font-size:12px;color:var(--m-text-dim);display:block">
                {{ s.type === 'PERSONAL' ? '个人空间 · 推荐' : '团队空间' }}
              </span>
            </span>
            <el-icon v-if="targetSpace === s.id" style="color:var(--m-primary)"><Check /></el-icon>
          </button>
          <div v-if="!spaces.length" style="padding:20px;text-align:center;color:var(--m-text-dim);font-size:13px">
            离线中，无法读取空间列表
          </div>
          <button class="m-sheet-item cancel" type="button" @click="sheetOpen = false">取消</button>
        </div>
      </div>
    </transition>

    <!-- 导出选项（水印 / 密码 / PDF 或长图） -->
    <transition name="sheet-up">
      <div v-if="exportSheet" class="m-sheet-mask" @click="exportSheet = false">
        <div class="m-sheet" @click.stop>
          <div class="ex-h">导出扫描件</div>
          <div class="ex-field">
            <span class="ex-label">水印文字</span>
            <input v-model="wmText" class="ex-input" type="text" maxlength="20" placeholder="留空 = 不加" />
          </div>
          <div class="ex-field">
            <span class="ex-label">PDF 密码</span>
            <input v-model="pdfPwd" class="ex-input" type="text" maxlength="32" placeholder="留空 = 不加密" />
          </div>
          <div class="ex-hint">
            共 {{ scanPages.length }} 页{{ wmText.trim() ? ' · 含水印' : '' }}{{ pdfPwd.trim() ? ' · 已加密' : '' }}
          </div>
          <button class="m-btn" :disabled="exporting" @click="exportPdf">
            <el-icon><Document /></el-icon>{{ exporting ? '导出中…' : '导出 PDF' }}
          </button>
          <button class="m-btn ghost" :disabled="exporting" @click="exportLongImage">
            <el-icon><Picture /></el-icon>导出长图（拼成一张）
          </button>
          <button class="m-sheet-item cancel" type="button" @click="exportSheet = false">取消</button>
        </div>
      </div>
    </transition>

    <!-- 证件类型选择 -->
    <transition name="sheet-up">
      <div v-if="idSheet" class="m-sheet-mask" @click.self="idSheet = false">
        <div class="m-sheet">
          <div class="ex-h">选择证件类型</div>
          <div class="ex-hint">裁剪框会按证件的真实比例锁定，避免拉变形</div>
          <button v-for="p in ID_PRESETS" :key="p.key" class="m-sheet-item" type="button" @click="pickIdType(p)">
            <el-icon><Postcard /></el-icon>
            <span>{{ p.name }}</span>
            <span class="id-ratio">{{ p.ratio.toFixed(2) }} : 1</span>
          </button>
          <button class="m-sheet-item cancel" type="button" @click="idSheet = false">取消</button>
        </div>
      </div>
    </transition>

    <!-- 单页编辑器（全屏） -->
    <div v-if="editingIndex >= 0 && scanPages[editingIndex]" class="se-overlay">
      <MobileScanEditor :page="scanPages[editingIndex]" :ratio="idRatio"
                        @done="onEditDone" @cancel="editingIndex = -1" />
    </div>
  </div>
</template>

<style scoped>
/* ---------- 多页扫描 ---------- */
.scan-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 10px; padding: 12px 16px 4px;
}
.scan-item {
  position: relative; border-radius: 10px; overflow: hidden;
  background: var(--m-card-2); box-shadow: var(--m-shadow);
}
.scan-item img { width: 100%; height: 128px; object-fit: cover; display: block; }
.scan-idx {
  position: absolute; left: 6px; top: 6px;
  min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px;
  background: rgba(0,0,0,.55); color: #fff; font-size: 11px; line-height: 18px; text-align: center;
}
.scan-ops {
  position: absolute; right: 4px; bottom: 4px; display: flex; gap: 4px;
}
.scan-ops button {
  width: 24px; height: 24px; border: none; border-radius: 6px;
  background: rgba(0,0,0,.55); color: #fff; font-size: 13px; line-height: 1;
}
.scan-ops button:disabled { opacity: .35; }
.scan-ops button.del { background: rgba(220,38,38,.85); font-size: 15px; }
.scan-add {
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px;
  height: 128px; border: 1px dashed var(--m-border); border-radius: 10px;
  background: transparent; color: var(--m-text-sub); font-size: 12.5px;
}
.scan-status {
  display: flex; align-items: center; gap: 8px; justify-content: center;
  padding: 10px 16px 0; color: var(--m-text-sub); font-size: 13px;
}
.rot { animation: kb-rot 1s linear infinite; }
@keyframes kb-rot { to { transform: rotate(360deg); } }
.scan-actions { display: flex; flex-direction: column; gap: 10px; padding: 16px; }

.cap { min-height: 100%; display: flex; flex-direction: column; }

.cap-head {
  position: sticky; top: 0; z-index: 30;
  display: flex; align-items: center; gap: 8px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
}
.cap-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }

.stage { flex: 1 1 auto; padding: 0 16px 26px; }

/* ---------- 阶段 1 ---------- */
.pick-hint { padding: 8px 2px 20px; }
.ph-title { font-size: 21px; font-weight: 700; line-height: 1.35; letter-spacing: -.3px; }
.ph-sub { font-size: 13.5px; color: var(--m-text-dim); margin-top: 7px; line-height: 1.55; }

.pick-actions { display: flex; flex-direction: column; gap: 12px; }
.pick-btn {
  border: none; border-radius: var(--m-radius-lg);
  background: var(--m-card); box-shadow: var(--m-shadow);
  padding: 20px; display: flex; flex-direction: column; align-items: center; gap: 6px;
  cursor: pointer; transition: transform .14s;
}
.pick-btn:active { transform: scale(.98); }
.pick-btn.primary { background: var(--m-primary); color: #fff; box-shadow: 0 8px 22px rgba(22, 119, 255, .28); }
.pb-ico {
  width: 62px; height: 62px; border-radius: 20px;
  display: inline-flex; align-items: center; justify-content: center;
  background: rgba(255, 255, 255, .2);
}
.pb-ico.gray { background: #f2f4f7; color: #667085; }
html.dark .pb-ico.gray { background: #2a2a42; color: #9aa0ae; }
.pb-txt { font-size: 17px; font-weight: 600; }
.pb-desc { font-size: 12.5px; opacity: .78; }

.engine { margin-top: 22px; }
.radio {
  flex: none; width: 21px; height: 21px; border-radius: 50%;
  border: 2px solid var(--m-border); display: inline-flex;
  align-items: center; justify-content: center; transition: border-color .15s;
}
.radio.on { border-color: var(--m-primary); }
.radio i {
  width: 11px; height: 11px; border-radius: 50%; background: var(--m-primary);
  transform: scale(0); transition: transform .16s cubic-bezier(.34, 1.56, .64, 1);
}
.radio.on i { transform: scale(1); }
.engine-tip {
  display: flex; gap: 6px; align-items: flex-start;
  font-size: 12px; color: var(--m-text-dim);
  margin-top: 10px; padding: 0 4px; line-height: 1.5;
}

/* ---------- 阶段 2 ---------- */
.preview-box {
  position: relative; border-radius: var(--m-radius-lg); overflow: hidden;
  background: #000; box-shadow: var(--m-shadow);
  max-height: 56vh; display: flex; align-items: center; justify-content: center;
}
.preview-box img { width: 100%; max-height: 56vh; object-fit: contain; display: block; }
.preview-mask {
  position: absolute; inset: 0;
  background: rgba(16, 24, 40, .72);
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px;
  color: #fff; padding: 20px;
}
.pm-ring { animation: kb-rot 1s linear infinite; color: #a5b4fc; }
@keyframes kb-rot { to { transform: rotate(360deg); } }
.pm-txt { font-size: 13.5px; }
.pm-bar { width: 160px; height: 4px; border-radius: 2px; background: rgba(255, 255, 255, .25); overflow: hidden; }
.pm-bar i { display: block; height: 100%; background: #a5b4fc; border-radius: 2px; transition: width .25s; }

.preview-info { margin-top: 14px; }
.pi-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 12px 14px; background: var(--m-card); border-radius: var(--m-radius);
  box-shadow: var(--m-shadow); font-size: 14px;
}
.pi-row + .pi-row { margin-top: 8px; }
.pi-k { color: var(--m-text-dim); font-size: 13.5px; }
.pi-v { color: var(--m-text); font-weight: 500; }
.pi-pick {
  border: none; background: transparent; color: var(--m-primary);
  font-size: 14px; font-weight: 600; display: inline-flex; align-items: center; gap: 2px;
  cursor: pointer; padding: 0;
}

/* ---------- 阶段 3 ---------- */
.review .field { margin-top: 16px; }
.review .field > label {
  display: flex; align-items: center; gap: 6px;
  font-size: 13px; font-weight: 600; color: var(--m-text-sub);
  margin-bottom: 8px; padding-left: 2px;
}
.field-hint { font-weight: 400; color: var(--m-text-dim); font-size: 12px; }
.txt {
  width: 100%; box-sizing: border-box;
  border: 1px solid var(--m-border);
  background: var(--m-card); color: var(--m-text);
  border-radius: var(--m-radius);
  padding: 13px 14px; font-size: 15px; line-height: 1.7;
  font-family: inherit; resize: vertical; min-height: 200px;
  outline: none; transition: border-color .15s;
}
.txt:focus { border-color: var(--m-primary); }

.field-row {
  display: flex; align-items: center; justify-content: space-between;
  background: var(--m-card); border-radius: var(--m-radius);
  box-shadow: var(--m-shadow); padding: 14px; font-size: 14.5px; cursor: pointer;
}
.fr-k { color: var(--m-text-dim); font-size: 13.5px; }
.fr-v { color: var(--m-primary); font-weight: 600; display: inline-flex; align-items: center; gap: 2px; }

.review-note {
  display: flex; gap: 6px; align-items: flex-start;
  font-size: 12.5px; color: var(--m-text-dim);
  margin-top: 16px; padding: 0 4px; line-height: 1.5;
}

/* 待整理箱开关（2026-10-15） */
.inbox-toggle {
  display: flex; gap: 8px; align-items: center;
  margin-top: 12px; padding: 0 4px;
  font-size: 13px; color: var(--m-text-sub); cursor: pointer;
  user-select: none;
}
.inbox-toggle input { accent-color: var(--m-primary); width: 16px; height: 16px; flex: none; }

.stage-actions { display: flex; gap: 10px; margin-top: 22px; }
.stage-actions .m-btn { flex: 1 1 0; width: auto; }
.rot { animation: kb-rot 1s linear infinite; }

/* ---------- 扫描增强（命名 / 编辑入口 / 导出面板） ---------- */
.scan-name {
  width: 100%; box-sizing: border-box; height: 40px; padding: 0 12px;
  border: 1px solid var(--m-border); border-radius: 11px; background: var(--m-card-2);
  color: var(--m-text); font-size: 14px; font-family: inherit; margin-bottom: 8px;
}
.scan-name:focus { border-color: var(--m-primary); background: var(--m-card); outline: none; }

.scan-item { position: relative; cursor: pointer; }
/* ⚠️ 图片默认 draggable：按住拖动会触发浏览器原生拖拽，吞掉后续 pointermove，
   导致长按排序失效（2026-10-13 修） */
.scan-item img { -webkit-user-drag: none; user-select: none; -webkit-user-select: none; }
.scan-edit {
  position: absolute; left: 4px; bottom: 4px;
  padding: 1px 7px; border-radius: 7px;
  background: rgba(0, 0, 0, .55); color: #fff; font-size: 10.5px;
}

.se-overlay { position: fixed; inset: 0; z-index: 3000; background: var(--m-bg); }

.ex-h { font-size: 15px; font-weight: 600; color: var(--m-text); padding: 4px 4px 12px; }
.ex-field { display: flex; align-items: center; gap: 10px; padding: 6px 4px; }
.ex-label { flex: none; width: 72px; font-size: 13.5px; color: var(--m-text-sub); }
.ex-input {
  flex: 1 1 auto; min-width: 0; height: 40px; padding: 0 12px;
  border: 1px solid var(--m-border); border-radius: 10px; background: var(--m-card-2);
  color: var(--m-text); font-size: 14px; font-family: inherit;
}
.ex-input:focus { border-color: var(--m-primary); background: var(--m-card); outline: none; }
.ex-hint { font-size: 12px; color: var(--m-text-dim); padding: 6px 4px 12px; }

/* 证件模式提示条 */
.id-chip {
  display: flex; align-items: center; gap: 8px; justify-content: space-between;
  margin-bottom: 8px; padding: 7px 12px;
  background: var(--m-primary-soft); border-radius: 10px;
  font-size: 12.5px; color: var(--m-primary);
}
.id-chip button {
  flex: none; border: none; background: none; color: var(--m-primary);
  font-size: 12.5px; text-decoration: underline; padding: 0;
}
.id-ratio { margin-left: auto; font-size: 12px; color: var(--m-text-dim); }

/* 拖拽排序 */
.scan-item.drag-src { opacity: .45; transform: scale(.96); }
.scan-item.drag-over { outline: 2px dashed var(--m-primary); outline-offset: -2px; }
</style>
