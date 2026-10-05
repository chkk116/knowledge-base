import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 按需引入：只注册项目实际用到的组件与指令。
// JS 走 tree-shake（package.json sideEffects 已排除 es 目录）；CSS 用各组件
// 的 style/css 单独引入，替换全量 360KB 的 dist/index.css。
// ⚠️ 新增页面用到新组件时：在下面 import 与 EP_COMPONENTS 各补一行，
// 并在 style 清单里补对应样式，否则组件解析不到（静默空白）或无样式。
import {
  ElAlert, ElAside, ElAvatar, ElAvatarGroup, ElBadge, ElButton, ElCard,
  ElCheckbox, ElCheckboxGroup, ElCol, ElColorPicker, ElContainer, ElDatePicker,
  ElDialog, ElDivider, ElDrawer, ElDropdown, ElDropdownItem, ElDropdownMenu,
  ElEmpty, ElForm, ElFormItem, ElHeader, ElIcon, ElImage, ElInput, ElInputNumber, ElMain,
  ElMenu, ElMenuItem, ElOption, ElPageHeader, ElPagination, ElPopconfirm,
  ElPopover, ElProgress, ElRadio, ElRadioButton, ElRadioGroup, ElRow, ElSelect,
  ElSkeleton, ElSlider, ElSubMenu, ElSwitch, ElTabPane, ElTable, ElTableColumn,
  ElTabs, ElTag, ElTimeline, ElTimelineItem, ElTooltip, ElTree, ElTreeSelect,
  ElDescriptions, ElDescriptionsItem, ElUpload, ElWatermark, ElConfigProvider, ElLoadingDirective,
  ElMessage
} from 'element-plus'
import 'element-plus/theme-chalk/dark/css-vars.css'
// —— 按需样式：56 个组件 + message/message-box/loading（命令式 API/指令）——
import 'element-plus/es/components/alert/style/css'
import 'element-plus/es/components/aside/style/css'
import 'element-plus/es/components/avatar/style/css'
import 'element-plus/es/components/avatar-group/style/css'
import 'element-plus/es/components/badge/style/css'
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/card/style/css'
import 'element-plus/es/components/checkbox/style/css'
import 'element-plus/es/components/checkbox-group/style/css'
import 'element-plus/es/components/col/style/css'
import 'element-plus/es/components/color-picker/style/css'
import 'element-plus/es/components/container/style/css'
import 'element-plus/es/components/date-picker/style/css'
import 'element-plus/es/components/dialog/style/css'
import 'element-plus/es/components/divider/style/css'
import 'element-plus/es/components/drawer/style/css'
import 'element-plus/es/components/dropdown/style/css'
import 'element-plus/es/components/dropdown-item/style/css'
import 'element-plus/es/components/dropdown-menu/style/css'
import 'element-plus/es/components/empty/style/css'
import 'element-plus/es/components/form/style/css'
import 'element-plus/es/components/form-item/style/css'
import 'element-plus/es/components/header/style/css'
import 'element-plus/es/components/icon/style/css'
import 'element-plus/es/components/image/style/css'
import 'element-plus/es/components/image-viewer/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/input-number/style/css'
import 'element-plus/es/components/main/style/css'
import 'element-plus/es/components/menu/style/css'
import 'element-plus/es/components/menu-item/style/css'
import 'element-plus/es/components/option/style/css'
import 'element-plus/es/components/page-header/style/css'
import 'element-plus/es/components/pagination/style/css'
import 'element-plus/es/components/popconfirm/style/css'
import 'element-plus/es/components/popover/style/css'
import 'element-plus/es/components/progress/style/css'
import 'element-plus/es/components/radio/style/css'
import 'element-plus/es/components/radio-button/style/css'
import 'element-plus/es/components/radio-group/style/css'
import 'element-plus/es/components/row/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/skeleton/style/css'
import 'element-plus/es/components/slider/style/css'
import 'element-plus/es/components/sub-menu/style/css'
import 'element-plus/es/components/switch/style/css'
import 'element-plus/es/components/tab-pane/style/css'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/table-column/style/css'
import 'element-plus/es/components/tabs/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/timeline/style/css'
import 'element-plus/es/components/timeline-item/style/css'
import 'element-plus/es/components/tooltip/style/css'
import 'element-plus/es/components/tree/style/css'
import 'element-plus/es/components/tree-select/style/css'
import 'element-plus/es/components/descriptions/style/css'
import 'element-plus/es/components/descriptions-item/style/css'
import 'element-plus/es/components/upload/style/css'
import 'element-plus/es/components/watermark/style/css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/loading/style/css'
import 'element-plus/es/components/config-provider/style/css'
import {
  HomeFilled, EditPen, Files, Star, Medal, Document, DocumentCopy, User,
  CircleClose, UserFilled, Expand, Fold, Sunny, Moon, Bell, Clock, Menu, DataLine, Collection, Notification,   ChatDotRound,
  Reading, Notebook, Trophy, ArrowLeft, Close, Check, Folder, InfoFilled,
  Setting, Tools, Edit, Warning, Switch, Delete, Key, Grid, Stamp, OfficeBuilding, Finished, Management, Suitcase, Box, Brush, Cpu, Search
} from '@element-plus/icons-vue'
import App from './App.vue'
import router from './router'
import { stackTable } from './utils/stackTable'
import './theme.css'
import './responsive.css'

