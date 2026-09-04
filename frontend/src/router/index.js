import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../store/auth'
import { featureMap } from '../api/features'

const routes = [
  { path: '/', component: () => import('../views/Home.vue'), meta: { title: '知识库', maintenance: 'docs' } },
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true, title: '登录' } },
  { path: '/post/:id', component: () => import('../views/PostDetail.vue'), meta: { title: '文档详情', maintenance: 'docs' } },
  // 独立阅读窗口：用 window.open 在桌面弹出真实浏览器窗口，public 以免新窗口被守卫踢回登录
  { path: '/reader/:id', component: () => import('../views/ReaderWindow.vue'), meta: { public: true, title: '文章阅读', maintenance: 'docs' } },
  // 写文章：所有登录用户可用（含 wangEditor，懒加载后独立成 chunk，不进首屏）
  { path: '/write', component: () => import('../views/admin/PostEdit.vue'), meta: { title: '写文章', maintenance: 'docs' } },
  { path: '/write/edit/:id', component: () => import('../views/admin/PostEdit.vue'), meta: { title: '编辑文章', maintenance: 'docs' } },
  { path: '/my/posts', component: () => import('../views/MyPosts.vue'), meta: { title: '我的帖子', maintenance: 'docs' } },
  // 回收站 / 软删除：本人（管理员可见全部）移入回收站的文档，可恢复或彻底删除
  { path: '/recycle', component: () => import('../views/RecycleBin.vue'), meta: { title: '回收站', maintenance: 'docs' } },
  // 文档版本历史：每次保存自动留档，可预览 / 回滚
  { path: '/post/:id/versions', component: () => import('../views/DocVersionHistory.vue'), meta: { title: '版本历史', maintenance: 'docs' } },
  { path: '/favorites', component: () => import('../views/Favorites.vue'), meta: { title: '我的收藏', maintenance: 'profile' } },
  // 我的订阅：订阅的文档更新时收到站内通知
  { path: '/subscriptions', component: () => import('../views/Subscriptions.vue'), meta: { title: '我的订阅', maintenance: 'docs' } },
  { path: '/history', component: () => import('../views/History.vue'), meta: { title: '浏览历史', maintenance: 'profile' } },
  { path: '/profile', component: () => import('../views/Profile.vue'), meta: { title: '个人资料', maintenance: 'profile' } },
  { path: '/ranking', component: () => import('../views/Ranking.vue'), meta: { title: '积分排行榜' } },
  // 徽章 / 勋章墙：基于用户实时统计派生（积分 / 文章 / 获赞 / 粉丝等），无需落库
  { path: '/badges', component: () => import('../views/Badges.vue'), meta: { title: '我的勋章', maintenance: 'profile' } },
  { path: '/ai/chat', component: () => import('../views/AiChat.vue'), meta: { title: 'AI 问答', maintenance: 'ai_chat' } },
  // 培训体系：学员侧浏览 / 学习
  { path: '/study/courses', component: () => import('../views/CourseList.vue'), meta: { title: '培训课程', maintenance: 'courses' } },
  { path: '/study/courses/:id', component: () => import('../views/CourseDetail.vue'), meta: { title: '课程详情', maintenance: 'courses' } },
  { path: '/study/courses/:courseId/chapters/:chapterId', component: () => import('../views/ChapterDetail.vue'), meta: { title: '章节学习', maintenance: 'courses' } },
  // 考试测评：学员侧列表 / 答题 / 成绩
  { path: '/exams', component: () => import('../views/ExamList.vue'), meta: { title: '在线考试', maintenance: 'exams' } },
  // 全新答题首页（蓝白风，参照 Farm2）：/quiz 入口
  { path: '/quiz', component: () => import('../views/QuizHome.vue'), meta: { title: '答题闯关', maintenance: 'quiz' } },
  { path: '/exams/:id/take', component: () => import('../views/ExamTake.vue'), meta: { title: '开始答题', maintenance: 'exams' } },
  { path: '/exams/records/:id', component: () => import('../views/ExamResult.vue'), meta: { title: '考试成绩', maintenance: 'exams' } },
  // 我的成绩：学员侧考试记录列表（/exams 列表页「我的成绩」按钮入口）
  { path: '/exams/mine', component: () => import('../views/ExamMine.vue'), meta: { title: '我的成绩', maintenance: 'exams' } },
  // 共享文档：协作空间
  { path: '/doc-shares', component: () => import('../views/DocShareList.vue'), meta: { title: '共享文档', maintenance: 'doc_shares' } },
  { path: '/doc-shares/:id', component: () => import('../views/DocShareWorkspace.vue'), meta: { title: '协作空间', maintenance: 'doc_shares' } },
  // 积分明细：个人资料「查看完整明细」跳转，带分页 + 类型筛选的独立页
  { path: '/points/log', component: () => import('../views/PointsLog.vue'), meta: { title: '积分明细', maintenance: 'points' } },
  { path: '/notifications', component: () => import('../views/Notifications.vue'), meta: { title: '通知' } },
  // 搜索结果独立页（关键词高亮 + 空状态）
  { path: '/search', component: () => import('../views/SearchResults.vue'), meta: { title: '搜索结果', maintenance: 'search' } },
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
  { path: '/admin/courses', component: () => import('../views/admin/CourseManage.vue'), meta: { admin: true, perm: 'COURSE_MANAGE', title: '培训管理' } },
  { path: '/admin/exams', component: () => import('../views/admin/ExamManage.vue'), meta: { admin: true, perm: 'EXAM_MANAGE', title: '考试管理' } },
  { path: '/admin/doc-shares', component: () => import('../views/admin/DocShareManage.vue'), meta: { admin: true, perm: 'SHARE_VIEW', title: '共享文档管理' } },
  { path: '/admin/maintain', component: () => import('../views/admin/CoreMaintain.vue'), meta: { admin: true, perm: 'CORE_MAINTAIN', title: '核心维护' } },
  { path: '/admin/watermark', component: () => import('../views/admin/WatermarkSettings.vue'), meta: { admin: true, perm: 'CORE_MAINTAIN', title: '水印设置' } },
  { path: '/admin/banned-words', component: () => import('../views/admin/BannedWordsManage.vue'), meta: { admin: true, perm: 'BANNED_WORDS_MANAGE', title: '违禁词管理' } },
  // 未开放 / 建设中功能统一提示页：?feature=功能名
  { path: '/coming-soon', component: () => import('../views/ComingSoon.vue'), meta: { title: '敬请期待' } },
  // 核心维护 · 临时维护界面：功能关闭且 closeMode=MAINTENANCE 时进入
  { path: '/maintenance', component: () => import('../views/Maintenance.vue'), meta: { title: '维护中' } },
  // 通配兜底：任何未定义的路径都提示「该区域尚未开放」，避免空白 / 404
  { path: '/:pathMatch(.*)*', component: () => import('../views/ComingSoon.vue'), meta: { title: '页面未开放' } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

// 功能开关缓存：featureKey → enabled，由首次进入受维护控制页时拉取
let featureFlags = null
let featureFlagsLoading = null
function loadFeatureFlags() {
  if (featureFlags) return Promise.resolve(featureFlags)
  if (!featureFlagsLoading) {
    featureFlagsLoading = featureMap()
      .then(({ data }) => { featureFlags = data || {}; return featureFlags })
      .catch(() => { featureFlags = {}; return featureFlags })
      .finally(() => { featureFlagsLoading = null })
  }
  return featureFlagsLoading
}
export function resetFeatureFlags() { featureFlags = null; featureFlagsLoading = null }

// 会话初始化：直接用本地令牌恢复 user（login 时已经写过 localUser，或从后端 /me 拉取）。
// 关键：直接访问 /admin 链接时，路由守卫可能在 App.onMounted 的 fetchMe 之前就执行，
// 那时 auth.user 为 null → hasPerm 全部误判为无权限 → 管理员也会被踢回主页。
// 所以守卫里用一次性 latch 保证 user 已就绪再判断权限。
let authReady = null
function ensureAuth() {
  const auth = useAuthStore()
  if (auth.user) return Promise.resolve()
  if (!authReady) authReady = auth.fetchMe().finally(() => { authReady = null })
  return authReady
}

// 路由守卫：未登录跳登录；后台管理页按 RBAC 权限码放行（ADMIN 隐式拥有全部）
router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true
  // 先确保本地令牌已还原成 user（首次直接访问后台页时守卫早于 App.onMounted 执行）
  await ensureAuth()
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
  // 核心维护：功能开关关闭时按关闭模式处理
  if (to.meta.maintenance) {
    const flags = await loadFeatureFlags()
    const st = flags[to.meta.maintenance]
    if (st && st.enabled === false) {
      // HIDDEN（默认）：隐藏入口，直接访问跳「敬请期待」
      // MAINTENANCE：入口保留，访问展示「维护中」界面
      if (st.closeMode === 'MAINTENANCE') {
        return {
          path: '/maintenance',
          query: {
            feature: to.meta.title || '该功能',
            msg: st.maintenanceMsg || '',
            restore: st.expectedRestoreAt || '',
          },
        }
      }
      return { path: '/coming-soon', query: { feature: to.meta.title || '该功能' } }
    }
  }
  return true
})

// 空闲时仅预加载登录后的着陆页（首页）chunk，提升登录后跳转的即时感。
// 注意：不能全量预载所有路由 —— 全部 import 会把 Tiptap/Yjs、wangEditor、markdown 等重型依赖
// 全部拉进 JS 堆（浏览器标签页内存从 ~120MB 涨到 200MB+ 的主因），未访问的页面代码
// 也会常驻内存。文章详情（PostDetail，含 markdown/highlight）等走正常懒加载，
// 内网点开时 chunk 下载几乎无感，避免在登录页就提前拉取无关的文章代码。
const PRELOAD_PATHS = ['/']
const preloadRoutes = () => routes
  .filter((r) => PRELOAD_PATHS.includes(r.path))
  .forEach((r) => { if (typeof r.component === 'function') r.component() })
if (window.requestIdleCallback) window.requestIdleCallback(preloadRoutes, { timeout: 4000 })
else setTimeout(preloadRoutes, 2000)

export default router
