<script setup>
/**
 * 移动端单页扫描编辑器（借鉴「全能扫描王」）
 *
 * 能力：
 *   - 裁剪：拖四角 / 整体移动，实时预览
 *   - 矫正：四点透视（自动检测文档边缘 + 手动拖角）
 *   - 旋转：左/右 90°（无损，可叠加）
 *   - 滤镜：原图 / 增强 / 灰度 / 黑白 / 去阴影（实时预览，导出时才烘焙）
 *   - 撤销 / 重置
 *
 * 坐标约定：裁剪框与角点均用**归一化坐标**（0~1），与预览像素尺寸解耦。
 * 预览用降采样画布（≤900px）保证流畅；「完成」时对全分辨率画布烘焙滤镜后导出。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import {
  applyFilter, cloneCanvas, cropCanvas, detectCorners, loadImage,
  makeCanvas, perspectiveWarp, rotateCanvas, toJpegPage, FILTERS,
} from '../../utils/scanEnhance'

const props = defineProps({
  page: { type: Object, required: true },   // { url, w, h }
  ratio: { type: Number, default: 0 },      // >0 = 证件模式：裁剪框锁定该宽高比
})
const emit = defineEmits(['done', 'cancel'])

const PREVIEW_MAX = 900
// 全分辨率克隆每层可达 ~16MB：层数过大在移动端极易 OOM（第四轮审查），12 → 5
const MAX_UNDO = 5

const workFull = shallowRef(null)          // 全分辨率工作画布（已应用几何变换）
const filter = ref('original')
const mode = ref('')                       // '' | 'crop' | 'perspective'
const busy = ref(false)
const ready = ref(false)
const previewEl = ref(null)
const stageEl = ref(null)

// 归一化裁剪框 / 透视四角
const crop = ref({ x: 0.06, y: 0.06, w: 0.88, h: 0.88 })
const corners = ref([
  { x: 0.06, y: 0.06 }, { x: 0.94, y: 0.06 },
  { x: 0.94, y: 0.94 }, { x: 0.06, y: 0.94 },
])

// 撤销栈必须是响应式：普通数组进 computed 零依赖 → 永久缓存 false，撤销按钮永远灰
// （第四轮审查 C1，2026-10-14 修）
const undoStack = ref([])
const canUndo = computed(() => undoStack.value.length > 0)

/**
 * 证件模式：把「真实宽高比」换算成**归一化坐标下的比例**。
 * 归一化坐标里 1 单位宽 = 图宽像素、1 单位高 = 图高像素，
 * 所以归一化比例 k = 真实比例 × (图高 / 图宽)。
 */
function normRatioFor(cv) {
  const r = Number(props.ratio) || 0
  if (!r || !cv) return 0
  return r * (cv.height / cv.width)
}
/** 重置裁剪框：证件模式给居中且符合比例的框，否则整幅留边 */
function resetCrop() {
  const k = normRatioFor(workFull.value)
  if (k > 0) {
    let w = 0.92
    let h = w / k
    if (h > 0.92) { h = 0.92; w = h * k }
    crop.value = { x: (1 - w) / 2, y: (1 - h) / 2, w, h }
  } else {
    crop.value = { x: 0.06, y: 0.06, w: 0.88, h: 0.88 }
  }
}

/* ---------------- 渲染 ---------------- */

function pushUndo() {
  if (!workFull.value) return
  undoStack.value.push(cloneCanvas(workFull.value))
  if (undoStack.value.length > MAX_UNDO) undoStack.value.shift()
}

/** 把 canvas 的 CSS 尺寸限制在可用区域内，保证覆盖层与画面严格对齐 */
function fitStage() {
  const el = previewEl.value
  const box = stageEl.value ? stageEl.value.parentElement : null
  if (!el || !box) return
  const cs = getComputedStyle(box)
  const availW = box.clientWidth - (parseFloat(cs.paddingLeft) || 0) - (parseFloat(cs.paddingRight) || 0)
  const availH = box.clientHeight - (parseFloat(cs.paddingTop) || 0) - (parseFloat(cs.paddingBottom) || 0)
  if (availW <= 0 || availH <= 0) return
  const k = Math.min(availW / el.width, availH / el.height, 1)
  el.style.width = Math.round(el.width * k) + 'px'
  el.style.height = Math.round(el.height * k) + 'px'
}

