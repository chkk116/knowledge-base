<script setup>
/**
 * 移动端根壳：TabBar + 路由视图 + 离线状态条
 *
 * 与桌面端 App.vue 的本质差别：
 *  - 没有侧边栏 / 顶栏。导航全部靠底部 TabBar（5 项，中间 ＋ 凸起）。
 *  - 每个 Tab 页自己画顶部标题栏（因为有的页要搜索框、有的要头像，统一顶栏反而僵化）。
 *  - 底部 TabBar 固定，页面容器留出 safe-area + TabBar 高度，内容区独立滚动。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from './store/auth'
import { useNotificationStore } from './store/notification'
import { useSiteInfoStore } from './store/siteInfo'
import { syncOnReconnect, pendingCount } from './utils/offlineStore'
import { openExternal } from './utils/openExternal'
import { downloadAndInstall, assertSafeAssetUrl, getCachedApk, installCachedApk } from './utils/appUpdate'
import { getAppVersion, getAppBundle } from './api/app'
import { shouldLock, verifyPin, getLockConfig } from './utils/appLock'
import { parseNotes, changelogOf } from './changelog'
import { dlActive, dlPercent, dlDone, restoreUpdateState, dismissUpdateState, updateProgressText } from './utils/updateProgress'
import { hapticsEnabled } from './utils/mobileTheme'
import { getPendingShares, onShareReceived } from './utils/shareReceiver'
import { setUpdateBadge } from './utils/updateBadge'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const siteInfo = useSiteInfoStore()
const notification = useNotificationStore()

const online = ref(navigator.onLine)
const pending = ref(0)
const syncing = ref(false)

// 登录页 / 拍照页：全屏，不显示 TabBar
/**
 * 隐藏底部导航的场景：
 *   - 登录页、拍照页：全屏任务页；
 *   - 答题页（/exam/:id）：限时答题要沉浸，且 TabBar 的悬浮 + 会压住「交卷」按钮。
 * 注意 /exam/mine 是普通列表页，仍然显示 TabBar。
 */
const hideTab = computed(() => {
  const p = route.path
  return p === '/login' || p === '/capture' || /^\/exam\/\d+$/.test(p)
})

const activeTab = computed(() => route.meta.tab || '')

const TABS = [
  { key: 'home', path: '/home', label: '首页', icon: 'HomeFilled' },
  { key: 'docs', path: '/docs', label: '文档库', icon: 'Document' },
  { key: 'capture', path: '/capture', label: '', icon: 'Plus' },  // 中间 ＋
  { key: 'messages', path: '/messages', label: '消息', icon: 'ChatDotRound' },
  { key: 'profile', path: '/profile', label: '我的', icon: 'User' }
]

function goTab(t) {
  haptic()
  if (t.key === 'capture') { router.push('/capture'); return }
  if (route.path === t.path) return
  router.replace(t.path)
}

async function refreshPending() {
  try { pending.value = await pendingCount() } catch { pending.value = 0 }
}

async function doSync() {
  if (syncing.value || !online.value) return
  syncing.value = true
  try {
    const n = await syncOnReconnect()
    if (n > 0) window.__kbMsg?.success(`已同步 ${n} 篇离线文档`)
    await refreshPending()
  } catch (e) {
    console.warn('同步失败', e)
  } finally {
    syncing.value = false
  }
}

function onOnline() { online.value = true; doSync() }
function onOffline() { online.value = false }
function onPendingChange() { refreshPending() }

// ---------- App 版本检查（仅原生壳内生效，Web 端不打扰） ----------
// window.Capacitor 由原生运行时注入，所以前端不需要依赖 @capacitor/core
const isNative = !!window.Capacitor?.isNativePlatform?.()

// ---------- 原生体验：状态栏 / 震动 / 启动图（插件缺失时全部静默跳过，Web 端不受影响） ----------
const capPlugins = window.Capacitor?.Plugins || {}
const SB = capPlugins.StatusBar
/**
 * 状态栏文字/图标随主题。⚠️ 命名与直觉相反（见 @capacitor/status-bar 定义）：
 *   Style.Dark  = 深色背景 → 浅色字；Style.Light = 浅色背景 → 深色字。
 * 故：浅色主题传 'LIGHT'（深字），深色主题传 'DARK'（浅字）。
 * ⚠️ 绝不再调 setOverlaysWebView / setBackgroundColor：targetSdk 36 下 Android 强制
 *    edge-to-edge，覆盖这些会与系统冲突，导致 WebView 丢掉 safe-area-inset-top →
 *    页面内容/提示条顶到状态栏底下（2026-10-14 踩坑）。
 */
function applyStatusBar() {
  if (!isNative || !SB) return
  const dark = document.documentElement.classList.contains('dark')
  try {
    SB.setStyle?.({ style: dark ? 'DARK' : 'LIGHT' })
  } catch { /* 插件不可用则忽略 */ }
}
let themeObserver = null

