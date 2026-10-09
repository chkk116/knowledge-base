<script setup>
/**
 * 移动端设置页：账号信息、外观、离线数据管理、退出登录
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '../../store/auth'
import request from '../../api/request'
import { cachedCount, clearCache, pendingCount, syncOnReconnect } from '../../utils/offlineStore'
import { applyTheme } from '../../utils/theme'

const router = useRouter()
const auth = useAuthStore()

const dark = ref(document.documentElement.classList.contains('dark'))
const cacheN = ref(0)
const pendingN = ref(0)
const online = ref(navigator.onLine)

const name = computed(() => auth.user?.displayName || auth.user?.username || '')

function fmtSize(n) {
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0, v = Number(n || 0)
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return (i === 0 ? v : v.toFixed(1)) + u[i]
}

const cacheSize = ref(0)

async function refresh() {
  cacheN.value = await cachedCount().catch(() => 0)
  cacheSize.value = await cachedSize().catch(() => 0)
  pendingN.value = await pendingCount().catch(() => 0)
}

async function cachedSize() {
  const { listCachedDocs } = await import('../../utils/offlineStore')
  const all = await listCachedDocs()
  return all.reduce((s, d) => s + (d.html ? d.html.length : 0), 0)
}

function toggleTheme() {
  const next = !dark.value
  applyTheme(next)
  dark.value = next
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
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      type: 'warning', confirmButtonText: '退出', cancelButtonText: '取消'
    })
  } catch { return }
  auth.logout()
  router.replace('/login')
}

async function deleteAccount() {
  try {
    await ElMessageBox.confirm(
      '注销后你的账号与关联的个人数据将被永久删除，且无法恢复。确定继续吗？',
      '注销账号',
      { type: 'warning', confirmButtonText: '下一步', cancelButtonText: '取消' }
    )
  } catch { return }
  let password = ''
  try {
    // 后端要求密码二次确认：不可逆删号仅凭 24h 令牌不够（外部复审 2026-10-08）
    const r = await ElMessageBox.prompt('请输入登录密码，确认后账号将立即注销且无法恢复', '身份确认', {
      type: 'warning', inputType: 'password', inputPlaceholder: '登录密码',
      confirmButtonText: '确认注销', cancelButtonText: '取消'
    })
    password = String(r.value || '')
    if (!password) { window.__kbMsg?.warning('需要密码确认'); return }
  } catch { return }
  try {
    await request({ url: '/api/auth/me', method: 'delete', data: { password } })
    try { localStorage.removeItem('kb_token') } catch {}
    await clearCache().catch(() => {})
    // 注销 = 账号不复存在：待同步草稿一并清除（否则残留在设备上且永远无法同步）
    import('../../utils/offlineStore').then((m) => m.purgeAllOffline()).catch(() => {})
    window.__kbMsg?.success('账号已注销')
    router.replace('/login')
  } catch (e) {
    const msg = (e && e.response && e.response.data && e.response.data.error) || (e && e.message) || '注销失败，请稍后再试'
    window.__kbMsg?.error(msg)
  }
}

function onBackOnline() { online.value = true; refresh() }
onMounted(() => {
  refresh()
  window.addEventListener('online', onBackOnline)
})
onUnmounted(() => window.removeEventListener('online', onBackOnline))
</script>

<template>
  <div class="st">
    <header class="st-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="st-title">设置</div>
      <button class="m-header-act" style="visibility:hidden"><el-icon><Setting /></el-icon></button>
    </header>

    <div class="body">
      <div class="m-sec-title">账号</div>
      <div class="m-card">
        <div class="m-row" style="cursor:default">
          <span class="m-ico"><el-icon><User /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">{{ name }}</span>
            <span class="m-row-desc">@{{ auth.user?.username }} · {{ auth.isAdmin ? '管理员' : '普通用户' }}</span>
          </span>
        </div>
        <div class="m-row" style="cursor:default">
          <span class="m-ico orange"><el-icon><Trophy /></el-icon></span>
          <span class="m-row-body"><span class="m-row-title">积分</span></span>
          <span class="m-row-extra">{{ auth.user?.points || 0 }}</span>
        </div>
      </div>
    </div>

    <div class="body">
      <div class="m-sec-title">外观</div>
      <div class="m-card">
        <button class="m-row" type="button" @click="toggleTheme">
          <span class="m-ico gray"><el-icon><component :is="dark ? 'Moon' : 'Sunny'" /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">深色模式</span>
            <span class="m-row-desc">当前：{{ dark ? '夜间' : '日间' }}</span>
          </span>
          <span class="switch" :class="{ on: dark }"><i></i></span>
        </button>
      </div>
    </div>

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
        <div class="m-row" style="cursor:default">
          <span class="m-ico gray"><el-icon><Files /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">本地缓存</span>
            <span class="m-row-desc">{{ cacheN }} 篇 · 约 {{ fmtSize(cacheSize) }}（离线可读）</span>
          </span>
          <button v-if="cacheN" class="mini-btn ghost" @click="doClearCache">清除</button>
        </div>
      </div>
      <div class="hint">
        <el-icon><InfoFilled /></el-icon>
        <span>离线时新建的文档会先存在本机，恢复网络后自动上传到「我的文档」。</span>
      </div>
    </div>

    <div class="body">
      <!-- 演示账号是公共账号，注销会把它删掉、其他人就用不了了；后端也会 403，这里直接禁用免得白点 -->
      <button class="logout del" :class="{ off: auth.isDemo }" :disabled="auth.isDemo" @click="deleteAccount">
        <el-icon><Delete /></el-icon> 注销账号
      </button>
      <p v-if="auth.isDemo" class="del-tip">演示账号为公共账号，不支持注销</p>
      <p v-else class="del-tip">注销后账号与个人数据将永久删除，无法恢复；唯一的管理员账号不允许注销</p>
    </div>

    <div class="body">
      <button class="logout" @click="logout">
        <el-icon><SwitchButton /></el-icon> 退出登录
      </button>
    </div>

    <div class="ver">知识库 · 移动端 v1.0</div>
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

.switch {
  flex: none; width: 44px; height: 26px; border-radius: 13px;
  background: var(--m-border); position: relative; transition: background-color .2s;
}
.switch i {
  position: absolute; top: 3px; left: 3px; width: 20px; height: 20px;
  border-radius: 50%; background: #fff; transition: transform .2s;
  box-shadow: 0 1px 3px rgba(0, 0, 0, .2);
}
.switch.on { background: var(--m-primary); }
.switch.on i { transform: translateX(18px); }

.mini-btn {
  flex: none; border: none; border-radius: 9px;
  background: var(--m-primary); color: #fff;
  font-size: 12.5px; font-weight: 600; padding: 7px 12px; cursor: pointer;
}
.mini-btn.ghost { background: transparent; color: var(--m-text-sub); border: 1px solid var(--m-border); }

.hint {
  display: flex; gap: 6px; align-items: flex-start;
  font-size: 12px; color: var(--m-text-dim); margin-top: 10px; padding: 0 4px; line-height: 1.5;
}

.logout {
  width: 100%; height: 50px; margin-top: 22px;
  border: none; border-radius: var(--m-radius);
  background: var(--m-card); color: var(--m-danger);
  box-shadow: var(--m-shadow); font-size: 15.5px; font-weight: 600; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; gap: 8px;
}
.logout.del { background: var(--m-danger); color: #fff; }
.logout.del.off { background: var(--m-card-2); color: var(--m-text-dim); cursor: not-allowed; box-shadow: none; }
.del-tip { margin: 8px 2px 0; font-size: 12px; line-height: 1.6; color: var(--m-text-dim); }
.ver { text-align: center; font-size: 12px; color: var(--m-text-dim); margin-top: 16px; }
</style>
