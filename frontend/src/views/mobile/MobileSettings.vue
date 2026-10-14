<script setup>
/**
 * 移动端「设置」页
 *
 * 视觉对齐主流 App（iOS 设置 / 微信 / 支付宝）：
 *   - 每个功能项左侧是**彩色圆角方块图标**（iOS Settings 的标志性做法）
 *   - 分隔线**内缩**（从文字处起，不贯穿整行）
 *   - 行高更松弛、分组卡片圆角更大、页头留白更足
 *   - 个性化：主题色（色环选中）、深色、正文字号（分段控件）、点击振动
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '../../store/auth'
import request from '../../api/request'
import { cachedCount, clearCache, pendingCount, syncOnReconnect } from '../../utils/offlineStore'
import { applyTheme } from '../../utils/theme'
import { THEMES, FONTS, getMobileTheme, setMobileTheme, resetMobileTheme } from '../../utils/mobileTheme'
import { hasUpdateBadge, UPDATE_BADGE_EVENT } from '../../utils/updateBadge'

const router = useRouter()
const auth = useAuthStore()

const dark = ref(document.documentElement.classList.contains('dark'))
const cur = ref(getMobileTheme())
const cacheN = ref(0)
const cacheSize = ref(0)
const pendingN = ref(0)
const online = ref(navigator.onLine)
const versionText = ref('')
const updDot = ref(false)   // 有新版本时点亮「关于」入口红点

const name = computed(() => auth.user?.displayName || auth.user?.username || '')
const fontPx = computed(() => (FONTS.find((f) => f.key === cur.value.font) || FONTS[1]).px)

function loadVersion() {
  const wv = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : ''
  if (wv) versionText.value = 'v' + wv
  window.Capacitor?.Plugins?.App?.getInfo?.().then((i) => {
    if (i?.version) versionText.value = 'v' + i.version + (i.build ? '（Build ' + i.build + '）' : '')
  }).catch(() => {})
}

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
function onReset() { resetMobileTheme(); cur.value = getMobileTheme(); window.__kbMsg?.success('已恢复默认外观') }

async function doClearCache() {
  try {
    await ElMessageBox.confirm('将清除本机缓存的文档内容（不影响服务器上的文档）。', '清除缓存', {
      type: 'warning', confirmButtonText: '清除', cancelButtonText: '取消'
    })
  } catch { return }
  await clearCache(); await refresh(); window.__kbMsg?.success('已清除')
}
async function doSync() {
  if (!online.value) { window.__kbMsg?.warning('当前没有网络'); return }
  const n = await syncOnReconnect(); await refresh()
  window.__kbMsg?.success(n > 0 ? `已同步 ${n} 篇` : '没有待同步内容')
}
async function logout() {
  try { await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消' }) } catch { return }
  auth.logout(); router.replace('/login')
}
async function deleteAccount() {
  try {
    await ElMessageBox.confirm('注销后你的账号与关联的个人数据将被永久删除，且无法恢复。确定继续吗？', '注销账号',
      { type: 'warning', confirmButtonText: '下一步', cancelButtonText: '取消' })
  } catch { return }
  let password = ''
  try {
    const r = await ElMessageBox.prompt('请输入登录密码，确认后账号将立即注销且无法恢复', '身份确认',
      { type: 'warning', inputType: 'password', inputPlaceholder: '登录密码', confirmButtonText: '确认注销', cancelButtonText: '取消' })
    password = String(r.value || '')
    if (!password) { window.__kbMsg?.warning('需要密码确认'); return }
  } catch { return }
  try {
    await request({ url: '/api/auth/me', method: 'delete', data: { password } })
    try { localStorage.removeItem('kb_token') } catch {}
    await clearCache().catch(() => {})
    import('../../utils/offlineStore').then((m) => m.purgeAllOffline()).catch(() => {})
    window.__kbMsg?.success('账号已注销'); router.replace('/login')
  } catch (e) {
    const msg = (e && e.response && e.response.data && e.response.data.error) || (e && e.message) || '注销失败，请稍后再试'
    window.__kbMsg?.error(msg)
  }
}

function onBackOnline() { online.value = true; refresh() }
function onUpdDot() { updDot.value = hasUpdateBadge() }

onMounted(() => {
  refresh(); loadVersion(); updDot.value = hasUpdateBadge()
  window.addEventListener('online', onBackOnline)
  window.addEventListener(UPDATE_BADGE_EVENT, onUpdDot)
})
onUnmounted(() => {
  window.removeEventListener('online', onBackOnline)
  window.removeEventListener(UPDATE_BADGE_EVENT, onUpdDot)
})
</script>

<template>
  <div class="st">
    <header class="st-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="st-title">设置</div>
      <button class="m-header-act" style="visibility:hidden"><el-icon><Setting /></el-icon></button>
    </header>

    <!-- 账号 -->
    <section class="grp">
      <h3 class="gt">账号</h3>
      <div class="card">
        <button class="row" type="button" @click="router.push('/profile')">
          <span class="ic" style="background:linear-gradient(135deg,#4f86ff,#1677ff)">
            <el-icon :size="17"><User /></el-icon>
          </span>
          <span class="lb">{{ name }}</span>
          <span class="val">@{{ auth.user?.username }}</span>
          <el-icon class="chev"><Right /></el-icon>
        </button>
        <button class="row" type="button" @click="router.push('/points')">
          <span class="ic" style="background:linear-gradient(135deg,#ffb340,#ff7d00)">
            <el-icon :size="17"><Coin /></el-icon>
          </span>
          <span class="lb">我的积分</span>
          <span class="val">{{ auth.user?.points || 0 }}</span>
          <el-icon class="chev"><Right /></el-icon>
        </button>
      </div>
    </section>

    <!-- 个性化 -->
    <section class="grp">
      <h3 class="gt">个性化</h3>
      <div class="card">
        <!-- 主题色 -->
        <div class="row col">
          <span class="ic" style="background:linear-gradient(135deg,#a78bfa,#7c5cff)">
            <el-icon :size="17"><Brush /></el-icon>
          </span>
          <span class="lb">主题色</span>
        </div>
        <div class="swatches">
          <button v-for="t in THEMES" :key="t.key" class="sw" type="button" @click="pickTheme(t.key)">
            <span class="dot" :style="{ background: t.primary }" :class="{ on: cur.theme === t.key }">
              <el-icon v-if="cur.theme === t.key" :size="15"><Check /></el-icon>
            </span>
            <span class="sw-n">{{ t.name }}</span>
          </button>
        </div>

        <!-- 深色 -->
        <button class="row" type="button" @click="toggleDark">
          <span class="ic" style="background:linear-gradient(135deg,#5b6478,#2b3140)">
            <el-icon :size="17"><component :is="dark ? 'Moon' : 'Sunny'" /></el-icon>
          </span>
          <span class="lb">深色模式</span>
          <span class="sw2" :class="{ on: dark }"><i></i></span>
        </button>

        <!-- 振动 -->
        <button class="row" type="button" @click="toggleHaptics">
          <span class="ic" style="background:linear-gradient(135deg,#ff6b8a,#ff375f)">
            <el-icon :size="17"><Bell /></el-icon>
          </span>
          <span class="lb">点击振动</span>
          <span class="sw2" :class="{ on: cur.haptics !== false }"><i></i></span>
        </button>

        <!-- 字号 -->
        <div class="row col">
          <span class="ic" style="background:linear-gradient(135deg,#33c8b0,#0ab6b6)">
            <el-icon :size="17"><Reading /></el-icon>
          </span>
          <span class="lb">正文字号</span>
        </div>
        <div class="seg">
          <button v-for="f in FONTS" :key="f.key" class="seg-i" :class="{ on: cur.font === f.key }"
                  type="button" @click="pickFont(f.key)">{{ f.name }}</button>
        </div>
        <div class="preview" :style="{ fontSize: fontPx + 'px' }">知识库 · 正文预览，可随时调整。</div>
        <button class="reset" @click="onReset">恢复默认外观</button>
      </div>
    </section>

    <!-- 通用 -->
    <section class="grp">
      <h3 class="gt">通用</h3>
      <div class="card">
        <button class="row" type="button" @click="router.push('/lock')">
          <span class="ic" style="background:linear-gradient(135deg,#3ddc84,#00b42a)">
            <el-icon :size="17"><Lock /></el-icon>
          </span>
          <span class="lb">应用锁</span>
          <span class="val">PIN</span>
          <el-icon class="chev"><Right /></el-icon>
        </button>
        <button class="row" type="button" @click="doClearCache">
          <span class="ic" style="background:linear-gradient(135deg,#a3aab8,#8a8f99)">
            <el-icon :size="17"><Files /></el-icon>
          </span>
          <span class="lb">清除缓存</span>
          <span class="val">{{ cacheN }} 篇 · {{ fmtSize(cacheSize) }}</span>
          <el-icon class="chev"><Right /></el-icon>
        </button>
        <div class="row">
          <span class="ic" style="background:linear-gradient(135deg,#4f86ff,#1677ff)">
            <el-icon :size="17"><Upload /></el-icon>
          </span>
          <span class="lb">待同步文档</span>
          <span class="val">{{ pendingN }} 篇</span>
          <button v-if="pendingN" class="mini" @click="doSync">同步</button>
        </div>
      </div>
      <p class="note">离线时新建的文档会先存在本机，恢复网络后自动上传。</p>
    </section>

    <!-- 关于 -->
    <section class="grp">
      <h3 class="gt">关于</h3>
      <div class="card">
        <button class="row" type="button" @click="router.push('/about')">
          <span class="ic" style="background:linear-gradient(135deg,#4f86ff,#1677ff)">
            <el-icon :size="17"><InfoFilled /></el-icon>
          </span>
          <span class="lb">关于知识库</span>
          <span class="val">{{ versionText }}</span>
          <i v-if="updDot" class="dot-upd" title="有新版本"></i>
          <el-icon class="chev"><Right /></el-icon>
        </button>
        <button class="row" type="button" @click="router.push('/changelog')">
          <span class="ic" style="background:linear-gradient(135deg,#33c8b0,#0ab6b6)">
            <el-icon :size="17"><Document /></el-icon>
          </span>
          <span class="lb">更新日志</span>
          <el-icon class="chev"><Right /></el-icon>
        </button>
        <button class="row" type="button" @click="router.push('/privacy')">
          <span class="ic" style="background:linear-gradient(135deg,#3ddc84,#00b42a)">
            <el-icon :size="17"><Document /></el-icon>
          </span>
          <span class="lb">隐私政策</span>
          <el-icon class="chev"><Right /></el-icon>
        </button>
        <button class="row" type="button" @click="router.push('/disclaimer')">
          <span class="ic" style="background:linear-gradient(135deg,#a3aab8,#8a8f99)">
            <el-icon :size="17"><Document /></el-icon>
          </span>
          <span class="lb">免责声明</span>
          <el-icon class="chev"><Right /></el-icon>
        </button>
      </div>
    </section>

    <!-- 账号安全 -->
    <section class="grp">
      <div class="card">
        <button class="row" type="button" :disabled="auth.isDemo" @click="deleteAccount">
          <span class="ic" style="background:linear-gradient(135deg,#ff6b8a,#ff375f)">
            <el-icon :size="17"><Delete /></el-icon>
          </span>
          <span class="lb danger">注销账号</span>
        </button>
      </div>
      <p v-if="auth.isDemo" class="note">演示账号为公共账号，不支持注销</p>
      <p v-else class="note">注销后账号与个人数据将永久删除，无法恢复；唯一的管理员账号不允许注销</p>
    </section>

    <section class="grp">
      <button class="logout" @click="logout"><el-icon><SwitchButton /></el-icon> 退出登录</button>
    </section>

    <div class="ver">知识库 · {{ versionText || '移动端' }}</div>
    <div style="height:24px"></div>
  </div>
</template>

<style scoped>
.st { min-height: 100%; background: var(--m-bg); }
.st-head {
  position: sticky; top: 0; z-index: 30;
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
}
.st-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }

/* 分组：标题更松弛，卡片圆角更大、阴影更柔 */
.grp { padding: 0 16px; }
.gt { margin: 18px 6px 8px; font-size: 12.5px; font-weight: 500; color: var(--m-text-dim); letter-spacing: .3px; }
.card {
  background: var(--m-card); border-radius: 14px; overflow: hidden;
  box-shadow: 0 1px 2px rgba(0, 0, 0, .04), 0 6px 18px rgba(15, 23, 42, .04);
}

