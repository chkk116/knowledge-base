<script setup>
/**
 * 移动端「离线内容」
 *
 * 管理两类本地数据（IndexedDB / utils/offlineStore.js）：
 *   1. 待同步草稿（queue）：离线时建的文档，联网自动上传
 *   2. 已缓存文档（cache）：读过的文档正文，无网也能打开
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  listQueuedDocs, listCachedDocs, removeQueuedDoc, removeCachedDoc,
  clearCache, syncOnReconnect,
} from '../../utils/offlineStore'

const router = useRouter()
const queued = ref([])
const cached = ref([])
const syncing = ref(false)

function fmtDay(t) {
  if (!t) return ''
  const d = new Date(t)
  const diff = Date.now() - d.getTime()
  const dd = 86400000
  if (diff < 3600000) return Math.max(1, Math.floor(diff / 60000)) + ' 分钟前'
  if (diff < dd) return Math.floor(diff / 3600000) + ' 小时前'
  if (diff < dd * 30) return Math.floor(diff / dd) + ' 天前'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

/** 正文体积估算（离线占用体感） */
function sizeOf(html) {
  const n = (html || '').length
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

async function load() {
  queued.value = await listQueuedDocs().catch(() => [])
  cached.value = await listCachedDocs().catch(() => [])
}

async function doSync() {
  if (syncing.value) return
  if (!navigator.onLine) { window.__kbMsg?.warning('当前无网络'); return }
  syncing.value = true
  try {
    const n = await syncOnReconnect()
    window.__kbMsg?.success(n > 0 ? `已同步 ${n} 篇` : '没有需要同步的内容')
    await load()
  } catch (e) {
    window.__kbMsg?.error('同步失败：' + ((e && e.message) || e))
  } finally { syncing.value = false }
}

async function delQueued(it) {
  try {
    await ElMessageBox.confirm(`删除本地草稿「${it.title}」？删除后无法恢复`, '删除草稿', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  try {
    await removeQueuedDoc(it.localId)
    await load()
  } catch (e) {
    window.__kbMsg?.error('删除失败：' + ((e && e.message) || e))
  }
}

async function delCached(it) {
  try {
    await removeCachedDoc(it.spaceId, it.docId)
    await load()
  } catch (e) {
    window.__kbMsg?.error('删除失败：' + ((e && e.message) || e))
  }
}

async function clearAllCache() {
  try {
    await ElMessageBox.confirm(`清空 ${cached.value.length} 篇离线缓存？（不影响服务器上的文档）`, '清空离线缓存', {
      confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  await clearCache()
  await load()
  window.__kbMsg?.success('已清空离线缓存')
}

const totalSize = computed(() => sizeOf(cached.value.map((c) => c.html).join('')))

onMounted(load)
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">离线内容</div>
      <span class="h-ph"></span>
    </header>

    <div class="body">
      <!-- 概览 -->
      <div class="ov">
        <div class="ov-item"><div class="ov-num">{{ queued.length }}</div><div class="ov-lbl">待同步</div></div>
        <i></i>
        <div class="ov-item"><div class="ov-num">{{ cached.length }}</div><div class="ov-lbl">已离线</div></div>
        <i></i>
        <div class="ov-item"><div class="ov-num">{{ totalSize }}</div><div class="ov-lbl">占用</div></div>
      </div>

      <!-- 待同步草稿 -->
      <div class="m-card sec" v-if="queued.length">
        <div class="m-sec-title">
          待同步草稿
          <button class="more" :disabled="syncing" @click="doSync">{{ syncing ? '同步中…' : '立即同步' }}</button>
        </div>
        <div v-for="it in queued" :key="it.localId" class="m-row">
          <button class="row-main" type="button" @click="router.push(`/draft/${it.localId}`)">
            <span class="m-ico orange"><el-icon><Upload /></el-icon></span>
            <span class="m-row-body">
              <span class="m-row-title">{{ it.title }}</span>
              <span class="m-row-desc">{{ fmtDay(it.createdAt) }}{{ it.tries ? ' · 已重试 ' + it.tries + ' 次' : '' }}</span>
            </span>
          </button>
          <button class="x" type="button" @click="delQueued(it)"><el-icon :size="16"><Close /></el-icon></button>
        </div>
      </div>

      <!-- 已缓存文档 -->
      <div class="m-card sec" v-if="cached.length">
        <div class="m-sec-title">
          已离线文档
          <button class="more" @click="clearAllCache">清空</button>
        </div>
        <div v-for="it in cached" :key="it.key" class="m-row">
          <button class="row-main" type="button" @click="router.push(`/doc/${it.spaceId}/${it.docId}`)">
            <span class="m-ico blue"><el-icon><Cloudy /></el-icon></span>
            <span class="m-row-body">
              <span class="m-row-title">{{ it.title }}</span>
              <span class="m-row-desc">{{ fmtDay(it.cachedAt) }} · {{ sizeOf(it.html) }}</span>
            </span>
          </button>
          <button class="x" type="button" @click="delCached(it)"><el-icon :size="16"><Close /></el-icon></button>
        </div>
      </div>

      <div v-if="!queued.length && !cached.length" class="m-card sec">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Cloudy /></el-icon></span>
          <div>还没有离线内容</div>
          <div style="font-size:12.5px;color:var(--m-text-dim)">读过的文档会自动缓存，无网也能打开</div>
        </div>
      </div>

      <div class="tip">离线内容保存在本机，换号登录后不会串号</div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100%; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.body { padding: 4px 16px 28px; }

.ov {
  display: flex; align-items: center; justify-content: space-around;
  background: var(--m-card); border-radius: var(--m-radius); padding: 14px 6px;
  box-shadow: var(--m-shadow); margin-bottom: 12px;
}
.ov-item { text-align: center; flex: 1 1 0; }
.ov-num { font-size: 18px; font-weight: 700; color: var(--m-text); font-variant-numeric: tabular-nums; }
.ov-lbl { margin-top: 3px; font-size: 11px; color: var(--m-text-dim); }
.ov i { width: 1px; height: 22px; background: var(--m-border); }

.sec { padding: 4px 14px 8px; }
.sec .m-sec-title { padding-left: 0; }
.row-main {
  flex: 1 1 auto; min-width: 0; display: flex; align-items: center; gap: 12px;
  padding: 13px 0; border: none; background: transparent; text-align: left;
  color: inherit; cursor: pointer;
}
.x {
  flex: none; width: 34px; height: 34px; border-radius: 50%; border: none;
  background: var(--m-card-2); color: var(--m-text-dim); display: grid; place-items: center; cursor: pointer;
}
.tip { margin-top: 14px; text-align: center; font-size: 12px; color: var(--m-text-dim); }
</style>
