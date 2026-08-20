<template>
  <div class="slide-captcha">
    <div class="sc-header">
      <div class="sc-title">安全验证</div>
      <div class="sc-subtitle">拖动下方滑块完成拼图</div>
    </div>
    <div class="sc-stage" :class="{ shake: shaking }">
      <canvas ref="bg" class="sc-bg" :width="W" :height="H"></canvas>
      <canvas ref="piece" class="sc-piece" :width="W" :height="H"></canvas>
    </div>
    <div class="sc-bar" ref="bar">
      <div class="sc-track"></div>
      <div
        class="sc-handle"
        :class="{ done: verified }"
        :style="{ left: handleLeft + 'px' }"
        @pointerdown="onDown"
      >
        <span class="grip">|||</span>
        <span class="ok">✓</span>
      </div>
    </div>
    <div class="sc-footer">
      <span class="sc-hint" :class="{ ok: verified }">
        {{ verified ? `验证通过 · 用时 ${elapsed.toFixed(1)}s` : '拖动滑块完成拼图' }}
      </span>
      <div class="sc-actions">
        <button type="button" class="sc-btn" title="刷新" @click="reset">↻</button>
        <button type="button" class="sc-btn" title="关闭" @click="emit('close')">✕</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import scenic1 from '../assets/captcha/scenic1.jpg'
import scenic2 from '../assets/captcha/scenic2.jpg'
import scenic3 from '../assets/captcha/scenic3.jpg'
import scenic4 from '../assets/captcha/scenic4.jpg'
import scenic5 from '../assets/captcha/scenic5.jpg'
import scenic6 from '../assets/captcha/scenic6.jpg'
import scenic7 from '../assets/captcha/scenic7.jpg'
import scenic8 from '../assets/captcha/scenic8.jpg'
import scenic9 from '../assets/captcha/scenic9.jpg'
import scenic10 from '../assets/captcha/scenic10.jpg'
import scenic11 from '../assets/captcha/scenic11.jpg'
import scenic12 from '../assets/captcha/scenic12.jpg'
import scenic13 from '../assets/captcha/scenic13.jpg'
import scenic14 from '../assets/captcha/scenic14.jpg'
import scenic15 from '../assets/captcha/scenic15.jpg'
import scenic16 from '../assets/captcha/scenic16.jpg'

const emit = defineEmits(['success', 'refresh', 'close'])
const props = defineProps({ imgUrl: { type: String, default: '' } })

const IMAGES = [scenic1, scenic2, scenic3, scenic4, scenic5, scenic6, scenic7, scenic8,
  scenic9, scenic10, scenic11, scenic12, scenic13, scenic14, scenic15, scenic16]
const W = 320
const H = 160
const PIECE_W = 45
const PIECE_H = 45
const HANDLE_W = 44
const TRACK_W = 320

// 每次刷新的拼图形状（圆角半径 + 随机 1~3 条边带凸起），保证形状每次都不同
const shape = ref(makeShape())

const bg = ref(null)
const piece = ref(null)
const bar = ref(null)

const targetX = ref(220)
const targetY = ref(80)
const handleLeft = ref(0)
const verified = ref(false)
const shaking = ref(false)
const elapsed = ref(0)
let startTime = 0
let currentImg = null
let lastUrl = ''

// 拼块当前 X = 手柄位置映射到 [PIECE_W/2, W - PIECE_W/2]
const sliderCenter = computed(() => {
  const maxOffset = TRACK_W - HANDLE_W
  const ratio = maxOffset > 0 ? handleLeft.value / maxOffset : 0
  return Math.round(PIECE_W / 2 + ratio * (W - PIECE_W))
})

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

// 随机生成拼图形状：圆角半径、凸起半径随机，四条边中随机 1~3 条带朝外凸起
function makeShape() {
  const sidesPool = ['right', 'bottom', 'left', 'top']
  const cnt = 1 + Math.floor(Math.random() * 3) // 1~3 条边
  const pool = sidesPool.slice()
  for (let i = pool.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    const t = pool[i]; pool[i] = pool[j]; pool[j] = t
  }
  return {
    r: 3 + Math.floor(Math.random() * 6),       // 圆角 3~8
    knobR: 6 + Math.floor(Math.random() * 6),    // 凸起半径 6~11
    sides: pool.slice(0, cnt)
  }
}

