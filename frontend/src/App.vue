<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore, permLabel } from './store/auth'
import { dmUnreadCount, refreshDmUnread } from './store/dm'
import ReportDialog from './components/ReportDialog.vue'
import { useWatermarkStore, buildContent } from './store/watermark'
import { useSiteInfoStore } from './store/siteInfo'
import { useDownloadStore } from './store/download'
import { usePrefsStore, applyPrefs } from './store/prefs'
import { useScenarioStore } from './store/scenario'
import WornBadgeStrip from './components/WornBadgeStrip.vue'
import SiteFooter from './components/SiteFooter.vue'
import MenuIcon from './components/MenuIcon.vue'
import { useNotificationStore } from './store/notification'
import { reader, closeReader, startDrag, startResize, clampToViewport } from './store/reader'
import { enhanceVideos, bindImgLightbox, absolutizeMedia } from './utils/mediaEnhance'
import { featureMap } from './api/features'
import { appearanceLogo as siteLogo, appearanceSiteName, appearanceShowName, loadAppearance, resetAppearance } from './utils/appearance'
import CommandPalette from './components/CommandPalette.vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn.mjs'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const notification = useNotificationStore()
const wm = useWatermarkStore()
const siteInfo = useSiteInfoStore()
const download = useDownloadStore()

// 界面偏好（布局 / 外观 / 密度）：本地持久化，改完即时生效（2026-10-22）
const prefs = usePrefsStore()
const scenario = useScenarioStore()
// 侧栏品牌文字：站点设置可改名称 / 整体隐藏（自定义 Logo 自带文字时）
const brandName = computed(() => appearanceSiteName.value || '知识库')
const showBrandText = computed(() => appearanceShowName.value)
prefs.load()
applyPrefs(prefs.state)

// 核心维护：功能开关状态（featureKey → { enabled, closeMode, maintenanceMsg, expectedRestoreAt }）
const featureFlags = ref({})
async function loadFlags() {
  try {
    const { data } = await featureMap()
    featureFlags.value = data || {}
  } catch { featureFlags.value = {} }
}
// 该功能入口是否在侧边栏展示：
//  - 未配置 / 开启 → 展示
//  - 关闭 + MAINTENANCE（维护界面模式）→ 展示（点进去看维护页）
//  - 关闭 + HIDDEN（默认）→ 隐藏
function featureOpen(key) {
  if (!key) return true
  const st = featureFlags.value[key]
  if (!st) return true            // 无记录视为开放
  if (st.enabled) return true
  return st.closeMode === 'MAINTENANCE'
}

// 全局水印：启用且已登录时，按当前用户身份构建内容；否则空数组（不绘制）
const wmContent = computed(() => {
  if (!wm.config.enabled || !auth.loggedIn) return []
  return buildContent(auth.user, wm.config, new Date(), wm.clientInfo)
})
const wmFont = computed(() => wm.font)
const wmGap = computed(() => wm.gap)

const fbOpen = ref(false)

// 启动时用本地令牌恢复会话；登录态则建立 SSE 实时通知长连接（替代路由切换轮询未读）
onMounted(async () => {
  await auth.fetchMe()
  wm.dark = isDark.value   // 初始化水印主题状态（与当前页面主题一致）
  // 页脚配置走公开接口，**登录前也要拉**（登录页底部同样要展示备案信息）
  siteInfo.load()
  // 客户端下载配置：决定页脚是否显示「客户端下载」入口，登录前也要拉
  download.load()
  // 场景模板：登录后拉一次，侧栏菜单等术语随之切换（未配置回落通用）
  // 读接口要求登录态，匿名请求只会换来一条 401 噪音，故与 wm.load() 同样按登录态把关；
  // 登录页是 router.push 进来的（不刷新页面），所以登录成功后再补拉一次。
  if (auth.loggedIn) scenario.load()
  // 水印配置与 client-info 都要求登录态（匿名访问会 401），仅登录后拉取；
  // 匿名用户本就不显示水印，无需请求。等 fetchMe 恢复登录态/令牌后再调，避免 401 噪音。
  if (auth.loggedIn) wm.load()
  if (auth.loggedIn) {
    notification.connect(); loadFlags(); loadAppearance()
    refreshDmUnread()                                   // 私信未读红点：首屏拉一次
    window.addEventListener('kb:dm', refreshDmUnread)    // 收到私信事件时刷新红点
  }
})

// 外观配置见 utils/appearance.js（共享缓存：Logo + 资料页背景，一次拉取全站使用）

// 登录成功（含另一标签页广播来的令牌）后再补拉场景模板：登录是 SPA 跳转，不会重跑 onMounted
watch(() => auth.loggedIn, (v) => { if (v) scenario.load() })

// 核心维护开关变更后即时刷新侧边栏菜单（无需整页刷新）：
// 监听全局事件 + 路由守卫缓存重置，保证普通用户立即看到入口变化。
function onFlagsChanged() {
  loadFlags()
}
window.addEventListener('kb:feature-flags-changed', onFlagsChanged)
onUnmounted(() => window.removeEventListener('kb:feature-flags-changed', onFlagsChanged))

// 登录态变化（登录 / 登出 / 令牌过期）时同步 SSE 连接与未读
// 登录成功后补拉水印配置（覆盖「本页登录」场景，否则水印参数仍是 localStorage 兜底值）
watch(() => auth.loggedIn, (v) => {
  if (v) { notification.connect(); wm.load(); loadAppearance() }   // SPA 登录不刷新页面也要拉自定义 Logo
  else { notification.disconnect(); notification.setUnread(0); resetAppearance() }   // 登出恢复内置默认，与匿名态一致
})

// 浏览器窗口缩放 / 全屏切换时，把阅读浮窗夹回可视区内（避免被挤出屏幕外）
window.addEventListener('resize', clampToViewport)
onUnmounted(() => window.removeEventListener('resize', clampToViewport))   // L5（2026-10-13 审查）

// 令牌过期时由 axios 拦截器广播，自动退出
function onUnauthorized() {
  if (auth.loggedIn) {
    auth.logout()
    ElMessage.warning('登录已过期，请重新登录')
    // 只清登录态不跳转的话，页面会停在原地直到用户点别的菜单
    router.push('/login')
  }
}
window.addEventListener('kb-unauthorized', onUnauthorized)
onUnmounted(() => window.removeEventListener('kb-unauthorized', onUnauthorized))

// 登录页 / 独立阅读窗口：独立整页，不带侧边栏与顶栏。
// 首次导航完成前 route 仍是 START_LOCATION（path='/'、matched 为空），若按普通页渲染会
// 闪一帧带旧标签的外壳（刷新 /login 时尤其明显）；就绪前一律按 barePage 只渲染 router-view。
const routerReady = ref(route.matched.length > 0)
router.isReady().then(() => { routerReady.value = true }).catch(() => { routerReady.value = true })
const barePage = computed(() => !routerReady.value || route.path === '/login' || route.path.startsWith('/reader/'))

// 未读通知数（顶栏铃铛红点，来自 SSE 实时推送，详见 store/notification）
function goNotifications() { router.push('/notifications') }

// 侧边菜单折叠/展开状态（持久化到 localStorage，刷新后保持）
const collapsed = ref(localStorage.getItem('kb_sidebar_collapsed') === '1')
function toggleSidebar() {
  collapsed.value = !collapsed.value
  localStorage.setItem('kb_sidebar_collapsed', collapsed.value ? '1' : '0')
}

// ---- 偏好设置驱动的骨架形态（2026-10-22）----
// 侧栏折叠后，鼠标悬停可临时展开（prefs.hoverExpand），移开自动收回
const hoveringAside = ref(false)
const menuCollapsed = computed(() => !isNarrow.value && collapsed.value
  && !(prefs.state.hoverExpand && hoveringAside.value))
// 顶部导航只在宽屏生效：窄屏放不下横排菜单，一律回退到侧边抽屉
const isTopNav = computed(() => prefs.state.navMode === 'top' && !isNarrow.value)
// 侧栏是否渲染：窄屏始终保留（抽屉），宽屏看偏好里的「显示侧边栏」
const showAside = computed(() => {
  if (isNarrow.value) return true                       // 窄屏一律走抽屉
  if (isMixedNav.value) return secondaryItems.value.length > 0   // 混合双列：有二级项才显示
  if (isTopNav.value) return false
  return prefs.state.sidebarVisible || drawerOpen.value
})
const asideWidth = computed(() => {
  if (isNarrow.value) return '210px'
  if (isMixedNav.value) return secondaryItems.value.length ? '92px' : '0px'
  return menuCollapsed.value ? '64px' : '210px'
})

