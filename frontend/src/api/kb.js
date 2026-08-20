import api from './request'

export const search = (q, category, author) => {
  const params = { q: q || '' }
  if (category) params.category = category
  if (author) params.author = author
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

export const categories = () =>
  api.get('/docs/categories')

export const getComments = (docId) =>
  api.get('/docs/' + docId + '/comments')

export const addComment = (docId, content, parentId = null) =>
  api.post('/docs/' + docId + '/comments', parentId != null ? { content, parentId } : { content })

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

// ---- 点赞（需登录）----
// 切换点赞：返回 { liked: true/false, likes: 当前点赞数 }
export const toggleLike = (docId) =>
  api.post('/likes/' + docId)

// 我点过赞的文档 id 列表（用于标记 👍 填充态）
export const myLikeIds = () =>
  api.get('/likes/mine/ids')

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

// 我关注的人 / 我的粉丝（访客个人主页的关注流）
export const getUserFollowing = (id) =>
  api.get('/users/' + id + '/following')
export const getUserFollowers = (id) =>
  api.get('/users/' + id + '/followers')

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

export const listAnnouncementsAdmin = () =>
  api.get('/admin/announcements')

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
