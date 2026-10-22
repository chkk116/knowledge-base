/**
 * 桌面端偏好设置（2026-10-22）
 *
 * 存储：localStorage `kb_prefs_v1`（单机偏好，不跨设备同步）。
 * 生效：applyPrefs() 把结果挂到 <html> 的 class / CSS 变量上，App.vue 负责布局层（导航位置等）。
 *
 * 只影响桌面端骨架（App.vue）；移动端是独立入口（mobile.html），不受这里影响。
 */
import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'
import { applyTheme } from '../utils/theme'

const KEY = 'kb_prefs_v1'

/** 强调色预设：只改 Element Plus 主色与其派生色，不动各页面自己的局部配色 */
export const ACCENTS = [
  { key: 'indigo', name: '靛蓝', color: '#4f46e5' },
  { key: 'blue', name: '天青', color: '#1677ff' },
  { key: 'teal', name: '松石', color: '#0d9488' },
  { key: 'green', name: '翠绿', color: '#16a34a' },
  { key: 'orange', name: '暖橙', color: '#ea580c' },
  { key: 'rose', name: '品红', color: '#e11d48' },
]

export const DEFAULTS = {
  navMode: 'side',        // side=侧边导航 / dual=双列菜单 / top=顶部导航
  sidebarVisible: true,   // 侧边栏是否显示（仅 side 模式）
  sidebarDark: false,     // 深色菜单
  hoverExpand: true,      // 折叠后鼠标悬停临时展开
  contentWidth: 'fluid',  // fluid=流式 / fixed=定宽
  density: 'normal',      // normal=标准 / compact=紧凑
  accent: 'indigo',       // 强调色（见 ACCENTS）
  theme: 'auto',          // auto=跟随系统 / light / dark
  startPage: 'last',      // last=上次访问 / home=首页（登录后落地）
  menuOrder: [],          // 侧边/双列一级菜单的自定义顺序（存 menu.index 数组）
  subMenuOrder: {},       // 分组内子菜单顺序：{ 'g-content': ['/admin/users', ...] }
}

function hexToRgb(hex) {
  let h = String(hex || '').replace('#', '').trim()
  // 非法色值会让 mix() 算出 NaN，写出 #NaNNaNNaN 到 CSS 变量（审查 r12 Low）
  if (!/^([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(h)) h = '4f46e5'
  const v = h.length === 3 ? h.split('').map((c) => c + c).join('') : h
  return [parseInt(v.slice(0, 2), 16), parseInt(v.slice(2, 4), 16), parseInt(v.slice(4, 6), 16)]
}

/** 把主色与白/黑按比例混合，生成 Element Plus 需要的 light-3/5/7/8/9 与 dark-2 */
function mix(hex, target, ratio) {
  const a = hexToRgb(hex)
  const b = hexToRgb(target)
  const out = a.map((c, i) => Math.round(c + (b[i] - c) * ratio))
  return '#' + out.map((c) => c.toString(16).padStart(2, '0')).join('')
}

function applyAccent(key) {
  const found = ACCENTS.find((a) => a.key === key) || ACCENTS[0]
  const root = document.documentElement
  root.style.setProperty('--el-color-primary', found.color)
  ;[[3, 0.3], [5, 0.5], [7, 0.7], [8, 0.8], [9, 0.9]].forEach(([lv, r]) => {
    root.style.setProperty(`--el-color-primary-light-${lv}`, mix(found.color, '#ffffff', r))
  })
  root.style.setProperty('--el-color-primary-dark-2', mix(found.color, '#000000', 0.2))
  root.style.setProperty('--kb-accent', found.color)
}

let mqTheme = null
let mqHandler = null
/** 上一次真正应用的主题（'dark' / 'light'）。切密度、换强调色不该顺带跑一次主题过渡动画 */
let lastTheme = null

function applyThemeOnce(dark) {
  if (lastTheme === dark) return
  lastTheme = dark
  applyTheme(dark)
}

/** 把偏好落到 DOM：class（布局/密度）+ CSS 变量（强调色）+ 主题 */
export function applyPrefs(p) {
  const root = document.documentElement
  root.classList.toggle('pref-compact', p.density === 'compact')
  root.classList.toggle('pref-fixed', p.contentWidth === 'fixed')
  applyAccent(p.accent)

  // 主题：auto 时跟随系统，并监听系统变化
  if (mqTheme && mqHandler) { mqTheme.removeEventListener('change', mqHandler); mqTheme = null; mqHandler = null }
  if (p.theme === 'auto') {
    if (window.matchMedia) {
      mqTheme = window.matchMedia('(prefers-color-scheme: dark)')
      mqHandler = (e) => applyThemeOnce(e.matches)
      mqTheme.addEventListener('change', mqHandler)
      applyThemeOnce(mqTheme.matches)
    }
  } else {
    applyThemeOnce(p.theme === 'dark')
  }
}

/** 枚举型偏好的合法取值：localStorage 被改坏时回落到默认值 */
const ENUMS = {
  navMode: ['side', 'dual', 'top'],
  contentWidth: ['fluid', 'fixed'],
  density: ['normal', 'compact'],
  theme: ['auto', 'light', 'dark'],
  startPage: ['last', 'home'],
}

/** 收敛外部数据（localStorage）形状：类型/枚举/数组元素都要校验，非法值丢弃而非整份信任 */
function sanitizePrefs(raw) {
  const out = { ...DEFAULTS, menuOrder: [], subMenuOrder: {} }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return out
  for (const k of ['sidebarVisible', 'sidebarDark', 'hoverExpand']) {
    if (typeof raw[k] === 'boolean') out[k] = raw[k]
  }
  for (const [k, allowed] of Object.entries(ENUMS)) {
    if (allowed.includes(raw[k])) out[k] = raw[k]
  }
  if (ACCENTS.some((a) => a.key === raw.accent)) out.accent = raw.accent
  if (Array.isArray(raw.menuOrder)) out.menuOrder = raw.menuOrder.filter((x) => typeof x === 'string')
  if (raw.subMenuOrder && typeof raw.subMenuOrder === 'object' && !Array.isArray(raw.subMenuOrder)) {
    for (const [k, v] of Object.entries(raw.subMenuOrder)) {
      if (Array.isArray(v)) out.subMenuOrder[k] = v.filter((x) => typeof x === 'string')
    }
  }
  return out
}

export const usePrefsStore = defineStore('prefs', () => {
  const state = ref({ ...DEFAULTS })

  function load() {
    try {
      const raw = JSON.parse(localStorage.getItem(KEY) || '{}')
      state.value = sanitizePrefs(raw)
    } catch {
      state.value = sanitizePrefs(null)
    }
    return state.value
  }

  function save() {
    try { localStorage.setItem(KEY, JSON.stringify(state.value)) } catch { /* 隐私模式忽略 */ }
  }

  function set(patch) {
    state.value = { ...state.value, ...patch }
  }

  function reset() {
    // 子菜单顺序也要清（审查 r12）：「恢复默认顺序」原来只清了一级菜单
    state.value = { ...DEFAULTS, menuOrder: [], subMenuOrder: {} }
  }

  watch(state, () => { save(); applyPrefs(state.value) }, { deep: true })

  /** 便捷 getter：侧栏是否真的要收成 64px */
  const effectiveNav = computed(() => (state.value.navMode === 'top' ? 'top' : 'side'))

  return { state, load, save, set, reset, effectiveNav }
})
