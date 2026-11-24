/**
 * 移动端独立入口（/m/*）
 *
 * 与桌面端 main.js 的区别：
 *  - 只注册移动端用到的少量 Element Plus 组件（弹窗、下拉、上传、消息提示等），
 *    不引入侧边栏 / 菜单 / 表格等桌面重组件，首屏体积显著小于桌面端。
 *  - 独立路由表 router/mobile.js，页面全部在 views/mobile/ 下，与桌面页面零复用（除了 API 与 store）。
 *  - 全局样式走 mobile.css：安全区、点击态、底部 TabBar、滚动动效都按 App 习惯重写。
 */
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import {
  ElButton, ElDialog, ElDrawer, ElInput, ElIcon, ElLoadingDirective,
  ElMessage, ElMessageBox, ElEmpty, ElSkeleton, ElSwitch, ElProgress, ElTag,
  ElDropdown, ElDropdownItem, ElDropdownMenu, ElAvatar,
  ElForm   // 登录页复用 Web 版 Login.vue，需要 el-form
} from 'element-plus'
import 'element-plus/theme-chalk/dark/css-vars.css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/drawer/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/icon/style/css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
import 'element-plus/es/components/empty/style/css'
import 'element-plus/es/components/skeleton/style/css'
import 'element-plus/es/components/switch/style/css'
import 'element-plus/es/components/progress/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/dropdown/style/css'
import 'element-plus/es/components/dropdown-item/style/css'
import 'element-plus/es/components/dropdown-menu/style/css'
import 'element-plus/es/components/avatar/style/css'
import 'element-plus/es/components/config-provider/style/css'
import 'element-plus/es/components/form/style/css'
import {
  Plus, HomeFilled, Document, ChatDotRound, ChatLineRound, User, Camera, Picture, Close, CircleCloseFilled,
  Check, ArrowLeft, MoreFilled, Delete, EditPen, Search, Refresh, Bell,
  Loading, CircleCheckFilled, WarningFilled, InfoFilled, Star, Clock,
  Setting, SwitchButton, DocumentCopy, FolderOpened, Upload, Sunny, Moon,
  Right, Edit, Files, Promotion, View, Lock, UserFilled, Calendar, Trophy, Reading,
  MagicStick,   // AI 问答入口图标
  Paperclip,    // AI 页「上传附件」按钮（此前漏注册 → 图标空白）
  DataLine, Avatar, CollectionTag, Brush,  // 「我的」页管理员分组图标
  // 新增功能页用到的图标（缺一个就会渲染成空白，务必与 icons 对象同步）
  Download, Share, Link, TrendCharts, Cloudy, Unlock, Key,
  Compass, StarFilled, Guide, Odometer, Medal, Notebook, Aim, Histogram,
  Crop, RefreshLeft, RefreshRight, Postcard,   // 扫描编辑器（裁剪/旋转）+ 证件扫描
  Lightning, CircleCheck,   // 考试中心「模拟练习」卡片 + 错题本空态（此前 Lightning 漏注册 → 图标空白）
  Coin, SuccessFilled   // 积分/考试结果图标
} from '@element-plus/icons-vue'

import MobileApp from './MobileApp.vue'
import mobileRouter from './router/mobile'
import './responsive.css'
import './mobile.css'

// 标记「当前跑的是移动端入口」：Web 版 Login.vue 复用时据此把登录后
// 重定向改到移动端首页（桌面路由在移动端包里不存在）
window.__KB_MOBILE__ = true

// 个性化外观（主题色 / 正文字号）：写 <html> 内联 CSS 变量，只影响移动端
import { applyMobileTheme } from './utils/mobileTheme'
// Kotlin 原生壳（mobile/mobile-native）的令牌桥接：与 Web 入口共用 utils/nativeToken。
// 必须在 Pinia store 创建之前落盘（store/auth.js 在 defineStore 时同步读 kb_token）。
import { adoptNativeToken } from './utils/nativeToken.js'
applyMobileTheme()
adoptNativeToken()

// 主题：与桌面端共用 localStorage('kb_theme')，手机 / 电脑切换保持一致。
// localStorage 在无痕/隐私模式可能抛 SecurityError：启动失败 = 整页白屏，包一层兜底（第四轮审查）
let savedTheme = null
try { savedTheme = localStorage.getItem('kb_theme') } catch { /* storage 不可用 → 跟随系统 */ }
const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
if (savedTheme === 'dark' || (savedTheme === null && prefersDark)) {
  document.documentElement.classList.add('dark')
}

const app = createApp(MobileApp)

const EP_COMPONENTS = {
  ElButton, ElDialog, ElDrawer, ElInput, ElIcon, ElEmpty, ElSkeleton,
  ElSwitch, ElProgress, ElTag, ElDropdown, ElDropdownItem, ElDropdownMenu,
  ElAvatar, ElForm
}
for (const [key, component] of Object.entries(EP_COMPONENTS)) {
  app.component(key, component)
}
app.directive('loading', ElLoadingDirective)

const icons = {
  Plus, HomeFilled, Document, ChatDotRound, ChatLineRound, CircleCloseFilled, User, Camera, Picture, Close,
  Check, ArrowLeft, MoreFilled, Delete, EditPen, Search, Refresh, Bell,
  Loading, CircleCheckFilled, WarningFilled, InfoFilled, Star, Clock,
  Setting, SwitchButton, DocumentCopy, FolderOpened, Upload, Sunny, Moon,
  Right, Edit, Files, Promotion, View, Lock, UserFilled, Calendar, Trophy, Reading,
  MagicStick,   // AI 问答入口图标
  Paperclip,    // AI 页「上传附件」按钮（此前漏注册 → 图标空白）
  DataLine, Avatar, CollectionTag, Brush,
  Download, Share, Link, TrendCharts, Cloudy, Unlock, Key,
  Compass, StarFilled, Guide, Odometer, Medal, Notebook, Aim, Histogram,
  Crop, RefreshLeft, RefreshRight, Postcard, Coin, SuccessFilled, Lightning, CircleCheck
}
for (const [key, component] of Object.entries(icons)) {
  app.component(key, component)
}

