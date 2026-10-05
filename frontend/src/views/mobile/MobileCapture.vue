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
import { enqueueDoc } from '../../utils/offlineStore'
import { mySpaces, resolveDefaultSpaceId, rememberSpace, personalSpace } from '../../utils/spaceResolver'
import { recognizeLocal, warmupLocalOcr } from '../../utils/localOcr'

const router = useRouter()

const fileInput = ref(null)
const cameraInput = ref(null)

const stage = ref('pick')        // pick | preview | review
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
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})

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
      })
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

function htmlToText(html) {
  const d = document.createElement('div')
  d.innerHTML = html || ''
  return (d.textContent || '').trim()
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
function openSheet() { sheetOpen.value = true }
function chooseSpaceSave(s) {
  targetSpace.value = s.id
  rememberSpace(s.id)
  sheetOpen.value = false
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

  busy.value = true
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
      await enqueueDoc({ spaceId: targetSpace.value, title: title.value.trim() || '未命名文档', content: html })
      window.__kbMsg?.success('已存到本机，联网后自动同步')
      router.replace('/docs')
    }
  } catch (e) {
    console.error(e)
    // 有网但请求失败（如断网瞬间）：降级为离线保存，避免用户白拍
    try {
      await enqueueDoc({ spaceId: targetSpace.value, title: title.value.trim() || '未命名文档', content: textToHtml(content.value) })
      window.__kbMsg?.warning('网络异常，已存到本机，联网后自动同步')
      router.replace('/docs')
    } catch {
      window.__kbMsg?.error('保存失败：' + (e.message || '未知错误'))
    }
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
      <div class="cap-title">{{ stage === 'pick' ? '拍照建文档' : (stage === 'preview' ? '识别' : '确认内容') }}</div>
      <button class="m-header-act" style="visibility:hidden"><el-icon><Close /></el-icon></button>
    </header>

    <input ref="cameraInput" type="file" accept="image/*" capture="environment" hidden @change="onFile" />
    <input ref="fileInput" type="file" accept="image/*" hidden @change="onFile" />

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
      </div>

      <!-- 识别引擎选择 -->
      <div class="engine">
        <div class="m-sec-title">识别方式</div>
        <div class="m-card">
          <button class="m-row" type="button" @click="engine = 'local'">
            <span class="m-ico green"><el-icon><Lock /></el-icon></span>
            <span class="m-row-body">
              <span class="m-row-title">本地识别</span>
              <span class="m-row-desc">图片不离开手机 · 离线可用 · 首次需下载语言包</span>
            </span>
            <span class="radio" :class="{ on: engine === 'local' }"><i></i></span>
          </button>
          <button class="m-row" type="button" @click="engine = 'cloud'">
            <span class="m-ico blue"><el-icon><Promotion /></el-icon></span>
            <span class="m-row-body">
              <span class="m-row-title">云端识别</span>
              <span class="m-row-desc">精度更高 · 需联网 · 排版还原更好</span>
            </span>
            <span class="radio" :class="{ on: engine === 'cloud' }"><i></i></span>
          </button>
        </div>
        <div class="engine-tip">
          <el-icon><InfoFilled /></el-icon>
          <span>推荐先用「本地识别」实测效果；不够准再换云端。</span>
        </div>
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
        <span v-if="pendingCloudDoc">云端识别已完成并存入库中，点下方按钮查看</span>
        <span v-else-if="online">保存后会直接进入「{{ targetName }}」</span>
        <span v-else>当前离线，保存后会在联网时自动同步</span>
      </div>

      <div class="stage-actions">
        <button class="m-btn ghost" @click="stage = 'preview'" :disabled="busy">返回</button>
        <button class="m-btn" @click="save" :disabled="busy">
          <el-icon v-if="busy" class="rot"><Loading /></el-icon>
          {{ pendingCloudDoc ? '查看文档' : (online ? '保存到文档库' : '存到本机') }}
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
  </div>
</template>

<style scoped>
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
.pick-btn.primary { background: linear-gradient(140deg, #6366f1, #4f46e5); color: #fff; box-shadow: 0 8px 22px rgba(79, 70, 229, .3); }
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

.stage-actions { display: flex; gap: 10px; margin-top: 22px; }
.stage-actions .m-btn { flex: 1 1 0; width: auto; }
.rot { animation: kb-rot 1s linear infinite; }
</style>