// 拼图块路径：圆角方块 + 随机边上朝外凸起（缺口与拼块共用同一 shape，保证裁切吻合）
function jigsawPath(ctx, cx, cy, w, h, shape) {
  const x = cx - w / 2
  const y = cy - h / 2
  roundRect(ctx, x, y, w, h, shape.r)
  const k = shape.knobR
  shape.sides.forEach((side) => {
    if (side === 'right') {
      ctx.moveTo(x + w, cy - k)
      ctx.arc(x + w + k * 0.4, cy, k, -Math.PI / 2, Math.PI / 2, false)
      ctx.closePath()
    } else if (side === 'left') {
      ctx.moveTo(x, cy - k)
      ctx.arc(x - k * 0.4, cy, k, Math.PI / 2, -Math.PI / 2, true)
      ctx.closePath()
    } else if (side === 'bottom') {
      ctx.moveTo(cx - k, y + h)
      ctx.arc(cx, y + h + k * 0.4, k, 0, Math.PI, false)
      ctx.closePath()
    } else if (side === 'top') {
      ctx.moveTo(cx - k, y)
      ctx.arc(cx, y - k * 0.4, k, Math.PI, Math.PI * 2, false)
      ctx.closePath()
    }
  })
}

function drawImg(ctx, img) {
  try {
    ctx.drawImage(img, 0, 0, W, H)
  } catch (e) {
    const g = ctx.createLinearGradient(0, 0, W, H)
    g.addColorStop(0, '#60a5fa'); g.addColorStop(1, '#34d399')
    ctx.fillStyle = g; ctx.fillRect(0, 0, W, H)
  }
}

function render() {
  if (!bg.value || !piece.value) return
  const bctx = bg.value.getContext('2d')
  const pctx = piece.value.getContext('2d')
  bctx.clearRect(0, 0, W, H)
  pctx.clearRect(0, 0, W, H)
  if (!currentImg) return
  drawImg(bctx, currentImg)
  if (verified.value) return
  const cx = targetX.value
  const cy = targetY.value
  // 缺口：暗色半透明（截图中的暗色缺口）
  bctx.save()
  jigsawPath(bctx, cx, cy, PIECE_W, PIECE_H, shape.value)
  bctx.fillStyle = 'rgba(0,0,0,0.45)'
  bctx.fill()
  bctx.lineWidth = 1
  bctx.strokeStyle = 'rgba(255,255,255,0.35)'
  bctx.stroke()
  bctx.restore()
  // 拼块：从真实图裁切，跟随滑块
  const dx = sliderCenter.value - cx
  pctx.save()
  pctx.translate(dx, 0)
  jigsawPath(pctx, cx, cy, PIECE_W, PIECE_H, shape.value)
  pctx.clip()
  drawImg(pctx, currentImg)
  pctx.restore()
  // 拼块白边
  pctx.save()
  pctx.translate(dx, 0)
  jigsawPath(pctx, cx, cy, PIECE_W, PIECE_H, shape.value)
  pctx.lineWidth = 2
  pctx.strokeStyle = 'rgba(255,255,255,0.95)'
  pctx.stroke()
  pctx.restore()
}

function loadImage(url) {
  if (!url || url === lastUrl) {
    // 刷新时强制换一张
    let next = IMAGES[Math.floor(Math.random() * IMAGES.length)]
    while (next === lastUrl && IMAGES.length > 1) {
      next = IMAGES[Math.floor(Math.random() * IMAGES.length)]
    }
    url = next
  }
  lastUrl = url
  const img = new Image()
  img.crossOrigin = 'anonymous'
  img.onload = () => { currentImg = img; render() }
  img.onerror = () => {
    // 兜底：本地图加载失败时用渐变
    currentImg = null
    render()
  }
  img.src = url
}

