/** AI 模型选择与上下文窗口的纯规则（无 Vue / DOM / 网络依赖）。 */

export const CTX_SET_KEY = 'kb_ai_ctx_window'
export const THINK_KEY = 'kb_ai_think'
export const DEFAULT_MAX_WINDOW = 128000
export const MIN_CTX_WINDOW = 8000

/** 档位阈值集中在这里，避免散落的魔法数字与 DEFAULT_MAX_WINDOW 重复 */
export const CTX_LABEL_TIERS = [
  [500000, '超长'],
  [DEFAULT_MAX_WINDOW, '长'],
  [32000, '标准'],
]

/** 只接受正整数 id：NaN / 0 / 空串不能一路混进请求体（JSON 里会变成 null 或真实 id 0） */
const toId = (s) => {
  const n = Number(s)
  return Number.isInteger(n) && n > 0 ? n : null
}

/** 模型 key：系统模型 s:<id>、自己的模型 u:<id>（两类 id 各自自增可能重复，必须带前缀区分） */
export function parseModelSelection(raw) {
  const v = String(raw || '')
  if (!v) return { modelId: null }
  if (v.startsWith('u:')) return { modelId: null, userModelId: toId(v.slice(2)) }
  if (v.startsWith('s:')) return { modelId: toId(v.slice(2)) }
  return { modelId: toId(v) }
}

/**
 * 用户只能把窗口调小、不能超过模型上限；0 / 空 = 跟随模型最大值。
 * 窗口大小由管理员按模型配置（64K~1M 差得很远），所以不能写死。
 */
export function clampCtxWindow(value, max) {
  const upper = Number(max) > 0 ? Number(max) : DEFAULT_MAX_WINDOW
  const v = Number(value) || 0
  return v > 0 ? Math.min(Math.max(v, MIN_CTX_WINDOW), upper) : upper
}

/** 窗口档位文案（对齐「1M 上下文·超长」这类语义）；max 的兜底与 clampCtxWindow 保持一致 */
export function ctxWindowLabelOf(win, max) {
  const upper = Number(max) > 0 ? Number(max) : DEFAULT_MAX_WINDOW
  if (win >= upper) return '模型上限'
  for (const [threshold, label] of CTX_LABEL_TIERS) {
    if (win >= threshold) return label
  }
  return '精简'
}