// 初始化主题：localStorage('kb_theme') 优先，否则跟随系统偏好
const savedTheme = localStorage.getItem('kb_theme')
const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
if (savedTheme === 'dark' || (savedTheme === null && prefersDark)) {
  document.documentElement.classList.add('dark')
}

const app = createApp(App)

// 按需注册 Element Plus 组件（模板里直接写 <el-xxx> 靠全局注册解析）
const EP_COMPONENTS = {
  ElAlert, ElAside, ElAvatar, ElAvatarGroup, ElBadge, ElButton, ElCard,
  ElCheckbox, ElCheckboxGroup, ElCol, ElColorPicker, ElContainer, ElDatePicker,
  ElDialog, ElDivider, ElDrawer, ElDropdown, ElDropdownItem, ElDropdownMenu,
  ElEmpty, ElForm, ElFormItem, ElHeader, ElIcon, ElImage, ElInput, ElInputNumber, ElMain,
  ElMenu, ElMenuItem, ElOption, ElPageHeader, ElPagination, ElPopconfirm,
  ElPopover, ElProgress, ElRadio, ElRadioButton, ElRadioGroup, ElRow, ElSelect,
  ElSkeleton, ElSlider, ElSubMenu, ElSwitch, ElTabPane, ElTable, ElTableColumn,
  ElTabs, ElTag, ElTimeline, ElTimelineItem, ElTooltip,   ElTree, ElTreeSelect, ElDescriptions, ElDescriptionsItem,
  ElUpload, ElWatermark, ElConfigProvider
}
for (const [key, component] of Object.entries(EP_COMPONENTS)) {
  app.component(key, component)
}
// v-loading 指令（服务端同款 Loading）
app.directive('loading', ElLoadingDirective)

// 按需注册 Element Plus 图标（仅注册实际用到的图标，避免全量注册撑大主包）
const icons = {
  HomeFilled, EditPen, Files, Star, Medal, Document, DocumentCopy, User,
  CircleClose, UserFilled, Expand, Fold, Sunny, Moon, Bell, Clock, Menu, DataLine, Collection, Notification,   ChatDotRound,
  Reading, Notebook, Trophy, ArrowLeft, Close, Check, Folder, InfoFilled,
  Setting, Tools, Edit, Warning, Switch, Delete, Key, Grid, Stamp, OfficeBuilding, Finished, Management, Suitcase, Box, Brush, Cpu, Search
}
for (const [key, component] of Object.entries(icons)) {
  app.component(key, component)
}

// 全局指令：窄屏把 el-table 转成卡片堆叠列表（详见 utils/stackTable.js）
app.directive('stack-table', stackTable)

// 演示账号只读拦截（全局、捕获阶段）：
// 演示账号（auth.isDemo）点击「保存/新建/删除/发布…」等写操作按钮时，直接弹出统一提示并阻止事件继续，
// 无需逐页改造。真实的兜底仍在服务端（403 + {demo:true}）。
// 注意：本文件不直接依赖 pinia 实例，演示状态通过 window.__kbIsDemo 由 store 侧同步（见 store/auth.js）。
const DEMO_WRITE_BTN = /保存|提交|确认|确定|新建|新增|添加|创建|删除|移除|编辑|修改|发布|通过|驳回|导入|上传|重命名|停用|启用|重置|恢复|清空|审批|发放|调整|设默认|绑定|解绑|同意|拒绝/
document.addEventListener('click', (e) => {
  if (typeof window.__kbIsDemo !== 'function' || !window.__kbIsDemo()) return
  const t = e.target
  const btn = t && t.closest && t.closest('button, .el-button, [role="button"]')
  if (!btn) return
  if (btn.disabled || btn.classList.contains('is-disabled')) return
  const text = (btn.innerText || btn.textContent || '').trim()
  if (!text || !DEMO_WRITE_BTN.test(text)) return
  e.preventDefault()
  e.stopPropagation()
  if (e.stopImmediatePropagation) e.stopImmediatePropagation()
  ElMessage.warning('演示账户不能执行写操作（' + text.replace(/\s+/g, '') + '），请使用正式账号')
}, true)

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
// 另：reportAllChanges / startTime 来自外部注入的 web-vitals 性能探针（浏览器扩展或企业监控脚本，非本应用代码），
// 其 PerformanceObserver 偶发读到 undefined entry 抛错，与本应用功能无关，同样过滤，避免红条刷屏。
window.addEventListener('error', (e) => {
  const m = e.message || (e.error && e.error.message) || ''
  // ResizeObserver loop 是浏览器在布局重排时的无害警告（Element Plus 常见）；
  // reportAllChanges/startTime 是外部注入的 web-vitals 探针（扩展/监控脚本）偶发抛错。
  // 两者均为已知无害项：preventDefault 连控制台打印一并静默，其余错误照常上报红条。
  if (m.includes('ResizeObserver loop completed with undelivered notifications')) { e.preventDefault(); return }
  if (m.includes('reportAllChanges') || m.includes("reading 'startTime'") || m.includes('reading "startTime"')) { e.preventDefault(); return }
  reportError((e.error && e.error.stack) || e.message)
})
window.addEventListener('unhandledrejection', (e) => reportError('Promise: ' + ((e.reason && e.reason.stack) || e.reason)))

app.use(createPinia()).use(router).mount('#app')