function reset() {
  verified.value = false
  handleLeft.value = 0
  elapsed.value = 0
  startTime = performance.now()
  const minX = PIECE_W + 20
  const maxX = W - PIECE_W - 20
  targetX.value = Math.floor(minX + Math.random() * (maxX - minX))
  targetY.value = 45 + Math.floor(Math.random() * 70)
  shape.value = makeShape() // 每次刷新换一个拼图形状
  let url = props.imgUrl
  if (!url) {
    let next = IMAGES[Math.floor(Math.random() * IMAGES.length)]
    while (next === lastUrl && IMAGES.length > 1) {
      next = IMAGES[Math.floor(Math.random() * IMAGES.length)]
    }
    url = next
  }
  loadImage(url)
  emit('refresh')
}

/* ---------- 拖拽 ---------- */
let dragging = false
let startX = 0
let startLeft = 0

function clampHandle(v) {
  return Math.max(0, Math.min(TRACK_W - HANDLE_W, v))
}

function onDown(e) {
  if (verified.value) return
  dragging = true
  startX = e.clientX
  startLeft = handleLeft.value
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

function onMove(e) {
  if (!dragging) return
  const delta = e.clientX - startX
  handleLeft.value = clampHandle(startLeft + delta)
  render()
}

function onUp() {
  if (!dragging) return
  dragging = false
  window.removeEventListener('pointermove', onMove)
  window.removeEventListener('pointerup', onUp)
  if (Math.abs(sliderCenter.value - targetX.value) <= 8) {
    verified.value = true
    handleLeft.value = (targetX.value - PIECE_W / 2) / (W - PIECE_W) * (TRACK_W - HANDLE_W)
    elapsed.value = (performance.now() - startTime) / 1000
    render()
    emit('success')
  } else {
    shaking.value = true
    setTimeout(() => {
      shaking.value = false
      reset() // 一次没对齐：自动刷新拼图（新图 + 新缺口位置）
    }, 300)
  }
}

onMounted(reset)
watch(() => props.imgUrl, () => { if (props.imgUrl) reset() })
</script>

<style scoped>
.slide-captcha {
  width: 320px;
  background: #fff;
  border-radius: 10px;
  box-shadow: 0 8px 30px rgba(0,0,0,0.15);
  padding: 14px 14px 12px;
  user-select: none;
}
.sc-header { margin-bottom: 10px; }
.sc-title { font-size: 12px; color: #94a3b8; margin-bottom: 2px; }
.sc-subtitle { font-size: 16px; color: #1e293b; font-weight: 500; }
.sc-stage {
  position: relative;
  width: 320px; height: 160px;
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
.sc-bg, .sc-piece { position: absolute; inset: 0; display: block; }
.sc-piece { pointer-events: none; }
.sc-bar {
  position: relative;
  width: 320px; height: 40px;
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
  width: 44px; height: 40px;
  border-radius: 20px;
  background: #3b82f6;
  color: #fff;
  display: flex; align-items: center; justify-content: center;
  cursor: grab;
  box-shadow: 0 2px 8px rgba(59,130,246,0.35);
  transition: background .2s, left 0ms;
}
.sc-handle:active { cursor: grabbing; }
.sc-handle .grip { font-size: 12px; letter-spacing: 2px; }
.sc-handle .ok { display: none; }
.sc-handle.done { background: #22c55e; box-shadow: 0 2px 8px rgba(34,197,94,0.35); }
.sc-handle.done .grip { display: none; }
.sc-handle.done .ok { display: inline; font-size: 18px; }
.sc-footer {
  display: flex; align-items: center; justify-content: space-between;
  margin-top: 10px;
}
.sc-hint { font-size: 12px; color: #94a3b8; }
.sc-hint.ok { color: #22c55e; font-weight: 600; }
.sc-actions { display: flex; gap: 8px; }
.sc-btn {
  width: 28px; height: 28px; border-radius: 50%; border: none;
  background: #f1f5f9; color: #64748b;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; font-size: 13px;
}
.sc-btn:hover { background: #e2e8f0; color: #334155; }
</style>