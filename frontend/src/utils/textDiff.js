/**
 * 段落级文本 diff（无第三方依赖）。
 *
 * 为什么是「段落级」而不是字级：文档正文是富文本，字级 diff 会把标签/样式搅在一起，
 * 结果既难读也没意义。这里先把 HTML 拆成「块级段落」，再对段落序列做 LCS，
 * 输出「新增 / 删除 / 未变」三类片段，界面上按行着色即可。
 */

/** 块级标签：遇到它们就切段（div/p/h1-6/li/tr/blockquote/pre/table 等） */
const BLOCK_RE = /<\/(p|div|h[1-6]|li|tr|blockquote|pre|table|ul|ol|section|article)>/gi

/** 行内标签直接去掉，只留文字 */
function stripTags(html) {
  return String(html || '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&')
}

/**
 * HTML → 段落数组。空段落（纯空白）丢弃，避免 diff 结果被一堆空行打断。
 * @param {string} html
 * @returns {string[]}
 */
export function htmlToParagraphs(html) {
  if (!html) return []
  const withBreaks = String(html).replace(BLOCK_RE, '$&\n')
  return stripTags(withBreaks)
    .split('\n')
    .map((s) => s.replace(/\s+/g, ' ').trim())
    .filter((s) => s.length > 0)
}

/**
 * 段落序列 diff（LCS 动态规划）。
 * @returns {{type:'same'|'add'|'del', text:string}[]}
 *   same = 两版都有；del = 旧版有、新版没有；add = 新版新增
 */
export function diffParagraphs(oldList, newList) {
  const a = oldList || []
  const b = newList || []
  const n = a.length
  const m = b.length
  // 大文档保护：超过 1500 段直接退化为「整体替换」，避免 O(n*m) 卡死页面
  if (n * m > 1500 * 1500) {
    return [
      ...a.map((t) => ({ type: 'del', text: t })),
      ...b.map((t) => ({ type: 'add', text: t }))
    ]
  }

  const dp = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }

  const ops = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      ops.push({ type: 'same', text: a[i] })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      ops.push({ type: 'del', text: a[i] })
      i++
    } else {
      ops.push({ type: 'add', text: b[j] })
      j++
    }
  }
  while (i < n) ops.push({ type: 'del', text: a[i++] })
  while (j < m) ops.push({ type: 'add', text: b[j++] })
  return ops
}

/** 统计：返回 { added, removed, same } 段数，用于弹窗顶部的摘要 */
export function diffStats(ops) {
  return (ops || []).reduce(
    (acc, op) => {
      if (op.type === 'add') acc.added++
      else if (op.type === 'del') acc.removed++
      else acc.same++
      return acc
    },
    { added: 0, removed: 0, same: 0 }
  )
}
