import api from './request'

// AI 智能问答：body { question, conversationId?, mode? }，返回 { answer, conversationId, title, sources? }
// mode: 'kb'(基于知识库检索, 默认) | 'general'(纯通用问答)
// timeout 单独放宽：全局 15s，但 RAG 检索 + 大模型生成实测常超 15s，会被 axios 掐断
// config 可传 { signal }（AbortController）用于前端「停止生成」按钮中断请求
export const aiAsk = (question, conversationId, mode, config = {}) =>
  api.post('/ai/ask', { question, conversationId, mode }, { timeout: 120000, ...config })

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
