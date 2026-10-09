<script setup>
/**
 * 移动端「关于」页
 *
 * 内容：应用标识与版本、检查更新、相关链接（隐私政策 / 开源仓库 / 官网）、版权。
 * 版本号取自原生 App 插件（@capacitor/app 的 getInfo），Web 端退化显示前端版本。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getAppVersion } from '../../api/app'
import { openExternal } from '../../utils/openExternal'
import { downloadAndInstall } from '../../utils/appUpdate'
import { useSiteInfoStore } from '../../store/siteInfo'

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
    // 先确保拿到本机版本号再比对（onMounted 里的 loadInfo 是异步的，点太快可能还没回来）
    if (!build.value) await loadInfo()
    const { data } = await getAppVersion()
    if (!data || !data.versionCode) {
      window.__kbMsg?.info('后台尚未开启版本检查')
      return
    }
    const cur = Number(build.value || 0)
    // 读不到本机 versionCode：网页版没有原生插件；老包没带 @capacitor/app。
    // 这种情况**不能**说"已是最新"（那是把"读不到"当成了"最新"），要如实告知。
    if (!cur) {
      window.__kbMsg?.warning('读不到本机版本号：网页版不支持检查更新，请在 App 内操作')
      return
    }
    if (data.versionCode > cur) {
      try {
        await ElMessageBox.confirm(
          `发现新版本 ${data.versionName || ''}\n${data.notes || ''}`,
          '发现新版本',
          { confirmButtonText: '前往下载', cancelButtonText: '稍后', type: 'info' }
        )
      } catch { return }   // 用户选择稍后
      if (data.url) {
        try {
          const r = await downloadAndInstall(data.url)
          if (r.mode === 'in-app') window.__kbMsg?.success('下载完成，请在弹出的安装界面完成安装')
        } catch (e) {
          window.__kbMsg?.error('更新失败：' + ((e && e.message) || e))
        }
      }
    } else {
      window.__kbMsg?.success(`当前已是最新版本（本机 Build ${cur}，线上 Build ${data.versionCode}）`)
    }
  } catch {
    window.__kbMsg?.error('检查更新失败，请稍后再试')
  } finally {
    checking.value = false
  }
}

function openUrl(u) { if (u) openExternal(u) }

/** 版本自检：把「本机 / 线上」两个 Build 号都亮出来，便于一眼定位问题 */
const remoteCode = ref(0)
const remoteName = ref('')
async function loadRemote() {
  try {
    const { data } = await getAppVersion()
    if (data) { remoteCode.value = Number(data.versionCode || 0); remoteName.value = data.versionName || '' }
  } catch { /* 拉不到就显示「未配置」 */ }
}

onMounted(() => {
  loadInfo()
  loadRemote()
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

        <!-- 版本自检：本机读不到的 Build 会显示「读不到」，线上没配则显示「未配置」 -->
        <div class="vdiag">
          <span>本机 Build <b :class="{ warn: !build }">{{ build || '读不到' }}</b></span>
          <i></i>
          <span>线上 Build <b :class="{ warn: !remoteCode }">{{ remoteCode || '未配置' }}</b>{{ remoteName ? '（' + remoteName + '）' : '' }}</span>
        </div>
      </div>

      <!-- 链接 -->
      <div class="m-card sec">
        <button class="row" @click="router.push('/privacy')">
          <span class="row-t">隐私政策</span>
          <el-icon class="row-a"><Right /></el-icon>
        </button>
        <button class="row" @click="router.push('/disclaimer')">
          <span class="row-t">免责声明</span>
          <el-icon class="row-a"><Right /></el-icon>
        </button>
        <button v-for="l in links" :key="l.name" class="row" @click="openUrl(l.url)">
          <span class="row-t">{{ l.name }}</span>
          <el-icon class="row-a"><Right /></el-icon>
        </button>
      </div>

      <!-- 版权（App 端不展示网站备案号：那是网站备案，App 备案是另一回事） -->
      <div class="legal">
        <div class="cp">© {{ new Date().getFullYear() }} 知识库</div>
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

/* 版本自检 */
.vdiag {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: center;
  margin-top: 10px; font-size: 12px; color: var(--m-text-dim);
}
.vdiag b { color: var(--m-text-sub); font-weight: 600; }
.vdiag b.warn { color: var(--m-warn); }
.vdiag i { width: 1px; height: 11px; background: var(--m-border); }
</style>
