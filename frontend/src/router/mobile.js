/**
 * 移动端路由表
 *
 * 设计要点：
 *  - 与桌面端 router/index.js 完全分离，页面在 views/mobile/ 下独立实现。
 *    好处：移动端布局可以彻底不同于网页端（底部 TabBar、全屏页面、手势返回），
 *    桌面端的侧边栏 / 面包屑 / 表格样式不会被带过来，也不会互相牵制。
 *  - **base 恒为 '/m/'**（App 与网页一致）。浏览器/App 直链 /m/home 时，
 *    nginx 用 `try_files $uri $uri/ /mobile.html` 回退到移动端入口。
 *  - ⚠️ App 端曾经用 base '/'（配合 server.url 指向站点根），结果 Capacitor 打开的是
 *    根路径 → nginx 回退成 index.html（桌面入口）→ App 里显示的是网页版 UI。
 *    现在 App 的 server.url 直接指向 `/m/home`，base 统一 '/m/'，两端行为完全一致。
 *  - Tab 之间用 router.replace 切换（不叠加历史栈），
 *    二级页面（文档详情 / 编辑）才 push，保证「返回」符合 App 习惯。
 */
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../store/auth'
import { isNative } from '../utils/runtimeBase'

const routes = [
  // ---- 登录：移动端 v3 构图（玻璃胶囊 + 渐变按钮），设计语言与 Web 版同源但布局不同 ----
  { path: '/login', component: () => import('../views/mobile/MobileLogin.vue'), meta: { public: true, title: '登录' } },

  // ---- 一级 Tab 页（底部 4 项 + 中间 ＋） ----
  { path: '/home', component: () => import('../views/mobile/MobileHome.vue'), meta: { tab: 'home', title: '首页' } },
  { path: '/docs', component: () => import('../views/mobile/MobileDocs.vue'), meta: { tab: 'docs', title: '文档库' } },
  { path: '/messages', component: () => import('../views/mobile/MobileMessages.vue'), meta: { tab: 'messages', title: '消息' } },
  { path: '/profile', component: () => import('../views/mobile/MobileProfile.vue'), meta: { tab: 'profile', title: '我的' } },

  // ---- ＋号：拍照识别建文档（全屏，不占 Tab） ----
  { path: '/capture', component: () => import('../views/mobile/MobileCapture.vue'), meta: { title: '拍照建文档' } },

  // ---- 二级页 ----
  { path: '/space/:id', component: () => import('../views/mobile/MobileSpace.vue'), meta: { title: '文档库' } },
  { path: '/doc/:spaceId/:docId', component: () => import('../views/mobile/MobileDocView.vue'), meta: { title: '文档' } },
  { path: '/doc/:spaceId/:docId/edit', component: () => import('../views/mobile/MobileDocEdit.vue'), meta: { title: '编辑' } },
  // 离线草稿详情（本地 IndexedDB 中的待同步文档）
  { path: '/draft/:localId', component: () => import('../views/mobile/MobileDraftView.vue'), meta: { title: '本地草稿' } },

  { path: '/search', component: () => import('../views/mobile/MobileSearch.vue'), meta: { title: '搜索' } },
  // ---- AI 问答：原生移动版（此前是 iframe 嵌桌面 /ai/chat，小屏体验差）----
  { path: '/ai', component: () => import('../views/mobile/MobileAiChat.vue'), meta: { title: 'AI 问答' } },
  { path: '/settings', component: () => import('../views/mobile/MobileSettings.vue'), meta: { title: '设置' } },
  { path: '/about', component: () => import('../views/mobile/MobileAbout.vue'), meta: { title: '关于' } },
  { path: '/privacy', component: () => import('../views/mobile/MobilePrivacy.vue'), meta: { public: true, title: '隐私政策' } },
  { path: '/disclaimer', component: () => import('../views/mobile/MobileDisclaimer.vue'), meta: { public: true, title: '免责声明' } },
  // ---- 我的文件：文件中心（与网页端 FileCenter 共用一套接口）----
  { path: '/files', component: () => import('../views/mobile/MobileFiles.vue'), meta: { title: '我的文件' } },

  // ---- 管理端原生页（高频管理功能做成 App 原生，不用 iframe 嵌桌面表格）----
  { path: '/manage/users', component: () => import('../views/mobile/MobileAdminUsers.vue'), meta: { title: '用户管理', perm: 'USER_MANAGE' } },
  { path: '/manage/roles', component: () => import('../views/mobile/MobileAdminRoles.vue'), meta: { title: '角色管理', perm: 'USER_MANAGE' } },
  { path: '/manage/ai', component: () => import('../views/mobile/MobileAdminAi.vue'), meta: { title: 'AI 模型管理', perm: 'AI_MANAGE' } },
  { path: '/manage/docs', component: () => import('../views/mobile/MobileAdminDocs.vue'), meta: { title: '内容管理', perm: 'POST_MANAGE' } },
  { path: '/manage/review', component: () => import('../views/mobile/MobileAdminReview.vue'), meta: { title: '文章审核', perm: 'DOC_REVIEW' } },
  { path: '/manage/announce', component: () => import('../views/mobile/MobileAdminAnnounce.vue'), meta: { title: '公告管理', perm: 'ANNOUNCEMENT_MANAGE' } },

  // 低频二级功能：用嵌入页承接（见 MobileEmbed.vue 的说明）。
  // 保留在移动端路由里，是为了让通知、个人中心里的入口都能落地，不出现死链。
  { path: '/embed/:kind', component: () => import('../views/mobile/MobileEmbed.vue'), meta: { title: '功能' } },

  // 发现（公开文章流）/ 文章详情
  { path: '/discover', component: () => import('../views/mobile/MobileDiscover.vue'), meta: { title: '发现' } },
  { path: '/post/:id', component: () => import('../views/mobile/MobilePostView.vue'), meta: { title: '文章' } },
  // 新增工具页
  { path: '/stats', component: () => import('../views/mobile/MobileStats.vue'), meta: { title: '我的数据' } },
  { path: '/favorites', component: () => import('../views/mobile/MobileFavorites.vue'), meta: { title: '我的收藏' } },
  { path: '/offline', component: () => import('../views/mobile/MobileOffline.vue'), meta: { title: '离线内容' } },
  { path: '/appearance', component: () => import('../views/mobile/MobileAppearance.vue'), meta: { title: '外观' } },
  { path: '/lock', component: () => import('../views/mobile/MobileLock.vue'), meta: { title: '应用锁' } },
  { path: '/versions/:spaceId/:docId', component: () => import('../views/mobile/MobileVersions.vue'), meta: { title: '历史版本' } },

  // ---- 培训 / 考试（复用桌面端 api/training.js，学员侧）----
  { path: '/courses', component: () => import('../views/mobile/MobileCourses.vue'), meta: { title: '课程中心' } },
  { path: '/course/:id', component: () => import('../views/mobile/MobileCourseDetail.vue'), meta: { title: '课程' } },
  { path: '/course/:id/chapter/:chId', component: () => import('../views/mobile/MobileChapter.vue'), meta: { title: '章节' } },
  { path: '/exams', component: () => import('../views/mobile/MobileExams.vue'), meta: { title: '考试中心' } },
  { path: '/exam/:id', component: () => import('../views/mobile/MobileExamTake.vue'), meta: { title: '考试' } },
  { path: '/exam/mine', component: () => import('../views/mobile/MobileExamMine.vue'), meta: { title: '我的考试' } },

  { path: '/', redirect: '/home' },
  { path: '/:pathMatch(.*)*', redirect: '/home' }
]

