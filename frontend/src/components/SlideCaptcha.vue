<template>
  <div class="slide-captcha" ref="rootEl">
    <div class="sc-header">
      <div class="sc-title">安全验证</div>
      <div class="sc-subtitle">拖动下方滑块，将拼块对齐缺口</div>
    </div>
    <div class="sc-stage" :class="{ shake: shaking }" ref="stageEl">
      <img
        v-if="bgUrl"
        class="sc-bg"
        :src="bgUrl"
        :width="W"
        :height="H"
        alt=""
      />
      <img
        v-if="bgUrl && pieceUrl && !verified"
        class="sc-piece"
        :src="pieceUrl"
        :style="pieceStyle"
        alt=""
      />
      <div v-if="!bgUrl" class="sc-loading">加载中…</div>
    </div>
    <div class="sc-bar" ref="barEl">
      <div class="sc-track"></div>
      <div
        class="sc-handle"
        :class="{ done: verified, checking }"
        :style="{ left: handleLeft + 'px', width: HANDLE_W + 'px' }"
        @pointerdown="onDown"
      >
        <span class="grip">|||</span>
        <span class="ok">✓</span>
      </div>
    </div>
    <div class="sc-footer">
      <span class="sc-hint" :class="{ ok: verified, err: !!failMsg }">
        {{ verified ? '验证通过' : checking ? '校验中…' : failMsg || '拖动滑块完成拼图' }}
      </span>
      <div class="sc-actions">
        <button type="button" class="sc-btn" title="刷新" @click="fetchCaptcha">↻</button>
        <button type="button" class="sc-btn" title="关闭" @click="emit('close')">✕</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { captcha as apiCaptcha, captchaSlide } from '../api/auth'

const emit = defineEmits(['success', 'close'])

// 设计基准尺寸（按 320x160 设计稿；小屏按比例缩放）
const BASE_W = 320
const BASE_H = 160
const PIECE_W = 45
const PIECE_H = 45
const HANDLE_W = 44

// 实际渲染尺寸（响应式）：由容器宽度驱动
const W = ref(BASE_W)            // stage 实际宽（≤ 容器宽）
const H = ref(BASE_H)            // stage 实际高 = W * BASE_H/BASE_W
const TRACK_W = ref(BASE_W)      // 拖动条实际宽
const MAX_HANDLE = computed(() => Math.max(0, TRACK_W.value - HANDLE_W))

const token = ref('')
const bgUrl = ref('')
const pieceUrl = ref('')
const pieceY = ref(0)

const handleLeft = ref(0)
const verified = ref(false)
const shaking = ref(false)
const checking = ref(false)
const elapsed = ref(0)
const trajectory = ref([])
const failMsg = ref('')   // 服务端返回的具体失败原因（对齐失败/被锁定等），展示到提示行
let startTime = 0

const rootEl = ref(null)
const stageEl = ref(null)
const barEl = ref(null)

// 拼块左偏移 = 手柄位置映射到 [0, W - 拼块显示宽]（按 stage 实际宽）
// 注意：全程用小数，不要各自 round——宽/高/left 分别取整会累积 1px 级误差，
// 表现为拼块与凹槽差半像素、边缘对不齐。
const pieceLeft = computed(() => {
  if (MAX_HANDLE.value <= 0) return 0
  const ratio = handleLeft.value / MAX_HANDLE.value
  return ratio * (W.value - pieceDisplayW.value)
})

// 拼块显示尺寸/位置：按实际渲染宽高从 320x160 基准缩放，使其视觉上正好嵌入凹槽
// （服务端 correctX/correctY 均基于 320x160 基准，前端必须把显示坐标换算回该基准再上报）
const pieceDisplayW = computed(() => Math.max(1, PIECE_W * W.value / BASE_W))
const pieceDisplayH = computed(() => Math.max(1, PIECE_H * H.value / BASE_H))
const pieceTop = computed(() => pieceY.value * H.value / BASE_H)
const pieceStyle = computed(() => ({
  left: pieceLeft.value.toFixed(2) + 'px',
  top: pieceTop.value.toFixed(2) + 'px',
  width: pieceDisplayW.value.toFixed(2) + 'px',
  height: pieceDisplayH.value.toFixed(2) + 'px'
}))

// 计算 stage / bar 实际尺寸：以 DOM 真实渲染尺寸为准（不能推算/硬编码，
// 否则拼块按 A 宽度缩放、背景按 B 宽度渲染 → 拼块与凹槽错开且上报坐标偏，验证必失败）
function measure() {
  nextTick(() => {
    const stage = stageEl.value
    const bar = barEl.value
    if (!stage || !bar) return
    const rs = stage.getBoundingClientRect()
    const rb = bar.getBoundingClientRect()
    if (!(rs.width > 0)) return              // 尚未布局（如弹窗延迟挂载），等 ResizeObserver 再触发
    const target = Math.min(BASE_W, Math.floor(rs.width))
    const h = Math.round(rs.height) || Math.round(target * BASE_H / BASE_W)
    const tw = Math.floor(rb.width) || target
    if (target === W.value && h === H.value && tw === TRACK_W.value) return
    W.value = target
    H.value = h
    TRACK_W.value = tw
    // 重置手柄位置（否则尺寸变化后偏移错乱）
    if (!dragging) handleLeft.value = 0
  })
}

