<script setup>
/**
 * 移动端「资产扫码」页：首页金刚区「扫一扫」进这里，也能由二维码链接直达。
 *
 * 扫码用摄像头 + jsQR（public/vendor 下的零依赖单文件，不进构建依赖树），
 * 识别结果既支持新标签的站内链接（/m/asset#code=xxx），也兼容旧标签的纯编号。
 * 不支持摄像头时给出明确降级：系统相机/微信扫标签链接直达，或手动输入编号。
 */
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { scanAsset } from '../../api/asset'
import { useAuthStore } from '../../store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const code = ref('')
const input = ref('')
const loading = ref(false)
const err = ref('')
const asset = ref(null)

const STATUS = { IN_USE: '在用', IDLE: '闲置', REPAIR: '维修', SCRAPPED: '报废' }
const statusText = (s) => STATUS[s] || s || '-'

/**
 * 编号放在 hash 里：hash 不会发给服务端，避开 AuthFilter「原始 URI 含 %XX 一律 400」
 * 对中文/特殊字符编号的误伤；同时兼容 ?code= 与 "#编号" 两种老写法。
 */
function codeFromRoute() {
  const h = String(route.hash || '').replace(/^#/, '')
  if (h) {
    if (h.includes('=')) {
      const v = new URLSearchParams(h).get('code')
      if (v) return v
    } else {
      try { return decodeURIComponent(h) } catch { return h }
    }
  }
  return String(route.query.code || '')
}

/** 扫码结果 → 资产编号：新标签是本站链接，旧标签是纯编号 */
function parseScanned(raw) {
  const s = String(raw || '').trim()
  if (!s) return ''
  try {
    const u = new URL(s)
    const h = String(u.hash || '').replace(/^#/, '')
    if (h.includes('=')) {
      const v = new URLSearchParams(h).get('code')
      if (v) return v
    } else if (h) {
      try { return decodeURIComponent(h) } catch { return h }
    }
    return u.searchParams.get('code') || s
  } catch { return s }
}

const needLogin = computed(() => !auth.loggedIn)

const rows = computed(() => {
  const a = asset.value || {}
  const defs = [
    ['责任人', a.ownerName], ['分类', a.categoryName], ['品牌', a.brand], ['型号', a.model],
    ['序列号', a.sn], ['部门', a.department], ['位置', a.location],
    ['购置日期', a.purchaseDate], ['价格', a.price != null ? '¥' + a.price : ''],
    ['保修到期', a.warrantyExpire], ['供应商', a.supplier], ['备注', a.remark]
  ]
  return defs.filter(([, v]) => v !== null && v !== undefined && String(v).trim() !== '')
})

async function load(c) {
  const v = String(c || '').trim()
  if (!v) { asset.value = null; err.value = ''; return }
  code.value = v
  input.value = v
  loading.value = true
  err.value = ''
  asset.value = null
  try {
    const { data } = await scanAsset(v)
    asset.value = data || null
    if (!asset.value) err.value = '没有查到该资产，请核对编号'
  } catch (e) {
    err.value = (e && (e.response?.data?.error || e.message)) || '查询失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function queryInput() {
  if (!input.value.trim()) return
  // 同步到地址栏，刷新/分享后还能回到同一条资产
  router.replace({ path: '/asset', hash: '#code=' + encodeURIComponent(input.value.trim()) })
  load(input.value.trim())
}

function goLogin() {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

// ---------- 摄像头扫码 ----------
const videoRef = ref(null)
const captureRef = ref(null)
const scanning = ref(false)
const decoding = ref(false)
const scanErr = ref('')
const scanTip = ref('')
let stream = null
let timer = 0
let canvas = null
let ctx = null
let jsQRLoading = null
const forceLive = ref(false)

/**
 * App 内优先走「系统相机拍照 + 静态图解码」。
 * 拍照建档用的 <input type=file capture> 已经验证可调起相机（WebView 的文件选择桥，
 * 不需要 CAMERA 运行时权限）；而 getUserMedia 实时视频流要 WebView onPermissionRequest
 * 放行 + App 声明并申请 CAMERA 权限，Capacitor 版两者都没有，点了只会一片黑。
 * 原生壳同样优先拍照，避免不同机型 WebView 表现不一致；想用实时预览可点页面上的入口。
 */
const preferCapture = (() => {
  try {
    if (window.Capacitor && typeof window.Capacitor.isNativePlatform === 'function' &&
        window.Capacitor.isNativePlatform()) return true
    if (typeof window.__KB_NATIVE_TOKEN !== 'undefined') return true
  } catch { /* 浏览器：按普通 Web 处理 */ }
  return false
})()

/** 解码库：新构建带 jsQR.min.js，旧 App 内置资源只有 jsqr.js，两个都挂 window.jsQR */
const JSQR_SRCS = ['/vendor/jsQR.min.js', '/vendor/jsqr.js']
function loadJsQR() {
  if (window.jsQR) return Promise.resolve(window.jsQR)
  if (jsQRLoading) return jsQRLoading
  jsQRLoading = new Promise((resolve, reject) => {
    let i = 0
    const tryNext = () => {
      if (window.jsQR) return resolve(window.jsQR)
      if (i >= JSQR_SRCS.length) return reject(new Error('解码库加载失败'))
      const s = document.createElement('script')
      s.src = JSQR_SRCS[i++]
      s.onload = () => window.jsQR ? resolve(window.jsQR) : tryNext()
      s.onerror = () => tryNext()
      document.head.appendChild(s)
    }
    tryNext()
  }).catch((e) => { jsQRLoading = null; throw e })
  return jsQRLoading
}

function ensureCanvas() {
  if (!canvas) canvas = document.createElement('canvas')
  if (!ctx) ctx = canvas.getContext('2d', { willReadFrequently: true })
}

/** 相机拍回来的文件 → 可绘制的位图；老 WebView 没有 createImageBitmap 时回退 Image */
function loadBitmap(file) {
  if (window.createImageBitmap) {
    return createImageBitmap(file).then((bmp) => ({
      image: bmp, width: bmp.width, height: bmp.height,
      release: () => { try { bmp.close() } catch {} }
    })).catch(() => loadBitmapByImage(file))
  }
  return loadBitmapByImage(file)
}

function loadBitmapByImage(file) {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => resolve({
      image: img, width: img.naturalWidth, height: img.naturalHeight,
      release: () => URL.revokeObjectURL(url)
    })
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('图片读取失败')) }
    img.src = url
  })
}

