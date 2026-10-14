<script setup>
/**
 * 移动端「设置」页（重构版）
 *
 * 结构对齐主流 App：账号 / 个性化 / 通用 / 离线数据 / 关于 / 账号安全。
 * 个性化（主题色 / 字号 / 深色 / 点击振动）整合自原「外观」页，实现见 utils/mobileTheme.js。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '../../store/auth'
import request from '../../api/request'
import { cachedCount, clearCache, pendingCount, syncOnReconnect } from '../../utils/offlineStore'
import { applyTheme } from '../../utils/theme'
import { THEMES, FONTS, getMobileTheme, setMobileTheme, resetMobileTheme } from '../../utils/mobileTheme'

const router = useRouter()
const auth = useAuthStore()

const dark = ref(document.documentElement.classList.contains('dark'))
const cur = ref(getMobileTheme())
const cacheN = ref(0)
const cacheSize = ref(0)
const pendingN = ref(0)
const online = ref(navigator.onLine)
const versionText = ref('')

/** 版本号：优先原生 getInfo，Web 端回退到构建注入的 __APP_VERSION__ */
function loadVersion() {
  const wv = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : ''
  if (wv) versionText.value = 'v' + wv
  window.Capacitor?.Plugins?.App?.getInfo?.().then((i) => {
    if (i?.version) versionText.value = 'v' + i.version + (i.build ? '（Build ' + i.build + '）' : '')
  }).catch(() => {})
}

const name = computed(() => auth.user?.displayName || auth.user?.username || '')
const fontPx = computed(() => (FONTS.find((f) => f.key === cur.value.font) || FONTS[1]).px)

function fmtSize(n) {
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0, v = Number(n || 0)
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return (i === 0 ? v : v.toFixed(1)) + u[i]
}

async function refresh() {
  cacheN.value = await cachedCount().catch(() => 0)
  pendingN.value = await pendingCount().catch(() => 0)
  const { listCachedDocs } = await import('../../utils/offlineStore')
  const all = await listCachedDocs().catch(() => [])
  cacheSize.value = all.reduce((s, d) => s + (d.html ? d.html.length : 0), 0)
}

// ---- 个性化 ----
function pickTheme(k) { cur.value = setMobileTheme({ theme: k }) }
function pickFont(k) { cur.value = setMobileTheme({ font: k }) }
function toggleDark() { dark.value = !dark.value; applyTheme(dark.value) }
function toggleHaptics() { cur.value = setMobileTheme({ haptics: !(cur.value.haptics !== false) }) }
function onReset() {
  resetMobileTheme()
  cur.value = getMobileTheme()
  window.__kbMsg?.success('已恢复默认外观')
}

async function doClearCache() {
  try {
    await ElMessageBox.confirm('将清除本机缓存的文档内容（不影响服务器上的文档）。', '清除缓存', {
      type: 'warning', confirmButtonText: '清除', cancelButtonText: '取消'
    })
  } catch { return }
  await clearCache()
  await refresh()
  window.__kbMsg?.success('已清除')
}

async function doSync() {
  if (!online.value) { window.__kbMsg?.warning('当前没有网络'); return }
  const n = await syncOnReconnect()
  await refresh()
  window.__kbMsg?.success(n > 0 ? `已同步 ${n} 篇` : '没有待同步内容')
}

async function logout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' })
  } catch { return }
  auth.logout()
  router.replace('/login')
}

async function deleteAccount() {
  try {
    await ElMessageBox.confirm('注销后你的账号与关联的个人数据将被永久删除，且无法恢复。确定继续吗？', '注销账号',
      { type: 'warning', confirmButtonText: '下一步', cancelButtonText: '取消' })
  } catch { return }
  let password = ''
  try {
    const r = await ElMessageBox.prompt('请输入登录密码，确认后账号将立即注销且无法恢复', '身份确认', {
      type: 'warning', inputType: 'password', inputPlaceholder: '登录密码', confirmButtonText: '确认注销', cancelButtonText: '取消'
    })
    password = String(r.value || '')
    if (!password) { window.__kbMsg?.warning('需要密码确认'); return }
  } catch { return }
  try {
    await request({ url: '/api/auth/me', method: 'delete', data: { password } })
    try { localStorage.removeItem('kb_token') } catch {}
    await clearCache().catch(() => {})
    import('../../utils/offlineStore').then((m) => m.purgeAllOffline()).catch(() => {})
    window.__kbMsg?.success('账号已注销')
    router.replace('/login')
  } catch (e) {
    const msg = (e && e.response && e.response.data && e.response.data.error) || (e && e.message) || '注销失败，请稍后再试'
    window.__kbMsg?.error(msg)
  }
}

function onBackOnline() { online.value = true; refresh() }
onMounted(() => { refresh(); loadVersion(); window.addEventListener('online', onBackOnline) })
onUnmounted(() => window.removeEventListener('online', onBackOnline))
</script>

