/**
 * v-stack-table — 把 el-table 在 ≤640px 切到「卡片堆叠列表」
 * ------------------------------------------------------------
 * 关键修正（v3）：用 column.id 把表头 th 和数据 td 关联，不再依赖
 * 哪个时机的 thead 位置。
 *
 * 流程：
 *  1) 收集所有 th：按 column.id(className 之一) 索引其 innerText 作为 label
 *  2) 给每个 td：找到 column.id，注入 data-label
 *  3) 用 rAF + MutationObserver 重试，直到 td 行数稳定
 *  4) 列若带 class-name="stack-hide"，data-stack-hide="1" 用于 CSS 隐藏
 *
 * 用法：<el-table v-stack-table :data="rows">...</el-table>
 */

const MQ = '(max-width: 640px)' // 与全项目断点规范一致（≤1024 / ≤640）
const STACKED = 'is-stacked'

function findColId(td) {
  for (const c of td.classList) {
    if (c.startsWith('el-table_') || !c) continue
    // 排除 Element Plus 内部通用类
    if (
      c === 'el-table__cell' ||
      c.endsWith('--striped') ||
      c === 'current-row' ||
      c === 'is-leaf' ||
      c === 'is-hidden' ||
      c === 'stack-hide'
    ) continue
    // column.id 通常是用户写的 prop / id 字段（如 "username"、"el-table_2_column_5" 等）
    return c
  }
  return ''
}

function collectHeaderMap(el) {
  const map = new Map()
  el.querySelectorAll('thead th').forEach((th) => {
    const cell = th.querySelector('.cell')
    const raw = ((cell?.innerText) || th.innerText || th.textContent || '').replace(/\s+/g, ' ').trim()
    const text = raw || '字段' // 空 label 用「字段」占位，避免 td 完全没有标签
    th.classList.forEach((c) => {
      if (c === 'el-table__cell' || c === 'is-leaf' || c === 'is-hidden') return
      if (map.has(c)) return
      map.set(c, text)
    })
  })
  return map
}

function labelize(el) {
  if (!el || !el.isConnected) return false
  const mq = window.matchMedia(MQ).matches
  if (!mq) {
    el.classList.remove(STACKED)
    el.querySelectorAll('[data-label]').forEach((td) => td.removeAttribute('data-label'))
    el.querySelectorAll('[data-stack-hide]').forEach((td) => td.removeAttribute('data-stack-hide'))
    el.querySelectorAll('[data-stack-role]').forEach((td) => td.removeAttribute('data-stack-role'))
    return true
  }

  el.classList.add(STACKED)

  const headerMap = collectHeaderMap(el)
  if (headerMap.size === 0) return false // 表格尚未渲染完，等下一轮

  const rows = el.querySelectorAll('tbody tr')
  rows.forEach((tr) => {
    if (tr.children.length === 1 && tr.children[0].hasAttribute('colspan')) return // 展开行
    let firstSeen = false
    Array.from(tr.children).forEach((td) => {
      if (td.hasAttribute('colspan')) return
      const colId = findColId(td)
      const label = (colId && headerMap.get(colId)) || '字段'
      if (td.getAttribute('data-label') !== label) {
        td.setAttribute('data-label', label)
      }
      const hidden = td.classList.contains('stack-hide')
      if (hidden) {
        td.setAttribute('data-stack-hide', '1')
        td.removeAttribute('data-stack-role')
      } else {
        td.removeAttribute('data-stack-hide')
        // 第一格可见 => 卡片头部（主键），渲染成大字号 chip
        if (!firstSeen) {
          td.setAttribute('data-stack-role', 'header')
          firstSeen = true
        } else {
          td.setAttribute('data-stack-role', 'field')
        }
      }
    })
  })
  return true
}

function schedule(el) {
  if (labelize(el)) return // 成功：thead 已渲染
  // 表格尚未完全渲染（Vue/Element Plus 子组件 mount 较晚），持续重试直到 1.5s
  const t = (el.__stackRetries = (el.__stackRetries || 0) + 1)
  if (t > 30) return
  setTimeout(() => schedule(el), 50)
}

function clearRetries(el) {
  el.__stackRetries = 0
}

export const stackTable = {
  mounted(el) {
    el.__stackResize = () => {
      // 拿掉残留属性，重算
      el.querySelectorAll('[data-label]').forEach((td) => td.removeAttribute('data-label'))
      el.querySelectorAll('[data-stack-hide]').forEach((td) => td.removeAttribute('data-stack-hide'))
      schedule(el)
    }
    window.addEventListener('resize', el.__stackResize)
    el.__stackObs = new MutationObserver(() => {
      // DOM 翻页 / 行重建时重新 labelize（180ms 防抖）
      clearTimeout(el.__stackT)
      el.__stackT = setTimeout(() => {
        el.querySelectorAll('[data-label]').forEach((td) => td.removeAttribute('data-label'))
        el.querySelectorAll('[data-stack-hide]').forEach((td) => td.removeAttribute('data-stack-hide'))
        schedule(el)
      }, 180)
    })
    el.__stackObs.observe(el, { childList: true, subtree: true })
    // Vue mounted 后下一帧开始拿
    if (window.requestAnimationFrame) {
      window.requestAnimationFrame(() => schedule(el))
    } else {
      schedule(el)
    }
  },
  updated(el) {
    schedule(el)
  },
  unmounted(el) {
    if (el.__stackResize) window.removeEventListener('resize', el.__stackResize)
    if (el.__stackObs) el.__stackObs.disconnect()
    clearTimeout(el.__stackT)
  },
}

export default stackTable
