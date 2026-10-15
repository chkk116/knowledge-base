<script setup>
/**
 * 移动端「文档库」Tab
 *
 * 交互参照「文件 App」：一级列出空间（个人 / 团队），点进去看该空间的文档。
 * 离线时展示已缓存的文档 + 本地待同步草稿，并给出「离线」条。
 */
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { listMyShares, getStorage, inboxCount } from '../../api/docShare'
import { listQueuedDocs, listCachedDocs } from '../../utils/offlineStore'

const router = useRouter()
const loading = ref(true)
const spaces = ref([])
const offlineDocs = ref([])
const cachedDocs = ref([])   // 本地缓存过的文档（离线可读）
const storage = ref(null)
const online = ref(navigator.onLine)
const inbCount = ref(0)      // 待整理箱未整理数（角标，2026-10-15）

function fmtSize(n) {
  if (n == null) return ''
  const u = ['B', 'KB', 'MB', 'GB']
  let i = 0, v = Number(n)
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return (i === 0 ? v : v.toFixed(1)) + u[i]
}

const usedPct = computed(() => {
  if (!storage.value || !storage.value.quota) return 0
  return Math.min(100, Math.round((storage.value.used / storage.value.quota) * 100))
})

async function load() {
  loading.value = true
  try {
    const [s, q, c] = await Promise.all([
      listMyShares().then((r) => (Array.isArray(r.data) ? r.data : [])).catch(() => []),
      listQueuedDocs().catch(() => []),
      listCachedDocs().catch(() => [])
    ])
    spaces.value = s
    offlineDocs.value = q
    cachedDocs.value = c   // 离线时仍可打开（详情页会读同一份缓存）
    getStorage().then((r) => { storage.value = r.data }).catch(() => {})
    inboxCount().then((r) => { inbCount.value = r.data?.count || 0 }).catch(() => {})
  } finally {
    loading.value = false
  }
}

function onBackOnline() { online.value = true; load() }
function onWentOffline() { online.value = false }
onMounted(() => {
  load()
  window.addEventListener('online', onBackOnline)
  window.addEventListener('offline', onWentOffline)
})
onUnmounted(() => {
  window.removeEventListener('online', onBackOnline)
  window.removeEventListener('offline', onWentOffline)
})

function openSpace(id) { router.push(`/space/${id}`) }
function openDraft(id) { router.push(`/draft/${id}`) }
</script>

