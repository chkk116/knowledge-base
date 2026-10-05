<script setup>
/**
 * 移动端文档阅读页
 *
 * 要点：
 *  - 阅读优先：去掉桌面端的目录树、右侧操作栏，正文全宽（左右 18px 边距）
 *  - 顶部栏随滚动渐显（下滑内容时标题栏半透明过渡），贴近原生阅读 App
 *  - 底部固定「编辑」按钮（有权限时），拇指可达
 *  - 离线可读：优先用本地缓存，无网且无缓存给明确提示
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getShareDoc } from '../../api/docShare'
import { getCachedDoc, cacheDoc } from '../../utils/offlineStore'

const route = useRoute()
const router = useRouter()

const spaceId = Number(route.params.spaceId)
const docId = Number(route.params.docId)

const doc = ref(null)
const loading = ref(true)
const fromCache = ref(false)
const scrolled = ref(false)

const html = computed(() => doc.value?.content || '')

onMounted(async () => {
  try {
    const { data } = await getShareDoc(spaceId, docId)
    doc.value = data
    cacheDoc(spaceId, data).catch(() => {})
  } catch (e) {
    // 离线 / 失败：读缓存
    const c = await getCachedDoc(spaceId, docId).catch(() => null)
    if (c) {
      doc.value = { id: docId, title: c.title, content: c.html, updatedAt: c.updatedAt }
      fromCache.value = true
    } else {
      window.__kbMsg?.error('无法打开文档：' + (e.response?.data?.error || e.message || '未知错误'))
    }
  } finally {
    loading.value = false
  }
})

function onScroll(e) {
  scrolled.value = e.target.scrollTop > 32
}

function fmtDate(s) {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}
</script>

<template>
  <div class="dv">
    <header class="dv-head" :class="{ solid: scrolled }">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="dv-title" :class="{ show: scrolled }">{{ doc?.title || '' }}</div>
      <button class="m-header-act" @click="router.push(`/doc/${spaceId}/${docId}/edit`)">
        <el-icon><EditPen /></el-icon>
      </button>
    </header>

    <div class="dv-scroll" @scroll="onScroll">
      <div v-if="loading" class="body">
        <div class="m-skel" style="height:26px;width:72%"></div>
        <div class="m-skel" style="height:15px;width:40%;margin-top:12px"></div>
        <div style="margin-top:24px">
          <div v-for="i in 6" :key="i" class="m-skel" style="height:15px;margin-bottom:11px"
               :style="{ width: [96, 88, 92, 75, 90, 60][i - 1] + '%' }"></div>
        </div>
      </div>

      <template v-else-if="doc">
        <div class="body">
          <h1 class="dv-h1">{{ doc.title || '未命名文档' }}</h1>
          <div class="dv-meta">
            <span>{{ fmtDate(doc.updatedAt || doc.createdAt) }}</span>
            <span v-if="fromCache" class="m-chip orange">离线缓存</span>
          </div>
        </div>
        <div class="body">
          <div class="m-prose" v-html="html || '<p style=\'color:var(--m-text-dim)\'>（空文档）</p>'"></div>
        </div>
        <div style="height:90px"></div>
      </template>

      <div v-else class="m-empty">
        <span class="ico"><el-icon :size="30"><Document /></el-icon></span>
        <div>文档不存在或无权访问</div>
        <button class="m-btn sm" style="margin-top:8px" @click="router.back()">返回</button>
      </div>
    </div>

    <!-- 底部编辑条 -->
    <div v-if="doc" class="m-actionbar">
      <button class="m-btn" @click="router.push(`/doc/${spaceId}/${docId}/edit`)">
        <el-icon><EditPen /></el-icon> 编辑这篇文档
      </button>
    </div>
  </div>
</template>

<style scoped>
.dv { height: 100%; display: flex; flex-direction: column; }
.body { padding: 0 18px; }

.dv-head {
  position: sticky; top: 0; z-index: 30; flex: none;
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
  transition: background-color .2s, box-shadow .2s;
}
.dv-head.solid { background: var(--m-card); box-shadow: 0 1px 0 var(--m-border); }
.dv-title {
  flex: 1 1 auto; min-width: 0; text-align: center;
  font-size: 15.5px; font-weight: 600;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  opacity: 0; transition: opacity .2s;
}
.dv-title.show { opacity: 1; }

.dv-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; -webkit-overflow-scrolling: touch; }
.dv-h1 { font-size: 23px; line-height: 1.35; margin: 4px 0 8px; letter-spacing: -.4px; }
.dv-meta { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--m-text-dim); margin-bottom: 18px; }
</style>