/** 多尺度试解码：拍照时二维码占比不定，缩一点/放一点命中率明显更高 */
async function decodeFile(file) {
  await loadJsQR()
  ensureCanvas()
  const bmp = await loadBitmap(file)
  try {
    const base = Math.min(bmp.width, 1600)
    for (const s of [1, 0.6, 1.6]) {
      const w = Math.max(1, Math.round(base * s))
      const h = Math.max(1, Math.round(w * bmp.height / bmp.width))
      if (w > 2400 || h > 2400) continue
      canvas.width = w; canvas.height = h
      ctx.clearRect(0, 0, w, h)
      ctx.drawImage(bmp.image, 0, 0, w, h)
      let img
      try { img = ctx.getImageData(0, 0, w, h) } catch { break }
      const res = window.jsQR(img.data, w, h, { inversionAttempts: 'attemptBoth' })
      if (res && res.data) return res.data
    }
  } finally { bmp.release() }
  return ''
}

async function onCaptureFile(e) {
  const f = (e.target.files || [])[0]
  e.target.value = ''   // 允许连续拍同一张标签
  if (!f) return
  scanErr.value = ''
  decoding.value = true
  scanTip.value = '正在识别二维码…'
  try {
    const raw = await decodeFile(f)
    if (!raw) {
      scanErr.value = '没识别出二维码：把标签二维码拍清楚、尽量占满画面再试一次，也可以在下方手动输入编号。'
      return
    }
    input.value = parseScanned(raw)
    queryInput()
  } catch (err) {
    scanErr.value = '识别失败：' + ((err && err.message) || '未知错误') + '；可手动输入编号查询。'
  } finally {
    decoding.value = false
    scanTip.value = ''
  }
}

