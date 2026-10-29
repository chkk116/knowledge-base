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

// ---- 登录日志（仅管理员）----
// listLoginLogs({ username, success, start, end, page, size })
export const listLoginLogs = (params = {}) => api.get('/admin/login-logs', { params })

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

// ---- 部门成员 ----
// 批量把用户加入部门（原属其他部门的用户会被移动过来）
export const assignDepartmentMembers = (id, userIds) => api.post('/admin/departments/' + id + '/members', { userIds })
// 把单个用户移出部门
export const removeDepartmentMember = (id, userId) => api.delete('/admin/departments/' + id + '/members/' + userId)

// ---- 审核队列（仅管理员）----
export const listPending = () => api.get('/admin/docs/pending')
export const approveDoc = (id) => api.post('/admin/docs/' + id + '/approve')
export const rejectDoc = (id, reason) => api.post('/admin/docs/' + id + '/reject', { reason })

// 头像审核（方案 A：通过转正 / 驳回沿用旧头像）
export const listPendingAvatars = () => api.get('/admin/avatars/pending')
export const approveAvatar = (id) => api.post('/admin/avatars/' + id + '/approve')
export const rejectAvatar = (id) => api.post('/admin/avatars/' + id + '/reject')

// ---- 角色管理（仅管理员，需 USER_MANAGE）----
export const listRoles = () => api.get('/admin/roles')

// 各角色成员数 { roleId: 人数 }（走 kb_user_role 关联表统计；
// 不能用用户列表自己数——列表接口返回的实体不含 roleIds）
export const roleMembers = () => api.get('/admin/roles/members')
export const createRole = (d) => api.post('/admin/roles', d)
export const updateRole = (id, d) => api.put('/admin/roles/' + id, d)
export const deleteRole = (id) => api.delete('/admin/roles/' + id)

// ---- 部门管理（仅管理员，需 USER_MANAGE）----
// listDepartments() 返回扁平列表，前端按 parentId 自组装成树
export const listDepartments = () => api.get('/admin/departments')
export const createDepartment = (d) => api.post('/admin/departments', d)
export const updateDepartment = (id, d) => api.put('/admin/departments/' + id, d)
export const deleteDepartment = (id) => api.delete('/admin/departments/' + id)
// 拖拽改层级与排序：parentId 为 null 表示移到顶级；sortOrder 为在同级中的目标下标
export const moveDepartment = (id, parentId, sortOrder) =>
  api.put('/admin/departments/' + id + '/move', { parentId, sortOrder })

// ---- 用户角色 / 部门分配 ----
// 角色 / 部门在用户新建、编辑接口（createUser / updateUser）中通过 roleIds / departmentId 字段一并提交。
// 编辑表单预填时，先拉取该用户的角色 id 列表与部门 id（安全视图，不含密码）。
export const getUserDetail = (id) => api.get('/admin/users/' + id)

// 生成一次性密码重置链接（30 分钟有效）：管理员转交用户，用户自己设新密码
export const userResetLink = (id) => api.post('/admin/users/' + id + '/reset-link')

// ---- 检索测试（仅管理员）----
// 用与用户搜索完全相同的链路跑一次查询：searchTest({ q, category }) → { engine, meiliEnabled, tookMs, total, hits }
export const searchTest = (params) => api.get('/admin/search/test', { params })

// ---- AI 反馈与知识缺口（AI_MANAGE / 管理员）----
// 返回 { days, total, kbTotal, hit, miss, hitRate, positive, negative, topMissed, recentMissed, recentNegative }
export const aiFeedbackStats = (days = 7) => api.get('/admin/ai/feedback', { params: { days } })

// ---- 批量导入用户（USER_MANAGE）----// payload: { rows: [{username,displayName,email,password,role}], defaultPassword?, dryRun? }
// 返回 { dryRun,total,created,skipped,failed,results:[{line,username,status,message}] }
export const importUsers = (payload) => api.post('/admin/users/import', payload, { timeout: 120000 })

// ---- 主观题阅卷（EXAM_MANAGE）----
export const gradingPending = () => api.get('/admin/exams/grading/pending')
export const gradingDetail = (recordId) => api.get('/admin/exams/grading/' + recordId)
export const submitGrading = (recordId, items) => api.post('/admin/exams/grading/' + recordId, { items })