// ---------- 系统分享导入（Android）：收到 PDF/图片/文本 → 跳导入页 ----------
let unsubShare = null
function handleShareFiles(files) {
  if (!files || !files.length) return
  window.__kbShareFiles = files
  try { router.push('/share-import') } catch { /* ignore */ }
}

// ---------- 更新体验（对齐大厂）：①「关于」红点 ② 首次启动展示本次更新 ----------
const showWhatsNew = ref(false)
const whatsNew = ref(null)

/** 探一次后台版本，有更新就给「关于」入口点亮红点 */
async function checkUpdateBadge() {
  if (!isNative || !navigator.onLine) return
  try {
    const { data } = await getAppVersion()
    if (!data || !data.versionCode) { setUpdateBadge(false); return }
    const cur = await currentVersionCode()
    setUpdateBadge(cur > 0 ? data.versionCode > cur : false)
  } catch { /* 探不到就不亮，不打扰 */ }
}

/** 升级后首次启动：弹一次「本次更新内容」（本机版本号与上次见到的不一致） */
async function maybeShowWhatsNew() {
  if (!isNative) return
  try {
    const v = await window.Capacitor?.Plugins?.App?.getInfo?.().then((i) => i?.version || '')
    if (!v) return
    let seen = null
    try { seen = localStorage.getItem('kb_seen_version') } catch { /* ignore */ }
    if (seen === v) return
    try { localStorage.setItem('kb_seen_version', v) } catch { /* ignore */ }
    const log = changelogOf(v)
    if (log && Array.isArray(log.items) && log.items.length) {
      whatsNew.value = log
      showWhatsNew.value = true
    }
  } catch { /* ignore */ }
}

/** 轻触反馈（Tab 切换等）；可在「设置 → 个性化」里关闭 */
function haptic() {
  if (!isNative || !hapticsEnabled()) return
  try { capPlugins.Haptics?.impact?.({ style: 'LIGHT' })?.catch?.(() => {}) } catch { /* ignore */ }
}
const updateInfo = ref(null)      // { versionCode, versionName, url, notes, force }
const showUpdate = ref(false)
const downloading = ref(false)
const progress = ref(-1)        // 应用内下载进度（-1=未知）
// 本地是否已有这个更新包（checksum 一致）：已有则按钮变「立即安装」，不再重复下载
const apkCached = ref(false)

// ---------- 应用锁（PIN）----------
const locked = ref(false)
const pin = ref('')
const pinErr = ref('')
const shaking = ref(false)
const pinLen = ref(getLockConfig().len || 4)
let bgAt = 0                     // 进入后台的时刻（用于「超时自动锁定」）

/** 更新说明条目：优先用后台下发的 notes，缺失则回落到前端内置 CHANGELOG */
const updateNotes = computed(() => {
  const fromServer = parseNotes(updateInfo.value?.notes)
  if (fromServer.length) return fromServer
  return changelogOf(updateInfo.value?.versionName)?.items || []
})

// 下载横幅文案（状态在 utils/updateProgress.js，切页/刷新都保留）
const dlText = computed(() => updateProgressText())

function pressKey(k) {
  if (shaking.value) return
  if (k === 'del') { pin.value = pin.value.slice(0, -1); pinErr.value = ''; return }
  if (pin.value.length >= pinLen.value) return
  pin.value += k
  pinErr.value = ''
  if (pin.value.length === pinLen.value) setTimeout(submitPin, 80)
}

async function submitPin() {
  // verifyPin 可能抛业务错误（失败锁定 / 旧算法需重设），把 message 透给用户而不是吞成「密码不正确」
  const ok = await verifyPin(pin.value).catch((e) => { pinErr.value = e?.message || ''; return false })
  if (ok) {
    locked.value = false
    pin.value = ''
    pinErr.value = ''
    return
  }
  if (!pinErr.value) pinErr.value = '密码不正确，请重试'
  shaking.value = true
  setTimeout(() => { shaking.value = false; pin.value = '' }, 420)
}

/** 从后台切回：超过设定时间则重新锁定 */
function onAppStateChange(st) {
  if (!st) return
  if (!st.isActive) { bgAt = Date.now(); return }
  const t = Number(getLockConfig().timeout ?? 60)
  if (shouldLock() && bgAt && Date.now() - bgAt > t * 1000) {
    pinLen.value = getLockConfig().len || 4
    pin.value = ''
    locked.value = true
  }
  bgAt = 0
}

/** 取当前安装包的 versionCode（来自 @capacitor/app 的 getInfo().build） */
async function currentVersionCode() {
  try {
    const info = await window.Capacitor?.Plugins?.App?.getInfo?.()
    return Number(info?.build || 0)
  } catch {
    return 0
  }
}