function stopScan() {
  scanning.value = false
  scanTip.value = ''
  if (timer) { clearTimeout(timer); timer = 0 }
  if (stream) { try { stream.getTracks().forEach(t => t.stop()) } catch {} stream = null }
  if (videoRef.value) videoRef.value.srcObject = null
}

function tick() {
  if (!scanning.value) return
  const v = videoRef.value
  if (v && v.readyState >= 2 && v.videoWidth > 0 && window.jsQR && ctx) {
    const w = 480
    const h = Math.max(1, Math.round(v.videoHeight / v.videoWidth * w))
    canvas.width = w; canvas.height = h
    try {
      ctx.drawImage(v, 0, 0, w, h)
      const img = ctx.getImageData(0, 0, w, h)
      const res = window.jsQR(img.data, w, h, { inversionAttempts: 'dontInvert' })
      if (res && res.data) {
        const c = parseScanned(res.data)
        stopScan()
        input.value = c
        queryInput()
        return
      }
    } catch { /* 单帧读取失败不影响下一帧 */ }
  }
  timer = window.setTimeout(() => requestAnimationFrame(tick), 160)
}

/** 实时取景扫码：手机浏览器可用；App 内作为备用入口 */
async function startLive() {
  if (scanning.value) return
  scanErr.value = ''
  if (!navigator.mediaDevices?.getUserMedia) {
    scanErr.value = '当前环境无法调用摄像头：请点「拍照识别」用系统相机，或手机相机/微信直接扫资产标签二维码（会自动打开本页），也可以在下方手动输入编号。'
    return
  }
  try {
    await loadJsQR()
    stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: 'environment' }, audio: false
    })
    ensureCanvas()
    scanning.value = true
    scanTip.value = '把资产标签二维码放进框内'
    await nextTick()
    const v = videoRef.value
    if (v) {
      v.srcObject = stream
      try { await v.play() } catch {}
    }
    tick()
  } catch (e) {
    stopScan()
    const denied = e && (e.name === 'NotAllowedError' || e.name === 'PermissionDeniedError')
    scanErr.value = denied
      ? '摄像头权限被拒绝：请点「拍照识别」用系统相机，或在系统设置里允许浏览器使用摄像头。'
      : '无法启动摄像头：' + ((e && e.message) || '未知错误') + '；也可以点「拍照识别」或手动输入编号。'
  }
}

/** 主按钮：App 内直接调系统相机，Web 内走实时取景 */
function startScan() {
  if (scanning.value) { stopScan(); return }
  if (preferCapture && !forceLive.value) { captureRef.value?.click(); return }
  startLive()
}

function useLive() {
  forceLive.value = true
  startLive()
}

let autoScanTimer = null
onMounted(() => {
  const c = codeFromRoute()
  if (c) load(c)
  else if (String(route.query.scan || '') === '1') {
    // 进页面就自动弹相机需要先有用户手势，App 内会被 WebView 静默拦掉 → 显示明确按钮等用户点
    if (preferCapture) {
      scanTip.value = '点「拍照识别」，对准资产标签二维码拍一张即可自动查询'
    } else {
      // 先让页面把 header / 卡片渲染出来，再申请摄像头。
      // 原生 WebView 在首帧就创建 video+getUserMedia 时，个别机型会把整个 WebView 刷成白屏。
      autoScanTimer = setTimeout(() => { autoScanTimer = null; startScan() }, 600)
    }
  }
})
onBeforeUnmount(() => {
  if (autoScanTimer) { clearTimeout(autoScanTimer); autoScanTimer = null }
  stopScan()
})
</script>

