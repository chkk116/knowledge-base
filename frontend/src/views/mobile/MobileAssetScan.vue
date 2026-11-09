<script setup>
/**
 * 移动端「资产扫码」结果页。
 *
 * 后端 /api/assets/scan?code= 与前端 api/asset.js 的 scanAsset() 早就有了，
 * 但一直没有任何手机端页面消费：Web 后台生成的二维码内容是纯编号，
 * 手机相机扫出来只是一串文字，点不开。现在把二维码内容改成指向本页的地址，
 * 系统相机 / 微信一扫即可直达；本页再按编号拉取脱敏后的资产信息。
 *
 * 兼容：已打印的旧标签仍可在页内手动输入编号查询。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { scanAsset } from '../../api/asset'
import { useAuthStore } from '../../store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const code = ref('')
const input = ref('')
const loading = ref(false)
const err = ref('')
const asset = ref(null)

const STATUS = { IN_USE: '在用', IDLE: '闲置', REPAIR: '维修', SCRAPPED: '报废' }
const statusText = (s) => STATUS[s] || s || '-'

/**
 * 编号放在 hash 里：hash 不会发给服务端，避开 AuthFilter「原始 URI 含 %XX 一律 400」
 * 对中文/特殊字符编号的误伤；同时兼容 ?code= 与 "#编号" 两种老写法。
 */