<template>
  <div class="st">
    <header class="st-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="st-title">设置</div>
      <button class="m-header-act" style="visibility:hidden"><el-icon><Setting /></el-icon></button>
    </header>

    <!-- 账号 -->
    <div class="body">
      <div class="m-sec-title">账号</div>
      <div class="m-card">
        <button class="m-row" type="button" @click="router.push('/profile')">
          <span class="ava" :style="auth.user?.avatar ? { backgroundImage: `url(${auth.user.avatar})` } : {}">
            <template v-if="!auth.user?.avatar">{{ (name || '?').slice(0, 1) }}</template>
          </span>
          <span class="m-row-body">
            <span class="m-row-title">{{ name }}</span>
            <span class="m-row-desc">@{{ auth.user?.username }} · {{ auth.isAdmin ? '管理员' : '普通用户' }}</span>
          </span>
          <span class="pts">{{ auth.user?.points || 0 }} 分</span>
        </button>
      </div>
    </div>

    <!-- 个性化 -->
    <div class="body">
      <div class="m-sec-title">个性化</div>

      <div class="m-card">
        <div class="sub">主题色</div>
        <div class="themes">
          <button v-for="t in THEMES" :key="t.key" class="theme" type="button" @click="pickTheme(t.key)">
            <span class="swatch" :style="{ background: t.primary }">
              <el-icon v-if="cur.theme === t.key" :size="15"><Check /></el-icon>
            </span>
            <span class="t-name">{{ t.name }}</span>
          </button>
        </div>
      </div>

      <div class="m-card">
        <button class="m-row" type="button" @click="toggleDark">
          <span class="m-ico gray"><el-icon><component :is="dark ? 'Moon' : 'Sunny'" /></el-icon></span>
          <span class="m-row-body"><span class="m-row-title">深色模式</span><span class="m-row-desc">当前：{{ dark ? '夜间' : '日间' }}</span></span>
          <span class="switch" :class="{ on: dark }"><i></i></span>
        </button>
        <button class="m-row" type="button" @click="toggleHaptics">
          <span class="m-ico gray"><el-icon><Bell /></el-icon></span>
          <span class="m-row-body"><span class="m-row-title">点击振动</span><span class="m-row-desc">切换页面等操作时的轻触反馈</span></span>
          <span class="switch" :class="{ on: cur.haptics !== false }"><i></i></span>
        </button>
      </div>

      <div class="m-card">
        <div class="sub">正文字号</div>
        <div class="fonts">
          <button v-for="f in FONTS" :key="f.key" class="font" :class="{ on: cur.font === f.key }"
                  type="button" @click="pickFont(f.key)">{{ f.name }}</button>
        </div>
        <div class="preview" :style="{ fontSize: fontPx + 'px' }">知识库 · 正文预览，可随时调整。</div>
        <button class="reset" @click="onReset">恢复默认外观</button>
      </div>
    </div>

    <!-- 通用 -->
    <div class="body">
      <div class="m-sec-title">通用</div>
      <div class="m-card">
        <button class="m-row" type="button" @click="router.push('/lock')">
          <span class="m-ico gray"><el-icon><Lock /></el-icon></span>
          <span class="m-row-body"><span class="m-row-title">应用锁</span><span class="m-row-desc">打开 App 需 PIN 码</span></span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
        <button class="m-row" type="button" @click="doClearCache">
          <span class="m-ico gray"><el-icon><Files /></el-icon></span>
          <span class="m-row-body"><span class="m-row-title">清除缓存</span><span class="m-row-desc">{{ cacheN }} 篇 · 约 {{ fmtSize(cacheSize) }}</span></span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>
    </div>

    <!-- 离线数据 -->
    <div class="body">
      <div class="m-sec-title">离线数据</div>
      <div class="m-card">
        <div class="m-row" style="cursor:default">
          <span class="m-ico blue"><el-icon><Upload /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">待同步文档</span>
            <span class="m-row-desc">{{ pendingN }} 篇{{ pendingN ? ' · 联网后自动上传' : ' · 全部已同步' }}</span>
          </span>
          <button v-if="pendingN" class="mini-btn" @click="doSync">立即同步</button>
        </div>
      </div>
      <div class="hint"><el-icon><InfoFilled /></el-icon><span>离线时新建的文档会先存在本机，恢复网络后自动上传。</span></div>
    </div>

    <!-- 关于 -->
    <div class="body">
      <div class="m-sec-title">关于</div>
      <div class="m-card">
        <button class="m-row" type="button" @click="router.push('/about')">
          <span class="m-ico gray"><el-icon><InfoFilled /></el-icon></span>
          <span class="m-row-body"><span class="m-row-title">关于知识库</span></span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
        <button class="m-row" type="button" @click="router.push('/changelog')">
          <span class="m-ico gray"><el-icon><Document /></el-icon></span>
          <span class="m-row-body"><span class="m-row-title">更新日志</span></span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
        <button class="m-row" type="button" @click="router.push('/privacy')">
          <span class="m-ico gray"><el-icon><Document /></el-icon></span>
          <span class="m-row-body"><span class="m-row-title">隐私政策</span></span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
        <button class="m-row" type="button" @click="router.push('/disclaimer')">
          <span class="m-ico gray"><el-icon><Document /></el-icon></span>
          <span class="m-row-body"><span class="m-row-title">免责声明</span></span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>
    </div>

    <!-- 账号安全 -->
    <div class="body">
      <button class="logout del" :class="{ off: auth.isDemo }" :disabled="auth.isDemo" @click="deleteAccount">
        <el-icon><Delete /></el-icon> 注销账号
      </button>
      <p v-if="auth.isDemo" class="del-tip">演示账号为公共账号，不支持注销</p>
      <p v-else class="del-tip">注销后账号与个人数据将永久删除，无法恢复；唯一的管理员账号不允许注销</p>
    </div>

    <div class="body">
      <button class="logout" @click="logout"><el-icon><SwitchButton /></el-icon> 退出登录</button>
    </div>

    <div class="ver">知识库 · {{ versionText || '移动端' }}</div>
    <div style="height:20px"></div>
  </div>