// ---------- 响应式骨架 ----------
// ≤1024px 视为窄屏：侧边栏改为抽屉（覆盖式滑出 + 遮罩），不再占文档流宽度；
// >1024px 保持原来的"折叠/展开"两态。
const MOBILE_MQ = '(max-width: 1024px)'
const isNarrow = ref(window.matchMedia ? window.matchMedia(MOBILE_MQ).matches : false)
const drawerOpen = ref(false)
let mq = null
function onMqChange(e) {
  isNarrow.value = e.matches
  if (!e.matches) drawerOpen.value = false      // 回到桌面宽度时收起抽屉
}
onMounted(() => {
  if (!window.matchMedia) return
  mq = window.matchMedia(MOBILE_MQ)
  mq.addEventListener('change', onMqChange)
})
onUnmounted(() => { if (mq) mq.removeEventListener('change', onMqChange) })

// 顶栏菜单按钮：窄屏开合抽屉；顶部导航模式无侧栏；侧栏被隐藏时先唤回
function toggleMenu() {
  if (isNarrow.value) { drawerOpen.value = !drawerOpen.value; return }
  if (prefs.state.navMode === 'top') return
  if (!prefs.state.sidebarVisible) { prefs.set({ sidebarVisible: true }); return }
  toggleSidebar()
}
// 窄屏下点菜单跳转后自动收起抽屉（否则菜单挡住内容）
watch(() => route.fullPath, () => { drawerOpen.value = false })

// ---------------- 顶部多标签页（中后台常见交互） ----------------
// 首页固定不可关；其余按访问顺序累积，sessionStorage 持久化（刷新/切页不丢）。
const HOME_TAB = { path: '/', title: '首页', pinned: true }
const TABS_KEY = 'kb_tabs'
const tabs = ref([{ ...HOME_TAB }])
function loadTabs() {
  try {
    const arr = JSON.parse(sessionStorage.getItem(TABS_KEY) || 'null')
    if (Array.isArray(arr)) {
      tabs.value = [{ ...HOME_TAB }, ...arr.filter((t) => t && t.path && t.path !== '/' && t.path !== '/login')]
    }
  } catch { /* ignore */ }
}
function saveTabs() {
  try { sessionStorage.setItem(TABS_KEY, JSON.stringify(tabs.value.filter((t) => !t.pinned))) } catch { /* ignore */ }
}
// 换账号/登出时清空标签栏：标签只按访问顺序累积在 sessionStorage，退出登录不清，
// 下一个人登进来会看到上一个人的标签（N77）。
// 不能只看「id 变了就清」：刷新页面时 store 初始化 user 为 null，fetchMe 回来后是 null→id，
// 那样每次刷新都会把刚恢复的标签清掉，与「刷新不丢」矛盾。所以另外记一个归属 id：
// 只有「登出」或「归属 id 与当前账号不一致」才清，刷新时两者一致、原样保留。
const TABS_OWNER_KEY = 'kb_tabs_owner'
function tabsOwner() {
  try { return sessionStorage.getItem(TABS_OWNER_KEY) || '' } catch { return '' }
}
function setTabsOwner(id) {
  try {
    if (id == null) sessionStorage.removeItem(TABS_OWNER_KEY)
    else sessionStorage.setItem(TABS_OWNER_KEY, String(id))
  } catch { /* ignore */ }
}
function resetTabs() {
  tabs.value = [{ ...HOME_TAB }]
  try { sessionStorage.removeItem(TABS_KEY) } catch { /* ignore */ }
}
watch(() => auth.user?.id ?? null, (id) => {
  // 登出时**故意保留**归属 id：这样下一个人登录进来，owner !== 当前 id 就能判出「换号」并清空；
  // 同一账号重登则保留原标签。
  if (id == null) return
  const owner = tabsOwner()
  if (owner && owner !== String(id)) resetTabs()   // 换号：清掉上一个人的标签
  setTabsOwner(id)
})
function addTab(r) {
  if (barePage.value || !r?.path || r.path === '/') return
  if (tabs.value.some((t) => t.path === r.path)) return
  tabs.value.push({ path: r.path, title: r.meta?.title || '页面' })
  saveTabs()
}
function goTab(path) { if (path !== route.path) router.push(path) }

// 多标签横向滚动：滚轮是垂直 deltaY，浏览器不会自动转成横向滚动，这里显式映射。
// 触控板横向手势（deltaX）也兼容；滚到两端时不再接管，页面可继续正常纵向滚动。
const tabScrollEl = ref(null)
function onTabWheel(e) {
  const el = e.currentTarget
  const unit = e.deltaMode === 1 ? 16 : (e.deltaMode === 2 ? el.clientWidth : 1)
  const delta = (Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY) * unit
  if (!delta) return
  const max = el.scrollWidth - el.clientWidth
  if (max <= 0) return
  if ((delta < 0 && el.scrollLeft <= 0) || (delta > 0 && el.scrollLeft >= max - 1)) return
  e.preventDefault()
  el.scrollLeft = Math.max(0, Math.min(max, el.scrollLeft + delta))
}
// 打开/切换标签后把激活项滚进可视区（标签多时新开的总在最右侧，否则用户看不到当前页）
watch(() => route.path, () => {
  nextTick(() => {
    const el = tabScrollEl.value
    const on = el?.querySelector?.('.tab.on')
    if (!el || !on) return
    const left = on.offsetLeft, right = left + on.offsetWidth
    if (left < el.scrollLeft) el.scrollLeft = Math.max(0, left - 8)
    else if (right > el.scrollLeft + el.clientWidth) el.scrollLeft = right - el.clientWidth + 8
  })
})
function closeTab(path) {
  const i = tabs.value.findIndex((t) => t.path === path)
  if (i < 0 || tabs.value[i].pinned) return
  tabs.value.splice(i, 1)
  saveTabs()
  if (route.path === path) {
    const next = tabs.value[i] || tabs.value[i - 1] || tabs.value[0]
    if (next) router.push(next.path)
  }
}
// 可关闭的标签数（首页固定不可关）
const closableCount = computed(() => tabs.value.filter((t) => !t.pinned).length)
/** 一键关闭全部（保留首页） */
function closeAllTabs() {
  if (!closableCount.value) return
  tabs.value = tabs.value.filter((t) => t.pinned)
  saveTabs()
  if (route.path !== '/') router.push('/')
}
/** 关闭其他（保留首页与目标页） */
function closeOtherTabs(path) {
  tabs.value = tabs.value.filter((t) => t.pinned || t.path === path)
  saveTabs()
  if (route.path !== path) router.push(path)
}
/** 关闭右侧（保留首页、目标页及其左侧） */
function closeRightTabs(path) {
  const i = tabs.value.findIndex((t) => t.path === path)
  if (i < 0) return
  tabs.value = tabs.value.filter((t, idx) => idx <= i || t.pinned)
  saveTabs()
  if (!tabs.value.some((t) => t.path === route.path)) router.push(path)
}
// 标签右键菜单（关闭其他 / 关闭右侧 / 全部关闭）
const tabMenu = ref({ open: false, x: 0, y: 0, path: '' })
function openTabMenu(e, t) {
  e.preventDefault()
  tabMenu.value = { open: true, x: e.clientX, y: e.clientY, path: t.path }
}
function closeTabMenu() { tabMenu.value.open = false }
function onDocClickTabMenu() { if (tabMenu.value.open) closeTabMenu() }
onMounted(() => document.addEventListener('click', onDocClickTabMenu))
onUnmounted(() => document.removeEventListener('click', onDocClickTabMenu))
loadTabs()
watch(() => route.fullPath, () => { addTab(route) }, { immediate: true })

// 顶栏全局搜索
const globalQ = ref('')
function doGlobalSearch() {
  const q = (globalQ.value || '').trim()
  if (!q) return
  router.push({ path: '/search', query: { q } })
}

// 小窗阅读：图片点开放大 + 视频增强（16:9 占位/倍速/全屏/画中画），样式在全局 responsive.css
const rfRef = ref(null)
const readerLightbox = ref('')
      // 组合监听 open + content：closeReader 只置 open=false 不清 content，
      // 只监听 content 的话「关掉再打开同一篇」不会重新绑定灯箱/视频增强
      watch([() => reader.open, () => reader.content], async () => {
  await nextTick()
  if (!reader.open || !rfRef.value) return
  absolutizeMedia(rfRef.value)
  enhanceVideos(rfRef.value)
  bindImgLightbox(rfRef.value, (src) => { if (src) readerLightbox.value = src })
})

// 夜间模式切换（B站风格：月亮/太阳 + 旋转淡入 + 过渡）
const isDark = ref(document.documentElement.classList.contains('dark'))
const spinning = ref(false)
function toggleTheme() {
  const next = !isDark.value
  // 顶栏按钮视为「显式选择」，覆盖偏好里的「跟随系统」；主题 class 由 prefs 的 watch 统一 apply
  prefs.set({ theme: next ? 'dark' : 'light' })
  isDark.value = next
  wm.dark = next            // 同步水印暗色状态，使水印颜色按主题自适应（暗色下用浅色，保证可见）
  spinning.value = true     // 图标旋转一次
  setTimeout(() => (spinning.value = false), 520)
}