/** 把 workFull 降采样 + 应用滤镜，画到可见 canvas 上 */
function render() {
  const el = previewEl.value
  const full = workFull.value
  if (!el || !full) return
  const scale = Math.min(1, PREVIEW_MAX / Math.max(full.width, full.height))
  const w = Math.max(1, Math.round(full.width * scale))
  const h = Math.max(1, Math.round(full.height * scale))
  const base = makeCanvas(w, h)
  base.getContext('2d').drawImage(full, 0, 0, w, h)
  const out = applyFilter(base, filter.value)
  el.width = out.width
  el.height = out.height
  const ctx = el.getContext('2d')
  ctx.clearRect(0, 0, el.width, el.height)
  ctx.drawImage(out, 0, 0)
  // ⚠️ 必须限制 CSS 尺寸：否则画布会纵向溢出 .se-stage，
  //    而裁剪框/角点用百分比定位（相对 stage）→ 覆盖层与画面错位（2026-10-13 修）
  //    要等布局稳定后再量（首次 render 时容器可能还没高度）
  nextTick(() => { fitStage(); requestAnimationFrame(fitStage) })
  ready.value = true
}

/* ---------------- 加载 ---------------- */

async function load() {
  busy.value = true
  try {
    const img = await loadImage(props.page.url)
    const scale = Math.min(1, 2000 / Math.max(img.naturalWidth, img.naturalHeight))
    const w = Math.round(img.naturalWidth * scale)
    const h = Math.round(img.naturalHeight * scale)
    const cv = makeCanvas(w, h)
    cv.getContext('2d').drawImage(img, 0, 0, w, h)
    workFull.value = cv
    undoStack.value.length = 0
    resetCrop()
    // 证件模式：载入即进裁剪，用户直接拖框对齐证件
    if (Number(props.ratio) > 0) mode.value = 'crop'
    render()
  } catch (e) {
    window.__kbMsg?.error(e.message || '图片加载失败')
  } finally {
    busy.value = false
  }
}
onMounted(load)
// 旋转屏幕 / 视口变化后重新贴合（否则覆盖层又会与画面错位）
function onResize() { fitStage() }
onMounted(() => window.addEventListener('resize', onResize))

/* ---------------- 手势：裁剪框 / 角点 ---------------- */

const drag = ref(null)   // { kind:'crop'|'corner', handle, startX, startY, origin }

function normFromEvent(e) {
  const r = stageEl.value.getBoundingClientRect()
  return {
    x: Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)),
    y: Math.max(0, Math.min(1, (e.clientY - r.top) / r.height)),
  }
}

function onPointerDown(e, kind, handle) {
  e.preventDefault()
  e.stopPropagation()
  const p = normFromEvent(e)
  drag.value = {
    kind, handle,
    origin: kind === 'crop' ? { ...crop.value } : corners.value.map((c) => ({ ...c })),
    start: p,
  }
  try { e.target.setPointerCapture?.(e.pointerId) } catch { /* ignore */ }
}

function onPointerMove(e) {
  const d = drag.value
  if (!d) return
  e.preventDefault()
  const p = normFromEvent(e)
  const dx = p.x - d.start.x
  const dy = p.y - d.start.y

  if (d.kind === 'crop') {
    const o = d.origin
    if (d.handle === 'move') {
      crop.value = {
        x: Math.max(0, Math.min(1 - o.w, o.x + dx)),
        y: Math.max(0, Math.min(1 - o.h, o.y + dy)),
        w: o.w, h: o.h,
      }
      return
    }
    const MIN = 0.08
    let x1 = o.x, y1 = o.y, x2 = o.x + o.w, y2 = o.y + o.h
    if (d.handle.includes('w')) x1 = Math.max(0, Math.min(x2 - MIN, o.x + dx))
    if (d.handle.includes('e')) x2 = Math.min(1, Math.max(x1 + MIN, o.x + o.w + dx))
    if (d.handle.includes('n')) y1 = Math.max(0, Math.min(y2 - MIN, o.y + dy))
    if (d.handle.includes('s')) y2 = Math.min(1, Math.max(y1 + MIN, o.y + o.h + dy))

    // 证件模式：由宽度推高度，保持「对角锚点」不动，并做边界收敛
    const k = normRatioFor(workFull.value)
    if (k > 0) {
      let w = Math.max(MIN, x2 - x1)
      let h = w / k
      if (h > 1) { h = 1; w = h * k }
      if (w > 1) { w = 1; h = w / k }
      const anchorRight = d.handle.includes('w')
      const anchorBottom = d.handle.includes('n')
      x1 = anchorRight ? x2 - w : x1
      y1 = anchorBottom ? y2 - h : y1
      if (x1 < 0) x1 = 0
      if (y1 < 0) y1 = 0
      if (x1 + w > 1) x1 = 1 - w
      if (y1 + h > 1) y1 = 1 - h
      crop.value = { x: x1, y: y1, w, h }
      return
    }
    crop.value = { x: x1, y: y1, w: x2 - x1, h: y2 - y1 }
    return
  }

  if (d.kind === 'corner') {
    const i = d.handle
    corners.value = d.origin.map((c, idx) => (idx === i ? p : c))
  }
}

