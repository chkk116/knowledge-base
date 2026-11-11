<script setup>
/**
 * 客户端下载中心（桌面网页版）
 *
 * 业内常见构成：平台卡片（Windows / Android）+ 版本号 + 更新说明 + 校验值 +
 * 自动识别当前平台高亮主按钮 + 手机端二维码 + 页脚入口。
 * 数据来源：
 *   - 开关/标题/说明：store/download（/api/public/download-config）
 *   - 各平台版本：/api/public/app-version?channel=desktop|native（免登录）
 * 后台「客户端下载」可关总开关或单独隐藏某个平台，关闭后页脚入口与页面同步隐藏。
 */
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Monitor, Cellphone, Download, Refresh, CopyDocument } from '@element-plus/icons-vue'
import api from '../api/request'
import QRCode from 'qrcode'
import { useDownloadStore } from '../store/download'

const dl = useDownloadStore()
const loading = ref(true)
const err = ref('')
const desktop = ref(null)
const android = ref(null)
const qr = ref('')

const isMobile = /android|iphone|ipad|ipod|mobile/i.test(navigator.userAgent)
const hasDesktop = computed(() => !!(desktop.value && desktop.value.url))
const hasAndroid = computed(() => !!(android.value && android.value.url))

async function fetchVersion(channel) {
  const url = channel ? `/public/app-version?channel=${channel}` : '/public/app-version'
  const { data } = await api.get(url)
  return data
}

