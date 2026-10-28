import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../store/auth'
import { featureMap } from '../api/features'

const routes = [
  { path: '/', component: () => import('../views/Home.vue'), meta: { title: '知识库', maintenance: 'docs' } },
  // 隐私政策：公网免登录可读（合规要求，登录页也要能打开）
  { path: '/privacy', component: () => import('../views/Privacy.vue'), meta: { public: true, title: '隐私政策' } },
  { path: '/disclaimer', component: () => import('../views/Disclaimer.vue'), meta: { public: true, title: '免责声明' } },
  // 开发调试页：生产构建不注册（免登录 public 路由不该出现在生产站点，外部复审 2026-10-09；
  // 外部审查 2026-10-11：补齐 mindmap/sheet/sheet-collab/vueflow 四个漏网的调试页）
  ...(import.meta.env.DEV ? [
    { path: '/dev/flowchart-test', component: () => import('../views/DevFlowchartTest.vue'), meta: { public: true, title: '流程图测试' } },
    { path: '/dev/mindmap-test', component: () => import('../views/DevMindmapTest.vue'), meta: { public: true, title: '导图测试' } },
    { path: '/dev/sheet-test', component: () => import('../views/DevSheetTest.vue'), meta: { public: true, title: '电子表格测试' } },
    { path: '/dev/sheet-collab-proto', component: () => import('../views/DevSheetCollabProto.vue'), meta: { public: true, title: '表格协同原型' } },
    { path: '/dev/vueflow-test', component: () => import('../views/DevVueFlowTest.vue'), meta: { public: true, title: 'Vue Flow 原型' } },
  ] : []),
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true, title: '登录' } },
  // 文档外部分享：免登录只读页（令牌换内容，服务端消毒 + 可选密码/有效期）
  { path: '/s/:token', component: () => import('../views/PublicDoc.vue'), meta: { public: true, title: '共享文档' } },
  // 单点登录回调落地：带 token 跳回本站，前端建立会话后跳首页
  { path: '/sso', component: () => import('../views/SsoCallback.vue'), meta: { public: true, title: '登录中' } },
  { path: '/post/:id', component: () => import('../views/PostDetail.vue'), meta: { title: '文档详情', maintenance: 'docs' } },
  // 独立阅读窗口：window.open 弹出真实浏览器窗口。同源窗口共享 localStorage，守卫可正常校验登录态；
  // 此前 public: true 让匿名者可凭文章 id 直接读全文（2026-10-13 审查 H7，已收回）
  { path: '/reader/:id', component: () => import('../views/ReaderWindow.vue'), meta: { title: '文章阅读', maintenance: 'docs' } },
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
  // 偏好设置：布局 / 外观 / 快捷键 / 通用（2026-10-22，存本机 localStorage）
  { path: '/preferences', component: () => import('../views/Preferences.vue'), meta: { title: '偏好设置' } },
  // 我的文件：文件中心（与移动端 MobileFiles 共用一套接口）
  { path: '/files', component: () => import('../views/FileCenter.vue'), meta: { title: '我的文件', maintenance: 'profile' } },
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
  // 模拟练习：答一题判一题，不落成绩（2026-10-21）
  { path: '/exams/practice', component: () => import('../views/ExamPractice.vue'), meta: { title: '练习', maintenance: 'exams' } },
  // 错题本：练习流水派生（必须排在 /exams/:id 之类动态路由之前）
  { path: '/exams/wrong', component: () => import('../views/PracticeWrong.vue'), meta: { title: '错题本', maintenance: 'exams' } },
  // 全新答题首页（蓝白风，参照 Farm2）：/quiz 入口
  { path: '/quiz', component: () => import('../views/QuizHome.vue'), meta: { title: '答题闯关', maintenance: 'quiz' } },
  { path: '/exams/:id/take', component: () => import('../views/ExamTake.vue'), meta: { title: '开始答题', maintenance: 'exams' } },
  { path: '/exams/records/:id', component: () => import('../views/ExamResult.vue'), meta: { title: '考试成绩', maintenance: 'exams' } },
  // 我的成绩：学员侧考试记录列表（/exams 列表页「我的成绩」按钮入口）
  { path: '/exams/mine', component: () => import('../views/ExamMine.vue'), meta: { title: '我的成绩', maintenance: 'exams' } },
  // 共享文档：协作空间
  { path: '/doc-shares', component: () => import('../views/DocShareList.vue'), meta: { title: '共享文档', maintenance: 'doc_shares' } },
  // 全部文档：跨空间汇总 + 标签/空间/类型/时间筛选（必须排在 /doc-shares/:id 之前，否则 all 会被当成空间 id）
  { path: '/doc-shares/all', component: () => import('../views/AllDocs.vue'), meta: { title: '全部文档', maintenance: 'doc_shares' } },
  { path: '/doc-shares/import', component: () => import('../views/DocShareImport.vue'), meta: { title: '导入文档', maintenance: 'doc_shares' } },
  { path: '/doc-shares/graph', component: () => import('../views/DocGraph.vue'), meta: { title: '知识图谱', maintenance: 'doc_shares' } },
  { path: '/doc-shares/:id', component: () => import('../views/DocShareWorkspace.vue'), meta: { title: '协作空间', maintenance: 'doc_shares' } },
  // 文档级深链：/doc-shares/12/docs/34 统一收敛到 ?doc= 形式（工作区唯一canonical地址，便于复制分享单篇文档）
  { path: '/doc-shares/:id/docs/:docId', redirect: to => `/doc-shares/${to.params.id}?doc=${to.params.docId}` },
  // 积分明细：个人资料「查看完整明细」跳转，带分页 + 类型筛选的独立页
  { path: '/points/log', component: () => import('../views/PointsLog.vue'), meta: { title: '积分明细', maintenance: 'points' } },
  { path: '/notifications', component: () => import('../views/Notifications.vue'), meta: { title: '通知' } },
  // 搜索结果独立页（关键词高亮 + 空状态）
  { path: '/search', component: () => import('../views/SearchResults.vue'), meta: { title: '搜索结果', maintenance: 'search' } },
  // 他人视角个人主页（访客）：展示资料 / 文章 / 关注的人 / 粉丝
  { path: '/user/:id', component: () => import('../views/UserProfile.vue'), meta: { title: '用户主页' } },
  // 管理后台：RBAC 细粒度权限（ADMIN 隐式拥有全部，普通用户需对应权限码）
  { path: '/admin/users', component: () => import('../views/admin/UserManage.vue'), meta: { admin: true, perm: 'USER_MANAGE', title: '用户管理' } },
  { path: '/admin/roles', component: () => import('../views/admin/RoleManage.vue'), meta: { admin: true, perm: 'USER_MANAGE', title: '角色管理' } },
  { path: '/admin/departments', component: () => import('../views/admin/DepartmentManage.vue'), meta: { admin: true, perm: 'USER_MANAGE', title: '部门管理' } },
  { path: '/admin/docs', component: () => import('../views/admin/DocManage.vue'), meta: { admin: true, perm: 'POST_MANAGE', title: '帖子管理' } },
  { path: '/admin/review', component: () => import('../views/admin/ReviewQueue.vue'), meta: { admin: true, perm: 'DOC_REVIEW', title: '文章审核' } },
  { path: '/admin/stats', component: () => import('../views/admin/Dashboard.vue'), meta: { admin: true, perm: 'STATS_VIEW', title: '数据看板' } },
  { path: '/admin/taxonomy', component: () => import('../views/admin/TaxonomyManage.vue'), meta: { admin: true, perm: 'TAXONOMY_MANAGE', title: '分类标签' } },
  { path: '/admin/announcements', component: () => import('../views/admin/AnnouncementManage.vue'), meta: { admin: true, perm: 'ANNOUNCEMENT_MANAGE', title: '公告管理' } },
  { path: '/admin/blacklist', component: () => import('../views/admin/Blacklist.vue'), meta: { admin: true, perm: 'BLACKLIST_MANAGE', title: '黑名单' } },
  { path: '/admin/audit', component: () => import('../views/admin/AuditLog.vue'), meta: { adminOnly: true, anyPerm: ['AUDIT_VIEW'], title: '操作审计' } },
  { path: '/admin/login-logs', component: () => import('../views/admin/LoginLog.vue'), meta: { adminOnly: true, anyPerm: ['LOGIN_LOG_VIEW'], title: '登录日志' } },
  { path: '/admin/files', component: () => import('../views/admin/AttachmentManage.vue'), meta: { adminOnly: true, title: '附件管理' } },
  { path: '/admin/courses', component: () => import('../views/admin/CourseManage.vue'), meta: { admin: true, perm: 'COURSE_MANAGE', title: '培训管理' } },
  { path: '/admin/exams', component: () => import('../views/admin/ExamManage.vue'), meta: { admin: true, perm: 'EXAM_MANAGE', title: '考试管理' } },
  { path: '/admin/doc-shares', component: () => import('../views/admin/DocShareManage.vue'), meta: { admin: true, perm: 'SHARE_VIEW', title: '共享文档管理' } },
  { path: '/admin/assets', component: () => import('../views/admin/AssetManage.vue'), meta: { admin: true, perm: 'ASSET_MANAGE', title: '资产管理' } },
  { path: '/admin/maintain', component: () => import('../views/admin/CoreMaintain.vue'), meta: { admin: true, perm: 'SITE_FEATURE_MANAGE|CORE_MAINTAIN', title: '功能开关' } },
  { path: '/admin/watermark', component: () => import('../views/admin/WatermarkSettings.vue'), meta: { admin: true, perm: 'SITE_SETTINGS_MANAGE|CORE_MAINTAIN', title: '水印设置' } },
  { path: '/admin/notify-webhook', component: () => import('../views/admin/NotifyWebhookSettings.vue'), meta: { admin: true, perm: 'SITE_SETTINGS_MANAGE|CORE_MAINTAIN', title: '通知外发' } },
  { path: '/admin/system', component: () => import('../views/admin/SystemStatus.vue'), meta: { admin: true, perm: 'SYSTEM_STATUS_VIEW|CORE_MAINTAIN', title: '系统状态' } },
  { path: '/admin/sso-ldap', component: () => import('../views/admin/SsoSettings.vue'), meta: { admin: true, perm: 'SSO_MANAGE|CORE_MAINTAIN', title: '企业登录' } },
  { path: '/admin/login-qr', component: () => import('../views/admin/LoginQrSettings.vue'), meta: { admin: true, perm: 'SITE_SETTINGS_MANAGE|CORE_MAINTAIN', title: '登录页二维码' } },
  { path: '/admin/appearance', component: () => import('../views/admin/AppearanceSettings.vue'), meta: { admin: true, perm: 'SITE_SETTINGS_MANAGE|CORE_MAINTAIN', title: '外观设置' } },
  { path: '/admin/site-footer', component: () => import('../views/admin/SiteFooterSettings.vue'), meta: { admin: true, perm: 'SITE_SETTINGS_MANAGE|CORE_MAINTAIN', title: '页脚设置' } },
  { path: '/admin/banned-words', component: () => import('../views/admin/BannedWordsManage.vue'), meta: { admin: true, perm: 'BANNED_WORDS_MANAGE', title: '违禁词管理' } },
  // AI 模型与 API Key 属敏感凭证：仅完全管理员（adminOnly），不给细粒度权限码，
  // 与后端 AuthFilter 对 /api/admin/ai 前缀「仅 ADMIN 角色」的判定保持一致
  // AI 模型管理：细粒度权限 AI_MANAGE（可在角色管理里授予；演示号勾上后是「能看改不了」）
  { path: '/admin/ai-models', component: () => import('../views/admin/AiModelManage.vue'), meta: { admin: true, perm: 'AI_MANAGE', title: 'AI 模型管理' } },
  // 检索测试：管理员验证搜索质量（引擎 / 耗时 / 命中明细），纯只读
  { path: '/admin/search-test', component: () => import('../views/admin/SearchTest.vue'), meta: { adminOnly: true, title: '检索测试' } },
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
  // 先确保本地令牌已还原成 user（首次直接访问后台页时守卫早于 App.onMounted 执行）。
  // 加超时兜底：连不上服务器时 axios 要等满 15s 超时，期间路由挂起 → 白屏「打不开」。
  // 到点即用本地状态放行，真正的 401 仍由 axios 拦截器统一登出。
  // 超时兜底：弱网下 ensureAuth 6s 未完成时不把已登录用户踢去登录页
  // （真 401 由 axios 拦截器统一登出；持 token 者放行，页面自行兜底）
  let authTimer
  // ensureAuth 自身 reject（网络异常）不能让守卫整体 reject —— 走下面的本地兜底
  try {
    await Promise.race([
      ensureAuth(),
      new Promise((resolve) => { authTimer = setTimeout(resolve, 6000) })
    ])
  } catch { /* 网络异常：按「校验超时」走本地 token 兜底 */ }
  clearTimeout(authTimer)
  if (!auth.loggedIn) {
    // 校验完成后再读 localStorage：ensureAuth 失败会清掉 token，不能用入口处的旧快照，
    // 否则「曾有 token 但已失效」的会话被误放行（2026-10-13 审查 H6）。
    // 仍有 token（校验超时但未清）= 放行（弱网兜底，真 401 由 axios 拦截器统一登出）
    if (!localStorage.getItem('kb_token')) return { path: '/login', query: { redirect: to.fullPath } }
  }
  // 后台页：要求对应权限码（hasPerm 已包含 ADMIN / 通配 * 的判断）
  if (to.meta.admin && to.meta.perm && !auth.hasPerm(to.meta.perm)) {
    return { path: '/' }
  }
  // 仅管理员可见的页：
  //   · 配了 anyPerm 的（操作审计/登录日志）= 严格按专用只读码判定，不回退 ADMIN_VIEW
  //     （否则「取消勾选也还能进」）
  //   · 未配 anyPerm 的（附件管理/检索测试）= 真实管理员或 ADMIN_VIEW 可看
  if (to.meta.adminOnly) {
    const anyPerm = to.meta.anyPerm || []
    // ⚠️ isAdmin 是 store 里的 computed（属性），不是方法——写成 isAdmin() 会让守卫抛
    // TypeError 导致所有跳转失败（2026-10-06 踩过）
    const ok = auth.isAdmin
      || (anyPerm.length ? anyPerm.some((p) => auth.hasPerm(p)) : auth.hasPerm('ADMIN_VIEW'))
    if (!ok) return { path: '/' }
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

// ---- 发版兜底：懒加载 chunk 取不到时自动刷新一次 ----
// 场景：新版本部署后旧入口文件引用的 chunk 已被清理（rsync --delete），
// 用户停留在旧页面时点进任意懒加载路由会抛
// "Failed to fetch dynamically imported module"。
// 这里刷新一次拿最新入口即可自愈；用 sessionStorage 标记，只刷一次避免死循环。
const CHUNK_RELOAD_KEY = 'kb_chunk_reload'
router.onError((err) => {
  const msg = String((err && err.message) || err || '')
  if (/dynamically imported module|Importing a module script failed|Loading chunk/i.test(msg)) {
    // 30 秒时间窗内只刷一次：会话（Electron 窗口/WebView）可能存活很久，
    // 永久标记会导致第二次发版后再遇 chunk 404 无法自愈（外部复审指出）
    const last = Number(sessionStorage.getItem(CHUNK_RELOAD_KEY) || 0)
    if (Date.now() - last > 30_000) {
      sessionStorage.setItem(CHUNK_RELOAD_KEY, String(Date.now()))
      window.location.reload()
    }
  }
})

export default router