function onPointerUp() { drag.value = null }

/* ---------------- 操作 ---------------- */

function doRotate(deg) {
  if (!workFull.value) return
  pushUndo()
  workFull.value = rotateCanvas(workFull.value, deg)
  // 旋转后裁剪框/角点归一化坐标已不适用，重置
  resetCrop()
  corners.value = [
    { x: 0.06, y: 0.06 }, { x: 0.94, y: 0.06 },
    { x: 0.94, y: 0.94 }, { x: 0.06, y: 0.94 },
  ]
  render()
}

function applyCrop() {
  if (!workFull.value) return
  const c = crop.value
  if (c.w >= 0.995 && c.h >= 0.995) { mode.value = ''; return }
  pushUndo()
  workFull.value = cropCanvas(workFull.value, c)
  resetCrop()
  mode.value = ''
  render()
}

function autoDetect() {
  if (!workFull.value) return
  busy.value = true
  try {
    corners.value = detectCorners(workFull.value)
    mode.value = 'perspective'
  } catch (e) {
    // tainted canvas（跨域页 URL）等会让 getImageData 直接抛 SecurityError
    window.__kbMsg?.error(e?.message || '自动检测失败，请手动拖动四个角点')
  } finally {
    busy.value = false
  }
}

/** 四点有效四边形粗校：面积太小 / 非有限值的透视变换没有意义 */
function quadAreaOk(pts) {
  if (!Array.isArray(pts) || pts.length !== 4) return false
  if (pts.some((p) => !Number.isFinite(p?.x) || !Number.isFinite(p?.y))) return false
  const area = Math.abs(
    (pts[0].x * pts[1].y - pts[1].x * pts[0].y) +
    (pts[1].x * pts[2].y - pts[2].x * pts[1].y) +
    (pts[2].x * pts[3].y - pts[3].x * pts[2].y) +
    (pts[3].x * pts[0].y - pts[0].x * pts[3].y)
  ) / 2
  return area > 0.02
}

function applyWarp() {
  if (!workFull.value) return
  if (!quadAreaOk(corners.value)) {
    window.__kbMsg?.warning('四个角点无效（重叠或共线），请重新拖动后再试')
    return
  }
  pushUndo()
  workFull.value = perspectiveWarp(workFull.value, corners.value)
  corners.value = [
    { x: 0.06, y: 0.06 }, { x: 0.94, y: 0.06 },
    { x: 0.94, y: 0.94 }, { x: 0.06, y: 0.94 },
  ]
  mode.value = ''
  render()
}

function undo() {
  const prev = undoStack.value.pop()
  if (!prev) return
  workFull.value = prev
  resetCrop()
  corners.value = [
    { x: 0.06, y: 0.06 }, { x: 0.94, y: 0.06 },
    { x: 0.94, y: 0.94 }, { x: 0.06, y: 0.94 },
  ]
  mode.value = ''
  render()
}

function reset() {
  if (!workFull.value) return
  // load() 会清空撤销栈，重置即清空历史——这里再入栈只是白克隆一张全分辨率画布
  load()
}

async function setFilter(k) {
  if (filter.value === k) return
  filter.value = k
  render()
}

async function finish() {
  if (!workFull.value || busy.value) return
  busy.value = true
  try {
    const out = applyFilter(workFull.value, filter.value)
    const page = await toJpegPage(out, 0.85)
    emit('done', page)
  } catch (e) {
    window.__kbMsg?.error(e.message || '保存失败')
  } finally {
    busy.value = false
  }
}