// 主题来源可能有三处：偏好页、跟随系统、顶栏按钮 —— 统一由 kb:theme 事件同步图标与水印配色
function onThemeChanged(e) {
  const dark = !!e?.detail?.dark
  isDark.value = dark
  wm.dark = dark
}
window.addEventListener('kb:theme', onThemeChanged)
onUnmounted(() => window.removeEventListener('kb:theme', onThemeChanged))

// 拖拽结束后的那次 click 不能触发菜单跳转
window.addEventListener('click', onCaptureClick, true)
onUnmounted(() => window.removeEventListener('click', onCaptureClick, true))


// 菜单项：按角色个性化
//  - 所有登录用户：写文章、我的帖子
//  - 管理类按细粒度权限归入「用户与权限」「内容管理」「站点设置」三个二级分组（ADMIN 隐式拥有全部，可见全部分组）
const menus = computed(() => {
  // 菜单标题走场景模板术语：t(key, 默认文案)，未配置场景时全部回落默认叫法
  const t = (k, d) => scenario.t(k, d)
  const base = [{ index: '/', title: t('home', '知识库首页'), icon: 'House' }]
  if (auth.loggedIn) {
    // 普通功能入口：受核心维护开关控制，关闭则不在侧边栏展示
    if (featureOpen('docs')) base.push(
      { index: '/write', title: t('write', '写文章'), icon: 'EditPen' },
      { index: '/my/posts', title: t('myPosts', '我的帖子'), icon: 'Files' },
      { index: '/recycle', title: t('recycle', '回收站'), icon: 'Delete' },
      { index: '/subscriptions', title: t('subscriptions', '我的订阅'), icon: 'Bell' },
    )
    if (featureOpen('profile')) base.push(
      { index: '/favorites', title: t('favorites', '我的收藏'), icon: 'Star' },
      { index: '/history', title: t('history', '浏览历史'), icon: 'Clock' },
      { index: '/badges', title: t('badges', '我的勋章'), icon: 'Medal' },
      { index: '/files', title: t('files', '我的文件'), icon: 'Upload' },
    )
    if (featureOpen('ranking')) base.push({ index: '/ranking', title: t('ranking', '积分排行榜'), icon: 'TrendCharts' })
    if (featureOpen('ai_chat')) base.push({ index: '/ai/chat', title: t('aiChat', 'AI 问答'), icon: 'AiSpark' })
    base.push({ index: '/messages', title: t('messages', '消息中心'), icon: 'ChatDotRound' })
    base.push({ index: '/mail', title: t('mail', '邮件'), icon: 'Message' })
    if (featureOpen('courses')) base.push({ index: '/study/courses', title: t('courses', '培训'), icon: 'Reading' })
    // 考试与练习：原 7 个入口收敛为「在线考试」「练习」两个主入口（原「答题闯关」/quiz 并入在线考试），
    // 错题本 / 我的成绩 / 我的题库 在页内用 tab 切换（2026-10-29 入口收敛）
    if (featureOpen('exams') || featureOpen('quiz')) {
      base.push({ index: '/exams', title: t('exams', '在线考试'), icon: 'Trophy' })
      base.push({ index: '/exams/practice', title: t('practice', '练习'), icon: 'Edit' })
    }
    if (featureOpen('doc_shares')) base.push({ index: '/doc-shares', title: t('docShares', '共享文档'), icon: 'DocumentCopy' })
    if (featureOpen('doc_shares')) base.push({ index: '/doc-shares/all', title: t('allDocs', '全部文档'), icon: 'Tickets' })
  }

  // 二级分组：用户与权限（用户管理 / 黑名单 / 操作审计）——账号、权限、黑名单与「谁操作了什么」归人域
  const userMgmt = []
  if (auth.hasPerm('USER_MANAGE')) userMgmt.push({ index: '/admin/users', title: '用户管理', icon: 'User' })
  if (auth.hasPerm('USER_MANAGE')) userMgmt.push({ index: '/admin/roles', title: '角色管理', icon: 'Suitcase' })
  if (auth.hasPerm('USER_MANAGE')) userMgmt.push({ index: '/admin/departments', title: '部门管理', icon: 'OfficeBuilding' })
  // 批量导入用户：企业部署时先导组织架构与账号
  if (auth.hasPerm('USER_MANAGE')) userMgmt.push({ index: '/admin/user-import', title: '批量导入用户', icon: 'Upload' })
  if (auth.isAdmin || auth.hasPerm('ADMIN_VIEW') || auth.hasPerm('AUDIT_VIEW') || auth.hasPerm('LOGIN_LOG_VIEW')) {
    // 黑名单：无对应后端接口，仅 ADMIN 角色可见
    if (auth.isAdmin) userMgmt.push({ index: '/admin/blacklist', title: '黑名单', icon: 'CircleClose' })
    // 操作审计 / 登录日志：严格按各自独立权限码显示（不回退 ADMIN_VIEW，与后端判定一致）
    if (auth.isAdmin || auth.hasPerm('AUDIT_VIEW')) {
      userMgmt.push({ index: '/admin/audit', title: '操作审计', icon: 'List' })
    }
    if (auth.isAdmin || auth.hasPerm('LOGIN_LOG_VIEW')) {
      userMgmt.push({ index: '/admin/login-logs', title: '登录日志', icon: 'Key' })
    }
  }
  if (userMgmt.length) base.push({ index: 'g-user', title: '用户与权限', icon: 'Management', children: userMgmt })

  // 二级分组：内容管理（帖子 / 分类标签 / 公告 / 附件 / 数据看板）
  // 原「数据中心」单独成组已并入此处，避免「仅一项」的空壳分组；内容资源 + 内容数据聚同一域
  // 站点级配置（功能开关 / 水印 / 登录二维码 / 外观）已移至下方「站点设置」组
  const contentMgmt = []
  if (auth.hasPerm('DOC_REVIEW')) contentMgmt.push({ index: '/admin/review', title: '文章审核', icon: 'Finished' })
  if (auth.hasPerm('POST_MANAGE')) contentMgmt.push({ index: '/admin/docs', title: '帖子管理', icon: 'Memo' })
  if (auth.hasPerm('TAXONOMY_MANAGE')) contentMgmt.push({ index: '/admin/taxonomy', title: '分类标签', icon: 'PriceTag' })
  if (auth.hasPerm('ANNOUNCEMENT_MANAGE')) contentMgmt.push({ index: '/admin/announcements', title: '公告管理', icon: 'Notification' })
  if (auth.hasPerm('BANNED_WORDS_MANAGE')) contentMgmt.push({ index: '/admin/banned-words', title: '违禁词管理', icon: 'Warning' })
  if (auth.isAdmin || auth.hasPerm('ADMIN_VIEW')) contentMgmt.push({ index: '/admin/files', title: '附件管理', icon: 'Folder' })
  if (auth.hasPerm('STATS_VIEW')) contentMgmt.push({ index: '/admin/stats', title: '数据看板', icon: 'DataLine' })
  if (auth.hasPerm('COURSE_MANAGE')) contentMgmt.push({ index: '/admin/courses', title: '培训管理', icon: 'Notebook' })
  if (auth.hasPerm('EXAM_MANAGE')) contentMgmt.push({ index: '/admin/exams', title: '题库与试卷', icon: 'Trophy' })
  if (auth.hasPerm('EXAM_MANAGE')) contentMgmt.push({ index: '/admin/exam-grading', title: '主观题阅卷', icon: 'EditPen' })
  if (auth.hasPerm('SHARE_VIEW')) contentMgmt.push({ index: '/admin/doc-shares', title: '共享文档管理', icon: 'FolderOpened' })
  if (auth.hasPerm('ASSET_MANAGE')) contentMgmt.push({ index: '/admin/assets', title: '资产管理', icon: 'Box' })
  // 检索测试：管理员诊断搜索质量（搜得到 / 搜得准）；演示账号（ADMIN_VIEW）也可只读探测
  if (auth.isAdmin || auth.hasPerm('ADMIN_VIEW')) contentMgmt.push({ index: '/admin/search-test', title: '检索测试', icon: 'Search' })
  // AI 模型管理：细粒度权限 AI_MANAGE（列表对 Key 脱敏，演示号勾上后只读）
  if (auth.isAdmin || auth.hasPerm('AI_MANAGE')) contentMgmt.push({ index: '/admin/ai-models', title: 'AI 模型管理', icon: 'Cpu' })
  if (auth.isAdmin || auth.hasPerm('AI_MANAGE')) contentMgmt.push({ index: '/admin/ai-capabilities', title: 'AI 能力配置', icon: 'SetUp' })
  // AI 反馈与知识缺口：赞/踩 + 检索未命中，指导补文档（只读）
  if (auth.isAdmin || auth.hasPerm('AI_MANAGE')) contentMgmt.push({ index: '/admin/ai-feedback', title: 'AI 反馈', icon: 'Collection' })
  if (contentMgmt.length) base.push({ index: 'g-content', title: '内容管理', icon: 'Document', children: contentMgmt })

  // 二级分组：站点设置（功能开关 / 水印 / 登录页二维码 / 外观）
  // 站点级配置，与「内容资源」分离：调的是整站的开关与呈现，不涉及具体条目
  const siteConfig = []
  // 站点设置细分（2026-10-28）：三项各有独立权限码，CORE_MAINTAIN 作为总码仍可全通
  if (auth.hasPerm('SITE_FEATURE_MANAGE|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/maintain', title: '功能开关', icon: 'Setting' })
  if (auth.hasPerm('SITE_SETTINGS_MANAGE|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/watermark', title: '水印设置', icon: 'Stamp' })
  if (auth.hasPerm('SITE_SETTINGS_MANAGE|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/notify-webhook', title: '通知外发', icon: 'Promotion' })
  if (auth.hasPerm('SITE_SETTINGS_MANAGE|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/mail', title: '邮件设置', icon: 'Message' })
  if (auth.hasPerm('SSO_MANAGE|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/sso-ldap', title: '企业登录', icon: 'Connection' })
  if (auth.hasPerm('SITE_SETTINGS_MANAGE|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/login-qr', title: '登录页二维码', icon: 'Grid' })
  if (auth.hasPerm('SITE_SETTINGS_MANAGE|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/appearance', title: '外观设置', icon: 'Brush' })
  if (auth.hasPerm('SITE_SETTINGS_MANAGE|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/site-footer', title: '页脚设置', icon: 'Postcard' })
  if (auth.hasPerm('SITE_SETTINGS_MANAGE|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/download', title: '客户端下载', icon: 'Download' })
  // 场景模板：按部署单位选叫法（医院/学校/企业），只改菜单术语不动数据
  if (auth.hasPerm('SITE_SETTINGS_MANAGE|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/scenario', title: '场景模板', icon: 'Collection' })
  // 系统状态：Redis / 第三方登录连通性 + Redis 开关
  if (auth.hasPerm('CORE_MAINTAIN')) siteConfig.push({ index: '/admin/reports', title: '举报处理', icon: 'Warning' })
  if (auth.hasPerm('SYSTEM_STATUS_VIEW|CORE_MAINTAIN')) siteConfig.push({ index: '/admin/system', title: '系统状态', icon: 'Monitor' })
  if (siteConfig.length) base.push({ index: 'g-site', title: '站点设置', icon: 'Tools', children: siteConfig })

  if (auth.loggedIn) {
    // 偏好设置紧邻个人资料（2026-10-22：只放头像下拉时用户找不到，提到侧边栏一级入口）
    base.push({ index: '/preferences', title: '偏好设置', icon: 'Operation' })
    // 个人资料放在最底部，符合常见布局习惯
    base.push({ index: '/profile', title: '个人资料', icon: 'UserFilled' })
  }
  return base
})

