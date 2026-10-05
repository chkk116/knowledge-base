<script setup>
/**
 * 移动端「空间」页：展示某个共享空间下的文档列表
 *
 * 功能对齐：新建文档、搜索空间内文档、进入编辑/阅读、移动端简洁菜单（重命名、删除）。
 * 离线时退化为「本地缓存列表 + 本地待同步草稿」。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import {
  getShare, listShareDocs, createShareDoc, deleteShareDoc, listMyShares
} from '../../api/docShare'
import { listCachedDocs, cacheDoc, enqueueDoc, listQueuedDocs } from '../../utils/offlineStore'

const route = useRoute()
const router = useRouter()

const spaceId = Number(route.params.id)
const space = ref(null)
const docs = ref([])
const loading = ref(true)
const keyword = ref('')
const searchOpen = ref(false)
const offline = ref(!navigator.onLine)
const localDrafts = ref([])
const sheetDoc = ref(null)

const shown = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  const arr = k ? docs.value.filter((d) => (d.title || '').toLowerCase().includes(k)) : docs.value
  return [...arr].sort((a, b) =>
    new Date(b.updatedAt || b.createdAt || 0) - new Date(a.updatedAt || a.createdAt || 0))
})

const draftsHere = computed(() =>
  localDrafts.value.filter((d) => !d.spaceId || Number(d.spaceId) === spaceId))

function fmtTime(s) {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  if (diff < 3600000) return Math.max(1, Math.floor(diff / 60000)) + ' 分钟前'
  if (diff < 86400000) return Math.floor(diff / 3600000) + ' 小时前'
  if (diff < 86400000 * 30) return Math.floor(diff / 86400000) + ' 天前'
  return `${d.getFullYear()}/${d.getMonth() + 1}/${d.getDate()}`
}

/** 从 HTML 取纯文本摘要 */
function summary(html) {
  if (!html) return ''
  const d = document.createElement('div')
  d.innerHTML = html
  return (d.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 60)
}

async function load() {
  loading.value = true
  offline.value = !navigator.onLine
  try {
    const [s, d, q] = await Promise.all([
      getShare(spaceId).then((r) => r.data).catch(() => null),
      listShareDocs(spaceId).then((r) => (Array.isArray(r.data) ? r.data : [])).catch(() => null),
      listQueuedDocs().catch(() => [])
    ])
    space.value = s
    localDrafts.value = q
    if (d) {
      docs.value = d
      // 顺手写本地缓存（离线可读）
      d.slice(0, 30).forEach((doc) => cacheDoc(spaceId, doc).catch(() => {}))
    } else {
      // 离线兜底：读缓存
      const cached = await listCachedDocs(spaceId).catch(() => [])
      docs.value = cached.map((c) => ({
        id: c.docId, title: c.title, content: c.html,
        updatedAt: c.updatedAt, createdAt: c.updatedAt
      }))
    }
  } finally {
    loading.value = false
  }
}

function openDoc(d) { router.push(`/doc/${spaceId}/${d.id}`) }
function openDraft(id) { router.push(`/draft/${id}`) }

async function newDoc() {
  if (!navigator.onLine) {
    const id = await enqueueDoc({ spaceId, title: '未命名文档', content: '' })
    router.push(`/draft/${id}`)
    return
  }
  try {
    const { data } = await createShareDoc(spaceId, { title: '未命名文档', content: '' })
    router.replace(`/doc/${spaceId}/${data.id}/edit`)
  } catch (e) {
    window.__kbMsg?.error('创建失败：' + (e.response?.data?.error || e.message))
  }
}

function openSheet(d) { sheetDoc.value = d }