onBeforeUnmount(() => {
  undoStack.value.length = 0
  window.removeEventListener('resize', onResize)
})
</script>

<template>
  <div class="se">
    <!-- 顶栏 -->
    <header class="se-head">
      <button class="se-hbtn" type="button" @click="emit('cancel')">取消</button>
      <div class="se-title">编辑扫描件</div>
      <button class="se-hbtn primary" type="button" :disabled="busy || !ready" @click="finish">完成</button>
    </header>

    <!-- 画布 + 覆盖层 -->
    <div class="se-body">
      <div ref="stageEl" class="se-stage"
           @pointermove="onPointerMove" @pointerup="onPointerUp" @pointercancel="onPointerUp">
        <canvas ref="previewEl" class="se-canvas"></canvas>

        <!-- 裁剪框 -->
        <div v-if="mode === 'crop'" class="se-crop"
             :style="{ left: crop.x * 100 + '%', top: crop.y * 100 + '%', width: crop.w * 100 + '%', height: crop.h * 100 + '%' }"
             @pointerdown="onPointerDown($event, 'crop', 'move')">
          <span v-for="h in ['nw', 'ne', 'sw', 'se']" :key="h" class="se-h" :class="'h-' + h"
                @pointerdown.stop="onPointerDown($event, 'crop', h)"></span>
        </div>

        <!-- 透视四角 -->
        <template v-if="mode === 'perspective'">
          <svg class="se-quad" viewBox="0 0 100 100" preserveAspectRatio="none">
            <polygon :points="corners.map((c) => c.x * 100 + ',' + c.y * 100).join(' ')" />
          </svg>
          <span v-for="(c, i) in corners" :key="i" class="se-corner"
                :style="{ left: c.x * 100 + '%', top: c.y * 100 + '%' }"
                @pointerdown.stop="onPointerDown($event, 'corner', i)"></span>
        </template>

        <div v-if="busy" class="se-mask"><el-icon class="rot" :size="24"><Loading /></el-icon></div>
      </div>
    </div>

    <!-- 工具区 -->
    <div class="se-tools">
      <div class="se-row">
        <button class="se-tool" type="button" :class="{ on: mode === 'crop' }"
                @click="mode = mode === 'crop' ? '' : 'crop'">
          <el-icon :size="19"><Crop /></el-icon><span>裁剪</span>
        </button>
        <button class="se-tool" type="button" :class="{ on: mode === 'perspective' }"
                @click="mode = mode === 'perspective' ? '' : 'perspective'">
          <el-icon :size="19"><Aim /></el-icon><span>矫正</span>
        </button>
        <button class="se-tool" type="button" @click="doRotate(-90)">
          <el-icon :size="19"><RefreshLeft /></el-icon><span>左转</span>
        </button>
        <button class="se-tool" type="button" @click="doRotate(90)">
          <el-icon :size="19"><RefreshRight /></el-icon><span>右转</span>
        </button>
        <button class="se-tool" type="button" @click="autoDetect">
          <el-icon :size="19"><MagicStick /></el-icon><span>自动</span>
        </button>
        <button class="se-tool" type="button" :disabled="!canUndo" @click="undo">
          <el-icon :size="19"><Refresh /></el-icon><span>撤销</span>
        </button>
      </div>

      <div v-if="mode" class="se-apply">
        <span class="se-tip">{{ mode === 'crop' ? '拖动边框裁剪，点「应用」生效' : '拖四个圆点对齐纸张四角，点「应用」拉正' }}</span>
        <button class="m-btn sm" type="button" @click="mode === 'crop' ? applyCrop() : applyWarp()">应用</button>
      </div>

      <div class="se-filters">
        <button v-for="f in FILTERS" :key="f.key" class="se-fbtn" type="button"
                :class="{ on: filter === f.key }" @click="setFilter(f.key)">{{ f.label }}</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.se { display: flex; flex-direction: column; height: 100%; background: var(--m-bg); }

