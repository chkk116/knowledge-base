/**
 * 本地阅读 / 使用统计（不上传，纯本地）
 *
 * 用途：「我的数据」页与首页数据概览卡。
 * 大厂 App 普遍有「学习报告 / 使用统计」，本地统计不依赖后端即可实现。
 *
 * 存储按用户隔离（换号登录后统计不串），键：kb_read_stats_v1:<userId>
 * 结构：
 *   {
 *     days:  { '2026-10-11': 3 },              // 每天打开文档次数
 *     docs:  { '3:12': { t: 1728…, title: 'x' } },  // 读过的文档（spaceId:docId）
 *     ai:    { '2026-10-11': 2 },              // 每天 AI 提问次数
 *     words: { '2026-10-11': 1200 }            // 每天阅读字数（估算）
 *   }
 */
const KEY = 'kb_read_stats_v1'

/** 本地数据按用户隔离 */
function uid() {
  try {
    const u = JSON.parse(localStorage.getItem('kb_user_cache') || 'null')
    return u && u.id != null ? String(u.id) : 'anon'
  } catch { return 'anon' }
}

function storeKey() { return KEY + ':' + uid() }

const pad = (n) => String(n).padStart(2, '0')
export function dayStr(d = new Date()) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function load() {
  try {
    const o = JSON.parse(localStorage.getItem(storeKey()) || 'null')
    if (o && typeof o === 'object') return { days: {}, docs: {}, ai: {}, words: {}, ...o }
  } catch { /* 损坏则重置 */ }
  return { days: {}, docs: {}, ai: {}, words: {} }
}

function save(o) {
  try { localStorage.setItem(storeKey(), JSON.stringify(o)) } catch { /* 配额满则忽略 */ }
}

/** 估算正文字数（去标签、去空白） */
export function estimateWords(html) {
  if (!html) return 0
  const text = String(html).replace(/<[^>]*>/g, ' ').replace(/\s+/g, '')
  return text.length
}

/** 打开一篇文档：计数 + 记录读过的文档 + 累计字数 */
export function recordDocOpen(spaceId, docId, title = '', html = '') {
  if (docId == null) return
  const o = load()
  const d = dayStr()
  o.days[d] = (o.days[d] || 0) + 1
  o.docs[`${spaceId}:${docId}`] = { t: Date.now(), title: title || '未命名文档', spaceId: Number(spaceId) }
  const w = estimateWords(html)
  if (w) o.words[d] = (o.words[d] || 0) + w
  save(o)
}

/** 一次 AI 提问 */
export function recordAiAsk() {
  const o = load()
  const d = dayStr()
  o.ai[d] = (o.ai[d] || 0) + 1
  save(o)
}

/** 汇总统计（含最近 14 天曲线、连续活跃天数） */
export function getStats() {
  const o = load()
  const today = dayStr()

  // 最近 14 天（含今天，倒序 → 正序输出）
  const series = []
  for (let i = 13; i >= 0; i--) {
    const d = new Date()
    d.setDate(d.getDate() - i)
    const k = dayStr(d)
    series.push({ day: k, md: `${d.getMonth() + 1}/${d.getDate()}`, docs: o.days[k] || 0, ai: o.ai[k] || 0 })
  }

  const last7 = series.slice(-7)
  const weekRead = last7.reduce((s, x) => s + x.docs, 0)
  const weekWords = last7.reduce((s, x) => s + (o.words[x.day] || 0), 0)
  const weekAi = last7.reduce((s, x) => s + x.ai, 0)

  // 连续活跃天数：从今天往前数（今天没记录就从昨天开始数，避免白天打开时显示 0）
  const active = new Set([
    ...Object.keys(o.days).filter((k) => o.days[k] > 0),
    ...Object.keys(o.ai).filter((k) => o.ai[k] > 0),
  ])
  let streak = 0
  const cur = new Date()
  if (!active.has(dayStr(cur))) cur.setDate(cur.getDate() - 1)
  while (active.has(dayStr(cur))) { streak++; cur.setDate(cur.getDate() - 1) }

  // 最近读过的文档（最多 10 条）
  const recentDocs = Object.entries(o.docs)
    .map(([k, v]) => ({ key: k, ...v }))
    .sort((a, b) => b.t - a.t)
    .slice(0, 10)

  return {
    todayRead: o.days[today] || 0,
    todayAi: o.ai[today] || 0,
    weekRead,
    weekWords,
    weekAi,
    totalDocs: Object.keys(o.docs).length,
    totalDays: active.size,
    streak,
    series,
    recentDocs,
    maxSeries: Math.max(1, ...series.map((x) => x.docs)),
  }
}

/** 清空本机统计 */
export function clearStats() {
  save({ days: {}, docs: {}, ai: {}, words: {} })
}