</template>

<style scoped>
.st { min-height: 100%; }
.body { padding: 0 16px; }
.st-head {
  position: sticky; top: 0; z-index: 30;
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
}
.st-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }

.ava {
  flex: none; width: 40px; height: 40px; border-radius: 50%;
  background: var(--m-primary-soft) center/cover; color: var(--m-primary);
  display: grid; place-items: center; font-weight: 700; font-size: 16px;
}
.pts { flex: none; font-size: 14px; font-weight: 700; color: var(--m-primary); }

.sub { font-size: 12.5px; color: var(--m-text-dim); padding: 12px 4px 2px; }
.themes { display: flex; gap: 6px; padding: 6px 0 10px; }
.theme { flex: 1 1 0; border: none; background: transparent; cursor: pointer; padding: 0; }
.swatch { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 50%; margin: 0 auto; color: #fff; box-shadow: 0 2px 8px rgba(0,0,0,.12); }
.t-name { display: block; margin-top: 5px; font-size: 11px; color: var(--m-text-dim); }

.fonts { display: flex; gap: 8px; padding: 6px 0 10px; }
.font { flex: 1 1 0; height: 36px; border-radius: 10px; cursor: pointer; border: 1px solid var(--m-border); background: var(--m-card-2); color: var(--m-text-sub); font-size: 14px; }
.font.on { border-color: var(--m-primary); color: var(--m-primary); background: var(--m-primary-soft); font-weight: 600; }
.preview { line-height: 1.8; color: var(--m-text); background: var(--m-card-2); border-radius: 10px; padding: 12px 14px; }
.reset { width: 100%; margin-top: 12px; height: 40px; border-radius: 10px; border: 1px solid var(--m-border); background: var(--m-card); color: var(--m-text-sub); font-size: 14px; cursor: pointer; }

.switch { flex: none; width: 44px; height: 26px; border-radius: 13px; background: var(--m-border); position: relative; transition: background-color .2s; }
.switch i { position: absolute; top: 3px; left: 3px; width: 20px; height: 20px; border-radius: 50%; background: #fff; transition: transform .2s; box-shadow: 0 1px 3px rgba(0,0,0,.2); }
.switch.on { background: var(--m-primary); }
.switch.on i { transform: translateX(18px); }

.mini-btn { flex: none; border: none; border-radius: 9px; background: var(--m-primary); color: #fff; font-size: 12.5px; font-weight: 600; padding: 7px 12px; cursor: pointer; }
.mini-btn.ghost { background: transparent; color: var(--m-text-sub); border: 1px solid var(--m-border); }

.hint { display: flex; gap: 6px; align-items: flex-start; font-size: 12px; color: var(--m-text-dim); margin-top: 10px; padding: 0 4px; line-height: 1.5; }

.logout { width: 100%; height: 50px; margin-top: 22px; border: none; border-radius: var(--m-radius); background: var(--m-card); color: var(--m-danger); box-shadow: var(--m-shadow); font-size: 15.5px; font-weight: 600; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; gap: 8px; }
.logout.del { background: var(--m-danger); color: #fff; }
.logout.del.off { background: var(--m-card-2); color: var(--m-text-dim); cursor: not-allowed; box-shadow: none; }
.del-tip { margin: 8px 2px 0; font-size: 12px; line-height: 1.6; color: var(--m-text-dim); }
.ver { text-align: center; font-size: 12px; color: var(--m-text-dim); margin-top: 16px; }
</style>