async function checkUpdate() {
  if (!isNative || !navigator.onLine) return
  try {
    const { data } = await getAppVersion()
    // versionCode=0 表示后台没配升级信息 —— 不打扰用户
    if (!data || !data.versionCode) return
    const cur = await currentVersionCode()
    // cur=0 说明拿不到本机版本（老包没带 App 插件），此时也不提示，避免误报
    if (cur > 0 && data.versionCode > cur) {
      updateInfo.value = data
      // 强制更新但后台漏配 url：不能把用户永久挡在弹层里（doUpdate 只会弹 warning 无法关闭）
      showUpdate.value = true
      // 本地已下过这个包（上次弹安装界面被取消）→ 按钮改「立即安装」，点了直接复用
      apkCached.value = false
      getCachedApk(data.checksum).then((c) => { apkCached.value = !!c }).catch(() => { apkCached.value = false })
    }
  } catch { /* 静默失败，绝不影响正常使用 */ }
}

/**
 * 下载并安装。
 * 第一步先走「跳系统浏览器下载」——不依赖任何原生插件，最稳。
 * （后续要改成 App 内静默下载 + 拉起安装器，需要自研一个 Capacitor 插件。）
 */
async function doUpdate() {
  const u = updateInfo.value
  if (!u?.url) {
    // force 弹层无法关闭：漏配 url 时必须解除锁死给逃生路径（外部复审 2026-10-09）
    window.__kbMsg?.warning('暂无下载地址，更新配置异常')
    showUpdate.value = false
    return
  }
  downloading.value = true
  progress.value = -1
  try {
    // 已下过同一个包（checksum 一致）就复用：上次弹安装界面被取消后，
    // 这里直接再拉起安装，不再重复下载十几 MB。
    const cached = await getCachedApk(u.checksum)
    if (cached) {
      try {
        const r = await installCachedApk(cached.filePath)
        if (r && r.launched === false) {
          // 原生已跳「安装未知应用」设置页：这里不能再弹成功（2026-10-18）
          window.__kbMsg?.warning('请先允许本应用「安装未知应用」，授权后返回再点「立即安装」')
        } else {
          window.__kbMsg?.success('已下载完成，正在打开安装界面')
        }
        return
      } catch {
        // 缓存被系统清掉了（cacheDir 可被回收）→ 回落到正常下载
        apkCached.value = false
      }
    }
    // App 内：下载完直接拉起系统安装器（不再跳浏览器）。
    // Web 端 / 老包没带 ApkInstaller 插件时，downloadAndInstall 内部会自动退回系统浏览器。
    const r = await downloadAndInstall(u.url, (p) => { progress.value = p }, u.checksum)
    if (r?.mode === 'in-app') window.__kbMsg?.success('下载完成，请在弹出的安装界面完成安装')
  } catch (e) {
    window.__kbMsg?.error('更新失败：' + ((e && e.message) || e))
  } finally {
    downloading.value = false
  }
}
function laterUpdate() {
  showUpdate.value = false
  if (updateInfo.value?.force) window.__kbMsg?.warning('该版本为强制更新，请尽快升级')
}

// ---------- 前端热更（Capgo）：只换前端资源，不用发 APK ----------
// 流程：改前端 → 打 zip 上传 → 后台配 app_bundle → 用户下次打开自动切新版。
const updater = window.Capacitor?.Plugins?.CapacitorUpdater

async function checkBundle() {
  if (!isNative || !updater) return
  // ⚠️ 必须最先调用：告知原生「本次启动成功」。不调的话 Capgo 会判定失败并回滚到上一版。
  try { await updater.notifyAppReady() } catch { /* 老版本没有该插件，忽略 */ }
  if (!navigator.onLine) return
  try {
    const { data } = await getAppBundle()
    if (!data?.version || !data.url) return          // 后台没配 → 不热更
    // fail-closed：热更包同样是「能执行任意 JS」的载荷，URL 必须过域名白名单，
    // 缺 checksum 一律跳过本次热更（外部审查 2026-10-11；签名验签为后续增强项）
    if (!data.checksum) { console.warn('[热更] 后端未提供 checksum，跳过本次热更'); return }
    try { assertSafeAssetUrl(data.url) } catch (e) { console.warn('[热更] 地址校验未通过，跳过：', e?.message || e); return }
    const cur = await updater.current()
    if (cur?.bundle?.version === data.version) return // 已是最新
    const b = await updater.download({ url: data.url, version: data.version, checksum: data.checksum })
    await updater.set({ id: b.id })
    // 不立刻 reload：留到下次冷启动生效，避免打断用户当前操作
  } catch (e) {
    console.warn('[热更] 检查失败（忽略，不影响使用）', e)
  }
}

// ---------- Android 返回键 / 右滑手势 ----------
// 不注册这个监听，返回键和「右滑返回」手势会走系统默认行为 = **直接退出应用**，
// 用户在二级页（如账号资料）右滑就直接退出了，体验很糟。
// 正确行为：有上一级就回上一级；已在根 Tab 才最小化（不杀进程）。
const capApp = window.Capacitor?.Plugins?.App
// 根 Tab 页：在这些页面按返回应「最小化」而不是再退
const ROOT_TABS = ['/home', '/docs', '/messages', '/profile']
let backListener = null
let appStateListener = null

