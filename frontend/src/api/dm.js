import api from './request'

export const dmConversations = () => api.get('/dm/conversations')

/** 发起私信时选人：按用户名/昵称搜索（后端排除自己） */
export const dmSearchUsers = (q) => api.get('/users/search', { params: { q: q || '' } })
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

// 私信图片：上传到私有目录，返回 dm:<name> 引用（不含可直接访问的 /uploads URL）
export const uploadDmImage = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/dm/attachment', fd, { timeout: 120000 })
}

// 私信图片读取：私有目录已 404，只能带令牌走鉴权接口取 blob
export const dmAttachment = (ref) =>
  api.get('/dm/attachment', { params: { key: ref }, responseType: 'blob', timeout: 30000, silent: true })
