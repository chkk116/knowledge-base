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
  { path: '/settings', component: () => import('../views/mobile/MobileSettings.vue'), meta: { title: '设置' } },

  // ---- 管理端原生页（高频管理功能做成 App 原生，不用 iframe 嵌桌面表格）----
  { path: '/manage/users', component: () => import('../views/mobile/MobileAdminUsers.vue'), meta: { title: '用户管理', perm: 'USER_MANAGE' } },
  { path: '/manage/roles', component: () => import('../views/mobile/MobileAdminRoles.vue'), meta: { title: '角色管理', perm: 'USER_MANAGE' } },

  // 低频二级功能：用嵌入页承接（见 MobileEmbed.vue 的说明）。
  // 保留在移动端路由里，是为了让通知、个人中心里的入口都能落地，不出现死链。
  { path: '/embed/:kind', component: () => import('../views/mobile/MobileEmbed.vue'), meta: { title: '功能' } },

  { path: '/', redirect: '/home' },
  { path: '/:pathMatch(.*)*', redirect: '/home' }
]

const router = createRouter({
  // App 与网页统一挂在 /m/ 下：App 的 server.url 指向 /m/home，
  // nginx 的 location /m/ 兜底到 mobile.html，两端行为一致（避免 App 落到桌面入口）。
  history: createWebHistory('/m/'),
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

/**
 * 离线兜底：断网时 fetchMe 必然失败，若直接跳登录页，用户会被挡在门外 ——
 * 但这时本地其实有有效 token（离线写文档正需要它）。
 * 因此：本地存在 token 时，允许带着「尚未校验」的状态进入界面；
 * 真正的 401 由 axios 拦截器派发 kb-unauthorized 统一登出。
 */
function hasLocalToken() {
  try { return !!localStorage.getItem('kb_token') } catch { return false }
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true

  // 离线：跳过服务端校验，直接放行（TabBar / 缓存文档仍可用）
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    if (hasLocalToken() || auth.loggedIn) return true
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  await ensureAuth()
  // fetchMe 成功 → user 已就绪，正常放行
  if (auth.user) {
    // 细粒度权限页：入口按 hasPerm 过滤，这里再兜一层（防手输 URL）
    if (to.meta.perm && !auth.isAdmin && !auth.hasPerm(to.meta.perm)) return { path: '/profile' }
    return true
  }
  // 本地有 token 但校验没成功：多半是网络抖动而非真的未登录。
  // 继续放行，让页面自己处理接口错误（避免"网络一抖就被踢回登录页"）；
  // 真 401 由 axios 拦截器派发 kb-unauthorized 统一登出。
  if (hasLocalToken()) return true
  return { path: '/login', query: { redirect: to.fullPath } }
})

export default router
