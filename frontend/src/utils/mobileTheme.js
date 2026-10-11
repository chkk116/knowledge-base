/**
 * 移动端个性化外观（主题色 / 正文字号）
 *
 * 只作用于**移动端**（main-mobile.js 调用 applyMobileTheme），桌面端不受影响。
 * 实现：在 <html> 上写内联 CSS 变量，覆盖 mobile.css 里 :root / .dark 的默认值。
 *   --m-primary / --m-primary-soft / --m-primary-deep  → 品牌色
 *   --m-prose-fs                                       → 正文阅读字号
 * 注意：内联变量优先级高于 .dark 规则，所以深色模式要另给一套 soft/deep
 * （否则浅色 tint 铺在深色底上会刺眼）。
 *
 * ⚠️ 与 utils/appearance.js（站点级 Logo/背景配置）不是一回事，勿混。
 */
const KEY = 'kb_mobile_theme_v1'

export const THEMES = [
  { key: 'blue',   name: '默认蓝', primary: '#1677ff', soft: '#e8f3ff', deep: '#0958d9', softDark: '#16233a', deepDark: '#2b5aa8' },
  { key: 'green',  name: '青竹',   primary: '#00b96b', soft: '#e6fff4', deep: '#00875a', softDark: '#12291f', deepDark: '#1f7a55' },
  { key: 'purple', name: '紫罗兰', primary: '#7c5cff', soft: '#f0edff', deep: '#5b3fd6', softDark: '#1e1a35', deepDark: '#5b3fd6' },
  { key: 'orange', name: '暖橙',   primary: '#ff7d00', soft: '#fff4e6', deep: '#d96400', softDark: '#33210f', deepDark: '#b85700' },
  { key: 'rose',   name: '玫红',   primary: '#f5317f', soft: '#ffedf4', deep: '#c81f63', softDark: '#33141f', deepDark: '#c81f63' },
]

export const FONTS = [
  { key: 's',  name: '小',   px: 14.5 },
  { key: 'm',  name: '标准', px: 15.5 },
  { key: 'l',  name: '大',   px: 17.5 },
  { key: 'xl', name: '特大', px: 19.5 },
]

const DEFAULT = { theme: 'blue', font: 'm' }

export function getMobileTheme() {
  try {
    const o = JSON.parse(localStorage.getItem(KEY) || 'null')
    if (o && typeof o === 'object') return { ...DEFAULT, ...o }
  } catch { /* ignore */ }
  return { ...DEFAULT }
}

export function setMobileTheme(patch) {
  const next = { ...getMobileTheme(), ...patch }
  try { localStorage.setItem(KEY, JSON.stringify(next)) } catch { /* ignore */ }
  applyMobileTheme()
  return next
}

export function resetMobileTheme() {
  try { localStorage.removeItem(KEY) } catch { /* ignore */ }
  applyMobileTheme()
}

let observerReady = false

/** 把设置写进 <html> 内联变量；深色切换时自动重算 */
export function applyMobileTheme() {
  const a = getMobileTheme()
  const t = THEMES.find((x) => x.key === a.theme) || THEMES[0]
  const f = FONTS.find((x) => x.key === a.font) || FONTS[1]
  const root = document.documentElement
  const dark = root.classList.contains('dark')

  root.style.setProperty('--m-primary', t.primary)
  root.style.setProperty('--m-primary-soft', dark ? t.softDark : t.soft)
  root.style.setProperty('--m-primary-deep', dark ? t.deepDark : t.deep)
  root.style.setProperty('--m-prose-fs', f.px + 'px')

  if (!observerReady) {
    observerReady = true
    try {
      new MutationObserver(() => applyMobileTheme())
        .observe(root, { attributes: true, attributeFilter: ['class'] })
    } catch { /* 老 WebView 无 MutationObserver 时忽略 */ }
  }
}