// ---- 菜单排序 & 双列导航（2026-10-22）----
// 一级菜单顺序：偏好里存 index 数组；没存过按默认顺序，新出现的菜单自动排到末尾
const orderedMenus = computed(() => {
  const list = menus.value
  const order = prefs.state.menuOrder || []
  if (!order.length) return list
  const map = new Map(list.map((m) => [m.index, m]))
  const out = []
  for (const idx of order) {
    const m = map.get(idx)
    if (m) { out.push(m); map.delete(idx) }
  }
  // 新增菜单还没进用户的偏好排序时，插到它的「默认锚点」后面（如邮件跟在消息中心后），
  // 而不是一股脑排到末尾落到管理分组下面
  const ANCHOR = { '/mail': '/messages' }
  for (const m of map.values()) {
    const at = ANCHOR[m.index] ? out.findIndex((x) => x.index === ANCHOR[m.index]) : -1
    if (at >= 0) out.splice(at + 1, 0, m)
    else out.push(m)
  }
  return out
})

// 双列菜单：只在宽屏生效（窄屏没有横向空间，回退侧边抽屉）
/** 分组内子菜单排序：按 subMenuOrder[分组 index] 排，没排过的保持默认，新子项排末尾 */
function orderedChildren(m) {
  const list = m.children || []
  const order = (prefs.state.subMenuOrder || {})[m.index] || []
  if (!order.length) return list
  const map = new Map(list.map((c) => [c.index, c]))
  const out = []
  for (const idx of order) {
    const c = map.get(idx)
    if (c) { out.push(c); map.delete(idx) }
  }
  for (const c of map.values()) out.push(c)
  return out
}

// 混合双列：顶部放一级导航，左侧保留双列（窄图标栏 + 二级列），只在宽屏生效
const isMixedNav = computed(() => prefs.state.navMode === 'dual' && !isNarrow.value)

// 双列左栏选中的一级菜单：默认跟随当前路由，点其它一级时临时切换
const primaryOverride = ref('')
const activePrimaryIndex = computed(() => {
  if (primaryOverride.value) return primaryOverride.value
  const hit = orderedMenus.value.find((m) => route.path === m.index
    || (m.children || []).some((c) => route.path === c.index || route.path.startsWith(c.index + '/')))
  return hit ? hit.index : (orderedMenus.value[0]?.index || '')
})
const activePrimaryMenu = computed(
  () => orderedMenus.value.find((m) => m.index === activePrimaryIndex.value) || null)
const secondaryItems = computed(() => {
  const m = activePrimaryMenu.value
  if (!m) return []
  // 没有子项的一级菜单（知识库首页、写文章…）右列留空，点了直接跳转，不再把自己当二级项重复显示
  if (!m.children || !m.children.length) return []
  // 有子项时走自定义排序，和侧边栏共用一份顺序
  return orderedChildren(m)
})
function onMixedPrimaryClick(m) {
  if (m.children && m.children.length) pickPrimary(m)
  else { primaryOverride.value = ''; router.push(m.index) }
}

function pickPrimary(m) {
  // 没有子项的一级菜单点了就直接跳
  if (!m.children || !m.children.length) {
    primaryOverride.value = ''
    router.push(m.index)
    return
  }
  primaryOverride.value = m.index
}
watch(() => route.fullPath, () => { primaryOverride.value = '' })

// ---- 菜单拖拽排序（pointer 事件，桌面 + 触屏通用）----
// 不用 HTML5 drag：它在 <button> 上不派发 dragover，el-menu 的 drop 也不可靠（实测过）。
// 动画只动 transform / opacity，走合成层，不触发布局。
const dragFrom = ref(-1)
const dragOver = ref(-1)
const dragStep = ref(48)
const subDrag = ref({ group: '', from: -1 })
const subOver = ref({ group: '', index: -1 })
const subStep = ref(60)
const justDragged = ref(false)
const settleKey = ref('')
let press = null
let ghostEl = null
let slotEl = null
let slotInit = false   // 本次拖拽是否已定位过占位槽（首次定位要冻结过渡，避免从上一次终点滑入）

/** 一级分组如果展开着，li 的高度会把整段子菜单算进来；排序只按标题行量 */
function rowEl(el) {
  return el && el.classList.contains('el-sub-menu')
    ? (el.querySelector('.el-sub-menu__title') || el)
    : el
}
function measureStep(el, gap) {
  if (!el) return 48
  const row = rowEl(el)
  const cs = getComputedStyle(row)
  const mt = parseFloat(cs.marginTop) || 0
  const mb = parseFloat(cs.marginBottom) || 0
  return Math.round((row.offsetHeight || 42) + mt + mb + (gap || 0))
}
function rectsOf(items) {
  return items.map((el) => {
    const r = rowEl(el).getBoundingClientRect()
    return { top: r.top, bottom: r.bottom, left: r.left, width: r.width }
  })
}
function indexAtY(rects, y) {
  if (!rects.length) return -1
  if (y <= rects[0].top) return 0
  // 行间有 3px margin，落在缝里时归到下一行，避免指针抖一下就被甩到列表末尾
  for (let i = 0; i < rects.length; i++) if (y < rects[i].bottom) return i
  return rects.length - 1
}
/** 让位位移：from<over 时区间内整体上移一格，from>over 时下移一格 */
function menuShift(i) {
  const from = dragFrom.value, over = dragOver.value
  if (from < 0 || over < 0 || from === over || i === from) return 0
  if (from < over && i > from && i <= over) return -dragStep.value
  if (from > over && i >= over && i < from) return dragStep.value
  return 0
}
function menuStyle(i) {
  const y = menuShift(i)
  return y ? { transform: 'translateY(' + y + 'px)' } : null
}
function subShift(i) {
  const from = subDrag.value.from, over = subOver.value.index
  if (subDrag.value.group !== subOver.value.group) return 0
  if (from < 0 || over < 0 || from === over || i === from) return 0
  if (from < over && i > from && i <= over) return -subStep.value
  if (from > over && i >= over && i < from) return subStep.value
  return 0
}
function subStyle(i) {
  const y = subShift(i)
  return y ? { transform: 'translateY(' + y + 'px)' } : null
}

