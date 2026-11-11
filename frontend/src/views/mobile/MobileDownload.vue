<script setup>
/**
 * 移动端「客户端下载」页
 *
 * 与桌面下载中心共用数据（store/download + /api/public/app-version 各通道），
 * 移动端以列表卡片呈现：Windows 桌面端 / Android App，显示版本、更新说明与下载按钮。
 * 后台关闭总开关或某个平台后，这里同步隐藏。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowLeft, Monitor, Cellphone } from '@element-plus/icons-vue'
import api from '../../api/request'
import { openExternal } from '../../utils/openExternal'
import { useDownloadStore } from '../../store/download'

const router = useRouter()
const dl = useDownloadStore()
const loading = ref(true)
const err = ref('')
const desktop = ref(null)
const android = ref(null)

async function fetchVersion(channel) {
  const url = channel ? `/public/app-version?channel=${channel}` : '/public/app-version'
  const { data } = await api.get(url)
  return data
}

async function loadAndroid() {
  let v = await fetchVersion('native')
  if (!v || !v.url) v = await fetchVersion(null)
  return v
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
  } catch (e) {
    err.value = e?.response?.data?.error || e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

const platforms = computed(() => {
  const list = []
  if (dl.desktop) {
    list.push({
      key: 'desktop', name: 'Windows 桌面端', sub: 'Windows 10 / 11 · 64 位',
      icon: Monitor, bg: 'linear-gradient(135deg,#3b82f6,#6366f1)',
      v: desktop.value, fallback: '原生桌面客户端：登录、文章阅读、我的文件与更新检查。'
    })
  }
  if (dl.app) {
    list.push({
      key: 'app', name: 'Android App', sub: 'Android 8.0 及以上',
      icon: Cellphone, bg: 'linear-gradient(135deg,#10b981,#059669)',
      v: android.value, fallback: '移动端 App：随时查阅文档、AI 问答与消息提醒。'
    })
  }
  return list
})

function open(u) { if (u) openExternal(u) }

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">{{ dl.title || '客户端下载' }}</div>
      <span class="h-ph"></span>
    </header>

    <div class="body">
      <p class="desc">{{ dl.desc }}</p>

      <div v-if="loading" class="empty">加载中…</div>
      <div v-else-if="err" class="empty">{{ err }}</div>
      <div v-else-if="!dl.enabled" class="empty">下载中心暂未开放</div>

      <template v-else>
        <div v-for="p in platforms" :key="p.key" class="m-card dl-card">
          <div class="dl-top">
            <div class="dl-ico" :style="{ background: p.bg }">
              <el-icon :size="22"><component :is="p.icon" /></el-icon>
            </div>
            <div class="dl-meta">
              <div class="dl-name">{{ p.name }}</div>
              <div class="dl-sub">{{ p.sub }}</div>
            </div>
            <span v-if="p.v && p.v.versionName" class="dl-ver">v{{ p.v.versionName }}</span>
          </div>
          <p class="dl-note">{{ (p.v && p.v.notes) || p.fallback }}</p>
          <div v-if="p.v && p.v.checksum" class="dl-hash">
            <span>SHA-256</span>
            <code>{{ p.v.checksum }}</code>
          </div>
          <button class="dl-btn" :disabled="!(p.v && p.v.url)" @click="open(p.v && p.v.url)">
            {{ (p.v && p.v.url) ? '下载' : '暂未上传' }}
          </button>
        </div>

        <p class="foot">安装包由本站服务器直出，下载后可核对 SHA-256 校验值。</p>
      </template>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100%; display: flex; flex-direction: column; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.body { flex: 1 1 auto; padding: 10px 16px 28px; }
.desc { margin: 2px 2px 14px; font-size: 13px; line-height: 1.7; color: var(--m-text-sub); }
.empty { padding: 50px 0; text-align: center; font-size: 13.5px; color: var(--m-text-dim); }

.dl-card { padding: 16px; margin-bottom: 12px; }
.dl-top { display: flex; align-items: center; gap: 12px; }
.dl-ico { width: 44px; height: 44px; border-radius: 12px; display: grid; place-items: center; color: #fff; flex: none; }
.dl-meta { flex: 1; min-width: 0; }
.dl-name { font-size: 15px; font-weight: 600; color: var(--m-text); }
.dl-sub { margin-top: 2px; font-size: 12px; color: var(--m-text-dim); }
.dl-ver {
  font-size: 12px; font-weight: 600; color: var(--m-primary);
  background: var(--m-primary-soft); border-radius: 6px; padding: 2px 8px; flex: none;
}
.dl-note { margin: 12px 0 14px; font-size: 13px; line-height: 1.7; color: var(--m-text-sub); }
/* 页脚提示用户核对 SHA-256，移动端也要能看到校验值（长按可复制） */
.dl-hash { margin: -4px 0 12px; font-size: 11px; color: var(--m-text-dim); }
.dl-hash span { display: block; margin-bottom: 3px; }
.dl-hash code { display: block; word-break: break-all; line-height: 1.5; user-select: all; color: var(--m-text-sub); }
.dl-btn {
  width: 100%; height: 42px; border: none; border-radius: 11px; cursor: pointer;
  background: var(--m-primary); color: #fff; font-size: 15px; font-weight: 600;
}
.dl-btn:disabled { background: var(--m-border); color: var(--m-text-dim); cursor: default; }
.foot { margin-top: 18px; text-align: center; font-size: 11.5px; line-height: 1.7; color: var(--m-text-dim); }
</style>
