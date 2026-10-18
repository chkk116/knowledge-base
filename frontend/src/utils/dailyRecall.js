/**
 * 每日回顾（2026-10-18）。
 *
 * 知识库最大的问题是「写过就忘」：文档躺在空间里，之后再没打开过。
 * 这个工具从「最久没更新」的文档里挑一篇推到首页，让旧内容重新被看见。
 *
 * 刻意做得**没有后端**：挑选规则、跳过记录都在本机，理由——
 *   1. 这是个人习惯，不需要跨端同步；
 *   2. 不写库就不需要考虑「谁看过」这类隐私与统计开销；
 *   3. 首页已经有 4 个接口在等，再加一个会拖慢首屏。
 */

/** 本机「不再推荐」清单（按文档维度） */
const SKIP_KEY = 'kb_recall_skip'
const MAX_SKIP = 200

function readJson(key, fallback) {
  try {
    const raw = localStorage.getItem(key)
    return raw ? JSON.parse(raw) : fallback
  } catch { return fallback }
}

/** 文档唯一键：优先 shareId:docId，退化用 docId，保证与页面上的 it.doc 对得上 */
export function docKey(doc) {
  if (!doc) return ''
  if (doc.shareId != null && doc.docId != null) return `${doc.shareId}:${doc.docId}`
  if (doc.shareId != null && doc.id != null) return `${doc.shareId}:${doc.id}`
  return String(doc.id ?? '')
}

export function getSkipList() { return readJson(SKIP_KEY, []) }

export function addSkip(key) {
  if (!key) return
  const list = getSkipList()
  if (list.includes(key)) return
  list.push(key)
  try { localStorage.setItem(SKIP_KEY, JSON.stringify(list.slice(-MAX_SKIP))) } catch { /* 隐私模式下忽略 */ }
}

/** 本地时区的今天（YYYY-MM-DD） */
export function today() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

/** 稳定字符串 hash：同一天 + 同一个日期串 → 必然同一个序号，不会每次刷新都变 */
function hash(s) {
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0
  return Math.abs(h)
}

/**
 * 挑一篇来回顾。
 *
 * 规则：
 *   1. 过滤掉「不再推荐」的；
 *   2. 按更新时间**升序**（最久没碰的排前面）取前 5 篇做候选池——
 *      不是只取最老的那一篇，那样连着几天都是同一篇，很快就烦；
 *   3. 用日期串做种子取模定序号：同一天刷新多少次都是同一篇，
 *      第二天自动换一个，用户感知是「每天一篇」而不是「每次刷新一篇」。
 *
 * @param items [{ spaceId, spaceName, doc }]
 * @returns { item, candidates } | null
 */
export function pickRecall(items, day = today(), skip = getSkipList()) {
  const pool = (items || []).filter((it) => it && it.doc && docKey(it.doc) && !skip.includes(docKey(it.doc)))
  if (!pool.length) return null
  const candidates = pool
    .slice()
    .sort((a, b) => new Date(a.doc.updatedAt || a.doc.createdAt || 0) - new Date(b.doc.updatedAt || b.doc.createdAt || 0))
    .slice(0, 5)
  const idx = hash(day) % candidates.length
  return { item: candidates[idx], candidates }
}

/** 距上一次更新多少天（用于「N 天没更新」的提示） */
export function daysAgo(doc) {
  const t = new Date(doc?.updatedAt || doc?.createdAt || 0).getTime()
  if (!t) return ''
  const days = Math.floor((Date.now() - t) / 86400000)
  if (days <= 0) return '今天'
  if (days === 1) return '昨天'
  return `${days} 天`
}
