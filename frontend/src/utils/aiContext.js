// 上下文预算与用量估算（2026-10-20）
//
// 为什么需要：模型的上下文窗口从 64K 到 1M 不等，写死「带最近 6 条 × 400 字」
// 对大窗口是浪费、对小窗口又可能撑爆。这里按「模型配置的 contextWindow」
// 动态算：历史占多少、当前用了多少、什么时候该压缩。

/**
 * 粗略 token 估算。
 * 中文（含日文假名）按 1 字 ≈ 1 token；其余按 3.5 字符 ≈ 1 token。
 * 不追求精确——只用来决定「还能带几条历史」和画用量环，
 * 真正的用量以后端返回的 usage.prompt_tokens 为准。
 */
export function estimateTokens(text) {
  const s = String(text || '')
  if (!s) return 0
  let cjk = 0
  let other = 0
  for (const ch of s) {
    const c = ch.codePointAt(0)
    // CJK 统一表意文字 / 假名 / 全角标点
    if ((c >= 0x4e00 && c <= 0x9fff) || (c >= 0x3040 && c <= 0x30ff) || (c >= 0xff00 && c <= 0xffef)) cjk++
    else other++
  }
  return Math.ceil(cjk + other / 3.5)
}

export function formatTokens(n) {
  const v = Number(n) || 0
  if (v >= 1_000_000) return (v / 1_000_000).toFixed(v >= 10_000_000 ? 0 : 1) + 'M'
  if (v >= 1000) return (v / 1000).toFixed(v >= 10_000 ? 0 : 1) + 'K'
  return String(v)
}

/**
 * 从最近往前挑历史，直到预算用完。
 * @param {Array<{role,content}>} messages 按时间正序
 * @param {number} budgetTokens 历史部分可用的 token 预算
 * @param {number} perMsgMax 单条最多取多少字符（防止一条超长消息吃掉整个预算）
 * @returns {Array<{role,content}>} 仍是正序，最旧的在前
 */
export function pickHistory(messages, budgetTokens, perMsgMax = 4000) {
  const list = Array.isArray(messages) ? messages : []
  const picked = []
  let used = 0
  for (let i = list.length - 1; i >= 0; i--) {
    const m = list[i]
    const text = String((m && m.content) || '')
    if (!text) continue
    const cut = text.length > perMsgMax ? text.slice(0, perMsgMax) : text
    const cost = estimateTokens(cut)
    if (used + cost > budgetTokens && picked.length > 0) break
    picked.unshift({ role: m.role, content: cut })
    used += cost
  }
  return picked
}

/** 把挑出来的历史拼成给 Agent 看的文本 */
export function historyToText(picked) {
  return (picked || [])
    .map((m) => (m.role === 'user' ? '用户：' : '助手：') + String(m.content || '').replace(/\s+/g, ' '))
    .join('\n')
}

/** 压缩上下文的提示词：要求保留「做过什么、结论是什么、还有什么没做完」 */
export const COMPACT_INSTRUCTION = `请把下面这段对话压缩成一份要点摘要，供后续对话继续使用。
要求：
1. 保留：用户的目标、已经完成的操作及其结果（含关键数字/名称/ID）、已确认的结论与偏好、尚未完成或待确认的事项。
2. 丢弃：寒暄、重复的中间过程、已被推翻的方案。
3. 用简洁的分条列表，总长控制在原文的 1/5 以内。
4. 只输出摘要本身，不要加「以下是摘要」这类开场白。

对话原文：
`

/** 思考强度档位（作用于提示词，不依赖厂商私有参数，所以对所有模型都有效） */
export const THINK_LEVELS = [
  { value: 'fast', label: '快速', tip: '直接给结论，少展开' },
  { value: 'normal', label: '标准', tip: '默认，兼顾速度与完整' },
  { value: 'deep', label: '深入', tip: '先分析再结论，指出风险与前提' },
]

export const THINK_PREFIX = {
  fast: '【回答风格】直接给结论，不要展开推理过程，能一句话说清就一句话；需要多步操作时也只做最少的步骤。\n\n',
  normal: '',
  deep: '【回答风格】先分析问题、比较可行方案，再给结论；必要时指出风险与前提条件，不要只给一个答案。\n\n',
}

export function thinkPrefix(level) {
  return THINK_PREFIX[level] || ''
}