// 命令式 API 独立挂到 window，便于在工具函数中直接用（不依赖组件实例）
window.__kbMsg = ElMessage
window.__kbMsgBox = ElMessageBox

// 全局错误兜底：移动端不放红条（挡视线），改控制台 + 一次性 toast
let errShown = 0
function reportError(msg) {
  console.error(msg)
  if (errShown++ < 3) ElMessage.error('页面出现问题，请下拉刷新重试')
}
window.__kbError = reportError
app.config.errorHandler = (err, _vm, info) => {
  // 原来是 (err && err.stack) || String(err) + '\n' + info：+ 优先级更高，有 stack 时 info 直接被丢掉（审查 r14）
  const stack = (err && err.stack) || String(err)
  reportError(info ? stack + '\n' + info : stack)
}
window.addEventListener('error', (e) => {
  const m = e.message || (e.error && e.error.message) || ''
  if (m.includes('ResizeObserver loop')) { e.preventDefault(); return }
  if (m.includes('reportAllChanges') || m.includes("reading 'startTime'") || m.includes('reading "startTime"')) {
    e.preventDefault(); return
  }
  reportError((e.error && e.error.stack) || e.message)
})
window.addEventListener('unhandledrejection', (e) => {
  const r = e.reason
  // 网络类失败（axios 无响应 / 被 CORS 拦截 / 超时）不当「页面问题」处理：
  // 弱网、离线、服务器重启时很常见，弹「页面出现问题」会误导用户以为 App 坏了。
  // 真正的 401 已由 axios 拦截器统一登出，其余由各页面自行兜底（走缓存/空态）。
  // 只吞「确凿的网络类/取消类」错误；5xx（r.response 存在）仍走上报通道，避免服务端故障两端同时无感。
  // 外部审查 2026-10-11：原 `!r.response` 会把普通 Error/TypeError 一并吞掉，
  // 真实编程错误从此隐身——现在必须是 axios 错误或带网络类 code 才放行。
  const NET_CODES = new Set(['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT', 'ERR_CANCELED'])
  if (r && (r.network
    || (r.isAxiosError && !r.response)
    || (r.code && NET_CODES.has(r.code)))) return
  reportError('Promise: ' + ((r && r.stack) || r))
})

app.use(createPinia()).use(mobileRouter).mount('#app')
// 占位要等「首次导航完成」再撤：mount() 是同步返回的，这时路由守卫的登录态核验（弱网最长 6s）
// 与首屏 chunk 下载都还没结束，提前撤掉等于又露出一段白屏。isReady 在导航 settle 后 resolve。
mobileRouter.isReady().catch(() => {}).finally(() => document.getElementById('boot-loading')?.remove())

/**
 * 路由分包预取：空闲时预加载常用二级页的 chunk，消除首次切页的白屏/卡顿。
 * 只预取高频页（文档库/消息/我的/发现/课程/考试），避免一次性拉全部包。
 * 用 requestIdleCallback（不支持则退化为 setTimeout），不阻塞首屏。
 */
const PREFETCH = [
  () => import('./views/mobile/MobileDocs.vue'),
  () => import('./views/mobile/MobileMessages.vue'),
  () => import('./views/mobile/MobileProfile.vue'),
  () => import('./views/mobile/MobileDiscover.vue'),
  () => import('./views/mobile/MobileCourses.vue'),
  () => import('./views/mobile/MobileExams.vue')
]
const runPrefetch = () => { PREFETCH.forEach((fn) => { try { fn() } catch { /* 忽略 */ } }) }
if (typeof requestIdleCallback === 'function') requestIdleCallback(runPrefetch, { timeout: 3000 })
else setTimeout(runPrefetch, 1500)

/**
 * 注册 Service Worker：让移动端在断网时也能冷启动打开界面。
 * - 生产环境才注册（开发环境 SW 会缓存 dev 资源，干扰 HMR）
 * - **App 端跳过**：Capacitor 套壳时页面由 WebView 从本地文件加载，
 *   资源本就随 APK 一起分发，不存在「断网打不开」，注册 /sw.js 反而会 404 报错。
 * - 失败不影响主流程（如 iOS 无痕模式不支持 SW）
 */
const inNativeApp = (() => {
  try {
    return !!(window.Capacitor && typeof window.Capacitor.isNativePlatform === 'function' && window.Capacitor.isNativePlatform())
  } catch (e) { return false }
})()

if (import.meta.env.PROD && 'serviceWorker' in navigator && !inNativeApp) {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js', { scope: '/' }).then((reg) => {
      // 有新版本就立刻激活，避免用户长期停留在旧外壳
      reg.addEventListener('updatefound', () => {
        const sw = reg.installing
        if (!sw) return
        sw.addEventListener('statechange', () => {
          if (sw.state === 'installed' && navigator.serviceWorker.controller) {
            sw.postMessage('SKIP_WAITING')
          }
        })
      })
    }).catch((e) => {
      // 不弹错误：离线能力属于增强，注册失败不应影响使用
      console.warn('[kb] Service Worker 注册失败：', e && e.message)
    })
  })
}