async function doDelete() {
  const d = sheetDoc.value
  sheetDoc.value = null
  if (!d) return
  try {
    await ElMessageBox.confirm(`确定删除「${d.title || '未命名文档'}」吗？`, '删除文档', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch { return }
  try {
    await deleteShareDoc(spaceId, d.id)
    docs.value = docs.value.filter((x) => x.id !== d.id)
    window.__kbMsg?.success('已删除')
  } catch (e) {
    window.__kbMsg?.error('删除失败：' + (e.response?.data?.error || e.message))
  }
}

onMounted(load)
</script>

<template>
  <div class="space">
    <header class="sp-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="sp-title">
        <div class="sp-name">{{ space?.name || '文档库' }}</div>
        <div class="sp-sub">
          {{ space?.type === 'PERSONAL' ? '个人空间' : '团队空间' }}
          <template v-if="docs.length"> · {{ docs.length }} 篇</template>
        </div>
      </div>
      <button class="m-header-act" @click="searchOpen = !searchOpen"><el-icon><Search /></el-icon></button>
    </header>

    <div v-if="searchOpen" class="body">
      <div class="searchbox">
        <el-icon><Search /></el-icon>
        <input v-model="keyword" placeholder="在本文档库中搜索" />
        <button v-if="keyword" class="sb-clear" @click="keyword = ''"><el-icon><Close /></el-icon></button>
      </div>
    </div>

    <div v-if="offline" class="body">
      <div class="offline-note">
        <el-icon><WarningFilled /></el-icon>
        <span>离线中，显示的是本机缓存；新建的文档会在联网后同步</span>
      </div>
    </div>

    <!-- 本地待同步草稿 -->
    <div v-if="draftsHere.length" class="body">
      <div class="m-sec-title">待同步 <span class="m-chip orange">{{ draftsHere.length }}</span></div>
      <div class="m-card">
        <button v-for="d in draftsHere" :key="d.localId" class="m-row" @click="openDraft(d.localId)">
          <span class="m-ico orange"><el-icon><Upload /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">{{ d.title || '未命名文档' }}</span>
            <span class="m-row-desc">{{ d.lastError || '等待联网同步' }}</span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>
    </div>

    <div class="body">
      <div class="m-sec-title">
        全部文档
        <button class="more" @click="newDoc">+ 新建</button>
      </div>

      <div v-if="loading" class="m-card">
        <div v-for="i in 4" :key="i" style="padding:14px">
          <div class="m-skel" style="height:16px;width:58%"></div>
          <div class="m-skel" style="height:12px;width:78%;margin-top:9px"></div>
        </div>
      </div>

      <div v-else-if="shown.length" class="m-card">
        <div v-for="d in shown" :key="d.id" class="docrow">
          <button class="dr-main" @click="openDoc(d)">
            <span class="dr-title">{{ d.title || '未命名文档' }}</span>
            <span class="dr-desc">{{ summary(d.content) || '空文档' }}</span>
            <span class="dr-time">{{ fmtTime(d.updatedAt || d.createdAt) }}</span>
          </button>
          <button class="dr-more" @click.stop="openSheet(d)"><el-icon><MoreFilled /></el-icon></button>
        </div>
      </div>

      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Document /></el-icon></span>
          <div>{{ keyword ? '没有匹配的文档' : '这个文档库还是空的' }}</div>
          <button v-if="!keyword" class="m-btn sm" style="margin-top:4px" @click="newDoc">新建一篇</button>
        </div>
      </div>
    </div>

    <div style="height:20px"></div>

    <!-- 文档操作弹层 -->
    <transition name="sheet-up">
      <div v-if="sheetDoc" class="m-sheet-mask" @click.self="sheetDoc = null">
        <div class="m-sheet">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">{{ sheetDoc.title || '未命名文档' }}</div>
          <button class="m-sheet-item" @click="openDoc(sheetDoc); sheetDoc = null">
            <el-icon><View /></el-icon> 阅读
          </button>
          <button class="m-sheet-item" @click="router.push(`/doc/${spaceId}/${sheetDoc.id}/edit`); sheetDoc = null">
            <el-icon><EditPen /></el-icon> 编辑
          </button>
          <button class="m-sheet-item danger" @click="doDelete">
            <el-icon><Delete /></el-icon> 删除
          </button>
          <button class="m-sheet-item cancel" @click="sheetDoc = null">取消</button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.space { min-height: 100%; }
.body { padding: 0 16px; }

.sp-head {
  position: sticky; top: 0; z-index: 30;
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
}
.sp-title { flex: 1 1 auto; min-width: 0; text-align: center; }
.sp-name { font-size: 16.5px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sp-sub { font-size: 11.5px; color: var(--m-text-dim); }

.searchbox {
  display: flex; align-items: center; gap: 8px;
  height: 42px; padding: 0 12px; margin-bottom: 12px;
  background: var(--m-card); border-radius: 12px;
  box-shadow: var(--m-shadow); color: var(--m-text-dim);
}
.searchbox input {
  flex: 1 1 auto; min-width: 0; border: none; outline: none;
  background: transparent; font-size: 15px; color: var(--m-text); font-family: inherit;
}
.sb-clear { border: none; background: transparent; color: var(--m-text-dim); padding: 4px; cursor: pointer; }

.offline-note {
  display: flex; gap: 6px; align-items: center;
  background: #fef2f2; color: #991b1b;
  padding: 10px 12px; border-radius: 12px; font-size: 12.5px; margin-bottom: 10px;
}
html.dark .offline-note { background: #3f1d1d; color: #fca5a5; }

/* 文档行：主区可点 + 右侧更多按钮，避免误触 */
.docrow { display: flex; align-items: stretch; }
.docrow + .docrow { border-top: 1px solid var(--m-border); }
.dr-main {
  flex: 1 1 auto; min-width: 0; text-align: left;
  border: none; background: transparent; cursor: pointer;
  padding: 13px 4px 13px 14px;
  display: flex; flex-direction: column; gap: 3px;
}
.dr-main:active { background: var(--m-card-2); }
.dr-title { font-size: 15px; font-weight: 500; color: var(--m-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dr-desc { font-size: 12.5px; color: var(--m-text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dr-time { font-size: 11.5px; color: var(--m-text-dim); }
.dr-more {
  flex: none; width: 44px; border: none; background: transparent;
  color: var(--m-text-dim); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
}
.dr-more:active { background: var(--m-card-2); }
</style>