function handleBack() {
  const cur = route.path
  // vue-router(createWebHistory) 会在 history.state.back 里记录上一条路由，
  // 比 history.length 准（后者会把站外/首次进入也算进去）。
  const canGoBack = !!(window.history.state && window.history.state.back)
  // 不在根 Tab 且有上一级 → 回上一级（就是用户说的「滑动返回上一个页面」）
  if (!ROOT_TABS.includes(cur) && canGoBack) {
    router.back()
    return
  }
  // 已是根 Tab（或没有上一级）→ 最小化（保持后台，用户切回来还在原处；比 exitApp 友好）
  // 老包无 minimizeApp 插件时也给用户反馈，不要静默无行为（外部审查 2026-10-12）
  try {
    if (capApp?.minimizeApp) capApp.minimizeApp()
    else window.__kbMsg?.info('当前版本不支持最小化，请从系统任务栏切换')
  } catch { /* 插件调用失败则忽略 */ }
}

onMounted(async () => {
  // 原生体验：状态栏随主题变化 + 隐藏启动图（插件缺失时静默跳过）
  applyStatusBar()
  themeObserver = new MutationObserver(applyStatusBar)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  try { capPlugins.SplashScreen?.hide?.()?.catch?.(() => {}) } catch { /* ignore */ }
  // 系统分享：冷启动取待导入清单 + 监听运行中收到（Android 原生 ShareReceiver）
  getPendingShares().then((f) => { if (f.length) handleShareFiles(f) }).catch(() => {})
  unsubShare = onShareReceived(handleShareFiles)
  // 更新体验：红点探测（延迟 4s，别抢首屏）+ 升级后首次启动弹「本次更新内容」
  setTimeout(checkUpdateBadge, 4000)
  setTimeout(maybeShowWhatsNew, 1200)
  // 刷新/冷启动后恢复「更新下载中」横幅（下载在原生后台线程，没被中断）
  restoreUpdateState()
  // 冷启动锁定（仅在已开启应用锁且本机有登录态时）
  if (shouldLock()) {
    pinLen.value = getLockConfig().len || 4
    locked.value = true
  }
  if (isNative && capApp?.addListener) {
    try {
      backListener = await capApp.addListener('backButton', handleBack)
    } catch { /* 插件不可用则忽略 */ }
  }
  await auth.fetchMe()
  if (auth.loggedIn) notification.connect()
  // 页脚配置走公开接口，登录前也要拉（移动端登录页底部要展示备案信息）
  siteInfo.load()
  refreshPending()
  if (navigator.onLine) doSync()
  checkBundle()
  checkUpdate()
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
  window.addEventListener('kb:offline-pending-changed', onPendingChange)
  // 前后台切换：配合「自动锁定」超时（外部审查 2026-10-11：返回值未捕获导致
  // onUnmounted 无从移除，监听器泄漏）
  try { appStateListener = await capApp?.addListener?.('appStateChange', onAppStateChange) } catch { /* 老包无该事件 */ }
})

onUnmounted(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
  window.removeEventListener('kb:offline-pending-changed', onPendingChange)
  try { backListener?.remove?.() } catch { /* ignore */ }
  try { appStateListener?.remove?.() } catch { /* ignore */ }
  try { themeObserver?.disconnect?.() } catch { /* ignore */ }
  try { unsubShare?.() } catch { /* ignore */ }
})

watch(() => auth.loggedIn, (v) => {
  if (v) { notification.connect(); refreshPending() }
  else notification.setUnread(0)
})

// 令牌过期：axios 拦截器广播
function onUnauthorized() {
  if (auth.loggedIn) {
    auth.logout()
    window.__kbMsg?.warning('登录已过期，请重新登录')
    router.replace('/login')
  }
}
window.addEventListener('kb-unauthorized', onUnauthorized)
onUnmounted(() => window.removeEventListener('kb-unauthorized', onUnauthorized))
</script>

