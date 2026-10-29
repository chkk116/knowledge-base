import api from './request'

export const search = (q, category, author, opts = {}) => {
  const params = { q: q || '' }
  if (category) params.category = category
  if (author) params.author = author
  // 排序 / 时间范围 / 标签：不传即默认相关度 + 不限时间
  if (opts.sort && opts.sort !== 'relevance') params.sort = opts.sort
  if (opts.days) params.days = opts.days
  if (opts.tags) params.tags = opts.tags
  return api.get('/search', { params })
}

export const listDocs = (page = 0, size = 15, sort, tag, category, ownerId) => {
  const params = { page, size }
  if (sort) params.sort = sort
  if (tag) params.tag = tag
  if (category) params.category = category
  if (ownerId) params.ownerId = ownerId
  return api.get('/docs', { params })
}

// 标签聚合（去重 + 计数），用于标签云/筛选
export const allTags = () =>
  api.get('/docs/tags')

export const getDoc = (id) =>
  api.get('/docs/' + id)

// 全局文档树（仅已发布，children 递归）：供「父文档」选择器与层级浏览使用
export const docTree = () => api.get('/docs/tree')

// 文档面包屑：从最顶层祖先到自身（含自身）
export const docBreadcrumb = (id) => api.get('/docs/' + id + '/breadcrumb')

// 移动文档：parentId 传 null 表示移到该空间根级
export const moveDoc = (id, parentId, sortOrder) =>
  api.post('/docs/' + id + '/move', { parentId, sortOrder })

// 违禁词管理（需 BANNED_WORDS_MANAGE 或 ADMIN）
// 词库：可分组建库 + 启停「选择加载」
export const listBannedLibraries = () => api.get('/admin/banned-words/libraries')
export const createBannedLibrary = (name, description) =>
  api.post('/admin/banned-words/libraries', { name, description })
export const updateBannedLibrary = (id, payload) =>
  api.put('/admin/banned-words/libraries/' + id, payload)
export const deleteBannedLibrary = (id) =>
  api.delete('/admin/banned-words/libraries/' + id)

// 词条：服务端分页（libraryId 可选，不传返回全部）
export const listBannedWords = (libraryId, page = 0, size = 15) => {
  const params = { page, size }
  if (libraryId != null) params.libraryId = libraryId
  return api.get('/admin/banned-words', { params })
}
export const addBannedWords = (libraryId, words) =>
  api.post('/admin/banned-words', { libraryId, words })
export const deleteBannedWord = (id) => api.delete('/admin/banned-words/' + id)
// 离线词库导入：向指定词库批量导入文本 / 已下载的词库文件内容（每行一个词）
export const importBannedWords = (libraryId, content) =>
  api.post('/admin/banned-words/import', { libraryId, content })

// 审核工具条：一键违禁词检查（对正文纯文本匹配，返回命中词）
export const checkDocBanned = (id) => api.post('/docs/' + id + '/check-banned')

export const categories = () =>
  api.get('/docs/categories')

export const getComments = (docId) =>
  api.get('/docs/' + docId + '/comments')

export const addComment = (docId, content, parentId = null) =>
  api.post('/docs/' + docId + '/comments', parentId != null ? { content, parentId } : { content })

// ===== 通用评论（跨对象：DOC 帖子 / CHAPTER 章节）=====
export const getCommentsGeneric = (targetType, targetId) =>
  api.get('/comments', { params: { targetType, targetId } })

export const addCommentGeneric = (targetType, targetId, content, parentId = null) =>
  api.post('/comments', parentId != null ? { targetType, targetId, content, parentId } : { targetType, targetId, content })

// ===== 个人学习笔记（按章节，私有）=====
export const getChapterNote = (chapterId) =>
  api.get('/chapters/' + chapterId + '/note')

export const saveChapterNote = (chapterId, content) =>
  api.put('/chapters/' + chapterId + '/note', { content })

export const deleteComment = (commentId) =>
  api.delete('/comments/' + commentId)

// 我点过赞的评论 id 列表（用于标记点赞填充态）
export const myCommentLikes = () =>
  api.get('/comments/my-likes')

// 切换评论点赞：返回 { liked, likes }
export const toggleCommentLike = (commentId) =>
  api.post('/comments/' + commentId + '/like')

// 我点赞过的文档列表（个人资料页）
export const myLikedDocs = () =>
  api.get('/likes/mine/docs')