/** 拖拽浮层：把当前项的内容抬到 pointer 上方，源项留在原位当占位 */
function makeGhost(el) {
  const src = rowEl(el)
  const r = src.getBoundingClientRect()
  const cs = getComputedStyle(src)
  const dark = !!el.closest('.dark-menu') || document.documentElement.classList.contains('dark')
  const transparent = !cs.backgroundColor || cs.backgroundColor === 'transparent' || cs.backgroundColor === 'rgba(0, 0, 0, 0)'
  const aside = el.closest('.aside')
  const asideBg = aside ? getComputedStyle(aside).backgroundColor : ''
  const fallbackBg = asideBg && asideBg !== 'rgba(0, 0, 0, 0)' ? asideBg : (dark ? '#1f2430' : '#ffffff')
  const isColumn = cs.flexDirection === 'column'
  const g = document.createElement('div')
  g.className = 'kb-drag-ghost'
  // 外层只负责跟手位移（不加过渡，避免拖影）；内层负责「拿起」缩放，互不干扰
  const inner = document.createElement('div')
  inner.className = 'kbg-inner'
  inner.innerHTML = src.innerHTML
  Object.assign(g.style, {
    position: 'fixed',
    left: r.left + 'px',
    top: r.top + 'px',
    width: r.width + 'px',
    height: r.height + 'px',
    margin: '0',
    borderRadius: '10px',
    background: transparent ? fallbackBg : cs.backgroundColor,
    overflow: 'hidden',
    boxShadow: '0 14px 30px rgba(15, 23, 42, .20), 0 3px 8px rgba(15, 23, 42, .10), 0 0 0 1px color-mix(in srgb, var(--el-color-primary) 22%, transparent)',
    pointerEvents: 'none',
    zIndex: '4000',
    opacity: '0',
    transform: 'translate3d(0, 0, 0)',
    willChange: 'transform, opacity',
    transition: 'opacity .12s ease',
  })
  Object.assign(inner.style, {
    width: '100%',
    height: '100%',
    boxSizing: 'border-box',
    display: 'flex',
    flexDirection: isColumn ? 'column' : 'row',
    alignItems: 'center',
    justifyContent: isColumn ? 'center' : 'flex-start',
    gap: cs.gap === 'normal' || !cs.gap ? '10px' : cs.gap,
    padding: cs.padding,
    color: cs.color,
    fontSize: cs.fontSize,
    fontWeight: cs.fontWeight,
    fontFamily: cs.fontFamily,
    lineHeight: cs.lineHeight,
    letterSpacing: cs.letterSpacing,
    transform: 'scale(1)',
    transformOrigin: 'center',
    transition: 'transform .16s cubic-bezier(.2, .8, .2, 1)',
  })
  g.appendChild(inner)
  document.body.appendChild(g)
  // 下一帧再放大，形成「拿起」的过渡
  requestAnimationFrame(() => {
    if (!g.isConnected || g.dataset.closing) return
    g.style.opacity = '1'
    inner.style.transform = 'scale(1.03)'
  })
  return g
}
function moveGhost(dx, dy) {
  if (ghostEl) ghostEl.style.transform = `translate3d(${dx}px, ${dy}px, 0)`
}
function removeGhost() {
  const g = ghostEl
  ghostEl = null
  if (!g) return
  const inner = g.querySelector('.kbg-inner')
  g.dataset.closing = '1'
  g.style.transition = 'opacity .14s ease'
  g.style.opacity = '0'
  if (inner) inner.style.transform = 'scale(.985)'
  setTimeout(() => g.remove(), 160)
}
/** 落点占位槽：跟着目标位置走，而不是在目标项上画死线 */
function showSlot(rect, step) {
  if (!rect) return
  if (!slotEl) {
    slotEl = document.createElement('div')
    slotEl.className = 'kb-drop-slot'
    slotEl.innerHTML = '<i></i>'
    document.body.appendChild(slotEl)
  }
  // 每次拖拽的首次定位都冻结过渡直接落位：否则会带着上一次拖拽的终点位置滑过来
  const fresh = !slotInit
  if (fresh) {
    slotInit = true
    slotEl.style.transition = 'none'
  }
  slotEl.style.top = (rect.top + 2) + 'px'
  slotEl.style.left = (rect.left + 6) + 'px'
  slotEl.style.width = Math.max(0, rect.width - 12) + 'px'
  slotEl.style.height = Math.max(26, step - 6) + 'px'
  slotEl.classList.add('on')
  if (fresh) requestAnimationFrame(() => { if (slotEl) slotEl.style.transition = '' })
}
function hideSlot() {
  if (slotEl) slotEl.classList.remove('on')
}

function onPressStart(kind, group, i, e) {
  if (e.pointerType === 'mouse' && e.button !== 0) return
  const el = e.currentTarget
  const box = el && el.parentElement
  const sel = kind === 'primary' ? ':scope > li' : ':scope > .ms-item, :scope > .el-menu-item'
  const items = box ? [...box.querySelectorAll(sel)] : [el]
  slotInit = false
  press = {
    kind,
    group,
    i,
    el,
    items,
    x: e.clientX,
    y: e.clientY,
    active: false,
    rects: rectsOf(items),
    step: measureStep(el, kind === 'sub' && el.classList.contains('ms-item') ? 4 : 0),
    measuredAt: 0,
  }
  window.addEventListener('pointermove', onPressMove)
  window.addEventListener('pointerup', onPressUp, { once: true })
  window.addEventListener('pointercancel', onPressUp, { once: true })
}

function onPressMove(e) {
  if (!press) return
  if (!press.active) {
    if (Math.abs(e.clientY - press.y) < 5) return   // 5px 内视为点击，不打断正常跳转
    press.active = true
    if (press.kind === 'primary') {
      dragStep.value = press.step
      dragFrom.value = press.i
      dragOver.value = press.i
    } else {
      subStep.value = press.step
      subDrag.value = { group: press.group, from: press.i }
      subOver.value = { group: press.group, index: press.i }
    }
    removeGhost()   // 多指/异常重入时上一个浮层可能还挂着，先淡出避免孤儿浮层
    ghostEl = makeGhost(press.el)
    showSlot(press.rects[press.i], press.step)
    document.body.classList.add('kb-dragging')
  }
  // 拖拽中布局可能变化（hoverExpand 收起、窗口缩放、aside 卸载）：节流重测，避免槽位/命中错位
  const now = performance.now()
  if (!press.measuredAt || now - press.measuredAt > 100) {
    press.measuredAt = now
    press.rects = rectsOf(press.items)
    press.step = measureStep(press.el, press.kind === 'sub' && press.el.classList.contains('ms-item') ? 4 : 0)
  }
  moveGhost(e.clientX - press.x, e.clientY - press.y)
  const i = indexAtY(press.rects, e.clientY)
  if (i < 0) return
  if (press.kind === 'primary') dragOver.value = i
  else subOver.value = { group: press.group, index: i }
  showSlot(press.rects[i], press.step)
}

function onPressUp() {
  window.removeEventListener('pointermove', onPressMove)
  window.removeEventListener('pointerup', onPressUp)
  window.removeEventListener('pointercancel', onPressUp)
  if (!press) return
  const { kind, group, i, active } = press
  const to = kind === 'primary' ? dragOver.value : subOver.value.index
  press = null
  document.body.classList.remove('kb-dragging')
  hideSlot()
  if (!active) { removeGhost(); return }
  justDragged.value = true
  setTimeout(() => { justDragged.value = false }, 0)
  // 重排和让位复位在同一帧完成；先禁掉过渡，避免「先回弹再重排」的闪动
  document.body.classList.add('kb-drop-commit')
  if (kind === 'primary') commitMenuOrder(i, to)
  else commitSubOrder(group, i, to)
  dragFrom.value = -1
  dragOver.value = -1
  subDrag.value = { group: '', from: -1 }
  subOver.value = { group: '', index: -1 }
  requestAnimationFrame(() => requestAnimationFrame(() => document.body.classList.remove('kb-drop-commit')))
  if (to !== i && to >= 0) {
    const key = kind === 'primary' ? 'p:' + to : 's:' + group + ':' + to
    settleKey.value = key
    setTimeout(() => { if (settleKey.value === key) settleKey.value = '' }, 380)
  }
  removeGhost()
}