<template>
  <div class="m-app" :class="{ 'no-tab': hideTab }">
    <!-- 离线 / 待同步提示条：贴在 TabBar 上方，不遮挡内容 -->
    <transition name="slide-up">
      <div v-if="!hideTab && (!online || pending > 0)" class="net-bar" :class="{ offline: !online }">
        <el-icon class="net-ico" :class="{ spin: syncing }">
          <component :is="!online ? 'WarningFilled' : (syncing ? 'Loading' : 'Upload')" />
        </el-icon>
        <span v-if="!online">当前离线：显示已缓存内容；新建/编辑会先存本机，联网后自动同步</span>
        <span v-else>{{ syncing ? '正在同步…' : `有 ${pending} 篇文档待同步` }}</span>
        <button v-if="online && !syncing" class="net-act" @click="doSync">立即同步</button>
      </div>
    </transition>

    <!-- 应用更新下载横幅：常驻在内容区之上，切页 / WebView 刷新都不丢
         （下载跑在原生后台线程，JS 监听会丢但下载不会中断） -->
    <div v-if="dlActive || dlDone" class="upd-bar" :class="{ done: dlDone }">
      <span class="ub-ico">
        <el-icon><component :is="dlDone ? 'CircleCheckFilled' : 'Download'" /></el-icon>
      </span>
      <span class="ub-body">
        <span class="ub-t">{{ dlDone ? '更新包下载完成' : '正在下载更新' }}</span>
        <span class="ub-d">{{ dlText }}</span>
        <span v-if="dlActive" class="ub-bar">
          <i :style="{ width: dlPercent >= 0 ? dlPercent + '%' : '100%' }" :class="{ indet: dlPercent < 0 }"></i>
        </span>
      </span>
      <button class="ub-x" type="button" @click="dismissUpdateState">
        <el-icon :size="15"><Close /></el-icon>
      </button>
    </div>

    <div class="m-view">
      <router-view v-slot="{ Component }">
        <transition name="fade-page" mode="out-in">
          <component :is="Component" />
        </transition>
      </router-view>
    </div>

    <!-- 底部 TabBar：中间 ＋ 用圆形凸起（主流 App 常见做法） -->
    <nav v-if="!hideTab" class="tabbar">
      <button v-for="t in TABS" :key="t.key" type="button"
              class="tab"
              :class="{ active: activeTab === t.key, center: t.key === 'capture' }"
              @click="goTab(t)">
        <span v-if="t.key === 'capture'" class="fab">
          <el-icon :size="26"><Plus /></el-icon>
        </span>
        <template v-else>
          <span class="tab-ico">
            <el-icon :size="21"><component :is="t.icon" /></el-icon>
            <i v-if="t.key === 'messages' && notification.unread > 0"
               class="dot">{{ notification.unread > 99 ? '99+' : notification.unread }}</i>
          </span>
          <span class="tab-txt">{{ t.label }}</span>
        </template>
      </button>
    </nav>

    <!-- 应用内升级弹层：原生包启动时若发现更高 versionCode 则弹出。
         force=true 时不给「稍后」按钮。Web 端不检查（checkUpdate 里已 return）。 -->
    <div v-if="showUpdate" class="upd-mask">
      <div class="upd-panel">
        <!-- 头部：图标 + 标题 + 版本号徽标 -->
        <div class="upd-head">
          <span class="upd-ico"><el-icon :size="22"><Download /></el-icon></span>
          <div class="upd-head-txt">
            <div class="upd-title">发现新版本</div>
            <div class="upd-ver">
              <span class="upd-badge">v{{ updateInfo?.versionName || '—' }}</span>
              <span v-if="updateInfo?.versionCode" class="upd-build">Build {{ updateInfo.versionCode }}</span>
            </div>
          </div>
        </div>

        <!-- 更新说明：按条目渲染（不再用 pre 原样输出） -->
        <div class="upd-body">
          <div class="upd-body-t">更新内容</div>
          <ul v-if="updateNotes.length" class="upd-list">
            <li v-for="(n, i) in updateNotes" :key="i">{{ n }}</li>
          </ul>
          <div v-else class="upd-none">本次更新优化了使用体验与稳定性</div>
        </div>

        <!-- 应用内下载进度（复用本地包时没有下载过程，不显示这条） -->
        <div v-if="downloading && !apkCached" class="upd-prog">
          <div class="upd-prog-bar"><i :style="{ width: progress >= 0 ? progress + '%' : '100%' }" :class="{ indet: progress < 0 }"></i></div>
          <div class="upd-prog-txt">{{ progress >= 0 ? '正在下载 ' + progress + '%' : '正在下载…' }}</div>
        </div>

        <div class="upd-acts">
          <button v-if="!updateInfo?.force" class="upd-btn ghost" :disabled="downloading" @click="laterUpdate">稍后</button>
          <button class="upd-btn primary" :disabled="downloading" @click="doUpdate">
            {{ downloading ? (apkCached ? '打开安装…' : '下载中…') : (apkCached ? '立即安装' : '立即更新') }}
          </button>
        </div>
        <div v-if="updateInfo?.force" class="upd-tip">该版本为强制更新，请尽快升级</div>
      </div>
    </div>

    <!-- 升级后首次启动：「本次更新内容」（大厂 App 常见；本机版本与上次见到的不一致时弹一次） -->
    <transition name="fade-page">
      <div v-if="showWhatsNew && whatsNew" class="wn-mask" @click.self="showWhatsNew = false">
        <div class="wn">
          <div class="wn-badge">NEW</div>
          <div class="wn-t">已更新到 v{{ whatsNew.version }}</div>
          <div class="wn-s">{{ whatsNew.title || '' }}</div>
          <ul class="wn-list"><li v-for="(it, i) in whatsNew.items" :key="i">{{ it }}</li></ul>
          <button class="wn-btn" @click="showWhatsNew = false">知道了</button>
        </div>
      </div>
    </transition>

    <!-- 应用锁：冷启动 / 超时切回时盖在最上层 -->
    <div v-if="locked" class="lock-mask">
      <div class="lock-box" :class="{ shake: shaking }">
        <span class="lock-ico"><el-icon :size="26"><Lock /></el-icon></span>
        <div class="lock-t">请输入密码</div>
        <div class="lock-d">已开启应用锁，解锁后可继续使用</div>

        <div class="lock-dots">
          <i v-for="i in pinLen" :key="i" :class="{ on: pin.length > i - 1 }"></i>
        </div>
        <div class="lock-err">{{ pinErr }}</div>

        <div class="lock-pad">
          <button v-for="k in ['1','2','3','4','5','6','7','8','9']" :key="k" class="lk" type="button"
                  @click="pressKey(k)">{{ k }}</button>
          <span class="lk ph"></span>
          <button class="lk" type="button" @click="pressKey('0')">0</button>
          <button class="lk del" type="button" @click="pressKey('del')">
            <el-icon :size="19"><Delete /></el-icon>
          </button>
        </div>
        <div class="lock-hint">忘记密码？清除应用数据后重新登录即可重设</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.m-app {
  height: 100vh; height: 100dvh;
  display: flex; flex-direction: column;
  background: var(--m-bg);
  color: var(--m-text);
  overflow: hidden;
}