<template>
  <div class="as">
    <header class="as-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="as-title">资产扫码</div>
      <span class="h-ph"></span>
    </header>

    <div class="as-body">
      <div class="as-card as-scan">
        <div class="as-scan-head">
          <div>
            <div class="as-scan-title">扫一扫</div>
            <div class="as-scan-sub">{{ preferCapture ? '拍一张资产标签二维码，自动读取编号并查询' : '扫描资产标签二维码，自动读取编号并查询' }}</div>
          </div>
          <button class="as-btn primary" :disabled="decoding" @click="startScan">
            {{ scanning ? '停止' : decoding ? '识别中…' : ((preferCapture && !forceLive) ? '拍照识别' : '开始扫码') }}
          </button>
        </div>
        <!-- 系统相机通道：App 内唯一稳定可用的摄像头入口（拍照建档同款） -->
        <input ref="captureRef" class="as-hidden" type="file" accept="image/*"
               capture="environment" @change="onCaptureFile" />
        <div v-if="scanning" class="as-camera">
          <video ref="videoRef" playsinline muted autoplay></video>
          <div class="as-frame"></div>
          <div class="as-camera-tip">{{ scanTip }}</div>
        </div>
        <div v-if="!scanning && scanTip" class="as-hint">{{ scanTip }}</div>
        <div v-if="scanErr" class="as-scan-err">{{ scanErr }}</div>
        <button v-if="preferCapture && !scanning && !forceLive" class="as-link" @click="useLive">改用实时预览取景</button>
      </div>

      <div class="as-tip">标签二维码也可用手机相机/微信直接扫，会自动打开本页；旧标签可在下方手动输入编号查询。</div>

      <div v-if="needLogin" class="as-card as-center">
        <div class="as-msg-title">登录后查看资产信息</div>
        <p class="as-msg-text">资产信息需登录后才可读取，普通用户看到的是脱敏字段。</p>
        <button class="as-btn primary" @click="goLogin">去登录</button>
      </div>

      <template v-else>
        <div v-if="loading" class="as-card as-center">
          <div class="as-msg-title">正在查询 {{ code }}…</div>
        </div>

        <div v-else-if="err" class="as-card as-center">
          <div class="as-msg-title">查询失败</div>
          <p class="as-msg-text">{{ err }}</p>
        </div>

        <div v-else-if="asset" class="as-card">
          <div class="as-name">{{ asset.name }}</div>
          <div class="as-code">{{ asset.code }}</div>
          <div class="as-tags">
            <span class="as-tag">{{ statusText(asset.status) }}</span>
            <span class="as-tag" :class="asset.borrowStatus === 'BORROWED' ? 'warn' : 'ok'">
              {{ asset.borrowStatus === 'BORROWED' ? '借出中' : '可借' }}
            </span>
          </div>
          <div v-if="rows.length" class="as-rows">
            <div v-for="r in rows" :key="r[0]" class="as-row">
              <span class="as-label">{{ r[0] }}</span>
              <span class="as-value">{{ r[1] }}</span>
            </div>
          </div>
        </div>

        <div v-else class="as-card as-center">
          <div class="as-msg-title">扫码或输入资产编号查询</div>
        </div>
      </template>

      <div class="as-card as-search">
        <div class="as-search-title">按编号查询</div>
        <div class="as-input-row">
          <input v-model="input" class="as-input" placeholder="如 ZC-2026-0001"
                 @keydown.enter="queryInput" />
          <button class="as-btn" :disabled="loading || !input.trim()" @click="queryInput">查询</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.as { min-height: 100vh; background: var(--m-bg); }
