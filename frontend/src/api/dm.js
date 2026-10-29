import api from './request'

export const dmConversations = () => api.get('/dm/conversations')
export const dmMessages = (peerId, beforeId) =>
  api.get('/dm/messages', { params: { peerId, ...(beforeId ? { beforeId } : {}) } })
export const dmSend = (toId, content, kind = 'TEXT') =>
  api.post('/dm/messages', { toId, content, kind })
export const dmRead = (peerId) => api.post('/dm/read', { peerId })
export const dmQuota = (peerId) => api.get('/dm/quota', { params: { peerId } })
export const dmSettings = () => api.get('/dm/settings')
export const dmSaveSettings = (allowDm) => api.put('/dm/settings', { allowDm })
export const dmUnread = () => api.get('/dm/unread')