<template>
  <div class="docs">
    <div class="m-header">
      <div>
        <div class="m-title">文档库</div>
        <div class="m-sub" v-if="storage">
          已用 {{ fmtSize(storage.used) }}<template v-if="storage.quota"> / {{ fmtSize(storage.quota) }}</template>
        </div>
      </div>
      <button class="m-header-act" @click="load"><el-icon><Refresh /></el-icon></button>
    </div>

    <!-- 存储条 -->
    <div v-if="storage && storage.quota" class="body">
      <div class="store">
        <div class="store-bar"><i :style="{ width: usedPct + '%' }" :class="{ warn: usedPct > 85 }"></i></div>
        <div class="store-txt">个人存储已用 {{ usedPct }}%</div>
      </div>
    </div>

    <!-- 全部文档：跨空间检索、标签和属性筛选 -->
    <div class="body">
      <button class="inbox-entry library-entry" type="button" @click="router.push('/all-docs')">
        <span class="ie-ico"><el-icon><CollectionTag /></el-icon></span>
        <span class="ie-main">
          <span class="ie-title">全部文档</span>
          <span class="ie-sub">按标签、空间、类型和更新时间筛选</span>
        </span>
        <span class="ie-arrow">›</span>
      </button>
    </div>

    <!-- 待整理箱入口（分享/扫描「先收进来」的资料） -->
    <div class="body">
      <button class="inbox-entry" type="button" @click="router.push('/inbox')">
        <span class="ie-ico">📥</span>
        <span class="ie-main">
          <span class="ie-title">待整理箱</span>
          <span class="ie-sub">{{ inbCount > 0 ? `${inbCount} 个待整理` : '暂无待整理' }}</span>
        </span>
        <span v-if="inbCount > 0" class="ie-badge">{{ inbCount > 99 ? '99+' : inbCount }}</span>
        <span class="ie-arrow">›</span>
      </button>
    </div>

    <!-- 离线缓存：离线也能打开的文档（进入详情页读同一份 IndexedDB 缓存） -->
    <div v-if="cachedDocs.length" class="body">
      <div class="m-sec-title">
        离线缓存
        <span class="m-chip gray" style="margin-left:6px">{{ cachedDocs.length }}</span>
      </div>
      <div class="m-card">
        <button v-for="d in cachedDocs" :key="d.spaceId + ':' + d.docId" class="m-row" type="button"
                @click="router.push(`/doc/${d.spaceId}/${d.docId}`)">
          <span class="m-ico blue"><el-icon><Document /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">{{ d.title || '未命名文档' }}</span>
            <span class="m-row-desc">{{ online ? '已缓存，可离线阅读' : '离线可读（本地缓存）' }}</span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>
    </div>

    <!-- 待同步草稿 -->
    <div v-if="offlineDocs.length" class="body">
      <div class="m-sec-title">
        待同步
        <span class="m-chip orange" style="margin-left:6px">{{ offlineDocs.length }}</span>
      </div>
      <div class="m-card">
        <button v-for="d in offlineDocs" :key="d.localId" class="m-row" type="button" @click="openDraft(d.localId)">
          <span class="m-ico orange"><el-icon><Upload /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">{{ d.title || '未命名文档' }}</span>
            <span class="m-row-desc">{{ d.lastError || '等待联网同步到「我的文档」' }}</span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>
    </div>

    <!-- 空间列表 -->
    <div class="body">
      <div class="m-sec-title">
        {{ offlineDocs.length ? '我的空间' : '全部空间' }}
      </div>

      <div v-if="loading" class="m-card">
        <div v-for="i in 3" :key="i" style="padding:14px">
          <div class="m-skel" style="height:18px;width:55%"></div>
          <div class="m-skel" style="height:1px;margin-top:12px"></div>
        </div>
      </div>

      <div v-else-if="spaces.length" class="m-card">
        <button v-for="s in spaces" :key="s.id" class="m-row" type="button" @click="openSpace(s.id)">
          <span class="m-ico" :class="s.type === 'PERSONAL' ? '' : (s.color ? '' : 'blue')"
                :style="s.color && s.type !== 'PERSONAL' ? { background: s.color, color: '#fff' } : {}">
            <el-icon><component :is="s.type === 'PERSONAL' ? 'User' : 'DocumentCopy'" /></el-icon>
          </span>
          <span class="m-row-body">
            <span class="m-row-title">{{ s.name }}</span>
            <span class="m-row-desc">
              {{ s.type === 'PERSONAL' ? '个人空间 · 仅自己可见' : '团队空间' }}
              <template v-if="s.memberCount"> · {{ s.memberCount }} 人</template>
            </span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>

      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><FolderOpened /></el-icon></span>
          <div>{{ online ? '还没有文档空间' : '离线中，暂无法读取空间列表' }}</div>
          <button v-if="online" class="m-btn sm" style="margin-top:4px" @click="router.push('/capture')">拍照建文档</button>
        </div>
      </div>
    </div>

    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.docs { min-height: 100%; }
.body { padding: 0 16px; }

/* 待整理箱入口（2026-10-15） */
.inbox-entry {
  width: 100%; display: flex; align-items: center; gap: 10px;
  margin-top: 10px; padding: 12px 14px; cursor: pointer;
  background: var(--m-card, #fff); border: 1px solid var(--m-border);
  border-radius: 12px; text-align: left;
}
.ie-ico { font-size: 20px; flex: none; }
.ie-main { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ie-title { font-size: 14.5px; font-weight: 600; color: var(--m-text); }
.ie-sub { font-size: 12px; color: var(--m-text-dim); }
.ie-badge { flex: none; min-width: 20px; height: 20px; padding: 0 6px; border-radius: 999px;
  background: #e5484d; color: #fff; font-size: 11.5px; font-weight: 700;
  display: inline-flex; align-items: center; justify-content: center; }
.ie-arrow { flex: none; color: var(--m-text-dim); font-size: 18px; }

.store { padding: 2px 2px 0; }
.store-bar {
  height: 6px; border-radius: 3px; background: var(--m-border); overflow: hidden;
}
.store-bar i {
  display: block; height: 100%; border-radius: 3px;
  background: var(--m-primary);
  transition: width .3s;
}
.store-bar i.warn { background: var(--m-warn); }
.store-txt { font-size: 12px; color: var(--m-text-dim); margin-top: 6px; }
</style>