/**
 * 同步测量（不等 nextTick）：**提交前必须调一次**。
 * 背景：拼图上报值 = 显示坐标 × (320 / 舞台实际宽)。若 W 停留在初始值 320
 * （弹层动画未结束时 getBoundingClientRect().width 为 0 → measure 提前返回，
 * 且 ResizeObserver 不可用时没有第二次机会），而舞台实际渲染只有 292，
 * 上报值恒定偏小约 8% → 服务端对齐校验必失败，表现为「手机版验证码怎么都过不了」。
 */
function measureSync() {
  const stage = stageEl.value
  const bar = barEl.value
  if (!stage || !bar) return
  const rs = stage.getBoundingClientRect()
  const rb = bar.getBoundingClientRect()
  if (!(rs.width > 0)) return
  const target = Math.min(BASE_W, Math.floor(rs.width))
  const h = Math.round(rs.height) || Math.round(target * BASE_H / BASE_W)
  const tw = Math.floor(rb.width) || target
  W.value = target
  H.value = h
  TRACK_W.value = tw
}

// 监听窗口尺寸变化（手机旋转 / 浏览器地址栏收起）
let ro = null
function watchResize() {
  if (typeof ResizeObserver === 'undefined') {
    window.addEventListener('resize', measure)
    return
  }
  ro = new ResizeObserver(measure)
  if (stageEl.value) ro.observe(stageEl.value)   // 直接观察舞台，宽度一变立即重算
}

function clampHandle(v) {
  return Math.max(0, Math.min(MAX_HANDLE.value, v))
}

async function fetchCaptcha() {
  try {
    const { data } = await apiCaptcha()
    token.value = data.token
    bgUrl.value = data.bg
    pieceUrl.value = data.piece
    pieceY.value = data.pieceY ?? 0
    handleLeft.value = 0
    verified.value = false
    shaking.value = false
    elapsed.value = 0
    trajectory.value = []
    startTime = performance.now()
    // 新图加载完后再量一次（DOM 宽度可能尚未稳定）
    await nextTick()
    measure()
  } catch (e) {
    bgUrl.value = ''
    pieceUrl.value = ''
  }
}

/* ---------- 拖拽 ---------- */
let dragging = false
let startX = 0
let startLeft = 0