.as-head {
  display: flex; align-items: center; gap: 8px;
  height: 52px; padding: 0 12px;
  background: var(--m-card); border-bottom: 1px solid var(--m-border);
}
.as-title { flex: 1; text-align: center; font-size: 16px; font-weight: 600; color: var(--m-text); }
.h-ph { width: 32px; }
.as-body { padding: 14px 12px 28px; }
.as-tip { font-size: 12px; color: var(--m-text-dim); line-height: 1.6; margin: 0 2px 12px; }
.as-card {
  background: var(--m-card); border: 1px solid var(--m-border);
  border-radius: var(--m-radius-lg); padding: 16px; margin-bottom: 12px;
}
.as-center { text-align: center; }
.as-msg-title { font-size: 15px; font-weight: 600; color: var(--m-text); }
.as-msg-text { font-size: 13px; color: var(--m-text-dim); line-height: 1.6; margin: 8px 0 14px; }
.as-name { font-size: 19px; font-weight: 600; color: var(--m-text); line-height: 1.4; }
.as-code { font-size: 12px; color: var(--m-text-dim); margin-top: 4px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.as-tags { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.as-tag {
  font-size: 12px; padding: 3px 10px; border-radius: 999px;
  background: var(--m-primary-soft); color: var(--m-primary-deep);
}
.as-tag.ok { background: #e8ffea; color: #00a620; }
.as-tag.warn { background: #fff3e8; color: #d25f00; }
html.dark .as-tag.ok { background: #16301c; color: #6bd07f; }
html.dark .as-tag.warn { background: #3a2a17; color: #ffb26b; }
.as-rows { margin-top: 14px; border-top: 1px solid var(--m-border); }
.as-row {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 9px 0; border-bottom: 1px solid var(--m-border); font-size: 13px;
}
.as-row:last-child { border-bottom: 0; }
.as-label { flex: none; width: 72px; color: var(--m-text-dim); }
.as-value { flex: 1; color: var(--m-text); word-break: break-word; white-space: pre-wrap; }

/* 扫一扫 */
.as-scan-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.as-scan-title { font-size: 15px; font-weight: 600; color: var(--m-text); }
.as-scan-sub { font-size: 12px; color: var(--m-text-dim); line-height: 1.5; margin-top: 3px; }
.as-camera { position: relative; margin-top: 12px; border-radius: 12px; overflow: hidden; background: #000; }
.as-camera video { display: block; width: 100%; max-height: 320px; object-fit: cover; }
.as-frame {
  position: absolute; top: 18%; bottom: 18%; left: 18%; right: 18%;
  border: 2px solid rgba(255,255,255,.92); border-radius: 14px;
  box-shadow: 0 0 0 9999px rgba(0,0,0,.3); pointer-events: none;
}
.as-camera-tip {
  position: absolute; left: 0; right: 0; bottom: 10px; text-align: center;
  color: #fff; font-size: 12px; text-shadow: 0 1px 3px rgba(0,0,0,.7); pointer-events: none;
}
.as-scan-err { margin-top: 10px; font-size: 12px; color: var(--m-danger); line-height: 1.6; }
.as-hidden { display: none; }
.as-hint { margin-top: 10px; font-size: 12px; color: var(--m-text-sub); line-height: 1.6; }
.as-link {
  margin-top: 10px; padding: 0; border: 0; background: none;
  color: var(--m-primary); font-size: 12px; text-decoration: underline;
}

.as-search-title { font-size: 13px; color: var(--m-text-sub); margin-bottom: 10px; }
.as-input-row { display: flex; gap: 8px; }
.as-input {
  flex: 1; min-width: 0; height: 38px; padding: 0 11px;
  border: 1px solid var(--m-border); border-radius: 9px;
  background: var(--m-card-2); color: var(--m-text); font-size: 14px; outline: none;
}
.as-input:focus { border-color: var(--m-primary); }
.as-btn {
  flex: none; height: 38px; padding: 0 16px; border: 1px solid var(--m-border);
  border-radius: 9px; background: var(--m-card-2); color: var(--m-text); font-size: 14px;
}
.as-btn.primary { background: var(--m-primary); border-color: var(--m-primary); color: #fff; padding: 0 18px; }
.as-btn:disabled { opacity: .5; }
</style>
