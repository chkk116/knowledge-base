import { reactive } from 'vue'

// 文章「小窗阅读」全局浮窗
// 之前该浮窗写在 PostDetail.vue 内，组件随路由卸载即消失（切页就没了）。
// 现提升为全局单例：在 App.vue 渲染（位于 router-view 之外），切换路由/页面后依然存在；
// 打开时快照当前文章正文，关闭前内容保留，可作为独立阅读窗边浏览别处。
export const reader = reactive({
  open: false,
  title: '',
  content: '',
  x: 0,
  y: 0,
  w: 380,
  h: 520,
})

export function openReader(title, content) {
  reader.title = title || ''
  reader.content = content || ''
  // 已打开时只刷新内容/标题，保持原位置不跳动；首次打开才定位到右下方
  if (!reader.open) {
    const w = Math.min(380, window.innerWidth - 32)
    const h = Math.min(520, window.innerHeight - 48)
    reader.w = w
    reader.h = h
    reader.x = window.innerWidth - w - 16
    reader.y = window.innerHeight - h - 16
  }
  reader.open = true
}

export function closeReader() {
  reader.open = false
}

// 浏览器窗口缩放 / 全屏切换时，把浮窗夹回可视区内，
// 否则窗口缩小后浮窗坐标仍指向旧位置，会被挤出屏幕外「看不见」
export function clampToViewport() {
  if (!reader.open) return
  // 尺寸不得超过可视区
  reader.w = Math.min(reader.w, window.innerWidth - 8)
  reader.h = Math.min(reader.h, window.innerHeight - 8)
  // 位置夹在 [0, 可视区-自身尺寸] 之间，保证完整可见
  reader.x = Math.max(0, Math.min(reader.x, window.innerWidth - reader.w))
  reader.y = Math.max(0, Math.min(reader.y, window.innerHeight - reader.h))
}

export function startDrag(e) {
  if (e.button !== 0) return          // 仅左键拖动
  e.preventDefault()                   // 防止拖动时误选浮窗内正文
  const sx = e.clientX, sy = e.clientY, ox = reader.x, oy = reader.y
  const move = (ev) => {
    reader.x = Math.max(0, Math.min(ox + ev.clientX - sx, window.innerWidth - reader.w))
    reader.y = Math.max(0, Math.min(oy + ev.clientY - sy, window.innerHeight - reader.h))
  }
  const up = () => { window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up) }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
}

export function startResize(e) {
  if (e.button !== 0) return
  e.preventDefault()
  e.stopPropagation()                  // 缩放手柄的 mousedown 绝不冒泡到标题栏，避免「缩放时整窗被拖动」
  const sx = e.clientX, sy = e.clientY, ow = reader.w, oh = reader.h
  const move = (ev) => {
    // 仅改变宽高，绝不碰 x/y，标题栏锚点保持不动
    reader.w = Math.max(320, Math.min(ow + ev.clientX - sx, window.innerWidth * 0.8))
    reader.h = Math.max(240, Math.min(oh + ev.clientY - sy, window.innerHeight * 0.8))
  }
  const up = () => { window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up) }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
}