const router = createRouter({
  // base 必须分环境：
  //   · 网页端 —— 挂在 /m/ 下（nginx 的 location /m/ 兜底到 mobile.html）
  //   · App 内置资源模式 —— 页面由 Capacitor 本地服务器在 https://localhost/ 提供，
  //     路径不带 /m 前缀；若仍写 '/m/' 会匹配不到任何路由 → 白屏（P0 离线化改造点）
  history: createWebHistory(isNative() ? '/' : '/m/'),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

let authReady = null
function ensureAuth() {
  const auth = useAuthStore()
  if (auth.user) return Promise.resolve()
  if (!authReady) authReady = auth.fetchMe().finally(() => { authReady = null })
  return authReady
}

// 登录态校验的最长等待：连不上服务器时 axios 要等满 15s 超时才 reject，
// 期间路由一直挂起 → 白屏「打不开」。到点即用本地状态放行，避免长时间白屏。
const AUTH_WAIT_MS = 6000

/**
 * 离线兜底：断网时 fetchMe 必然失败，若直接跳登录页，用户会被挡在门外 ——
 * 但这时本地其实有有效 token（离线写文档正需要它）。
 * 因此：本地存在 token 时，允许带着「尚未校验」的状态进入界面；
 * 真正的 401 由 axios 拦截器派发 kb-unauthorized 统一登出。
 */
function hasLocalToken() {
  try { return !!localStorage.getItem('kb_token') } catch { return false }
}

// 权限页兜底：未拿到服务端确认的身份时（离线 / 超时 / 校验异常），
// 权限页必须粗检——无缓存身份一律拒入（外部审查 2026-10-11：原实现手输 URL 可绕过 perm 检查）
function permGate(to, auth) {
  return !to.meta.perm || auth.isAdmin || auth.hasPerm(to.meta.perm)
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true

  // 离线：跳过服务端校验；权限页用缓存身份粗检，无缓存一律拒入（fail-closed）
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    if (!(hasLocalToken() || auth.loggedIn)) return { path: '/login', query: { redirect: to.fullPath } }
    if (!permGate(to, auth)) return { path: '/profile' }
    return true
  }

  // 校验登录态。加超时兜底：连不上服务器（弱网 / onLine 误判为在线）时
  // axios 要等满 15s 超时才 reject，期间路由一直挂起 → 白屏「打不开」。
  // 到点即用本地 token / 缓存状态放行，真正的 401 仍由 axios 拦截器统一登出。
  // ensureAuth 自身 reject（网络异常）也不能让守卫整体 reject —— 同样走本地兜底
  let authTimer
  try {
    await Promise.race([
      ensureAuth(),
      new Promise((resolve) => { authTimer = setTimeout(resolve, AUTH_WAIT_MS) })
    ])
  } catch { /* 网络异常：按「校验未完成」走下面的本地 token 兜底 */ }
  clearTimeout(authTimer)
  // fetchMe 成功 → user 已就绪，正常放行
  if (auth.user) {
    if (!permGate(to, auth)) return { path: '/profile' }
    return true
  }
  // 本地有 token 但校验没成功：多半是网络抖动而非真的未登录。
  // 继续放行，让页面自己处理接口错误（避免"网络一抖就被踢回登录页"）；
  // 真 401 由 axios 拦截器派发 kb-unauthorized 统一登出。
  // 权限页例外：无缓存身份 fail-closed，不让手输 URL 摸进权限页
  if (hasLocalToken()) {
    if (!permGate(to, auth)) return { path: '/profile' }
    return true
  }
  return { path: '/login', query: { redirect: to.fullPath } }
})

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
