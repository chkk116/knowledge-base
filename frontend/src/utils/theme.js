// 主题切换（日间 / 夜间）
//
// 两条路径，目的都是"整屏一起柔和变暗"，而不是各组件各变各的：
//  1) 支持 View Transitions（Chromium 111+）：整页快照淡入覆盖。
//     能兜住纯 CSS 过渡管不到的部分 —— body 背景向画布传播、linear-gradient
//     背景、背景图、滚动条区域。此时必须关掉逐元素 CSS 过渡，否则快照捕获到
//     的是"过渡刚起步"的颜色，淡入完还会再跳一下。
//  2) 不支持时：挂 html.theme-anim，靠全局颜色过渡（theme.css），结束后移除。
//
// 尊重系统「减少动效」偏好：直接瞬切。

export const THEME_SWITCH_MS = 420

const THEME_KEY = 'kb_theme'

export function isDarkNow() {
  return document.documentElement.classList.contains('dark')
}

function prefersReducedMotion() {
  return !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)
}

let cleanupTimer = 0

export function applyTheme(next) {
  const html = document.documentElement
  const commit = () => {
    html.classList.toggle('dark', next)
    try {
      localStorage.setItem(THEME_KEY, next ? 'dark' : 'light')
    } catch (e) {
      /* 隐私模式下 localStorage 可能不可写，忽略 */
    }
    // 广播主题变化：偏好页/系统跟随/顶栏按钮多个入口都要同步图标与水印配色
    try {
      window.dispatchEvent(new CustomEvent('kb:theme', { detail: { dark: next } }))
    } catch (e) { /* ignore */ }
  }

  if (prefersReducedMotion()) {
    commit()
    return
  }

  if (typeof document.startViewTransition === 'function') {
    html.classList.add('theme-vt')
    let transition
    try {
      transition = document.startViewTransition(() => commit())
    } catch (e) {
      // 极少数情况（如已有过渡在跑）会抛错，回退到 CSS 过渡
      html.classList.remove('theme-vt')
      fallback(commit)
      return
    }
    const cleanup = () => html.classList.remove('theme-vt')
    // 连续切换主题时浏览器会中止上一个过渡，ready/finished 都会 reject —— 静默处理，
    // 否则控制台出现「Transition was skipped. New ViewTransition started」的 AbortError
    if (transition.ready && typeof transition.ready.catch === 'function') transition.ready.catch(() => {})
    transition.finished.then(cleanup, cleanup)
    return
  }

  fallback(commit)
}

function fallback(commit) {
  const html = document.documentElement
  html.classList.add('theme-anim')
  commit()
  window.clearTimeout(cleanupTimer)
  cleanupTimer = window.setTimeout(() => {
    html.classList.remove('theme-anim')
  }, THEME_SWITCH_MS + 60)
}