// 我评论过的文档列表（个人资料页）
export const myCommentedDocs = () =>
  api.get('/comments/mine/docs')

// ---- 普通用户写帖（登录可用）----
export const listMyDocs = () =>
  api.get('/docs/mine')

export const createMyDoc = (d) =>
  api.post('/docs', d)

export const updateMyDoc = (id, d) =>
  api.put('/docs/' + id, d)

export const deleteMyDoc = (id) =>
  api.delete('/docs/' + id)

// 文档导入：把 .md/.txt/.html/.docx/.pdf 直接转为帖子（后端解析），返回创建好的文档
export const importDoc = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return api.post('/docs/import', fd)
}

// 提交审核（草稿 → 待审核）
export const submitReview = (id) =>
  api.post('/docs/' + id + '/submit')

// 撤回审核（待审核 → 草稿，审核前可拉回）
export const withdrawReview = (id) =>
  api.post('/docs/' + id + '/withdraw')

// ---- 收藏（需登录）----
// 切换收藏：返回 { favorited: true/false }
export const toggleFavorite = (docId) =>
  api.post('/favorites/' + docId)

// 我收藏的文档列表（仅已发布）
export const myFavorites = () =>
  api.get('/favorites/mine')

// 我收藏的文档 id 列表（用于标记卡片星标）
export const myFavoriteIds = () =>
  api.get('/favorites/mine/ids')

// 收藏分组：设置/取消（group 传空串 = 移出分组）
export const setFavoriteGroup = (docId, group) => api.put('/favorites/' + docId + '/group', { group })
// 分组列表（含数量）与 docId→分组映射
export const favoriteGroups = () => api.get('/favorites/groups')
export const favoriteGroupMap = () => api.get('/favorites/groups/map')

// ---- 点赞（需登录）----
// 切换点赞：返回 { liked: true/false, likes: 当前点赞数 }
export const toggleLike = (docId) =>
  api.post('/likes/' + docId)

// 我点过赞的文档 id 列表（用于标记 👍 填充态）
export const myLikeIds = () =>
  api.get('/likes/mine/ids')

// 通用点赞（跨对象类型：DOC 帖子 / SDOC 空间文档），返回 { liked, likes }
export const toggleLikeGeneric = (targetType, targetId, ownerId) =>
  api.post('/likes', { targetType, targetId, ownerId })

// 某对象当前点赞数（进入页面取初始值）
export const likeCountGeneric = (targetType, targetId) =>
  api.get('/likes/count', { params: { targetType, targetId } })

// 我点过赞的某类型对象 id 列表（用于标记 👍 填充态）
export const myLikeIdsGeneric = (targetType) =>
  api.get('/likes/mine/ids', { params: { targetType } })

// ---- 通知（站内信 / @提醒）----
export const notificationList = () =>
  api.get('/notifications')

export const notificationUnread = () =>
  api.get('/notifications/unread')

export const notificationRead = (id) =>
  api.post('/notifications/' + id + '/read')

export const notificationReadAll = () =>
  api.post('/notifications/read-all')

/** 删除单条通知 */
export const notificationDelete = (id) =>
  api.delete('/notifications/' + id)

/** 批量删除通知（ids: number[]） */
export const notificationDeleteBatch = (ids) =>
  api.delete('/notifications/batch', { data: { ids } })

/** 清空全部通知 */
export const notificationDeleteAll = () =>
  api.delete('/notifications/all')

// 修改密码（需登录，校验原密码）
export const changePassword = (oldPassword, newPassword) =>
  api.post('/auth/change-password', { oldPassword, newPassword })

// ---- 作者卡片 / 关注 ----
export const getUserCard = (id) =>
  api.get('/users/' + id + '/card')
export const followUser = (id) =>
  api.post('/users/' + id + '/follow')
export const unfollowUser = (id) =>
  api.delete('/users/' + id + '/follow')

// 作者已发布文章（分页），供侧边栏「他的文章」列表
export const getUserArticles = (id, page = 0, size = 5) =>
  api.get('/users/' + id + '/articles', { params: { page, size } })

// 我关注的人 / 我的粉丝（访客个人主页的关注流，分页）
export const getUserFollowing = (id, page = 0, size = 50) =>
  api.get('/users/' + id + '/following', { params: { page, size } })
export const getUserFollowers = (id, page = 0, size = 50) =>
  api.get('/users/' + id + '/followers', { params: { page, size } })