// 拖拽进行中被卸载（HMR / 应用级拆卸）时兜底收尾，避免浮层与 body 状态残留
onUnmounted(() => {
  press = null
  document.body.classList.remove('kb-dragging', 'kb-drop-commit')
  removeGhost()
  if (slotEl) { slotEl.remove(); slotEl = null }
  slotInit = false
  window.removeEventListener('pointermove', onPressMove)
  window.removeEventListener('pointerup', onPressUp)
  window.removeEventListener('pointercancel', onPressUp)
})

function commitMenuOrder(from, to) {
  if (from < 0 || to < 0 || from === to) return
  const arr = orderedMenus.value.map((m) => m.index)
  const [moved] = arr.splice(from, 1)
  arr.splice(to, 0, moved)
  prefs.set({ menuOrder: arr })
  ElMessage.success('菜单顺序已保存')
}
function commitSubOrder(group, from, to) {
  if (from < 0 || to < 0 || from === to) return
  const m = orderedMenus.value.find((x) => x.index === group)
  if (!m) return
  const arr = orderedChildren(m).map((c) => c.index)
  const [moved] = arr.splice(from, 1)
  arr.splice(to, 0, moved)
  prefs.set({ subMenuOrder: { ...(prefs.state.subMenuOrder || {}), [group]: arr } })
  ElMessage.success('子菜单顺序已保存')
}

/** 拖拽结束后紧跟的 click 要吞掉，否则会误跳转 */
function onCaptureClick(e) {
  if (!justDragged.value) return
  e.stopPropagation()
  e.preventDefault()
}
/** 菜单项跳转的唯一入口（el-menu 上**不再**开 router 模式）。
 *  实测过「首次点击要点两次」——菜单自带的 router 跳转被这层 pointer/click 逻辑吞掉，
 *  于是自己兜一次；但两边同时跳会在同一次点击里 push 两次：路由守卫是异步的（ensureAuth /
 *  功能开关），第一次 push 还没落地时 route.path 仍是旧值，去重判断必然失效，第二次 push
 *  把在途的那次取消掉，表现出来就是「点了没反应」。所以只留这一条路，别把 router 加回去。 */
function goMenu(path) {
  if (!path) return
  if (route.path !== path) router.push(path)
}
function onMixedItemClick(it) {
  if (justDragged.value) return
  router.push(it.index)
}

function onLogout() {
  auth.logout()
  ElMessage.success('已退出登录')
  // 直接去登录页：push('/') 在首页退出时是重复导航会被忽略，且还要经守卫转一道
  router.push('/login')
}

function goHome() { router.push('/') }

function goProfile() { router.push('/profile') }

// 头像下拉：将权限码映射为中文名展示（ADMIN 已在上文单独显示「管理员」，此处只列普通用户细粒度权限）
// ⚠️ 只列前 3 项，其余折叠成「等 N 项」——把 20+ 个权限全拼出来会把下拉菜单撑到近 300px 宽，
// 打开就占掉五分之一屏幕（用户反馈）。
const permText = computed(() => {
  const list = auth.permissions.map((c) => permLabel(c))
  if (list.length <= 3) return list.join('、')
  return list.slice(0, 3).join('、') + ` 等 ${list.length} 项`
})

// ---- 帮助中心：操作手册与规范（内联展示，同时作为知识库文档供 AI 检索）----
const helpVisible = ref(false)
const helpActive = ref('start')
const helpData = [
  { key: 'start', title: '快速开始', html: `<h3>快速开始</h3>
<p>嗨，欢迎来到知识库 👋 第一次来？先登录就好：</p>
<ul>
  <li>点右上角「登录」，输入账号密码，滑一下<strong>滑动验证码</strong>就能进来啦。</li>
  <li>还没账号？点「注册」就能开一个；密码忘了也不怕，登录页有「修改密码」。</li>
</ul>
<p>登录后你就到了知识库首页，这儿能看到「最新」「最热」的文章，顶部搜索框还能搜全站内容。</p>` },
  { key: 'write', title: '写文章与发布', html: `<h3>写文章与发布</h3>
<ul>
  <li>左侧菜单「写文章」进入富文本编辑器（支持标题、加粗、列表、图片、表格、代码块等）。</li>
  <li>填写<strong>标题</strong>，选择<strong>分类</strong>与<strong>标签</strong>，便于他人检索。</li>
  <li>点击「提交」进入<strong>待审核</strong>状态，管理员审核通过后公开；也可「保存草稿」稍后继续。</li>
  <li>「我的帖子」可查看自己文章的状态（已发布 / 待审核 / 草稿 / 已封禁）。</li>
</ul>` },
  { key: 'read', title: '阅读与互动', html: `<h3>阅读与互动</h3>
<ul>
  <li><strong>搜索</strong>：顶部搜索框或「搜索结果」页，按关键词检索文章标题与正文。</li>
  <li><strong>收藏 / 点赞 / 评论</strong>：在文章详情页操作；收藏的文章见于「我的收藏」。</li>
  <li><strong>浏览历史</strong>：自动记录你读过的文章，见于「浏览历史」。</li>
  <li>文章内「小窗阅读」可在悬浮窗中阅读，不影响继续浏览其他页面。</li>
</ul>` },
  { key: 'ai', title: 'AI 智能问答', html: `<h3>AI 智能问答</h3>
<p>有啥想问的，来「AI 问答」跟我聊～</p>
<ul>
  <li>两种模式：<strong>知识库</strong>（我会先在本站已发布的文档里找答案，还会给你标出来源，点一下就能看原文）；<strong>通用</strong>（就是跟大模型闲聊、聊啥都行）。</li>
  <li>你可以开好几个对话，还能设置「对话条例」和「个人习惯」，让我更懂你的口味。</li>
  <li>小提醒：每小时每位用户最多问 20 次哦。</li>
  <li>要是看到「AI 问答暂不可用」，那是管理员还没把 AI 服务打开（需要配置智谱 API Key），找管理员帮帮忙就好。</li>
</ul>` },
  { key: 'share', title: '共享文档协作', html: `<h3>共享文档协作</h3>
<p>想跟同事一起写文档？进「共享文档」建个团队空间就好啦。</p>
<ul>
  <li>新建的空间默认给你 <strong>2GB</strong> 容量，够日常用了。</li>
  <li>容量不够了？在空间里点「申请扩容」，填好想要的容量和理由，交给管理员审批通过就生效～ 创建之后容量只能由管理员帮你调，自己改不了哦。</li>
  <li>空间里支持<strong>多人实时一起编辑</strong>（用的是 CRDT 技术，你能看到别人正停在哪儿、敲了啥）。</li>
  <li>在空间里你会被赋予不同的操作权限：有的能只看、有的能一起改、有的能帮忙管理——具体能做什么，空间主人会帮你安排好，你按提示操作就行，不用记那些角色名。</li>
</ul>` },
  { key: 'course', title: '培训与学习', html: `<h3>培训与学习</h3>
<ul>
  <li>进入「培训」浏览课程；点开课程查看章节列表。</li>
  <li>每章为<strong>独立学习页</strong>，支持正文阅读与<strong>章节笔记</strong>（自动保存）。</li>
  <li>读完本章（停留够时长并滑到末尾）会<strong>自动标记完成</strong>并累计进度，无需手动点。</li>
  <li>完成后会倒计时<strong>自动进入下一章</strong>，可随时暂停；已完成的章节可点「重学」重新走一遍。</li>
</ul>` },
  { key: 'exam', title: '考试与练习', html: `<h3>考试与练习</h3>
<ul>
  <li>「在线考试」：参加测评、提交后查看成绩与解析。</li>
  <li>「练习」：答一题判一题，不落成绩；答错的题自动进「错题本」，答对即移出。</li>
  <li>「我的成绩」查看历次考试；「我的题库」用 AI 按你的文档出题，只属于你自己。</li>
</ul>` },
  { key: 'points', title: '积分体系', html: `<h3>积分体系</h3>
<ul>
  <li>发帖、评论、获得点赞等行为可累积<strong>积分</strong>。</li>
  <li>「积分排行榜」查看全站排名，激励优质内容创作。</li>
</ul>` },
  { key: 'profile', title: '个人中心', html: `<h3>个人中心</h3>
<ul>
  <li>「个人资料」：修改头像、简介、登录密码。</li>
  <li>「我的收藏」「浏览历史」「消息中心」：管理你的内容与消息（私信与通知在同一处切换）。</li>
</ul>` },
  { key: 'download', title: '客户端下载', html: `<h3>客户端下载</h3>
<p>想把知识库装到电脑或手机上用？打开<strong>下载中心</strong>就行：桌面网址是 <code>/download</code>，手机端是 <code>/m/download</code>；页面底部和右上角头像菜单里也都有「客户端下载」入口。</p>
<ul>
  <li><strong>Windows 桌面端</strong>：下载安装包（如 <code>kb-desktop-1.0.0.exe</code>）双击安装，装好后登录即连本站。</li>
  <li><strong>Android App</strong>：下载 APK 安装；手机扫下载页的二维码也能直接下。</li>
  <li>下载页会显示<strong>版本号、更新说明和 SHA-256 校验值</strong>，可自行核对。</li>
  <li>管理员可在「站点设置 → 客户端下载」开关入口，或单独隐藏某个平台。</li>
</ul>` },
  { key: 'rule', title: '使用规范', html: `<h3>使用规范与社区公约</h3>
<ul>
  <li>发布内容须<strong>合法合规</strong>，禁止传播违法违规、谣言、色情、暴力等信息。</li>
  <li>尊重<strong>知识产权</strong>，转载请注明出处，勿上传侵权内容。</li>
  <li>文明互动：禁止人身攻击、刷屏、发布垃圾广告。</li>
  <li>账号安全：妥善保管密码，不共享账号；发现异常及时修改密码或联系管理员。</li>
</ul>` },
]
const currentHelp = computed(() => helpData.find(s => s.key === helpActive.value) || helpData[0])
function openHelp() { helpVisible.value = true }

