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