function codeFromRoute() {
  const h = String(route.hash || '').replace(/^#/, '')
  if (h) {
    if (h.includes('=')) {
      const v = new URLSearchParams(h).get('code')
      if (v) return v
    } else {
      try { return decodeURIComponent(h) } catch { return h }
    }
  }
  return String(route.query.code || '')
}

const needLogin = computed(() => !auth.loggedIn)

const rows = computed(() => {
  const a = asset.value || {}
  const defs = [
    ['责任人', a.ownerName], ['分类', a.categoryName], ['品牌', a.brand], ['型号', a.model],
    ['序列号', a.sn], ['部门', a.department], ['位置', a.location],
    ['购置日期', a.purchaseDate], ['价格', a.price != null ? '¥' + a.price : ''],
    ['保修到期', a.warrantyExpire], ['供应商', a.supplier], ['备注', a.remark]
  ]
  return defs.filter(([, v]) => v !== null && v !== undefined && String(v).trim() !== '')
})

async function load(c) {
  const v = String(c || '').trim()
  if (!v) { asset.value = null; err.value = ''; return }
  code.value = v
  input.value = v
  loading.value = true
  err.value = ''
  asset.value = null
  try {
    const { data } = await scanAsset(v)
    asset.value = data || null
    if (!asset.value) err.value = '没有查到该资产，请核对编号'
  } catch (e) {
    err.value = (e && (e.response?.data?.error || e.message)) || '查询失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function queryInput() {
  if (!input.value.trim()) return
  // 同步到地址栏，刷新/分享后还能回到同一条资产
  router.replace({ path: '/asset', hash: '#code=' + encodeURIComponent(input.value.trim()) })
  load(input.value.trim())
}

function goLogin() {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

onMounted(() => load(codeFromRoute()))
</script>

<template>
  <div class="as">
    <header class="as-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="as-title">资产信息</div>
      <span class="h-ph"></span>
    </header>

    <div class="as-body">
      <div class="as-tip">扫描资产标签上的二维码即可查看信息；旧标签可在下方手动输入编号查询。</div>

      <div v-if="needLogin" class="as-card as-center">
        <div class="as-msg-title">登录后查看资产信息</div>
        <p class="as-msg-text">资产信息需登录后才可读取，普通用户看到的是脱敏字段。</p>
        <button class="as-btn primary" @click="goLogin">去登录</button>
      </div>

      <template v-else>
        <div v-if="loading" class="as-card as-center">
          <div class="as-msg-title">正在查询 {{ code }}…</div>
        </div>

        <div v-else-if="err" class="as-card as-center">
          <div class="as-msg-title">查询失败</div>
          <p class="as-msg-text">{{ err }}</p>
        </div>

        <div v-else-if="asset" class="as-card">
          <div class="as-name">{{ asset.name }}</div>
          <div class="as-code">{{ asset.code }}</div>
          <div class="as-tags">
            <span class="as-tag">{{ statusText(asset.status) }}</span>
            <span class="as-tag" :class="asset.borrowStatus === 'BORROWED' ? 'warn' : 'ok'">
              {{ asset.borrowStatus === 'BORROWED' ? '借出中' : '可借' }}
            </span>
          </div>
          <div v-if="rows.length" class="as-rows">
            <div v-for="r in rows" :key="r[0]" class="as-row">
              <span class="as-label">{{ r[0] }}</span>
              <span class="as-value">{{ r[1] }}</span>
            </div>
          </div>
        </div>

        <div v-else class="as-card as-center">
          <div class="as-msg-title">输入资产编号查询</div>
        </div>
      </template>

      <div class="as-card as-search">
        <div class="as-search-title">按编号查询</div>
        <div class="as-input-row">
          <input v-model="input" class="as-input" placeholder="如 ZC-2026-0001"
                 @keydown.enter="queryInput" />
          <button class="as-btn" :disabled="loading || !input.trim()" @click="queryInput">查询</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.as { min-height: 100vh; background: var(--m-bg); }
.as-head {
  display: flex; align-items: center; gap: 8px;
  height: 52px; padding: 0 12px;
  background: var(--m-card); border-bottom: 1px solid var(--m-border);
}
.as-title { flex: 1; text-align: center; font-size: 16px; font-weight: 600; color: var(--m-text); }
.h-ph { width: 32px; }
.as-body { padding: 14px 12px 28px; }
.as-tip { font-size: 12px; color: var(--m-text-dim); line-height: 1.6; margin: 0 2px 12px; }
.as-card {
  background: var(--m-card); border: 1px solid var(--m-border);
  border-radius: var(--m-radius-lg); padding: 16px; margin-bottom: 12px;
}
.as-center { text-align: center; }
.as-msg-title { font-size: 15px; font-weight: 600; color: var(--m-text); }
.as-msg-text { font-size: 13px; color: var(--m-text-dim); line-height: 1.6; margin: 8px 0 14px; }
.as-name { font-size: 19px; font-weight: 600; color: var(--m-text); line-height: 1.4; }
.as-code { font-size: 12px; color: var(--m-text-dim); margin-top: 4px; font-family: ui-monospace, Menlo, Consolas, monospace; }
.as-tags { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; }
.as-tag {
  font-size: 12px; padding: 3px 10px; border-radius: 999px;
  background: var(--m-primary-soft); color: var(--m-primary-deep);
}
.as-tag.ok { background: #e8ffea; color: #00a620; }
.as-tag.warn { background: #fff3e8; color: #d25f00; }
html.dark .as-tag.ok { background: #16301c; color: #6bd07f; }
html.dark .as-tag.warn { background: #3a2a17; color: #ffb26b; }
.as-rows { margin-top: 14px; border-top: 1px solid var(--m-border); }
.as-row {
  display: flex; align-items: flex-start; gap: 12px;
  padding: 9px 0; border-bottom: 1px solid var(--m-border); font-size: 13px;
}
.as-row:last-child { border-bottom: 0; }
.as-label { flex: none; width: 72px; color: var(--m-text-dim); }
.as-value { flex: 1; color: var(--m-text); word-break: break-word; white-space: pre-wrap; }
.as-search-title { font-size: 13px; color: var(--m-text-sub); margin-bottom: 10px; }
.as-input-row { display: flex; gap: 8px; }
.as-input {
  flex: 1; min-width: 0; height: 38px; padding: 0 11px;
  border: 1px solid var(--m-border); border-radius: 9px;
  background: var(--m-card-2); color: var(--m-text); font-size: 14px; outline: none;
}
.as-input:focus { border-color: var(--m-primary); }
.as-btn {
  flex: none; height: 38px; padding: 0 16px; border: 1px solid var(--m-border);
  border-radius: 9px; background: var(--m-card-2); color: var(--m-text); font-size: 14px;
}
.as-btn.primary { background: var(--m-primary); border-color: var(--m-primary); color: #fff; padding: 0 22px; }
.as-btn:disabled { opacity: .5; }
</style>