/* 行：高 52，彩色圆角方块图标 + 内缩分隔线 */
.row {
  position: relative; width: 100%; min-height: 52px; box-sizing: border-box;
  display: flex; align-items: center; gap: 12px; padding: 9px 14px;
  border: none; background: transparent; color: var(--m-text); text-align: left; cursor: pointer;
}
/* 内缩分隔线：从文字处起（left:55 ≈ 图标 29 + 间距 12 + 内边距 14） */
.row + .row::before, .swatches + .row::before {
  content: ''; position: absolute; left: 55px; right: 0; top: 0; height: 1px;
  background: var(--m-border);
}
.row.col { cursor: default; }
.row:disabled { cursor: not-allowed; }

.ic {
  flex: none; width: 29px; height: 29px; border-radius: 8px;
  display: grid; place-items: center; color: #fff;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .12);
}
.lb { flex: 1 1 auto; font-size: 15.5px; color: var(--m-text); }
.lb.danger { color: var(--m-danger); }
.val { flex: none; font-size: 14px; color: var(--m-text-dim); max-width: 46%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chev { flex: none; color: #c8c9cc; }
/* 「关于」入口的更新红点（后台有新版本时亮） */
.dot-upd { flex: none; width: 8px; height: 8px; border-radius: 50%; background: #ff375f;
  box-shadow: 0 0 0 2px var(--m-card); }

/* iOS 风格开关 */
.sw2 { flex: none; width: 46px; height: 28px; border-radius: 14px; background: var(--m-border); position: relative; transition: background .22s; }
.sw2 i { position: absolute; top: 2px; left: 2px; width: 24px; height: 24px; border-radius: 50%; background: #fff; transition: transform .22s; box-shadow: 0 1px 3px rgba(0,0,0,.25); }
.sw2.on { background: var(--m-primary); }
.sw2.on i { transform: translateX(18px); }

/* 主题色 */
.swatches { display: flex; gap: 4px; padding: 2px 14px 14px; }
.sw { flex: 1 1 0; border: none; background: transparent; cursor: pointer; padding: 0; }
.dot { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 50%; margin: 0 auto; color: #fff;
  box-shadow: 0 2px 8px rgba(0,0,0,.14); transition: transform .15s; }
.dot.on { transform: scale(1.06); box-shadow: 0 0 0 3px var(--m-card), 0 0 0 5px currentColor, 0 2px 8px rgba(0,0,0,.14); }
.sw-n { display: block; margin-top: 5px; font-size: 11px; color: var(--m-text-dim); }

/* 字号分段控件 */
.seg { display: flex; gap: 2px; margin: 0 14px 10px; padding: 3px; background: var(--m-card-2); border-radius: 10px; }
.seg-i { flex: 1 1 0; height: 32px; border: none; border-radius: 8px; background: transparent; color: var(--m-text-sub); font-size: 13.5px; cursor: pointer; }
.seg-i.on { background: var(--m-card); color: var(--m-primary); font-weight: 600; box-shadow: 0 1px 3px rgba(15,23,42,.1); }
.preview { margin: 0 14px 12px; line-height: 1.8; color: var(--m-text); background: var(--m-card-2); border-radius: 10px; padding: 12px 14px; }
.reset { width: calc(100% - 28px); margin: 0 14px 14px; height: 40px; border-radius: 10px; border: 1px solid var(--m-border); background: var(--m-card); color: var(--m-text-sub); font-size: 14px; cursor: pointer; }

.mini { flex: none; border: none; border-radius: 8px; background: var(--m-primary); color: #fff; font-size: 12.5px; font-weight: 600; padding: 6px 12px; cursor: pointer; }

.note { margin: 8px 8px 0; font-size: 12px; line-height: 1.6; color: var(--m-text-dim); }

.logout {
  width: 100%; height: 50px; border: none; border-radius: 14px; background: var(--m-card);
  color: var(--m-danger); box-shadow: 0 1px 2px rgba(0,0,0,.04); font-size: 15.5px; font-weight: 600;
  cursor: pointer; display: inline-flex; align-items: center; justify-content: center; gap: 8px;
}
.ver { text-align: center; font-size: 12px; color: var(--m-text-dim); margin-top: 18px; }
</style>