function onDown(e) {
  if (verified.value || checking.value) return
  dragging = true
  failMsg.value = ''   // 新一轮拖拽，清除上次的失败提示
  trajectory.value = []
  startX = e.clientX
  startLeft = handleLeft.value
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

function onMove(e) {
  if (!dragging) return
  const delta = e.clientX - startX
  handleLeft.value = clampHandle(startLeft + delta)
  trajectory.value.push({ x: pieceLeft.value, t: Math.round(performance.now()) })
}

function onUp() {
  if (!dragging) return
  dragging = false
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  check()
}

async function check() {
  measureSync()                 // 先按当前真实宽度刷新 W/TRACK_W，再换算上报值
  const userX = pieceLeft.value
  checking.value = true
  try {
    // 服务端 correctX 基于 320px 基准画布；显示坐标按实际渲染宽缩放回基准再上报，
    // 否则窄屏/带内边距的弹窗里舞台宽 ≠ 320 时，上报值恒偏小 → 对齐校验失败（400）。
    const scale = W.value > 0 ? BASE_W / W.value : 1
    const scaledX = Math.round(userX * scale)
    const { data } = await captchaSlide(token.value, scaledX, trajectory.value)
    if (data && data.ok) {
      verified.value = true
      elapsed.value = (performance.now() - startTime) / 1000
      emit('success', token.value)
    } else {
      failMsg.value = (data && data.error) || '滑动验证未通过，请重新拖动拼图'
      fail()
    }
  } catch (e) {
    failMsg.value = (e?.response?.data && e.response.data.error) || '滑动验证未通过，请重新拖动拼图'
    fail()
  } finally {
    checking.value = false
  }
}

function fail() {
  shaking.value = true
  setTimeout(() => {
    shaking.value = false
    fetchCaptcha() // 对齐失败：服务端已作废旧票据，重新拉取新拼图
  }, 350)
}

onMounted(() => {
  // 等首屏样式与父容器宽度就绪后再 measure
  measure()
  // 移动端底部弹层有入场动画：挂载瞬间宽度可能是 0（measure 会提前返回），
  // 这里补几次延迟测量，保证 ResizeObserver 不可用时也能拿到真实宽度
  requestAnimationFrame(measure)
  setTimeout(measure, 120)
  setTimeout(measure, 450)
  watchResize()
  fetchCaptcha()
})

onBeforeUnmount(() => {
  if (ro) ro.disconnect()
  window.removeEventListener('resize', measure)
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
})
</script>

<style scoped>
.slide-captcha {
  width: 100%;
  max-width: 320px;
  margin: 0 auto;              /* 居中：移动端底部弹层是全宽的，不居中会贴在左下角 */
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 8px 30px rgba(0,0,0,0.15);
  padding: 14px 14px 12px;
  user-select: none;
  box-sizing: border-box;
}
.sc-header { margin-bottom: 10px; }
.sc-title { font-size: 12px; color: #94a3b8; margin-bottom: 2px; }
.sc-subtitle { font-size: 16px; color: #1e293b; font-weight: 500; }
.sc-stage {
  position: relative;
  width: 100%;
  aspect-ratio: 2 / 1;          /* 320:160 → 自适应高，图片仍以原尺寸绘制 */
  max-width: 320px;
  border-radius: 6px; overflow: hidden;
  background: #e2e8f0;
}
.sc-stage.shake { animation: sc-shake .3s ease-in-out; }
@keyframes sc-shake {
  0%,100%{transform:translateX(0)}
  25%{transform:translateX(-5px)}
  50%{transform:translateX(5px)}
  75%{transform:translateX(-5px)}
}
.sc-bg, .sc-piece { position: absolute; display: block; }
.sc-bg {
  /* 图片资源固定 320x160，舞台自适应缩放，图片跟随铺满 */
  width: 100%; height: 100%;
  object-fit: fill;            /* 拼图坐标依赖 stage 实际宽，图片必须填满不裁切 */
}
.sc-piece { cursor: grab; filter: drop-shadow(0 1px 3px rgba(0,0,0,0.4)); }
.sc-piece:active { cursor: grabbing; }
.sc-loading {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  color: #94a3b8; font-size: 13px;
}
.sc-bar {
  position: relative;
  width: 100%;
  max-width: 320px;
  height: 40px;
  margin-top: 12px;
  display: flex; align-items: center;
}
.sc-track {
  position: absolute; left: 0; right: 0; top: 50%; transform: translateY(-50%);
  height: 12px; border-radius: 6px;
  background: #e5e7eb;
}
.sc-handle {
  position: absolute; top: 0; bottom: 0;
  height: 40px;
  border-radius: 20px;
  background: #3b82f6;
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  cursor: grab;
  box-shadow: 0 2px 8px rgba(59,130,246,0.35);
  touch-action: none;            /* 手机拖动时禁掉浏览器默认手势，避免页面横滚 */
}
.sc-handle:active { cursor: grabbing; }
.sc-handle .grip { font-size: 12px; letter-spacing: 2px; }
.sc-handle .ok { display: none; }
.sc-handle.done { background: #22c55e; box-shadow: 0 2px 8px rgba(34,197,94,0.35); }
.sc-handle.done .grip { display: none; }
.sc-handle.done .ok { display: inline; font-size: 18px; }
.sc-handle.checking { opacity: .7; cursor: progress; }
.sc-footer {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 10px;
}
.sc-hint { font-size: 12px; color: #94a3b8; }
.sc-hint.ok { color: #22c55e; font-weight: 600; }
.sc-hint.err { color: #ef4444; font-weight: 600; }
.sc-actions { display: flex; gap: 8px; }
.sc-btn {
  width: 28px; height: 28px; border-radius: 50%; border: none;
  background: #f1f5f9; color: #64748b;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; font-size: 13px;
}
.sc-btn:hover { background: #e2e8f0; color: #334155; }

/* 暗色模式适配 */
html.dark .slide-captcha { background: var(--el-bg-color-overlay); box-shadow: 0 8px 30px rgba(0, 0, 0, .45); }
html.dark .sc-title { color: var(--el-text-color-secondary); }
html.dark .sc-subtitle { color: var(--el-text-color-primary); }
html.dark .sc-stage { background: var(--el-fill-color); }
html.dark .sc-loading { color: var(--el-text-color-secondary); }
html.dark .sc-track { background: rgba(255, 255, 255, .08); }
html.dark .sc-handle { background: #60a5fa; box-shadow: 0 2px 8px rgba(96, 165, 250, .35); }
html.dark .sc-handle.done { background: #34d399; box-shadow: 0 2px 8px rgba(52, 211, 153, .35); }
html.dark .sc-hint { color: var(--el-text-color-secondary); }
html.dark .sc-hint.ok { color: #4ade80; }
html.dark .sc-btn { background: var(--el-fill-color); color: var(--el-text-color-regular); }
html.dark .sc-btn:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
</style>
