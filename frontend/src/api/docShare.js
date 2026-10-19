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
// 跨空间聚合文档列表（只返回当前用户有权读取的条目）
// withContent=true 时一并返回正文（服务端截断到 20000 字符），供「全部文档」做正文检索
export const listAllShareDocs = (withContent = false) =>
  api.get('/doc-shares/docs/all', { params: withContent ? { withContent: true } : {} })

/**
 * 整库导出：返回 zip 的 Blob（需 DOC_EXPORT 权限，ADMIN 隐式拥有）。
 * 导出范围 = 后端按空间级/文档级逐条裁决后的「用户可读范围」，不存在越权。
 */
export const exportAllShareDocs = () => api.get('/doc-shares/export', { responseType: 'blob' })
/**
 * 批量改标签：docs = [{ shareId, docId }]，add / remove 为标签数组。
 * 后端逐条校验写权限，无权限或已删除的会被跳过并在 skipped 里返回，不会整批失败。
 */
export const batchSetDocTags = (docs, add, remove) =>
  api.post('/doc-shares/docs/batch-tags', { docs, add, remove })

/**
 * 正文 @提及：点选成员即发通知。后端会校验对方本来就能打开该文档，
 * 否则拒绝（避免通知过去是死链）。返回 { ok, name }。
 */
export const mentionDocUser = (shareId, docId, userId) =>
  api.post(`/doc-shares/${shareId}/docs/${docId}/mention`, { userId })

/**
 * 文档双向链接：{ out: [{title,resolved,shareId,docId,spaceName}], back: [...] }
 * out = 本文引用的；back = 引用本文的。解析范围仅限当前用户可读的文档。
 */
export const getDocLinks = (shareId, docId) => api.get(`/doc-shares/${shareId}/docs/${docId}/links`)

// 更新单篇文档标签（空数组清除标签）
export const setShareDocTags = (id, docId, tags) =>
  api.put('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId') + '/tags', { tags })

// ---- 文档批注 / 摘录（私人笔记，仅本人可见）----
// 某文档下我的批注列表
export const listDocAnnotations = (id, docId) =>
  api.get('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId') + '/annotations')
// 新增摘录：quote 必填，note 可为空；prefix/suffix 是上下文锚点（用于正文高亮定位）
export const createDocAnnotation = (id, docId, d) =>
  api.post('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId') + '/annotations', d)
// 修改笔记内容
export const updateDocAnnotation = (id, docId, annoId, note) =>
  api.put('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId') + '/annotations/' + pid(annoId, 'annoId'), { note })
// 删除摘录
export const deleteDocAnnotation = (id, docId, annoId) =>
  api.delete('/doc-shares/' + pid(id) + '/docs/' + pid(docId, 'docId') + '/annotations/' + pid(annoId, 'annoId'))
// 「我的摘录」：跨文档聚合
export const listMyAnnotations = (limit = 100) =>
  api.get('/doc-shares/annotations/mine', { params: { limit } })
// inbox=true：收进待整理箱（快速收纳，先落个人空间并打标记，不占空间文档列表）
export const createShareDoc = (id, d, inbox = false) =>
  api.post('/doc-shares/' + pid(id) + '/docs' + (inbox ? '?inbox=true' : ''), d)

// ---- 待整理箱（2026-10-15）----
export const listInbox = () => api.get('/doc-shares/inbox/list')
export const inboxCount = () => api.get('/doc-shares/inbox/count')
export const archiveInboxDoc = (docId, targetShareId, title) =>
  api.post('/doc-shares/inbox/' + pid(docId, 'docId') + '/archive', { targetShareId, title })
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

// 图片 OCR 识别导入：图片经后端智谱视觉模型识别文字后生成协作文档，需写入权限
// persist=false 只识别不落库（移动端多页扫描合并预览用，避免每页遗留孤儿文档）
// timeout 必须单独放宽：全局 15s，而 OCR 走免费档（4.6V 挤爆降级 4.1V-Thinking）
// 实测单次调用 14~30s，15s 会被 axios 掐断 → 前端只能弹兜底「识别失败」
export const ocrImportShareDoc = (id, file, persist = true) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/doc-shares/' + pid(id) + '/docs/ocr' + (persist ? '' : '?persist=false'), fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120000,
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
// 删除某个历史版本（只移除快照，不影响文档当前内容）
export const deleteShareDocVersion = (shareId, docId, vid) =>
  api.delete('/doc-shares/' + pid(shareId, 'shareId') + '/docs/' + pid(docId, 'docId') + '/versions/' + pid(vid, 'vid'))

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
