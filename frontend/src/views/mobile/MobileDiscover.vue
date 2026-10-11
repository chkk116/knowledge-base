<script setup>
/**
 * 移动端「发现」—— 公开文章流
 *
 * 补的缺口：Web 端首页展示的是**公开文章（Document）**，而 App 原来只有空间文档
 * （SharedDocument），公开文章在 App 里完全没有入口。
 * 这里给一个原生文章流：最新 / 热门排序 + 分类筛选 + 上拉加载更多。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listDocs, categories } from '../../api/kb'

const router = useRouter()

const sort = ref('')            // '' = 最新 | 'hot' = 热门
const cat = ref('')             // '' = 全部
const cats = ref([])
const items = ref([])
const page = ref(0)
const total = ref(0)
const loading = ref(false)
const finished = ref(false)
const scroller = ref(null)

const PAGE_SIZE = 12

function excerpt(html) {
  const t = String(html || '')
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
  return t.length > 72 ? t.slice(0, 72) + '…' : t
}

function fmtTime(s) {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  const dd = 86400000
  if (diff < 3600000) return Math.max(1, Math.floor(diff / 60000)) + ' 分钟前'
  if (diff < dd) return Math.floor(diff / 3600000) + ' 小时前'
  if (diff < dd * 7) return Math.floor(diff / dd) + ' 天前'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

async function load(reset = false) {
  if (loading.value) { if (reset) discoverPendingReset = true; return }
  if (reset) { page.value = 0; items.value = []; finished.value = false }
  if (finished.value) return
  loading.value = true
  try {
    const { data } = await listDocs(page.value, PAGE_SIZE, sort.value || undefined, undefined, cat.value || undefined)
    const list = data?.content || []
    total.value = data?.totalElements ?? list.length
    items.value = page.value === 0 ? list : items.value.concat(list)
    finished.value = list.length < PAGE_SIZE
    page.value += 1
  } catch {
    finished.value = true
  } finally {
    loading.value = false
    if (discoverPendingReset) { discoverPendingReset = false; load(true) }
  }
}

// 外部审查 2026-10-11：切换排序/分类时若上一轮 load 还在途，load(true) 会被 loading
// 守卫吞掉——筛选状态已变但结果不刷新。记 pending，本轮结束后补跑 reset。
let discoverPendingReset = false
function switchSort(s) { if (sort.value === s) return; sort.value = s; load(true) }
function switchCat(c) { if (cat.value === c) return; cat.value = c; load(true) }

function onScroll(e) {
  const el = e.target
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 220) load()
}

const showEmpty = computed(() => !loading.value && !items.value.length)

onMounted(async () => {
  cats.value = await categories().then((r) => r.data || []).catch(() => [])
  load(true)
})
onUnmounted(() => { /* nothing */ })
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">发现</div>
      <button class="m-header-act" @click="router.push('/search')"><el-icon :size="19"><Search /></el-icon></button>
    </header>

    <!-- 排序 + 分类 -->
    <div class="filters">
      <div class="segs">
        <button class="seg" :class="{ on: sort === '' }" type="button" @click="switchSort('')">最新</button>
        <button class="seg" :class="{ on: sort === 'hot' }" type="button" @click="switchSort('hot')">热门</button>
      </div>
      <div class="cats">
        <button class="cat" :class="{ on: cat === '' }" type="button" @click="switchCat('')">全部</button>
        <button v-for="c in cats" :key="c" class="cat" :class="{ on: cat === c }"
                type="button" @click="switchCat(c)">{{ c }}</button>
      </div>
    </div>

    <div ref="scroller" class="list" @scroll="onScroll">
      <div class="list-inner">
        <button v-for="d in items" :key="d.id" class="post" type="button" @click="router.push('/post/' + d.id)">
          <div class="p-title">
            <el-icon v-if="d.pinned" :size="14" class="pin"><Promotion /></el-icon>
            {{ d.title || '未命名文档' }}
          </div>
          <div v-if="excerpt(d.content)" class="p-ex">{{ excerpt(d.content) }}</div>
          <div class="p-meta">
            <span v-if="d.category" class="m-chip gray">{{ d.category }}</span>
            <span class="p-author">{{ d.ownerName || '匿名' }}</span>
            <span class="p-dot">·</span>
            <span>{{ fmtTime(d.updatedAt || d.createdAt) }}</span>
            <span class="p-stat"><el-icon :size="13"><View /></el-icon>{{ d.views || 0 }}</span>
            <span class="p-stat"><el-icon :size="13"><Star /></el-icon>{{ d.likes || 0 }}</span>
          </div>
        </button>

        <div v-if="loading" class="more">加载中…</div>
        <div v-else-if="finished && items.length" class="more">— 已经到底了 —</div>

        <div v-if="showEmpty" class="m-card">
          <div class="m-empty">
            <span class="ico"><el-icon :size="30"><Compass /></el-icon></span>
            <div>还没有公开文章</div>
            <button class="m-btn sm" style="margin-top:6px" @click="router.push('/docs')">看我的文档库</button>
          </div>
        </div>
        <div style="height:16px"></div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { height: 100%; display: flex; flex-direction: column; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }

.filters { flex: none; padding: 2px 0 6px; background: var(--m-bg); }
.segs { display: flex; gap: 8px; padding: 4px 16px 8px; }
.seg {
  height: 30px; padding: 0 14px; border-radius: 15px; cursor: pointer; font-size: 13.5px;
  border: 1px solid var(--m-border); background: var(--m-card); color: var(--m-text-sub);
}
.seg.on { background: var(--m-primary-soft); border-color: transparent; color: var(--m-primary); font-weight: 600; }
.cats { display: flex; gap: 8px; padding: 0 16px 4px; overflow-x: auto; -webkit-overflow-scrolling: touch; }
.cats::-webkit-scrollbar { display: none; }
.cat {
  flex: none; height: 28px; padding: 0 12px; border-radius: 14px; cursor: pointer; font-size: 12.5px;
  border: 1px solid var(--m-border); background: transparent; color: var(--m-text-sub); white-space: nowrap;
}
.cat.on { background: var(--m-text); border-color: var(--m-text); color: var(--m-card); }

.list { flex: 1 1 auto; min-height: 0; overflow-y: auto; -webkit-overflow-scrolling: touch; }
.list-inner { padding: 0 16px; }

.post {
  width: 100%; text-align: left; border: none; cursor: pointer;
  background: var(--m-card); border-radius: var(--m-radius); padding: 13px 14px;
  margin-bottom: 10px; box-shadow: var(--m-shadow); color: inherit;
}
.post:active { background: var(--m-card-2); }
.p-title { font-size: 15.5px; font-weight: 600; color: var(--m-text); line-height: 1.45; display: flex; align-items: baseline; gap: 5px; }
.pin { color: var(--m-danger); transform: translateY(1px); }
.p-ex { margin-top: 6px; font-size: 13px; line-height: 1.6; color: var(--m-text-dim); }
.p-meta { margin-top: 9px; display: flex; align-items: center; gap: 7px; font-size: 11.5px; color: var(--m-text-dim); flex-wrap: wrap; }
.p-author { color: var(--m-text-sub); }
.p-dot { opacity: .5; }
.p-stat { display: inline-flex; align-items: center; gap: 3px; }

.more { text-align: center; font-size: 12.5px; color: var(--m-text-dim); padding: 10px 0 4px; }
</style>