/** 唤起命令面板：与 CommandPalette 组件内的 Ctrl/⌘ + K 走同一个 window 事件 */
function openPalette() { window.dispatchEvent(new Event('kb-open-command-palette')) }
</script>

<template>
  <!-- 全局中文 locale（Element Plus 已改按需注册，locale 由 ConfigProvider 提供） -->
  <el-config-provider :locale="zhCn">
  <!-- 登录页 / 独立阅读窗口：独立整页，不带菜单 -->
  <router-view v-if="barePage" />

  <!-- 其余页面：左侧菜单 + 顶栏 -->
  <el-watermark v-else
    :content="wmContent"
    :font="wmFont"
    :rotate="wm.config.rotate"
    :gap="wmGap"
    :z-index="wm.config.zIndex">
  <div class="app-shell" :class="{ 'is-mixed': isMixedNav }">
  <!-- 混合双列：顶部通栏一级导航（logo + 一级菜单），左侧双列在它下方 -->
  <nav v-if="isMixedNav" class="top-nav top-nav--mixed top-nav--global">
    <div class="brand-top" @click="goHome">
      <img class="brand-ico" :src="siteLogo || '/logo.png?v=3'" alt="" />
      <span v-if="showBrandText" class="brand-txt">{{ brandName }}</span>
    </div>
    <div class="mixed-bar">
      <button v-for="m in orderedMenus" :key="m.index" type="button" class="mb-item"
              :class="{ on: m.index === activePrimaryIndex }" :title="m.title"
              @click="onMixedPrimaryClick(m)">
        <MenuIcon :variant="prefs.state.menuIconStyle" :name="m.icon" :size="16" />
        <span>{{ m.title }}</span>
      </button>
    </div>
  </nav>
  <el-container class="layout" :class="{ 'is-narrow': isNarrow, 'nav-top': isTopNav, 'nav-mixed': isMixedNav }">
    <!-- 窄屏抽屉遮罩：点击关闭 -->
    <div v-if="isNarrow && drawerOpen" class="aside-mask" @click="drawerOpen = false"></div>
    <el-aside v-if="showAside"
              :width="asideWidth"
              class="aside"
              :class="{ collapsed: menuCollapsed, 'is-drawer': isNarrow, 'drawer-open': drawerOpen,
                        'dark-menu': prefs.state.sidebarDark, dual: isMixedNav }"
              @mouseenter="hoveringAside = true" @mouseleave="hoveringAside = false">
      <div v-if="!isMixedNav" class="brand" :title="menuCollapsed ? brandName : ''" @click="goHome">
        <img class="brand-ico" :src="siteLogo || '/logo.png?v=3'" alt="" />
        <span v-if="showBrandText" v-show="!menuCollapsed" class="brand-txt">{{ brandName }}</span>
      </div>

      <!-- 混合双列：左侧只有当前一级的二级项（图标 + 竖排文字，可拖拽排序），一级在顶部通栏 -->
      <Transition v-if="isMixedNav" name="ms-switch" mode="out-in">
      <div :key="activePrimaryIndex" class="mixed-side">
        <button v-for="(it, ii) in secondaryItems" :key="it.index" type="button"
                class="ms-item"
                :class="{ on: route.path === it.index,
                          'is-dragging': subDrag.group === activePrimaryIndex && subDrag.from === ii,
                          'just-dropped': settleKey === 's:' + activePrimaryIndex + ':' + ii }"
                :style="subStyle(ii)" :title="it.title"
                @pointerdown="onPressStart('sub', activePrimaryIndex, ii, $event)"
                @click="onMixedItemClick(it)">
          <MenuIcon :variant="prefs.state.menuIconStyle" :name="it.icon" :size="22" />
          <span class="ms-txt">{{ it.title }}</span>
        </button>
      </div>
      </Transition>

      <el-menu v-else :default-active="route.path" class="menu"
               :collapse="menuCollapsed" :collapse-transition="false">
        <template v-for="(m, i) in orderedMenus" :key="m.index">
          <el-sub-menu v-if="m.children" :index="m.index" class="drag-item"
                       :class="{ 'is-dragging': dragFrom === i,
                                 'just-dropped': settleKey === 'p:' + i }"
                       :style="menuStyle(i)"
                       @pointerdown="onPressStart('primary', '', i, $event)">
            <template #title>
              <MenuIcon :variant="prefs.state.menuIconStyle" :name="m.icon" :size="20" />
              <span>{{ m.title }}</span>
            </template>
            <el-menu-item v-for="(c, ci) in orderedChildren(m)" :key="c.index" :index="c.index"
                          class="sub-item drag-item"
                          :class="{ 'is-dragging': subDrag.group === m.index && subDrag.from === ci,
                                    'just-dropped': settleKey === 's:' + m.index + ':' + ci }"
                          :style="subStyle(ci)"
                          @pointerdown.stop="onPressStart('sub', m.index, ci, $event)"
                          @click="goMenu(c.index)">
              <MenuIcon :variant="prefs.state.menuIconStyle" :name="c.icon" :size="18" />
              <span>{{ c.title }}</span>
              <el-badge v-if="c.index === '/messages' && dmUnreadCount" :value="dmUnreadCount" :max="99" class="menu-dot" />
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="m.index" class="drag-item"
                        :class="{ 'is-dragging': dragFrom === i,
                                  'just-dropped': settleKey === 'p:' + i }"
                        :style="menuStyle(i)"
                        @pointerdown="onPressStart('primary', '', i, $event)"
                        @click="goMenu(m.index)">
            <MenuIcon :variant="prefs.state.menuIconStyle" :name="m.icon" :size="20" />
            <span>{{ m.title }}</span>
            <el-badge v-if="m.index === '/messages' && dmUnreadCount" :value="dmUnreadCount" :max="99" class="menu-dot" />
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="head-left">
          <el-button v-if="!isTopNav && !isMixedNav" class="fold-btn" text
                     :title="isNarrow ? '打开菜单'
                       : (!prefs.state.sidebarVisible ? '显示侧边栏' : (menuCollapsed ? '展开菜单' : '收起菜单'))"
                     @click="toggleMenu">
            <el-icon size="18"><component :is="(isNarrow ? !drawerOpen : menuCollapsed) ? 'Expand' : 'Fold'" /></el-icon>
          </el-button>
          <div class="crumb">{{ route.meta.title || '知识库' }}</div>
        </div>
        <!-- 顶栏全局搜索：回车跳「搜索结果」页 -->
        <div class="head-search">
          <el-icon class="hs-ico" :size="16"><Search /></el-icon>
          <input v-model="globalQ" class="hs-input" placeholder="搜索知识库 / 作者名…"
                 @keyup.enter="doGlobalSearch" />
        </div>
        <div class="right">
          <el-button v-if="auth.loggedIn" class="pref-btn" text circle title="偏好设置"
                     @click="router.push('/preferences')">
            <el-icon size="18"><Setting /></el-icon>
          </el-button>
          <el-button class="help-btn" text circle title="帮助中心" @click="openHelp">
            <el-icon size="18"><InfoFilled /></el-icon>
          </el-button>
          <el-button class="theme-toggle" text circle
                     :title="isDark ? '切换到日间模式' : '切换到夜间模式'"
                     @click="toggleTheme">
            <el-icon :size="18" :class="{ spinning }">
              <component :is="isDark ? 'Sunny' : 'Moon'" />
            </el-icon>
          </el-button>
          <!-- 命令面板入口（快捷键 Ctrl/⌘ + K，和组件内保持同一个 window 事件） -->
          <el-button class="cmd-btn" text round title="命令面板（Ctrl/⌘ + K）" @click="openPalette">
            <el-icon :size="15"><Search /></el-icon>
            <span class="cmd-txt">快速跳转</span>
            <span class="cmd-k">Ctrl K</span>
          </el-button>
          <template v-if="auth.loggedIn">
            <el-badge :value="notification.unread + dmUnreadCount" :hidden="!notification.unread && !dmUnreadCount" :max="99">
              <el-button class="bell" text circle title="通知" @click="goNotifications">
                <el-icon size="18"><Bell /></el-icon>
              </el-button>
            </el-badge>
            <!-- 头像 + 佩戴勋章：组成 inline-flex 单元，整组在 .right 中垂直居中，
                 与左侧铃铛/月亮/帮助等按钮同一基线（修复合并后基线漂移问题） -->
            <span class="avatar-cluster">
              <WornBadgeStrip v-if="auth.user?.wornBadges?.length"
                              :badge-ids="auth.user.wornBadges" :size="20" class="cluster-badges" />
              <el-dropdown trigger="click" popper-class="kb-avatar-menu">
                <span class="avatar">
                  <img v-if="auth.user?.avatar" :src="auth.user.avatar" alt="" class="avatar-img" />
                  <span v-else>{{ (auth.user?.displayName || auth.user?.username || '?').charAt(0) }}</span>
                </span>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item disabled>
                      {{ auth.user?.displayName || auth.user?.username }}
                    </el-dropdown-item>
                    <el-dropdown-item disabled>角色：{{ auth.user?.role === 'ADMIN' ? '管理员' : '普通用户' }}</el-dropdown-item>
                    <el-dropdown-item v-if="!auth.isAdmin && auth.permissions.length" disabled>
                      权限：{{ permText }}
                    </el-dropdown-item>
                    <el-dropdown-item disabled>积分：{{ auth.user?.points || 0 }}</el-dropdown-item>
                    <el-dropdown-item divided @click="fbOpen = true">意见反馈</el-dropdown-item>
                    <el-dropdown-item v-if="download.visible" @click="router.push('/download')">客户端下载</el-dropdown-item>
                    <el-dropdown-item @click="router.push('/preferences')">偏好设置</el-dropdown-item>
                    <el-dropdown-item @click="goProfile">个人资料</el-dropdown-item>
                    <el-dropdown-item @click="onLogout">退出登录</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </span>
          </template>
          <el-button v-else type="primary" @click="router.push('/login')">登录</el-button>
        </div>
      </el-header>

      <!-- 纯顶部导航条（混合双列的顶部条已提到最外层通栏） -->
      <nav v-if="isTopNav" class="top-nav">
        <el-menu mode="horizontal" :default-active="route.path" router :ellipsis="true" class="top-menu">
          <template v-for="m in menus" :key="m.index">
            <el-sub-menu v-if="m.children" :index="m.index">
              <template #title>
                <MenuIcon :variant="prefs.state.menuIconStyle" :name="m.icon" :size="18" />
                <span>{{ m.title }}</span>
              </template>
              <el-menu-item v-for="c in m.children" :key="c.index" :index="c.index">
                <MenuIcon :variant="prefs.state.menuIconStyle" :name="c.icon" :size="16" />
                <span>{{ c.title }}</span>
              </el-menu-item>
            </el-sub-menu>
            <el-menu-item v-else :index="m.index">
              <MenuIcon :variant="prefs.state.menuIconStyle" :name="m.icon" :size="18" />
              <span>{{ m.title }}</span>
            </el-menu-item>
          </template>
        </el-menu>
      </nav>

      <!-- 顶部多标签页：首页固定，其余可关闭（参考中后台常见交互） -->
      <nav v-if="tabs.length" class="tabbar">
        <div ref="tabScrollEl" class="tabbar-scroll" @wheel="onTabWheel">
          <div v-for="t in tabs" :key="t.path" class="tab" :class="{ on: t.path === route.path }"
               @click="goTab(t.path)" @contextmenu="openTabMenu($event, t)">
            <span v-if="t.path === route.path" class="tab-dot"></span>
            <span class="tab-txt">{{ t.title }}</span>
            <span v-if="!t.pinned" class="tab-x" title="关闭" @click.stop="closeTab(t.path)">×</span>
          </div>
        </div>
        <!-- 一键关闭全部（有可关标签时出现）；右键任意标签可「关闭其他 / 关闭右侧」 -->
        <button v-if="closableCount" class="tab-clear" type="button"
                title="一键关闭全部标签页" @click="closeAllTabs">
          <svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true">
            <path d="M4 7h16M9 7V5.2A1.2 1.2 0 0 1 10.2 4h3.6A1.2 1.2 0 0 1 15 5.2V7M6.2 7l.9 11.4A2 2 0 0 0 9.1 20h5.8a2 2 0 0 0 2-1.6L17.8 7"
                  fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"/>
          </svg>
          <span>全部关闭</span>
        </button>
      </nav>

      <!-- 标签右键菜单 -->
      <div v-if="tabMenu.open" class="tab-menu" :style="{ left: tabMenu.x + 'px', top: tabMenu.y + 'px' }"
           @click.stop>
        <button type="button" @click="closeOtherTabs(tabMenu.path); closeTabMenu()">关闭其他标签</button>
        <button type="button" @click="closeRightTabs(tabMenu.path); closeTabMenu()">关闭右侧标签</button>
        <button type="button" class="danger" @click="closeAllTabs(); closeTabMenu()">全部关闭</button>
      </div>

      <el-main class="main" :class="{ 'is-fixed': prefs.state.contentWidth === 'fixed' }">
        <!-- 演示账号横幅：全局提示当前处于「只读演示」状态，写操作会被拒绝 -->
        <div v-if="auth.isDemo" class="demo-banner">
          <el-icon><InfoFilled /></el-icon>
          <span>当前为<strong>演示账户</strong>：可浏览全部页面，但不能保存、删除或发布数据。如需操作请使用正式账号登录。</span>
        </div>
        <router-view :key="$route.fullPath" />
        <!-- 站点页脚：备案信息（合规要求网站底部必须可见）+ 自定义内容 + 版本。
             吸底布局见下方 .main 的 CSS 注释（内容短时贴底、内容长时随内容滚动）。
             /login 走 barePage 分支不经过 el-main，其页脚由 Login.vue 自行渲染 -->
        <SiteFooter />
      </el-main>
    </el-container>

    <!-- 文章「小窗阅读」浮窗：渲染于路由之外，切换页面后依然存在，关闭前内容保留 -->
    <div v-if="reader.open" class="reader-float"
         :style="{ left: reader.x + 'px', top: reader.y + 'px', width: reader.w + 'px', height: reader.h + 'px' }">
      <div class="rf-bar" @mousedown="startDrag">
        <span class="rf-title">{{ reader.title }}</span>
        <button class="rf-close" type="button" @click="closeReader" @mousedown.stop title="关闭">×</button>
      </div>
      <div ref="rfRef" class="rf-body content" v-html="reader.content"></div>
      <div class="rf-resize" @mousedown.stop.prevent="startResize" title="拖动右下角缩放"></div>
    </div>

    <!-- 小窗阅读里的图片灯箱（点正文图片全屏看原图） -->
    <div v-if="readerLightbox" class="kb-lightbox" @click="readerLightbox = ''">
      <img :src="readerLightbox" alt="" @click.stop />
      <button class="lb-x" type="button" @click="readerLightbox = ''">×</button>
    </div>

    <!-- 帮助中心：操作手册与规范 -->
    <el-dialog v-model="helpVisible" title="帮助中心 · 操作手册与规范" width="820px" top="6vh" class="help-dialog">
      <div class="help-wrap">
        <aside class="help-nav">
          <button v-for="s in helpData" :key="s.key" type="button"
                  class="help-nav-item" :class="{ active: helpActive === s.key }" @click="helpActive = s.key">
            {{ s.title }}
          </button>
        </aside>
        <div class="help-content" v-html="currentHelp.html"></div>
      </div>
      <template #footer>
        <span class="help-tip">仍有疑问？可在「AI 问答」中提问，或联系系统管理员。</span>
      </template>
    </el-dialog>
  </el-container>
  </div>
  </el-watermark>
  <!-- 全局命令面板（Ctrl/⌘ + K）：只在带菜单的主界面挂载，登录页/独立阅读窗不掺和 -->
  <CommandPalette v-if="!barePage" />
  <!-- 意见反馈弹窗（顶部头像菜单 → 意见反馈）；此前只改了 fbOpen，没有渲染组件，点了没反应 -->
  <ReportDialog v-model="fbOpen" target-type="FEEDBACK" />
  </el-config-provider>
</template>

<style src="./App.css"></style>