import api from './request'

// 参数清洗：当 id 为 undefined / null / '' / NaN 时，直接抛错并打印调用栈，
// 避免拼出 "/doc-shares/undefined" 这类模糊错误（后端只会报 "Failed to convert ... 'undefined'"）。
// 这样在浏览器 F12 控制台能立刻看到是哪个函数、哪行代码传了非法 id。
function pid(v, name = 'id') {
  if (v === undefined || v === null || v === '' || (typeof v === 'number' && Number.isNaN(v))) {
    const e = new Error(`[docShare] 参数 ${name} 无效(${v})，已阻止请求 /doc-shares/...`)
    console.error(e.stack || e.message)
    throw e
  }
  return v
}

// 我参与/创建的共享空间
export const listMyShares = () => api.get('/doc-shares/my')
export const createShare = (d) => api.post('/doc-shares', d)
export const getShare = (id) => api.get('/doc-shares/' + pid(id))
export const updateShare = (id, d) => api.put('/doc-shares/' + pid(id), d)
export const deleteShare = (id) => api.delete('/doc-shares/' + pid(id))

// 选人搜索（创建/邀请成员用）
export const searchShareUsers = (keyword) => api.get('/doc-shares/users', { params: { keyword } })

// 成员权限
export const listMembers = (id) => api.get('/doc-shares/' + pid(id) + '/members')
export const addMember = (id, d) => api.post('/doc-shares/' + pid(id) + '/members', d)
export const updateMember = (id, userId, d) => api.put('/doc-shares/' + pid(id) + '/members/' + pid(userId, 'userId'), d)
export const removeMember = (id, userId) => api.delete('/doc-shares/' + pid(id) + '/members/' + pid(userId, 'userId'))

// 空间内文档
export const listShareDocs = (id) => api.get('/doc-shares/' + pid(id) + '/docs')
export const createShareDoc = (id, d) => api.post('/doc-shares/' + pid(id) + '/docs', d)
export const getShareDoc = (id, docId) => api.get('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId'))
export const updateShareDoc = (id, docId, d) => api.put('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId'), d)
export const deleteShareDoc = (id, docId) => api.delete('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId'))
export const moveShareDoc = (id, docId, targetShareId) => api.post('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId') + '/move', { targetShareId })

// 文档级权限（在空间权限之上对单篇文档单独收窄/提升）
export const listDocMembers = (id, docId) => api.get('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId') + '/members')
export const setDocMember = (id, docId, userId, permission) =>
  api.put('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId') + '/members/' + pid(userId, 'userId'), { permission })
export const clearDocMember = (id, docId, userId) =>
  api.delete('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId') + '/members/' + pid(userId, 'userId'))
// 从外部文件导入为共享文档（.md/.txt/.html/.docx/.pdf），需写入权限
export const importShareDoc = (id, file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/doc-shares/' + pid(id) + '/docs/import', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
}

// 个人存储用量（有效配额 = 管理员分配或默认 200MB）
export const getStorage = () => api.get('/doc-shares/storage')

// 单个空间用量（已用 / 上限 / 所有者总配额）
export const getShareStorage = (id) => api.get('/doc-shares/' + pid(id) + '/storage')

// 管理后台
export const listAllShares = () => api.get('/admin/doc-shares')
export const adminDeleteShare = (id) => api.delete('/admin/doc-shares/' + pid(id))
// 管理员设置空间容量上限（字节），capacity=0 取消上限
export const adminSetCapacity = (id, capacity) => api.put('/admin/doc-shares/' + pid(id) + '/capacity', { capacity })

// ---- 共享文档版本历史 ----
// 手动保存当前文档状态为一份版本快照
export const saveShareDocVersion = (shareId, docId) =>
  api.post('/doc-shares/' + pid(shareId, 'shareId') + '/docs/' + pid(docId, 'docId') + '/versions')
// 列出某共享文档的全部历史版本（倒序）
export const listShareDocVersions = (shareId, docId) =>
  api.get('/doc-shares/' + pid(shareId, 'shareId') + '/docs/' + pid(docId, 'docId') + '/versions')
// 恢复到指定版本（写回 title/content/yjs_state，协同端重连后生效）
export const restoreShareDocVersion = (shareId, docId, vid) =>
  api.post('/doc-shares/' + pid(shareId, 'shareId') + '/docs/' + pid(docId, 'docId') + '/versions/' + pid(vid, 'vid') + '/restore')

// 共享空间扩容申请（空间 OWNER/ADMIN 提交；审批在后台由完全管理员处理）
// requestedCapacity：字节，0 = 不限
export const submitCapacityRequest = (shareId, requestedCapacity, reason) =>
  api.post('/doc-shares/' + pid(shareId, 'shareId') + '/capacity-requests', { requestedCapacity, reason })
// 查看某空间下的扩容申请
export const listCapacityRequests = (shareId) => api.get('/doc-shares/' + pid(shareId, 'shareId') + '/capacity-requests')
// 后台：待审批列表 / 审批通过 / 驳回（仅完全管理员）
export const listPendingCapacityRequests = () => api.get('/admin/doc-shares/capacity-requests')
export const approveCapacityRequest = (rid) => api.post('/admin/doc-shares/capacity-requests/' + pid(rid, 'rid') + '/approve')
export const rejectCapacityRequest = (rid, comment) =>
  api.post('/admin/doc-shares/capacity-requests/' + pid(rid, 'rid') + '/reject', { comment })

// 团队空间创建上限配额（前端据此决定是否走申请流程）
export const getMyShareQuota = () => api.get('/doc-shares/my-quota')
// 已达上限的 SHARE_CREATE 用户提交创建空间申请
export const submitCreateRequest = (d) => api.post('/doc-shares/create-requests', d)
// 我的创建申请列表
export const listMyCreateRequests = () => api.get('/doc-shares/create-requests/mine')
// 后台：待审批创建申请列表
export const listPendingCreateRequests = () => api.get('/admin/doc-shares/create-requests')
export const approveCreateRequest = (rid) => api.post('/admin/doc-shares/create-requests/' + pid(rid, 'rid') + '/approve')
export const rejectCreateRequest = (rid, comment) =>
  api.post('/admin/doc-shares/create-requests/' + pid(rid, 'rid') + '/reject', { comment })
