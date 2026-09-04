import api from './request'

// 后台数据看板：各项聚合计数
export const stats = () =>
  api.get('/admin/stats')

// ---- 用户管理（仅管理员）----
// 支持检索/排序/分页：listUsers({ keyword, sort, page, size })
export const listUsers = (params = {}) => api.get('/admin/users', { params })
export const createUser = (d) => api.post('/admin/users', d)
export const updateUser = (id, d) => api.put('/admin/users/' + id, d)
export const deleteUser = (id) => api.delete('/admin/users/' + id)
export const toggleUser = (id, enabled) => api.post('/admin/users/' + id + '/toggle', { enabled })
export const adjustPoints = (id, delta, reason) => api.post('/admin/users/' + id + '/points', { delta, reason })
// 管理员重置用户密码（返回明文新密码）
export const resetPassword = (id) => api.post('/admin/users/' + id + '/reset-password')
// 管理员查看任意用户存储用量（已用 / 有效配额）
export const adminUserStorage = (id) => api.get('/admin/users/' + id + '/storage')

// ---- 操作审计日志（仅管理员）----
export const listAudit = (params = {}) => api.get('/admin/audit', { params })

// ---- 附件（孤儿文件）管理（仅管理员）----
// 服务端分页 + 过滤：listOrphanFiles({ page, size, kind, q })
export const listOrphanFiles = (params = {}) => api.get('/admin/files/orphans', { params })
export const deleteOrphanFile = (url) => api.delete('/admin/files/orphan', { params: { url } })

// ---- 帖子管理（仅管理员）----
// 支持检索/排序/分页：listDocsAdmin({ keyword, sort, page, size })
export const listDocsAdmin = (params = {}) => api.get('/admin/docs', { params })
// 管理员：封禁文档（黑名单）分页
export const listBlockedDocs = (params = {}) => api.get('/admin/docs/blocked', { params })
export const createDoc = (d) => api.post('/admin/docs', d)
export const updateDoc = (id, d) => api.put('/admin/docs/' + id, d)
export const deleteDoc = (id) => api.delete('/admin/docs/' + id)
export const blockDoc = (id) => api.post('/admin/docs/' + id + '/block')
export const unblockDoc = (id) => api.post('/admin/docs/' + id + '/unblock')
export const pinDoc = (id) => api.post('/admin/docs/' + id + '/pin')
export const unpinDoc = (id) => api.post('/admin/docs/' + id + '/unpin')

// ---- 审核队列（仅管理员）----
export const listPending = () => api.get('/admin/docs/pending')
export const approveDoc = (id) => api.post('/admin/docs/' + id + '/approve')
export const rejectDoc = (id, reason) => api.post('/admin/docs/' + id + '/reject', { reason })
