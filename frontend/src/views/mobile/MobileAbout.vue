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
import { CHANGELOG, CURRENT_VERSION } from '../../changelog'

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

// 本版本更新说明（简洁列表；历史版本可展开）
const current = CURRENT_VERSION
const showLog = ref(false)
const history = CHANGELOG.slice(1, 6)

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

      <!-- 本版本更新说明 -->
      <div class="m-card sec">
        <div class="log-head">
          <span class="log-v">v{{ current.version }}</span>
          <span class="log-t">{{ current.title }}</span>
          <span class="log-d">{{ current.date }}</span>
        </div>
        <ul class="log-list">
          <li v-for="(it, i) in current.items" :key="i">{{ it }}</li>
        </ul>
        <button v-if="history.length" class="log-more" type="button" @click="showLog = !showLog">
          {{ showLog ? '收起历史版本' : '查看历史版本（' + history.length + '）' }}
          <el-icon :size="13" :class="{ open: showLog }"><Right /></el-icon>
        </button>
        <div v-show="showLog" class="log-old">
          <div v-for="h in history" :key="h.version" class="log-old-item">
            <div class="log-old-head"><b>v{{ h.version }}</b><span>{{ h.date }}</span></div>
            <ul><li v-for="(it, i) in h.items" :key="i">{{ it }}</li></ul>
          </div>
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

/* 本版本更新说明 */
.log-head { display: flex; align-items: baseline; gap: 8px; padding: 14px 0 8px; flex-wrap: wrap; }
.log-v {
  font-size: 12px; font-weight: 700; color: var(--m-primary);
  background: var(--m-primary-soft); border-radius: 6px; padding: 2px 7px;
}
.log-t { font-size: 14.5px; font-weight: 600; color: var(--m-text); }
.log-d { font-size: 12px; color: var(--m-text-dim); margin-left: auto; }
.log-list { margin: 0; padding-left: 18px; }
.log-list li { font-size: 13.5px; line-height: 1.85; color: var(--m-text-sub); }
.log-more {
  margin-top: 10px; width: 100%; height: 36px; border-radius: 10px; cursor: pointer;
  border: 1px solid var(--m-border); background: var(--m-card-2); color: var(--m-text-sub);
  font-size: 13px; display: inline-flex; align-items: center; justify-content: center; gap: 4px;
}
.log-more .el-icon { transition: transform .2s; }
.log-more .el-icon.open { transform: rotate(90deg); }
.log-old { margin-top: 12px; border-top: 1px solid var(--m-border); }
.log-old-item { padding: 10px 0; }
.log-old-head { display: flex; align-items: baseline; gap: 8px; margin-bottom: 4px; }
.log-old-head b { font-size: 13px; color: var(--m-text); }
.log-old-head span { font-size: 11.5px; color: var(--m-text-dim); }
.log-old ul { margin: 0; padding-left: 18px; }
.log-old li { font-size: 12.5px; line-height: 1.8; color: var(--m-text-dim); }

/* 版本自检 */
.vdiag {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: center;
  margin-top: 10px; font-size: 12px; color: var(--m-text-dim);
}
.vdiag b { color: var(--m-text-sub); font-weight: 600; }
.vdiag b.warn { color: var(--m-warn); }
.vdiag i { width: 1px; height: 11px; background: var(--m-border); }
</style>
