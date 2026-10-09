<script setup>
/**
 * 移动端「关于」页
 *
 * 内容：应用标识与版本、检查更新、相关链接（隐私政策 / 开源仓库 / 官网）、备案与版权。
 * 版本号取自原生 App 插件（@capacitor/app 的 getInfo），Web 端退化显示前端版本。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getAppVersion } from '../../api/app'
import { useSiteInfoStore, ICP_URL, policeUrl } from '../../store/siteInfo'

const router = useRouter()
const siteInfo = useSiteInfoStore()

const appName = '知识库'
const version = ref('')          // 1.0
const build = ref('')            // versionCode
const checking = ref(false)

const isNative = !!window.Capacitor?.isNativePlatform?.()

// 前端版本兜底（Web 端 / 拿不到原生信息时）
const webVersion = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : ''

const versionText = computed(() => {
  if (version.value) return `v${version.value}${build.value ? '（Build ' + build.value + '）' : ''}`
  return webVersion ? `v${webVersion}` : '—'
})

/** 官方/仓库等外链：来自后台页脚配置的自定义项 */
const links = computed(() => (siteInfo.items || []).filter((it) => it && it.url))

async function loadInfo() {
  try {
    const info = await window.Capacitor?.Plugins?.App?.getInfo?.()
    if (info) {
      version.value = info.version || ''
      build.value = String(info.build || '')
    }
  } catch { /* Web 端没有该插件，走前端版本兜底 */ }
}

/** 检查更新：拉后台版本信息与本机 versionCode 比对 */
async function checkUpdate() {
  if (checking.value) return
  checking.value = true
  try {
    const { data } = await getAppVersion()
    if (!data || !data.versionCode) {
      window.__kbMsg?.info('当前已是最新版本')
      return
    }
    const cur = Number(build.value || 0)
    if (cur > 0 && data.versionCode > cur) {
      try {
        await ElMessageBox.confirm(
          `发现新版本 ${data.versionName || ''}\n${data.notes || ''}`,
          '发现新版本',
          { confirmButtonText: '前往下载', cancelButtonText: '稍后', type: 'info' }
        )
      } catch { return }   // 用户选择稍后
      if (data.url) window.open(data.url, '_blank', 'noopener,noreferrer')
    } else {
      window.__kbMsg?.success('当前已是最新版本')
    }
  } catch {
    window.__kbMsg?.error('检查更新失败，请稍后再试')
  } finally {
    checking.value = false
  }
}

function openUrl(u) { if (u) window.open(u, '_blank', 'noopener,noreferrer') }

onMounted(() => {
  loadInfo()
  siteInfo.load()
})
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">关于</div>
      <span class="h-ph"></span>
    </header>

    <div class="body">
      <!-- 应用标识 -->
      <div class="hero">
        <div class="logo"><el-icon :size="30"><Reading /></el-icon></div>
        <div class="app-name">{{ appName }}</div>
        <div class="app-ver">{{ versionText }}</div>
        <button class="chk" :disabled="checking" @click="checkUpdate">
          <el-icon v-if="checking" class="rot"><Loading /></el-icon>
          <el-icon v-else><Refresh /></el-icon>
          {{ checking ? '检查中…' : '检查更新' }}
        </button>
      </div>

      <!-- 链接 -->
      <div class="m-card sec">
        <button class="row" @click="router.push('/privacy')">
          <span class="row-t">隐私政策</span>
          <el-icon class="row-a"><Right /></el-icon>
        </button>
        <button v-for="l in links" :key="l.name" class="row" @click="openUrl(l.url)">
          <span class="row-t">{{ l.name }}</span>
          <el-icon class="row-a"><Right /></el-icon>
        </button>
      </div>

      <!-- 备案与版权 -->
      <div class="legal">
        <a v-if="siteInfo.icpNo" :href="ICP_URL" target="_blank" rel="noopener noreferrer" class="lk">{{ siteInfo.icpNo }}</a>
        <a v-if="siteInfo.policeNo" :href="policeUrl(siteInfo.policeNo)" target="_blank" rel="noopener noreferrer" class="lk">{{ siteInfo.policeNo }}</a>
        <div class="cp">© {{ new Date().getFullYear() }} 浅光雅致</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100%; display: flex; flex-direction: column; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.body { flex: 1 1 auto; padding: 8px 16px 24px; }

.hero { display: flex; flex-direction: column; align-items: center; padding: 24px 0 20px; }
.logo {
  width: 68px; height: 68px; border-radius: 18px;
  display: grid; place-items: center;
  background: var(--m-primary-weak, #e8eefe); color: var(--m-primary);
}
.app-name { margin-top: 12px; font-size: 18px; font-weight: 600; color: var(--m-text); }
.app-ver { margin-top: 4px; font-size: 13px; color: var(--m-text-sub); font-variant-numeric: tabular-nums; }
.chk {
  margin-top: 14px; display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 18px; border-radius: 19px; border: 1px solid var(--m-border);
  background: var(--m-card); color: var(--m-primary); font-size: 14px; cursor: pointer;
}
.chk:disabled { opacity: .6; }
.rot { animation: kb-rot 1s linear infinite; }
@keyframes kb-rot { to { transform: rotate(360deg); } }

.sec { padding: 0 14px; }
.row {
  width: 100%; display: flex; align-items: center; justify-content: space-between;
  padding: 15px 0; border: none; border-bottom: 1px solid var(--m-border);
  background: transparent; color: var(--m-text); font-size: 15px; cursor: pointer;
}
.row:last-child { border-bottom: none; }
.row-a { color: var(--m-text-dim); }

.legal { margin-top: 26px; text-align: center; font-size: 12px; line-height: 1.9; color: var(--m-text-dim); }
.lk { color: var(--m-text-dim); text-decoration: none; display: block; }
.cp { margin-top: 6px; }
</style>