// ---- 徽章 / 勋章体系 ----
// 当前登录用户的徽章墙：{ badges:[{id,name,desc,icon,color,tier,metric,threshold,current,earned,progress}], earned, total }
export const listBadges = () =>
  api.get('/badges')
// 某用户已获得的徽章列表（公开，用于主页 / 资料页展示）
export const userBadges = (id) =>
  api.get('/badges/users/' + id)

// 佩戴 / 卸下徽章：badgeId 为空串表示卸下
export const wearBadge = (badgeId) =>
  api.post('/badges/wear', { badgeId: badgeId || '' })

// ---- 分类树 / 标签体系（规范表，管理后台与发帖选择共用）----
// 分类层级树（{ id, name, parentId, children }）
export const getCategoryTree = () =>
  api.get('/categories')

export const createCategory = (payload) =>
  api.post('/admin/categories', payload)

export const updateCategory = (id, payload) =>
  api.put('/admin/categories/' + id, payload)

export const deleteCategory = (id) =>
  api.delete('/admin/categories/' + id)

// 标签列表（规范，带文档计数 { id, name, count }）
export const listTags = () =>
  api.get('/tags')

export const createTag = (name) =>
  api.post('/admin/tags', { name })

export const deleteTag = (id) =>
  api.delete('/admin/tags/' + id)

// ---- 公告栏 ----
export const listAnnouncements = () =>
  api.get('/announcements')

// 首页轻量数据概览：{ totalDocs, weekNew, members, pendingReview?(管理员/STATS_VIEW) }
export const homeOverview = () =>
  api.get('/docs/overview')

export const listAnnouncementsAdmin = (params) =>
  api.get('/admin/announcements', { params })

export const createAnnouncement = (payload) =>
  api.post('/admin/announcements', payload)

export const updateAnnouncement = (id, payload) =>
  api.put('/admin/announcements/' + id, payload)

export const deleteAnnouncement = (id) =>
  api.delete('/admin/announcements/' + id)

export const toggleAnnouncementPin = (id) =>
  api.post('/admin/announcements/' + id + '/pin')

export const toggleAnnouncementEnabled = (id) =>
  api.post('/admin/announcements/' + id + '/enable')

// ---- 文档版本历史 ----
export const listDocVersions = (id) =>
  api.get('/docs/' + id + '/versions')

export const getDocVersion = (id, vid) =>
  api.get('/docs/' + id + '/versions/' + vid)

export const restoreDocVersion = (id, vid) =>
  api.post('/docs/' + id + '/versions/' + vid + '/restore')

// ---- 回收站 / 软删除 ----
// 我的回收站（仅自己移入回收站的文档）
export const listRecycle = () =>
  api.get('/docs/recycle')

// 从回收站恢复
export const restoreDoc = (id) =>
  api.post('/docs/' + id + '/restore')

// 彻底删除（物理删除）
export const purgeDoc = (id) =>
  api.delete('/docs/' + id + '/purge')

// 管理员：查看全部回收站文档
export const listRecycleAdmin = () =>
  api.get('/admin/docs/recycle')

// 管理员：恢复任意回收站文档
export const restoreDocAdmin = (id) =>
  api.post('/admin/docs/' + id + '/restore')

// 管理员：彻底删除任意回收站文档
export const purgeDocAdmin = (id) =>
  api.delete('/admin/docs/' + id + '/purge')

// 导出文档：返回 Blob（Word .doc）。PDF 由前端 window.print 实现，不走该接口
export const exportDoc = (id, format = 'word') =>
  api.get('/docs/' + id + '/export', { params: { format }, responseType: 'blob' })

// 相关文档推荐：同分类 + 标签重叠打分，不足时用高浏览文档补位
export const getRelatedDocs = (id, limit = 6) =>
  api.get('/docs/' + id + '/related', { params: { limit } })

// 文档订阅：订阅后文档更新会收到站内通知（幂等，重复订阅无副作用）
export const subscribeDoc = (id) =>
  api.post('/docs/' + id + '/subscribe')

// 取消文档订阅
export const unsubscribeDoc = (id) =>
  api.delete('/docs/' + id + '/subscribe')

// 查询当前用户对某文档的订阅状态：{ subscribed, count }
export const getDocSubscription = (id) =>
  api.get('/docs/' + id + '/subscribed')

// 我的订阅列表：含文档标题，文档已删除时 deleted=true
export const listMySubscriptions = () =>
  api.get('/docs/subscriptions')
