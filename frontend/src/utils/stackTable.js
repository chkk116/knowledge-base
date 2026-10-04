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

/** 单元格文本是否为空占位（空串 / — / - / N/A / null / undefined）。
 *  手机卡片模式下这类字段会渲染成一个孤立的占位行，纯属噪音，标记后由 CSS 隐藏。 */
function isBlankCell(td) {
  const cell = td.querySelector('.cell') || td
  const t = (cell.innerText || cell.textContent || '').replace(/\s+/g, ' ').trim()
  if (!t) return true
  return /^(—|–|-|--|N\/A|n\/a|null|undefined|无|暂无)$/.test(t)
}

function findColId(td) {
  // 首选 Element Plus 自动生成的 el-table_{tableId}_column_{colIndex}：
  // 表头 th 与数据 td 共用同一个类名，是关联「列定义 ↔ 单元格」最稳的键。
  // （旧实现把 el-table_ 前缀一律跳过，导致 td 永远取不到 colId、表头映射失效。）
  for (const c of td.classList) {
    if (/^el-table_\d+_column_\d+$/.test(c)) return c
  }
  // 回退：用户自写的 class-name（非 Element 内部类）
  for (const c of td.classList) {
    if (!c || c.startsWith('el-table_')) continue
    if (
      c === 'el-table__cell' ||
      c.endsWith('--striped') ||
      c === 'current-row' ||
      c === 'is-leaf' ||
      c === 'is-hidden' ||
      c === 'stack-hide'
    ) continue
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
      // 与 findColId 保持同一套键：优先 el-table_N_column_M，其次是用户自写 class-name
      if (/^el-table_\d+_column_\d+$/.test(c)) {
        if (!map.has(c)) map.set(c, text)
        return
      }
      if (
        c.startsWith('el-table_') ||
        c === 'el-table__cell' ||
        c === 'is-leaf' ||
        c === 'is-hidden' ||
        c === 'is-center' ||
        c === 'is-first-column' ||
        c === 'is-last-column' ||
        c === 'stack-hide' ||
        c.startsWith('el-table-fixed-column')
      ) return
      if (map.has(c)) return
      map.set(c, text)
    })
  })
  return map
}

/** td 是否是「跨列单元格」（展开行 / 合计行等）。
 *  注意：Element Plus 会给每个普通单元格也渲染 colspan="1"，
 *  绝不能按「有 colspan 属性」判定，必须看解析后的数值 > 1。 */
function isSpanCell(td) {
  const v = td.getAttribute('colspan')
  return v != null && Number(v) > 1
}

function labelize(el) {
  if (!el || !el.isConnected) return false
  const mq = window.matchMedia(MQ).matches
  if (!mq) {
    el.classList.remove(STACKED)
    el.querySelectorAll('[data-label]').forEach((td) => td.removeAttribute('data-label'))
    el.querySelectorAll('[data-stack-hide]').forEach((td) => td.removeAttribute('data-stack-hide'))
    el.querySelectorAll('[data-stack-role]').forEach((td) => td.removeAttribute('data-stack-role'))
    el.querySelectorAll('[data-stack-empty]').forEach((td) => td.removeAttribute('data-stack-empty'))
    return true
  }

  // 先在内存里算好表头映射；拿不到就本轮不动 DOM（避免留下"有 is-stacked 但无标记"的半成品）
  const headerMap = collectHeaderMap(el)
  if (headerMap.size === 0) return false // 表格尚未渲染完，等下一轮

  el.classList.add(STACKED)

  const rows = el.querySelectorAll('tbody tr')
  rows.forEach((tr) => {
    if (tr.children.length === 1 && isSpanCell(tr.children[0])) return // 整行展开行：跳过
    let firstSeen = false
    Array.from(tr.children).forEach((td) => {
      if (isSpanCell(td)) return // 跨列单元格（展开行内容等）不套字段名
      const colId = findColId(td)
      const label = (colId && headerMap.get(colId)) || '字段'
      if (td.getAttribute('data-label') !== label) {
        td.setAttribute('data-label', label)
      }
      const hidden = td.classList.contains('stack-hide')
      if (hidden) {
        td.setAttribute('data-stack-hide', '1')
        td.removeAttribute('data-stack-role')
        td.removeAttribute('data-stack-empty')
      } else {
        td.removeAttribute('data-stack-hide')
        // 第一格可见 => 卡片头部（主键），渲染成大字号 chip
        if (!firstSeen) {
          td.setAttribute('data-stack-role', 'header')
          firstSeen = true
        } else {
          td.setAttribute('data-stack-role', 'field')
          // 空占位字段在卡片里只留一行空白，标记后由 CSS 隐藏（主键即便为空也保留，保证卡片有标题）
          if (isBlankCell(td)) td.setAttribute('data-stack-empty', '1')
          else td.removeAttribute('data-stack-empty')
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
    // labelize 是幂等的（同值不重写属性），resize / DOM 变更只需重新跑一次，
    // 不要先清空再算 —— 清空与重算之间若遇到「表头尚未重建完」的一瞬，就会留下
    // 有 is-stacked 却没有任何标记的空卡片（曾出现卡片无字段名的 bug）。
    el.__stackResize = () => { clearRetries(el); schedule(el) }
    window.addEventListener('resize', el.__stackResize)
    el.__stackObs = new MutationObserver(() => {
      // DOM 翻页 / 行重建时重新 labelize（180ms 防抖）
      clearTimeout(el.__stackT)
      el.__stackT = setTimeout(() => { clearRetries(el); schedule(el) }, 180)
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
    clearRetries(el)
    schedule(el)
  },
  unmounted(el) {
    if (el.__stackResize) window.removeEventListener('resize', el.__stackResize)
    if (el.__stackObs) el.__stackObs.disconnect()
    clearTimeout(el.__stackT)
  },
}

export default stackTable
