import api from './request'

export const dmConversations = () => api.get('/dm/conversations')
// 归档 / 删除会话（本用户视角）
export const dmArchiveConv = (peerId, archived = true) =>
  api.post(`/dm/conversations/${peerId}/archive`, { archived })
export const dmDeleteConv = (peerId) => api.delete(`/dm/conversations/${peerId}`)
export const dmArchivedConversations = () => api.get('/dm/conversations', { params: { archived: true } })
export const dmMessages = (peerId, beforeId) =>
  api.get('/dm/messages', { params: { peerId, ...(beforeId ? { beforeId } : {}) } })
export const dmSend = (toId, content, kind = 'TEXT', replyId = null) =>
  api.post('/dm/messages', { toId, content, kind, replyId })
export const dmRead = (peerId) => api.post('/dm/read', { peerId })
export const dmQuota = (peerId) => api.get('/dm/quota', { params: { peerId } })
export const dmSettings = () => api.get('/dm/settings')
export const dmSaveSettings = (allowDm) => api.put('/dm/settings', { allowDm })
export const dmUnread = () => api.get('/dm/unread')
export const dmRecall = (id) => api.delete('/dm/messages/' + encodeURIComponent(id))
