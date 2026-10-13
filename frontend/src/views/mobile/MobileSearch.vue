<script setup>
/**
 * 移动端搜索页
 *
 * 搜「我的文档库」中的文档（按标题 + 正文纯文本匹配）。
 * 之所以不在移动端接全站搜索接口：手机上「我要找自己的某篇文档」是绝对主场景，
 * 全站搜索（帖子/课程/考试）在手机上的操作成本高、结果噪音大，留到桌面端更合适。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMyShares, listShareDocs } from '../../api/docShare'
import { listCachedDocs } from '../../utils/offlineStore'

const router = useRouter()

const keyword = ref('')
const all = ref([])           // [{ spaceId, spaceName, doc, text }]
const loading = ref(true)
const history = ref(JSON.parse(localStorage.getItem('kb_m_search_hist') || '[]'))

const results = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return []
  return all.value
    .filter((it) => {
      const t = (it.doc.title || '').toLowerCase()
      return t.includes(k) || it.text.toLowerCase().includes(k)
    })
    .slice(0, 60)
})

function snippet(it) {
  const k = keyword.value.trim().toLowerCase()
  const t = it.text
  const i = t.toLowerCase().indexOf(k)
  if (i < 0) return t.slice(0, 70)
  const s = Math.max(0, i - 24)
  return (s > 0 ? '…' : '') + t.slice(s, s + 80) + (s + 80 < t.length ? '…' : '')
}

function plain(html) {
  // DOMParser 替代 div.innerHTML（H9）
  return (new DOMParser().parseFromString(html || '', 'text/html').body.textContent || '').replace(/\s+/g, ' ').trim()
}

function rememberHistory(k) {
  const v = k.trim()
  if (!v) return
  const list = [v, ...history.value.filter((x) => x !== v)].slice(0, 10)
  history.value = list
  localStorage.setItem('kb_m_search_hist', JSON.stringify(list))
}

function doSearch() {
  rememberHistory(keyword.value)
}

function openDoc(it) {
  rememberHistory(keyword.value)
  router.push(`/doc/${it.spaceId}/${it.doc.id}`)
}

function useHistory(h) { keyword.value = h }

function clearHistory() {
  history.value = []
  localStorage.removeItem('kb_m_search_hist')
}

onMounted(async () => {
  try {
    const list = await listMyShares().then((r) => (Array.isArray(r.data) ? r.data : [])).catch(() => [])
    const buckets = await Promise.all(
      list.map(async (s) => {
        try {
          const { data } = await listShareDocs(s.id)
          return (data || []).map((doc) => ({
            spaceId: s.id, spaceName: s.name, doc, text: plain(doc.content)
          }))
        } catch { return [] }
      })
    )
    let flat = buckets.flat()
    if (!flat.length) {
      // 离线：用缓存
      const cached = await listCachedDocs().catch(() => [])
      flat = cached.map((c) => ({
        spaceId: c.spaceId, spaceName: '', doc: { id: c.docId, title: c.title, updatedAt: c.updatedAt },
        text: plain(c.html)
      }))
    }
    all.value = flat
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="sr">
    <header class="sr-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="searchbox">
        <el-icon><Search /></el-icon>
        <input v-model="keyword" placeholder="搜索我的文档" autofocus
               @keyup.enter="doSearch" />
        <button v-if="keyword" class="sb-clear" @click="keyword = ''"><el-icon><Close /></el-icon></button>
      </div>
      <button class="sr-go" @click="doSearch">搜索</button>
    </header>

    <div class="body">
      <!-- 未输入：历史记录 -->
      <template v-if="!keyword.trim()">
        <div v-if="history.length" class="m-sec-title">
          搜索历史
          <button class="more" @click="clearHistory">清空</button>
        </div>
        <div v-if="history.length" class="tags">
          <button v-for="h in history" :key="h" class="tag" @click="useHistory(h)">{{ h }}</button>
        </div>

        <div class="m-sec-title">提示</div>
        <div class="m-card">
          <div class="m-row" style="cursor:default">
            <span class="m-ico gray"><el-icon><InfoFilled /></el-icon></span>
            <span class="m-row-body">
              <span class="m-row-title">支持标题与正文检索</span>
              <span class="m-row-desc">已收录 {{ all.length }} 篇你的文档</span>
            </span>
          </div>
        </div>
      </template>

      <!-- 有输入：结果 -->
      <template v-else>
        <div v-if="loading" class="m-card">
          <div v-for="i in 3" :key="i" style="padding:14px">
            <div class="m-skel" style="height:16px;width:55%"></div>
            <div class="m-skel" style="height:12px;width:85%;margin-top:9px"></div>
          </div>
        </div>

        <template v-else-if="results.length">
          <div class="res-count">找到 {{ results.length }} 篇</div>
          <div class="m-card">
            <button v-for="(it, i) in results" :key="i" class="m-row res" type="button" @click="openDoc(it)">
              <span class="m-ico blue"><el-icon><Document /></el-icon></span>
              <span class="m-row-body">
                <span class="m-row-title">{{ it.doc.title || '未命名文档' }}</span>
                <span class="m-row-desc">{{ snippet(it) || '空文档' }}</span>
              </span>
            </button>
          </div>
        </template>

        <div v-else class="m-card">
          <div class="m-empty">
            <span class="ico"><el-icon :size="30"><Search /></el-icon></span>
            <div>没有找到「{{ keyword }}」</div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.sr { min-height: 100%; }
.body { padding: 0 16px; }
.sr-head {
  position: sticky; top: 0; z-index: 30;
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 12px 8px;
  background: var(--m-bg);
}
.searchbox {
  flex: 1 1 auto; min-width: 0;
  display: flex; align-items: center; gap: 8px;
  height: 40px; padding: 0 12px;
  background: var(--m-card); border-radius: 12px;
  box-shadow: var(--m-shadow); color: var(--m-text-dim);
}
.searchbox input {
  flex: 1 1 auto; min-width: 0; border: none; outline: none;
  background: transparent; font-size: 15px; color: var(--m-text); font-family: inherit;
}
.sb-clear { border: none; background: transparent; color: var(--m-text-dim); padding: 4px; cursor: pointer; }
.sr-go {
  flex: none; border: none; background: transparent;
  color: var(--m-primary); font-size: 15px; font-weight: 600; padding: 6px 2px; cursor: pointer;
}

.tags { display: flex; flex-wrap: wrap; gap: 8px; }
.tag {
  border: none; border-radius: 9px; padding: 0 13px; height: 34px;
  background: var(--m-card); color: var(--m-text-sub);
  font-size: 13.5px; cursor: pointer; box-shadow: var(--m-shadow);
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.tag:active { background: var(--m-card-2); }

.res-count { font-size: 12.5px; color: var(--m-text-dim); margin-bottom: 8px; padding-left: 2px; }
.res { align-items: flex-start; }
</style>
