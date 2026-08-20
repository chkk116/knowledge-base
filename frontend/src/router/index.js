import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../store/auth'

const routes = [
  { path: '/', component: () => import('../views/Home.vue'), meta: { title: '知识库' } },
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true, title: '登录' } },
  { path: '/post/:id', component: () => import('../views/PostDetail.vue'), meta: { title: '文档详情' } },
  // 独立阅读窗口：用 window.open 在桌面弹出真实浏览器窗口，public 以免新窗口被守卫踢回登录
  { path: '/reader/:id', component: () => import('../views/ReaderWindow.vue'), meta: { public: true, title: '文章阅读' } },
  // 写文章：所有登录用户可用（含 wangEditor，懒加载后独立成 chunk，不进首屏）
  { path: '/write', component: () => import('../views/admin/PostEdit.vue'), meta: { title: '写文章' } },
  { path: '/write/edit/:id', component: () => import('../views/admin/PostEdit.vue'), meta: { title: '编辑文章' } },
  { path: '/my/posts', component: () => import('../views/MyPosts.vue'), meta: { title: '我的帖子' } },
  { path: '/favorites', component: () => import('../views/Favorites.vue'), meta: { title: '我的收藏' } },
  { path: '/history', component: () => import('../views/History.vue'), meta: { title: '浏览历史' } },
  { path: '/profile', component: () => import('../views/Profile.vue'), meta: { title: '个人资料' } },
  { path: '/ranking', component: () => import('../views/Ranking.vue'), meta: { title: '积分排行榜' } },
  // 积分明细：个人资料「查看完整明细」跳转，带分页 + 类型筛选的独立页
  { path: '/points/log', component: () => import('../views/PointsLog.vue'), meta: { title: '积分明细' } },
  { path: '/notifications', component: () => import('../views/Notifications.vue'), meta: { title: '通知' } },
  // 搜索结果独立页（关键词高亮 + 空状态）
  { path: '/search', component: () => import('../views/SearchResults.vue'), meta: { title: '搜索结果' } },
  // 他人视角个人主页（访客）：展示资料 / 文章 / 关注的人 / 粉丝
  { path: '/user/:id', component: () => import('../views/UserProfile.vue'), meta: { title: '用户主页' } },
  // 管理后台：RBAC 细粒度权限（ADMIN 隐式拥有全部，普通用户需对应权限码）
  { path: '/admin/users', component: () => import('../views/admin/UserManage.vue'), meta: { admin: true, perm: 'USER_MANAGE', title: '用户管理' } },
  { path: '/admin/docs', component: () => import('../views/admin/DocManage.vue'), meta: { admin: true, perm: 'DOC_REVIEW', title: '帖子管理' } },
  { path: '/admin/stats', component: () => import('../views/admin/Dashboard.vue'), meta: { admin: true, perm: 'STATS_VIEW', title: '数据看板' } },
  { path: '/admin/taxonomy', component: () => import('../views/admin/TaxonomyManage.vue'), meta: { admin: true, perm: 'TAXONOMY_MANAGE', title: '分类标签' } },
  { path: '/admin/announcements', component: () => import('../views/admin/AnnouncementManage.vue'), meta: { admin: true, perm: 'ANNOUNCEMENT_MANAGE', title: '公告管理' } },
  { path: '/admin/blacklist', component: () => import('../views/admin/Blacklist.vue'), meta: { admin: true, perm: 'BLACKLIST_MANAGE', title: '黑名单' } },
  { path: '/admin/audit', component: () => import('../views/admin/AuditLog.vue'), meta: { adminOnly: true, title: '操作审计' } },
  { path: '/admin/files', component: () => import('../views/admin/AttachmentManage.vue'), meta: { adminOnly: true, title: '附件管理' } },
  // 未开放 / 建设中功能统一提示页：?feature=功能名
  { path: '/coming-soon', component: () => import('../views/ComingSoon.vue'), meta: { title: '敬请期待' } },
  // 通配兜底：任何未定义的路径都提示「该区域尚未开放」，避免空白 / 404
  { path: '/:pathMatch(.*)*', component: () => import('../views/ComingSoon.vue'), meta: { title: '页面未开放' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 路由守卫：未登录跳登录；后台管理页按 RBAC 权限码放行（ADMIN 隐式拥有全部）
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true
  if (!auth.loggedIn) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 后台页：要求对应权限码（hasPerm 已包含 ADMIN / 通配 * 的判断）
  if (to.meta.admin && to.meta.perm && !auth.hasPerm(to.meta.perm)) {
    return { path: '/' }
  }
  // 仅管理员可见的页（操作审计 / 附件管理）
  if (to.meta.adminOnly && !auth.isAdmin) {
    return { path: '/' }
  }
  return true
})

// 空闲时预加载所有懒加载路由 chunk：首屏稳定后趁浏览器空闲逐个 import，
// 之后切换任何页面（尤其管理后台公告页等）不再有「chunk 下载 + 解析」的可感知卡顿。
// 仅在浏览器空闲时执行，不阻塞交互，对运行时性能无影响。
const preloadRoutes = () => routes.forEach((r) => { if (typeof r.component === 'function') r.component() })
if (window.requestIdleCallback) window.requestIdleCallback(preloadRoutes, { timeout: 4000 })
else setTimeout(preloadRoutes, 2000)

export default router
