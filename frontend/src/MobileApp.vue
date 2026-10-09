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
import { downloadAndInstall } from './utils/appUpdate'
import { getAppVersion, getAppBundle } from './api/app'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const siteInfo = useSiteInfoStore()
const notification = useNotificationStore()

const online = ref(navigator.onLine)
const pending = ref(0)
const syncing = ref(false)

// 登录页 / 拍照页：全屏，不显示 TabBar
const hideTab = computed(() => route.path === '/login' || route.path === '/capture')

const activeTab = computed(() => route.meta.tab || '')

const TABS = [
  { key: 'home', path: '/home', label: '首页', icon: 'HomeFilled' },
  { key: 'docs', path: '/docs', label: '文档库', icon: 'Document' },
  { key: 'capture', path: '/capture', label: '', icon: 'Plus' },  // 中间 ＋
  { key: 'messages', path: '/messages', label: '消息', icon: 'ChatDotRound' },
  { key: 'profile', path: '/profile', label: '我的', icon: 'User' }
]

function goTab(t) {
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
const updateInfo = ref(null)      // { versionCode, versionName, url, notes, force }
const showUpdate = ref(false)
const downloading = ref(false)
const progress = ref(-1)        // 应用内下载进度（-1=未知）

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
      showUpdate.value = true
    }
  } catch { /* 静默失败，绝不影响正常使用 */ }
}

/**
 * 下载并安装。
 * 第一步先走「跳系统浏览器下载」——不依赖任何原生插件，最稳。
 * （后续要改成 App 内静默下载 + 拉起安装器，需要自研一个 Capacitor 插件。）
 */
function doUpdate() {
  const u = updateInfo.value
  if (!u?.url) { window.__kbMsg?.warning('暂无下载地址，请联系管理员'); return }
  downloading.value = true
  progress.value = -1
  // App 内：下载完直接拉起系统安装器（不再跳浏览器）。
  // Web 端 / 老包没带 ApkInstaller 插件时，downloadAndInstall 内部会自动退回系统浏览器。
  downloadAndInstall(u.url, (p) => { progress.value = p })
    .then((r) => {
      if (r.mode === 'in-app') window.__kbMsg?.success('下载完成，请在弹出的安装界面完成安装')
    })
    .catch((e) => {
      window.__kbMsg?.error('更新失败：' + ((e && e.message) || e))
    })
    .finally(() => { downloading.value = false })
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
    const cur = await updater.current()
    if (cur?.bundle?.version === data.version) return // 已是最新
    const b = await updater.download({ url: data.url, version: data.version })
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
  try { capApp?.minimizeApp?.() } catch { /* 老版本没有则忽略 */ }
}

onMounted(async () => {
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
})

onUnmounted(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
  window.removeEventListener('kb:offline-pending-changed', onPendingChange)
  try { backListener?.remove?.() } catch { /* ignore */ }
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
        <div class="upd-title">发现新版本 {{ updateInfo?.versionName || '' }}</div>
        <pre v-if="updateInfo?.notes" class="upd-notes">{{ updateInfo.notes }}</pre>
        <!-- 应用内下载进度 -->
        <div v-if="downloading" class="upd-prog">
          <div class="upd-prog-bar"><i :style="{ width: progress >= 0 ? progress + '%' : '100%' }" :class="{ indet: progress < 0 }"></i></div>
          <div class="upd-prog-txt">{{ progress >= 0 ? '正在下载 ' + progress + '%' : '正在下载…' }}</div>
        </div>
        <div class="upd-acts">
          <button v-if="!updateInfo?.force" class="upd-btn ghost" :disabled="downloading" @click="laterUpdate">稍后</button>
          <button class="upd-btn primary" :disabled="downloading" @click="doUpdate">
            {{ downloading ? '下载中…' : '立即更新' }}
          </button>
        </div>
        <div v-if="updateInfo?.force" class="upd-tip">该版本为强制更新，请尽快升级</div>
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
  position: absolute; top: -5px; left: 51%;
  min-width: 15px; height: 15px; padding: 0 3px;
  border-radius: 8px; background: #ef4444; color: #fff;
  font-size: 9.5px; font-style: normal; line-height: 15px;
  text-align: center; font-weight: 600;
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

/* ---------- 过渡动画 ---------- */
.fade-page-enter-active, .fade-page-leave-active { transition: opacity .16s ease, transform .16s ease; }
.fade-page-enter-from { opacity: 0; transform: translateY(6px); }
.fade-page-leave-to { opacity: 0; transform: translateY(-4px); }

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
.upd-title { font-size: 16.5px; font-weight: 600; color: var(--m-text); text-align: center; }
.upd-notes {
  margin: 12px 0 0; padding: 10px 12px;
  background: var(--m-card-2); border-radius: var(--m-radius);
  font-size: 13px; line-height: 1.7; color: var(--m-text-sub);
  white-space: pre-wrap; word-break: break-word;
  font-family: inherit; text-align: left;
  max-height: 40vh; overflow-y: auto;
}
.upd-acts { display: flex; gap: 10px; margin-top: 16px; }
.upd-btn {
  flex: 1; height: 42px; border: none; border-radius: var(--m-radius);
  font-size: 15px; font-family: inherit; cursor: pointer;
}
.upd-btn.ghost { background: var(--m-card-2); color: var(--m-text-sub); }
.upd-btn.primary { background: var(--m-primary); color: #fff; font-weight: 600; }
.upd-btn:disabled { opacity: .6; }
.upd-tip { margin-top: 10px; font-size: 12px; color: var(--m-warn); text-align: center; }

/* 应用内下载进度条 */
.upd-prog { margin-top: 14px; }
.upd-prog-bar { height: 6px; border-radius: 999px; background: var(--m-card-2); overflow: hidden; }
.upd-prog-bar > i { display: block; height: 100%; background: var(--m-primary); border-radius: 999px; transition: width .2s ease; }
.upd-prog-bar > i.indet { animation: upd-indet 1.1s ease-in-out infinite; }
@keyframes upd-indet { 0% { opacity: .35 } 50% { opacity: 1 } 100% { opacity: .35 } }
.upd-prog-txt { margin-top: 6px; font-size: 12px; color: var(--m-text-dim); text-align: center; }
</style>
