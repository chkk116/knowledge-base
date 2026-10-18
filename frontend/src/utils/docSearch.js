/**
 * 文档正文检索工具。
 *
 * 背景：「全部文档」列表接口 (/api/doc-shares/docs/all) 本来就带着正文 content，
 * 所以正文搜索在前端做即可，不需要后端加全文索引。
 *
 * 性能：正文是 HTML，每次按键都重新去标签会很慢，这里按 doc.id 缓存
 * 一份「纯文本 + 小写版」，列表刷新时调 clearDocSearchCache() 清掉。
 */

const cache = new Map()

const ENTITY = {
  '&nbsp;': ' ', '&lt;': '<', '&gt;': '>', '&amp;': '&', '&quot;': '"', '&#39;': "'"
}

/** HTML → 纯文本（去脚本/样式/标签，反转义常见实体，压缩空白） */
export function htmlToText(html) {
  let s = String(html || '')
  if (!s) return ''
  s = s.replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<[^>]+>/g, ' ')
  s = s.replace(/&(nbsp|lt|gt|amp|quot|#39);/g, (m) => ENTITY[m] || ' ')
  return s.replace(/\s+/g, ' ').trim()
}

/** 取文档的可检索正文（带缓存）：{ raw, lower } */
export function plainOf(doc) {
  if (!doc) return { raw: '', lower: '' }
  const key = doc.id
  const hit = cache.get(key)
  // 正文变了（例如刚编辑过）就重建：用 content 长度做粗判，够用且便宜
  if (hit && hit.len === String(doc.content || '').length) return hit
  const raw = htmlToText(doc.content)
  const item = { raw, lower: raw.toLowerCase(), len: String(doc.content || '').length }
  cache.set(key, item)
  return item
}

/** 关键词是否命中正文 */
export function contentMatches(doc, q) {
  if (!q) return false
  return plainOf(doc).lower.includes(q)
}

/**
 * 命中片段：返回关键词前后各 span 个字，供列表里显示上下文。
 * 没命中或关键词为空时返回空串。
 */
export function snippetOf(doc, q, span = 24) {
  if (!q) return ''
  const { raw, lower } = plainOf(doc)
  if (!raw) return ''
  const i = lower.indexOf(q)
  if (i < 0) return ''
  const start = Math.max(0, i - span)
  const end = Math.min(raw.length, i + q.length + span)
  return (start > 0 ? '…' : '') + raw.slice(start, end) + (end < raw.length ? '…' : '')
}

/** 列表重新加载后调用，避免旧缓存占内存/显示过期内容 */
export function clearDocSearchCache() {
  cache.clear()
}