async function load() {
  loading.value = true
  err.value = ''
  try {
    if (!dl.loaded) await dl.load()
    if (!dl.enabled) return
    const [d, a] = await Promise.all([
      dl.desktop ? fetchVersion('desktop') : Promise.resolve(null),
      dl.app ? loadAndroid() : Promise.resolve(null)
    ])
    desktop.value = d
    android.value = a
    if (a && a.url) {
      qr.value = await QRCode.toDataURL(a.url, { width: 168, margin: 1, color: { dark: '#1f2329', light: '#ffffff' } })
    }
  } catch (e) {
    err.value = e?.response?.data?.error || e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

/** Android 优先原生壳通道，未发布则回退 Capacitor 通道 */
async function loadAndroid() {
  let v = await fetchVersion('native')
  if (!v || !v.url) v = await fetchVersion(null)
  return v
}

function openUrl(u) {
  if (!u) return
  window.open(u, '_blank', 'noopener')
}

async function copyChecksum(v) {
  const s = v?.checksum
  if (!s) return
  try {
    await navigator.clipboard.writeText(s)
    ElMessage.success('校验值已复制')
  } catch (e) {
    ElMessage.info(s)
  }
}

onMounted(load)
</script>

<template>
  <div class="dl-page">
    <div class="dl-head">
      <h1>{{ dl.title }}</h1>
      <p>{{ dl.desc }}</p>
    </div>

    <el-skeleton v-if="loading" :rows="4" animated />

    <el-alert v-else-if="err" :title="err" type="error" :closable="false" show-icon />

    <div v-else-if="!dl.enabled" class="dl-empty">下载中心暂未开放</div>

    <div v-else class="dl-grid">
      <!-- Windows 桌面端 -->
      <section v-if="dl.desktop" class="dl-card" :class="{ primary: !isMobile }">
        <div class="dc-top">
          <div class="dc-icon win"><el-icon :size="26"><Monitor /></el-icon></div>
          <div class="dc-title">
            <h3>Windows 桌面端</h3>
            <span class="dc-sub">Windows 10 / 11 · 64 位</span>
          </div>
          <el-tag v-if="desktop && desktop.versionName" size="small" effect="plain" round>v{{ desktop.versionName }}</el-tag>
        </div>
        <p class="dc-note">{{ (desktop && desktop.notes) || '原生桌面客户端：登录、文章阅读、我的文件与更新检查。' }}</p>
        <div v-if="desktop && desktop.checksum" class="dc-hash">
          <span class="hash-label">SHA-256</span>
          <code>{{ desktop.checksum.slice(0, 16) }}…</code>
          <el-button link :icon="CopyDocument" @click="copyChecksum(desktop)">复制</el-button>
        </div>
        <div class="dc-actions">
          <el-button v-if="hasDesktop" type="primary" size="large" :icon="Download" @click="openUrl(desktop.url)">
            下载桌面端
          </el-button>
          <el-button v-else disabled size="large">安装包暂未上传</el-button>
        </div>
      </section>

      <!-- Android App -->
      <section v-if="dl.app" class="dl-card" :class="{ primary: isMobile }">
        <div class="dc-top">
          <div class="dc-icon android"><el-icon :size="26"><Cellphone /></el-icon></div>
          <div class="dc-title">
            <h3>Android App</h3>
            <span class="dc-sub">Android 8.0 及以上</span>
          </div>
          <el-tag v-if="android && android.versionName" size="small" effect="plain" round>v{{ android.versionName }}</el-tag>
        </div>
        <p class="dc-note">{{ (android && android.notes) || '移动端 App：随时查阅文档、AI 问答与消息提醒。' }}</p>
        <div class="dc-body">
          <div class="dc-info">
            <div v-if="android && android.checksum" class="dc-hash">
              <span class="hash-label">SHA-256</span>
              <code>{{ android.checksum.slice(0, 16) }}…</code>
              <el-button link :icon="CopyDocument" @click="copyChecksum(android)">复制</el-button>
            </div>
            <div class="dc-actions">
              <el-button v-if="hasAndroid" type="primary" size="large" :icon="Download" @click="openUrl(android.url)">
                下载 App
              </el-button>
              <el-button v-else disabled size="large">安装包暂未上传</el-button>
            </div>
            <p class="dc-tip">安装前请在系统设置中允许「安装未知来源应用」。</p>
          </div>
          <div v-if="qr" class="dc-qr">
            <img :src="qr" alt="扫码下载 Android App" />
            <span>手机扫码下载</span>
          </div>
        </div>
      </section>
    </div>

    <div class="dl-foot">
      <el-button link :icon="Refresh" @click="load">刷新版本信息</el-button>
      <span>安装包由本站服务器直出，下载后可核对 SHA-256 校验值。</span>
    </div>
  </div>
</template>

<style scoped>
.dl-page { max-width: 980px; margin: 0 auto; padding: 28px 20px 60px; }
.dl-head { text-align: center; margin-bottom: 26px; }
.dl-head h1 { margin: 0 0 8px; font-size: 26px; font-weight: 800; color: var(--el-text-color-primary); letter-spacing: .5px; }
.dl-head p { margin: 0; font-size: 13.5px; color: var(--el-text-color-secondary); }
.dl-empty { padding: 60px 0; text-align: center; color: var(--el-text-color-placeholder); font-size: 14px; }

.dl-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(340px, 1fr)); gap: 18px; }
.dl-card {
  background: var(--el-bg-color); border: 1px solid var(--el-border-color-lighter);
  border-radius: 16px; padding: 22px; transition: box-shadow .18s, border-color .18s;
}
.dl-card.primary { border-color: var(--el-color-primary-light-5); box-shadow: 0 10px 30px -18px var(--el-color-primary); }
.dc-top { display: flex; align-items: center; gap: 12px; }
.dc-icon { width: 48px; height: 48px; border-radius: 13px; display: flex; align-items: center; justify-content: center; color: #fff; flex: none; }
.dc-icon.win { background: linear-gradient(135deg, #3b82f6, #6366f1); }
.dc-icon.android { background: linear-gradient(135deg, #10b981, #059669); }
.dc-title { flex: 1; min-width: 0; }
.dc-title h3 { margin: 0; font-size: 16px; font-weight: 700; color: var(--el-text-color-primary); }
.dc-sub { font-size: 12px; color: var(--el-text-color-secondary); }
.dc-note { margin: 14px 0; font-size: 13px; line-height: 1.7; color: var(--el-text-color-regular); }
.dc-body { display: flex; gap: 16px; align-items: flex-end; justify-content: space-between; flex-wrap: wrap; }
.dc-info { flex: 1; min-width: 200px; }
.dc-hash { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--el-text-color-secondary); margin-bottom: 12px; flex-wrap: wrap; }
.dc-hash code { font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 11.5px; background: var(--el-fill-color-light); padding: 2px 6px; border-radius: 5px; }
.hash-label { font-weight: 600; }
.dc-actions { display: flex; gap: 8px; }
.dc-tip { margin: 10px 0 0; font-size: 11.5px; color: var(--el-text-color-placeholder); }
.dc-qr { text-align: center; flex: none; }
.dc-qr img { width: 132px; height: 132px; border-radius: 10px; border: 1px solid var(--el-border-color-lighter); display: block; }
.dc-qr span { display: block; margin-top: 6px; font-size: 11.5px; color: var(--el-text-color-secondary); }
.dl-foot { margin-top: 26px; display: flex; align-items: center; justify-content: center; gap: 14px; flex-wrap: wrap; font-size: 12px; color: var(--el-text-color-placeholder); }
</style>
