import api from './request'

// AI 智能问答：body { question, conversationId?, mode?, modelId? }
// mode: 'kb'(基于知识库检索, 默认) | 'general'(纯通用问答)
// modelId: 本次指定的模型配置 id；空/不传 = 用管理员设的默认模型
// 返回 { answer, conversationId, title, sources?, modelName?, latencyMs?, usage? }
// timeout 单独放宽：全局 15s，但 RAG 检索 + 大模型生成实测常超 15s，会被 axios 掐断
// config 可传 { modelId } 与 { signal }（AbortController，用于「停止生成」按钮中断请求）
// images: 图片 dataURL 数组（模型原生多模态，直接看图；不传则退化为纯文本）
export const aiAsk = (question, conversationId, mode, { modelId = null, images = null, ...config } = {}) =>
  api.post('/ai/ask', { question, conversationId, mode, modelId, images }, { timeout: 120000, ...config })

/**
 * 对某条 AI 回答点赞/踩。qaId 来自 /ai/ask 或 /ai/ask/stream 的 done 事件返回值。
 * rating：1=赞，-1=踩；reason 仅在踩时填（可选）。只能评价自己的记录。
 */
export const aiFeedback = (qaId, rating, reason = '') =>
  api.post('/ai/feedback', { qaId, rating, reason })

/**
 * 流式问答（SSE）：逐段回调 onDelta / onReasoning，结束后返回与 aiAsk 同结构的对象。
 * 用 fetch + ReadableStream 而不是 axios：axios 浏览器端拿不到逐块 body。
 * signal 直接交给 fetch —— abort 时连接断开，后端 sink 抛错退出并保留已生成内容。
 */
export async function aiAskStream(question, conversationId, mode, opts = {}) {
  const { modelId = null, images = null, signal, onDelta, onReasoning } = opts
  const token = localStorage.getItem('kb_token') || ''
  const url = (api.defaults.baseURL || '') + '/ai/ask/stream'
  const resp = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: JSON.stringify({ question, conversationId, mode, modelId, images }),
    signal
  })
  if (!resp.ok || !resp.body) {
    const err = new Error('HTTP ' + resp.status)
    err.status = resp.status
    throw err
  }
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  let result = null
  const handle = (chunk) => {
    let event = 'message'
    const data = []
    for (const line of chunk.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) data.push(line.slice(5).trim())
    }
    if (!data.length) return
    let payload
    try { payload = JSON.parse(data.join('\n')) } catch { return }
    if (event === 'done') { result = payload; return }
    if (event === 'error') { throw new Error(payload.error || 'AI 生成中断') }
    if (payload.delta) onDelta && onDelta(payload.delta)
    if (payload.reasoning) onReasoning && onReasoning(payload.reasoning)
  }
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    // 有些反向代理会把换行规范成 CRLF；SSE 规范两种都算事件边界，统一成 LF 再切
    buf = (buf + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')
    let idx
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const chunk = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      handle(chunk)
    }
  }
  if (buf.trim()) handle(buf)
  return result || {}
}

// 会话列表
export const listConversations = () =>
  api.get('/ai/conversations')

// 创建会话
export const createConversation = (title) =>
  api.post('/ai/conversations', { title })

// 更新会话（标题 / 对话条例）
export const updateConversation = (id, { title, instructions }) =>
  api.put(`/ai/conversations/${id}`, { title, instructions })

// 删除会话
export const deleteConversation = (id) =>
  api.delete(`/ai/conversations/${id}`)

// 批量删除会话：一次请求删完（后端单事务）。
// 旧后端没有该接口（404）时回退成逐条并发删，保证前端不受版本影响。
export async function batchDeleteConversations(ids) {
  try {
    const { data } = await api.post('/ai/conversations/batch-delete', { ids })
    return data && typeof data.deleted === 'number' ? data.deleted : ids.length
  } catch (e) {
    if (e && e.response && e.response.status === 404) {
      const queue = [...ids]
      let ok = 0
      const worker = async () => {
        while (queue.length) {
          const id = queue.shift()
          try { await deleteConversation(id); ok++ } catch { /* 单条失败不影响其余 */ }
        }
      }
      await Promise.all(Array.from({ length: Math.min(4, queue.length) }, worker))
      return ok
    }
    throw e
  }
}

// 获取某会话的消息列表
export const listMessages = (id) =>
  api.get(`/ai/conversations/${id}/messages`)

// 用户 AI 偏好（习惯）
export const getPreference = () =>
  api.get('/ai/preferences')

export const savePreference = (preference) =>
  api.put('/ai/preferences', preference)

// ---- 当前可用模型（不含 API Key，用于对话页下拉选择）----
export const listAiModels = () =>
  api.get('/ai/models')

// ---- AI 模型管理（仅完全管理员：AuthFilter 对 /api/admin/ai 前缀返回 null，只认 ADMIN 角色）----
// 列表一律脱敏：keyMasked 形如 ****abcd，hasKey 表示是否已填 Key
export const adminListModels = () => api.get('/admin/ai/models')
// 可选平台（key/label/baseUrl/doc），选平台自动带出 baseUrl
export const adminModelPlatforms = () => api.get('/admin/ai/models/platforms')
// 新增（id 为空）/ 更新：apiKey 传空串或回显的脱敏值（含 ****）= 不改动，保留库中原值
export const adminSaveModel = (d) => api.post('/admin/ai/models', d)
export const adminDeleteModel = (id) => api.delete('/admin/ai/models/' + id)
export const adminToggleModel = (id, enabled) => api.post('/admin/ai/models/' + id + '/toggle', { enabled })
export const adminSetDefaultModel = (id) => api.post('/admin/ai/models/' + id + '/default')
// 测试连接：真实发一条极短请求（消耗少量 token），返回 latencyMs / reply / model
export const adminTestModel = (id) =>
  api.post('/admin/ai/models/' + id + '/test', {}, { timeout: 60000 })
// 用量统计：adminUsage({ days, modelId }) → { summary, daily[], models[] }（调用流水聚合）
export const adminUsage = (params) => api.get('/admin/ai/models/usage', { params })
// ---- AI 媒体生成（文生图 / 文生视频）----
export const generateImage = (prompt, modelId = null) =>
  api.post('/ai/image', { prompt, modelId }, { timeout: 180000 })
export const createVideo = (prompt, modelId = null) =>
  api.post('/ai/video', { prompt, modelId })
export const videoStatus = (taskId) => api.get('/ai/video/' + encodeURIComponent(taskId))
