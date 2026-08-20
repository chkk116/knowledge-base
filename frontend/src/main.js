import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn.mjs'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import {
  HomeFilled, EditPen, Files, Star, Medal, Document, User,
  CircleClose, UserFilled, Expand, Fold, Sunny, Moon, Bell, Clock, Menu, DataLine, Collection, Notification
} from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import './theme.css'

// 初始化主题：localStorage('kb_theme') 优先，否则跟随系统偏好
const savedTheme = localStorage.getItem('kb_theme')
const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
if (savedTheme === 'dark' || (savedTheme === null && prefersDark)) {
  document.documentElement.classList.add('dark')
}

const app = createApp(App)

// 按需注册 Element Plus 图标（仅注册实际用到的 14 个，避免全量注册撑大主包）
const icons = {
  HomeFilled, EditPen, Files, Star, Medal, Document, User,
  CircleClose, UserFilled, Expand, Fold, Sunny, Moon, Bell, Clock, Menu, DataLine, Collection, Notification
}
for (const [key, component] of Object.entries(icons)) {
  app.component(key, component)
}

// 全局错误兜底：把未捕获异常显示成页面底部浮条（避免 Vite 红色浮层反复闪屏）
function reportError(msg) {
  console.error(msg)
  let el = document.getElementById('kb-error-banner')
  if (!el) {
    el = document.createElement('div')
    el.id = 'kb-error-banner'
    el.style.cssText = 'position:fixed;left:12px;bottom:12px;max-width:90vw;z-index:99999;background:#7f1d1d;color:#fff;padding:10px 14px;border-radius:8px;font:12px/1.5 ui-monospace,monospace;white-space:pre-wrap;box-shadow:0 6px 24px rgba(0,0,0,.3);cursor:pointer;max-height:40vh;overflow:auto'
    el.onclick = () => el.remove()
    document.body.appendChild(el)
  }
  el.textContent = '⚠️ 运行时报错（点我关闭）：\n' + msg
}
window.__kbError = reportError
app.config.errorHandler = (err, _vm, info) => reportError((err && err.stack) || String(err) + '\n' + info)
// ResizeObserver loop 是浏览器在布局重排时的无害警告（Element Plus 表格/分页常见），不应当作运行时错误兜出红条
window.addEventListener('error', (e) => {
  const m = e.message || (e.error && e.error.message) || ''
  if (m.includes('ResizeObserver loop completed with undelivered notifications')) return
  reportError((e.error && e.error.stack) || e.message)
})
window.addEventListener('unhandledrejection', (e) => reportError('Promise: ' + ((e.reason && e.reason.stack) || e.reason)))

app.use(createPinia()).use(ElementPlus, { locale: zhCn }).use(router).mount('#app')