.se-head {
  flex: none; display: flex; align-items: center; gap: 8px;
  padding: calc(env(safe-area-inset-top) + 8px) 12px 8px;
  background: var(--m-card); border-bottom: 1px solid var(--m-border);
}
.se-hbtn {
  flex: none; border: none; background: none; padding: 6px 4px;
  font-size: 15px; color: var(--m-text-sub);
}
.se-hbtn.primary { color: var(--m-primary); font-weight: 600; }
.se-hbtn:disabled { opacity: .4; }
.se-title { flex: 1 1 auto; text-align: center; font-size: 16px; font-weight: 600; color: var(--m-text); }

.se-body {
  flex: 1 1 auto; min-height: 0; display: flex; align-items: center; justify-content: center;
  padding: 12px; overflow: hidden;
}
.se-stage {
  position: relative; display: inline-block; line-height: 0; max-width: 100%; max-height: 100%;
  touch-action: none; user-select: none; -webkit-user-select: none;
  box-shadow: 0 2px 12px rgba(0, 0, 0, .14); background: #fff;
}
.se-canvas { display: block; max-width: 100%; max-height: 100%; object-fit: contain; }

/* 裁剪框 */
.se-crop {
  position: absolute; box-sizing: border-box;
  border: 2px solid var(--m-primary);
  box-shadow: 0 0 0 9999px rgba(0, 0, 0, .34);
  cursor: move;
}
.se-h {
  position: absolute; width: 30px; height: 30px; margin: -15px;
  border-radius: 50%; background: rgba(22, 119, 255, .18);
}
.se-h::after {
  content: ''; position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  width: 13px; height: 13px; border-radius: 50%; background: var(--m-primary);
  border: 2px solid #fff;
}
.h-nw { left: 0; top: 0; cursor: nwse-resize; }
.h-ne { right: 0; top: 0; margin-left: 0; transform: translateX(50%); cursor: nesw-resize; }
.h-sw { left: 0; bottom: 0; margin-top: 0; transform: translateY(50%); cursor: nesw-resize; }
.h-se { right: 0; bottom: 0; margin: 0; transform: translate(50%, 50%); cursor: nwse-resize; }

/* 透视四角 */
.se-quad { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; }
.se-quad polygon { fill: rgba(22, 119, 255, .14); stroke: var(--m-primary); stroke-width: .6; vector-effect: non-scaling-stroke; }
.se-corner {
  position: absolute; width: 32px; height: 32px; margin: -16px;
  border-radius: 50%; background: rgba(22, 119, 255, .2);
  border: 2px solid var(--m-primary); box-shadow: 0 0 0 2px rgba(255, 255, 255, .9);
  cursor: grab;
}
.se-corner::after {
  content: ''; position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  width: 8px; height: 8px; border-radius: 50%; background: var(--m-primary);
}

.se-mask {
  position: absolute; inset: 0; display: flex; align-items: center; justify-content: center;
  background: rgba(255, 255, 255, .6);
}
.rot { animation: se-spin 1s linear infinite; }
@keyframes se-spin { to { transform: rotate(360deg); } }

/* 工具区 */
.se-tools { flex: none; background: var(--m-card); border-top: 1px solid var(--m-border); padding: 10px 12px calc(env(safe-area-inset-bottom) + 10px); }
.se-row { display: flex; align-items: center; justify-content: space-between; gap: 4px; }
.se-tool {
  flex: 1 1 0; display: flex; flex-direction: column; align-items: center; gap: 3px;
  border: none; background: none; padding: 6px 2px; border-radius: 10px;
  font-size: 11.5px; color: var(--m-text-sub); -webkit-tap-highlight-color: transparent;
}
.se-tool.on { color: var(--m-primary); background: var(--m-primary-soft); }
.se-tool:disabled { opacity: .35; }

.se-apply { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.se-tip { flex: 1 1 auto; font-size: 12px; color: var(--m-text-dim); line-height: 1.4; }

.se-filters { display: flex; gap: 8px; overflow-x: auto; margin-top: 10px; padding-bottom: 2px; }
.se-filters::-webkit-scrollbar { height: 0; }
.se-fbtn {
  flex: none; height: 34px; padding: 0 14px; border-radius: 999px;
  border: 1px solid var(--m-border); background: var(--m-card-2); color: var(--m-text-sub);
  font-size: 13px; -webkit-tap-highlight-color: transparent;
}
.se-fbtn.on { border-color: var(--m-primary); background: var(--m-primary-soft); color: var(--m-primary); font-weight: 600; }
</style>
