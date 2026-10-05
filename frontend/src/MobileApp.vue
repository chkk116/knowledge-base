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

onMounted(async () => {
  await auth.fetchMe()
  if (auth.loggedIn) notification.connect()
  // 页脚配置走公开接口，登录前也要拉（移动端登录页底部要展示备案信息）
  siteInfo.load()
  refreshPending()
  if (navigator.onLine) doSync()
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
  window.addEventListener('kb:offline-pending-changed', onPendingChange)
})

onUnmounted(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
  window.removeEventListener('kb:offline-pending-changed', onPendingChange)
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
        <span v-if="!online">当前离线，内容会先存在本机，联网后自动同步</span>
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

/* 页面容器：唯一滚动区。padding-bottom 给 TabBar 让位（含 iOS 底部横条安全区） */
.m-view {
  flex: 1 1 auto; min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior-y: contain;
  padding-bottom: calc(58px + env(safe-area-inset-bottom));
}
.m-app.no-tab .m-view { padding-bottom: 0; }

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
  color: #4f46e5; font-size: 12.5px; font-weight: 600;
  padding: 4px 6px; cursor: pointer;
}
html.dark .net-bar { background: #3b2411; color: #fdba74; border-color: #7c2d12; }
html.dark .net-bar.offline { background: #3f1d1d; color: #fca5a5; border-color: #7f1d1d; }
html.dark .net-act { color: #a5b4fc; }

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
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #fff;
  display: inline-flex; align-items: center; justify-content: center;
  box-shadow: 0 6px 18px rgba(79, 70, 229, .42);
  transform: translateY(-14px);
  transition: transform .16s cubic-bezier(.34, 1.56, .64, 1), box-shadow .16s;
}
.tab.center:active .fab { transform: translateY(-14px) scale(.92); box-shadow: 0 3px 12px rgba(79, 70, 229, .5); }

/* ---------- 过渡动画 ---------- */
.fade-page-enter-active, .fade-page-leave-active { transition: opacity .16s ease, transform .16s ease; }
.fade-page-enter-from { opacity: 0; transform: translateY(6px); }
.fade-page-leave-to { opacity: 0; transform: translateY(-4px); }

.slide-up-enter-active, .slide-up-leave-active { transition: opacity .2s, transform .2s; }
.slide-up-enter-from, .slide-up-leave-to { opacity: 0; transform: translateY(10px); }
</style>