/* 页面容器：唯一滚动区。
   ⚠️ 这里**不能再给 TabBar 预留 padding-bottom**：TabBar 是 .m-app 的 flex 兄弟节点、
   在文档流里占高度（不是 fixed 覆盖层），.m-view 的下边缘本来就紧贴 TabBar 上边缘。
   之前那 58px 预留会把整页内容顶高 —— 聊天页这类「满高 flex」页面的底部输入条
   因此悬在半空、视觉上偏上（2026-10-07 修）。 */
.m-view {
  flex: 1 1 auto; min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior-y: contain;
}

/* ---------- 应用更新下载横幅 ---------- */
.upd-bar {
  flex: none; display: flex; align-items: center; gap: 10px;
  padding: 9px 12px;
  background: var(--m-primary-soft); color: var(--m-text);
  border-bottom: 1px solid var(--m-border);
}
.upd-bar.done { background: #ecfdf5; }
html.dark .upd-bar.done { background: #0e2e26; }
.ub-ico { flex: none; color: var(--m-primary); display: inline-flex; }
.upd-bar.done .ub-ico { color: #059669; }
.ub-body { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ub-t { font-size: 13px; font-weight: 600; color: var(--m-text); }
.ub-d { font-size: 11.5px; color: var(--m-text-sub); }
.ub-bar { margin-top: 3px; height: 4px; border-radius: 999px; background: var(--m-card); overflow: hidden; }
.ub-bar > i { display: block; height: 100%; background: var(--m-primary); border-radius: 999px; transition: width .2s ease; }
.ub-bar > i.indet { animation: ub-indet 1.1s ease-in-out infinite; }
@keyframes ub-indet { 0% { opacity: .35 } 50% { opacity: 1 } 100% { opacity: .35 } }
.ub-x {
  flex: none; width: 28px; height: 28px; border-radius: 50%; border: none;
  background: transparent; color: var(--m-text-dim);
  display: grid; place-items: center; cursor: pointer;
}

/* ---------- 离线 / 待同步提示条 ---------- */
.net-bar {
  position: fixed; left: 8px; right: 8px;
  bottom: calc(60px + env(safe-area-inset-bottom));
  z-index: 60;
  display: flex; align-items: center; gap: 8px;
  padding: 9px 12px;
  border-radius: 12px;
  background: #fff7ed; color: #9a3412;
  border: 1px solid #fed7aa;
  font-size: 12.5px;
  box-shadow: 0 6px 20px rgba(15, 23, 42, .12);
}
.net-bar.offline { background: #fef2f2; color: #991b1b; border-color: #fecaca; }
.net-ico { flex: none; }
.net-ico.spin { animation: kb-spin 1s linear infinite; }
@keyframes kb-spin { to { transform: rotate(360deg); } }
.net-act {
  margin-left: auto; flex: none;
  border: none; background: transparent;
  color: var(--m-primary); font-size: 12.5px; font-weight: 600;
  padding: 4px 6px; cursor: pointer;
}
html.dark .net-bar { background: #3b2411; color: #fdba74; border-color: #7c2d12; }
html.dark .net-bar.offline { background: #3f1d1d; color: #fca5a5; border-color: #7f1d1d; }
html.dark .net-act { color: #4c8dff; }   /* 深棕底上 #1677ff 对比度仅 ~3.5:1，用亮蓝变体达 AA */

/* ---------- 底部 TabBar ---------- */
.tabbar {
  flex: none;
  display: flex; align-items: stretch;
  height: calc(58px + env(safe-area-inset-bottom));
  padding-bottom: env(safe-area-inset-bottom);
  background: var(--m-tabbar-bg);
  border-top: 1px solid var(--m-border);
  backdrop-filter: saturate(180%) blur(14px);
  z-index: 50;
}
.tab {
  flex: 1 1 0;
  border: none; background: transparent;
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  gap: 2px;
  color: var(--m-text-dim);
  font-size: 10.5px;
  padding: 0; cursor: pointer;
  position: relative;
  transition: color .18s;
  -webkit-tap-highlight-color: transparent;
}
.tab.active { color: var(--m-primary); }
.tab-ico { position: relative; display: inline-flex; line-height: 0; }
.tab-txt { line-height: 1; }

/* 消息未读红点 */
.dot {
  /* 定位：红点**中心**正好落在图标的右上角（Vant / Ant Design Mobile 的标准做法）。
     用 right/top 而不是 left:%，好处是图标尺寸变了也不会错位、多位数（99+）向左自然伸展。
     ⚠️ 曾经用 `left: 51%` + `line-height == height`：实测红点中心落在图标右边缘内侧 2.5px、
     上边缘下方 2px（视觉偏左偏下），且数字会被行框下伸部顶偏（2026-10-11 两次修复）。 */
  position: absolute; top: -8px; right: -8px;
  min-width: 16px; height: 16px; padding: 0 4px;
  box-sizing: border-box;
  border-radius: 9px; background: #ef4444; color: #fff;
  font-size: 10px; font-style: normal; font-weight: 600;
  /* flex 居中：line-height 居中会被行框下伸部影响 */
  display: inline-flex; align-items: center; justify-content: center;
  line-height: 1; letter-spacing: 0;
  font-variant-numeric: tabular-nums;
  box-shadow: 0 0 0 2px var(--m-tabbar-bg);
}

/* 中间 ＋：圆形凸起按钮，向上顶出 TabBar */
.tab.center { overflow: visible; }
.fab {
  width: 46px; height: 46px;
  border-radius: 50%;
  background: var(--m-primary);
  color: #fff;
  display: inline-flex; align-items: center; justify-content: center;
  box-shadow: 0 6px 18px rgba(22, 119, 255, .38);
  transform: translateY(-14px);
  transition: transform .16s cubic-bezier(.34, 1.56, .64, 1), box-shadow .16s;
}
.tab.center:active .fab { transform: translateY(-14px) scale(.92); box-shadow: 0 3px 12px rgba(22, 119, 255, .45); }

/* ---------- 过渡动画（原生式左右滑入，替代网页感淡入） ---------- */
.fade-page-enter-active, .fade-page-leave-active { transition: opacity .2s ease, transform .22s cubic-bezier(.4, 0, .2, 1); }
.fade-page-enter-from { opacity: 0; transform: translateX(16px); }
.fade-page-leave-to { opacity: 0; transform: translateX(-12px); }

.slide-up-enter-active, .slide-up-leave-active { transition: opacity .2s, transform .2s; }
.slide-up-enter-from, .slide-up-leave-to { opacity: 0; transform: translateY(10px); }

/* ---------- 应用内升级弹层 ---------- */
.upd-mask {
  position: fixed; inset: 0; z-index: 3000;
  background: var(--m-mask);
  display: flex; align-items: center; justify-content: center;
  padding: 24px;
}
.upd-panel {
  width: 100%; max-width: 320px;
  background: var(--m-card);
  border-radius: var(--m-radius-lg);
  padding: 20px 18px 16px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, .18);
}
/* 弹层头部：图标 + 标题 + 版本徽标 */
.upd-head { display: flex; align-items: center; gap: 12px; }
.upd-ico {
  flex: none; width: 44px; height: 44px; border-radius: 14px;
  display: inline-flex; align-items: center; justify-content: center;
  background: var(--m-primary-soft); color: var(--m-primary);
}
.upd-head-txt { flex: 1 1 auto; min-width: 0; }
.upd-title { font-size: 16.5px; font-weight: 600; color: var(--m-text); }
.upd-ver { margin-top: 4px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.upd-badge {
  font-size: 11.5px; font-weight: 700; color: var(--m-primary);
  background: var(--m-primary-soft); border-radius: 6px; padding: 2px 7px;
}
.upd-build { font-size: 11.5px; color: var(--m-text-dim); }

/* 更新内容：条目列表 */
.upd-body { margin-top: 14px; background: var(--m-card-2); border-radius: var(--m-radius); padding: 12px 14px; max-height: 40vh; overflow-y: auto; }
.upd-body-t { font-size: 12px; font-weight: 600; color: var(--m-text-dim); margin-bottom: 6px; }
.upd-list { margin: 0; padding-left: 18px; }
.upd-list li { font-size: 13.5px; line-height: 1.8; color: var(--m-text-sub); }
.upd-none { font-size: 13.5px; color: var(--m-text-sub); }
.upd-acts { display: flex; gap: 10px; margin-top: 16px; }
.upd-btn {
  flex: 1; height: 42px; border: none; border-radius: var(--m-radius);
  font-size: 15px; font-family: inherit; cursor: pointer;
}
.upd-btn.ghost { background: var(--m-card-2); color: var(--m-text-sub); }
.upd-btn.primary { background: var(--m-primary); color: #fff; font-weight: 600; }
.upd-btn:disabled { opacity: .6; }
.upd-tip { margin-top: 10px; font-size: 12px; color: var(--m-warn); text-align: center; }

/* ---------- 升级后首次启动的「本次更新内容」 ---------- */
.wn-mask { position: fixed; inset: 0; z-index: 3300; background: var(--m-mask);
  display: flex; align-items: center; justify-content: center; padding: 26px; }
.wn { width: 100%; max-width: 320px; background: var(--m-card); border-radius: 22px;
  padding: 26px 20px 16px; text-align: center; box-shadow: 0 18px 44px rgba(0,0,0,.26); }
.wn-badge { display: inline-block; font-size: 10.5px; font-weight: 800; letter-spacing: 1px; color: #fff;
  background: linear-gradient(135deg, #ff6b8a, #ff375f); border-radius: 999px; padding: 3px 10px; }
.wn-t { margin-top: 10px; font-size: 18px; font-weight: 700; color: var(--m-text); }
.wn-s { margin-top: 3px; font-size: 13px; color: var(--m-text-sub); }
.wn-list { margin: 14px 0 0; padding: 0 6px 0 20px; text-align: left; max-height: 44vh; overflow-y: auto; }
.wn-list li { font-size: 13.5px; line-height: 1.75; color: var(--m-text-sub); margin-bottom: 4px; }
.wn-btn { width: 100%; height: 44px; margin-top: 14px; border: none; border-radius: 14px;
  background: var(--m-primary); color: #fff; font-size: 15.5px; font-weight: 600; cursor: pointer; }

/* 应用内下载进度条 */
.upd-prog { margin-top: 14px; }
.upd-prog-bar { height: 6px; border-radius: 999px; background: var(--m-card-2); overflow: hidden; }
.upd-prog-bar > i { display: block; height: 100%; background: var(--m-primary); border-radius: 999px; transition: width .2s ease; }
.upd-prog-bar > i.indet { animation: upd-indet 1.1s ease-in-out infinite; }
@keyframes upd-indet { 0% { opacity: .35 } 50% { opacity: 1 } 100% { opacity: .35 } }
.upd-prog-txt { margin-top: 6px; font-size: 12px; color: var(--m-text-dim); text-align: center; }

/* ---------- 应用锁 ---------- */
.lock-mask {
  position: fixed; inset: 0; z-index: 3200;
  background: var(--m-bg);
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  padding: 0 28px calc(env(safe-area-inset-bottom) + 16px);
}
.lock-box { width: 100%; max-width: 320px; text-align: center; }
.lock-box.shake { animation: lock-shake .4s; }
@keyframes lock-shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-8px); }
  40% { transform: translateX(8px); }
  60% { transform: translateX(-5px); }
  80% { transform: translateX(5px); }
}
.lock-ico {
  display: inline-flex; align-items: center; justify-content: center;
  width: 58px; height: 58px; border-radius: 20px;
  background: var(--m-primary-soft); color: var(--m-primary);
}
.lock-t { margin-top: 14px; font-size: 18px; font-weight: 600; color: var(--m-text); }
.lock-d { margin-top: 5px; font-size: 12.5px; color: var(--m-text-dim); }

.lock-dots { display: flex; justify-content: center; gap: 14px; margin: 26px 0 6px; }
.lock-dots i {
  width: 12px; height: 12px; border-radius: 50%;
  background: transparent; border: 1.5px solid var(--m-border);
  transition: background .15s, border-color .15s, transform .15s;
}
.lock-dots i.on { background: var(--m-primary); border-color: var(--m-primary); transform: scale(1.08); }
.lock-err { height: 18px; font-size: 12.5px; color: var(--m-danger); }

.lock-pad {
  margin-top: 14px;
  display: grid; grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.lk {
  height: 58px; border-radius: 16px; cursor: pointer;
  border: none; background: var(--m-card); color: var(--m-text);
  font-size: 22px; font-weight: 500; font-family: inherit;
  box-shadow: var(--m-shadow);
  display: inline-flex; align-items: center; justify-content: center;
}
.lk:active { background: var(--m-card-2); transform: scale(.97); }
.lk.ph { background: transparent; box-shadow: none; pointer-events: none; }
.lk.del { color: var(--m-text-sub); font-size: 18px; }
.lock-hint { margin-top: 22px; font-size: 12px; color: var(--m-text-dim); line-height: 1.7; }
</style>
