<script setup>
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { search, categories } from '../api/kb'
import { fromNow, fmtDateTime } from '../utils/time'
import { escapeHtml } from '../utils/text'

const route = useRoute()
const router = useRouter()

const q = ref(route.query.q || '')
const category = ref(route.query.category || '')
const tab = ref('doc')            // 'doc' = 按文档搜（标题/正文/标签/分类）| 'author' = 按作者名搜其文档
const docResults = ref([])        // 文档维度结果
const authorResults = ref([])     // 作者维度结果（输入当作者名）
const loading = ref(false)
const error = ref('')
const hasSearched = ref(false)
const categoryOptions = ref([])

const loadCategories = async () => {
  try { categoryOptions.value = (await categories()).data || [] } catch (e) {}
}
loadCategories()

// 关键词高亮：先转义 HTML，再对命中词包裹 <mark>（标题 / 摘要均为纯文本，安全）
const highlight = (text) => {
  const t = escapeHtml(text || '')
  const kw = (q.value || '').trim()
  if (!kw) return t
  const safe = kw.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  try { return t.replace(new RegExp('(' + safe + ')', 'gi'), '<mark>$1</mark>') } catch (e) { return t }
}

// 重新发起搜索（点击搜索按钮或回车）
const doSearch = () => {
  const query = {}
  if (q.value.trim()) query.q = q.value.trim()
  if (category.value) query.category = category.value
  router.push({ path: '/search', query })
}

// 路由 query 变化即重新拉取：两个维度并行搜（QQ 式——同一输入分别按文档 / 作者匹配）
const load = async () => {
  const kw = (route.query.q || '').trim()
  const cat = route.query.category || ''
  q.value = kw
  category.value = cat
  if (!kw && !cat) { docResults.value = []; authorResults.value = []; hasSearched.value = false; return }
  loading.value = true
  error.value = ''
  hasSearched.value = true
  try {
    // 文档维度：关键词匹配标题/正文/标签/分类（现有 hybrid）
    const p1 = search(kw, cat)
    // 作者维度：把输入当作者名，走 SearchService 的 author 路径（解析作者名 → 该作者已发布文档）
    const p2 = kw ? search('', cat, kw) : Promise.resolve({ data: [] })
    const [r1, r2] = await Promise.all([p1, p2])
    docResults.value = r1.data || []
    authorResults.value = r2.data || []
  } catch (e) {
    error.value = '搜索失败，请稍后重试'
    docResults.value = []
    authorResults.value = []
  } finally { loading.value = false }
}

watch(() => route.query, load, { immediate: true })

const openPost = (id) => router.push('/post/' + id)

const docTotal = computed(() => docResults.value.length)
const authorTotal = computed(() => authorResults.value.length)
const list = computed(() => tab.value === 'author' ? authorResults.value : docResults.value)
</script>

<template>
  <div class="page">
    <section class="search">
      <div class="bar">
        <input v-model="q" class="q" placeholder="搜索知识库 / 作者名…" @keyup.enter="doSearch" />
        <button type="button" class="go" @click="doSearch">搜索</button>
      </div>
      <div class="filters">
        <button type="button" class="back" @click="router.push('/')">← 返回首页</button>
        <select v-model="category" @change="doSearch">
          <option value="">全部分类</option>
          <option v-for="c in categoryOptions" :key="c" :value="c">{{ c }}</option>
        </select>
        <span class="hint" v-if="hasSearched">共 <b>{{ list.length }}</b> 条结果</span>
      </div>
    </section>

    <!-- QQ 式双维度切换：同一输入分别按文档 / 作者匹配，各自计数 -->
    <div class="tabs" v-if="hasSearched">
      <button type="button" class="tab" :class="{ on: tab === 'doc' }" @click="tab = 'doc'">文档 ({{ docTotal }})</button>
      <button type="button" class="tab" :class="{ on: tab === 'author' }" @click="tab = 'author'">按作者 ({{ authorTotal }})</button>
    </div>

    <div class="author-banner" v-if="tab === 'author' && list.length">
      按作者「<b>{{ q }}</b>」找到 <b>{{ authorTotal }}</b> 篇文档
    </div>

    <section class="list">
      <p v-if="loading" class="state">搜索中…</p>
      <div v-else-if="error" class="state err">
        {{ error }}
        <button type="button" class="retry" @click="doSearch">重试</button>
      </div>

      <!-- 空结果：文档维度为空且作者维度有结果时，引导切到「按作者」 -->
      <div v-else-if="hasSearched && !list.length" class="empty">
        <div class="emoji">🔍</div>
        <h3>没有找到相关内容</h3>
        <p v-if="tab === 'doc' && authorTotal > 0">
          文档中没有与「<b>{{ q }}</b>」匹配的内容。试试上方「按作者 ({{ authorTotal }})」标签，按作者名查找其文档。
        </p>
        <p v-else-if="tab === 'author'">
          没有名为「<b>{{ q }}</b>」的作者，或其暂无已发布文档。
        </p>
        <p v-else>
          没有与「<b>{{ q }}</b>」匹配的文档。试试更换关键词，或
          <a @click="router.push('/')">返回首页浏览全部内容</a>。
        </p>
      </div>

      <ul class="grid" v-else-if="list.length">
        <li v-for="d in list" :key="d.id" class="card" @click="openPost(d.id)">
          <div class="row">
            <span class="title" v-html="highlight(d.title)"></span>
            <span class="side">
              <span class="top" v-if="d.pinned" title="置顶">置顶</span>
              <span class="hot" v-if="d.hot" title="热帖">HOT</span>
              <span class="score" v-if="d.score != null">相关度 {{ Number(d.score).toFixed(1) }}</span>
            </span>
          </div>
          <div class="meta">
            <span class="au" v-if="d.ownerName">👤 {{ d.ownerName }}</span>
            <span class="time" v-if="d.updatedAt">更新于 {{ fromNow(d.updatedAt) }}（{{ fmtDateTime(d.updatedAt) }}）</span>
            <span class="tag" v-if="d.category">{{ d.category }}</span>
            <span class="tag grey" v-for="t in (d.tags ? d.tags.split(',').map(x=>x.trim()).filter(Boolean) : [])" :key="t">{{ t }}</span>
          </div>
          <p class="snippet" v-html="highlight(d.snippet)"></p>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.page { max-width: 880px; margin: 0 auto; padding: 24px; color: #0f172a; }
.search .bar { display: flex; gap: 8px; }
.q { flex: 1; padding: 12px 14px; font-size: 15px; border: 1px solid #cbd5e1; border-radius: 10px; outline: none; }
.q:focus { border-color: #4f46e5; }
.go { padding: 12px 18px; border: none; border-radius: 10px; background: #4f46e5; color: #fff; font-size: 15px; cursor: pointer; }
.filters { display: flex; align-items: center; gap: 12px; margin: 10px 0; }
.filters select { padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 8px; }
.back { padding: 8px 12px; border: 1px solid #cbd5e1; border-radius: 8px; background: #fff; color: #475569; cursor: pointer; font-size: 14px; flex-shrink: 0; }
.back:hover { border-color: #4f46e5; color: #4f46e5; }
.hint { color: #64748b; font-size: 13px; }
.hint b { color: #4f46e5; }
/* QQ 式双维度 tab */
.tabs { display: flex; gap: 8px; margin: 6px 0 4px; border-bottom: 1px solid #e2e8f0; }
.tab { border: none; background: none; padding: 10px 14px; font-size: 14px; color: #64748b; cursor: pointer; border-bottom: 2px solid transparent; margin-bottom: -1px; }
.tab:hover { color: #4f46e5; }
.tab.on { color: #4f46e5; font-weight: 600; border-bottom-color: #4f46e5; }
.author-banner { padding: 8px 14px; margin: 6px 0 2px; background: #eef2ff; border: 1px solid #e0e7ff; border-radius: 10px; color: #4338ca; font-size: 13px; }
.author-banner b { color: #4338ca; }
.state { color: #64748b; padding: 12px 0; }
.state.err { color: #ef4444; display: flex; align-items: center; gap: 10px; }
.state .retry { border: 1px solid #ef4444; color: #ef4444; background: #fff; border-radius: 6px; padding: 3px 12px; cursor: pointer; font-size: 13px; }
.state .retry:hover { background: #ef4444; color: #fff; }
.empty { text-align: center; padding: 48px 16px; color: #64748b; }
.empty .emoji { font-size: 44px; }
.empty h3 { margin: 12px 0 6px; color: #334155; }
.empty p { font-size: 14px; line-height: 1.7; }
.empty a { color: #4f46e5; cursor: pointer; }
.grid { list-style: none; padding: 0; margin: 8px 0 0; display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; align-items: start; }
.card { border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; cursor: pointer; transition: .15s; }
.card:hover { border-color: #4f46e5; box-shadow: 0 4px 14px rgba(79, 70, 229,.12); }
.row { display: flex; justify-content: space-between; align-items: center; }
.title { font-weight: 600; font-size: 16px; }
.score { color: #4f46e5; font-size: 12px; }
.side { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.hot { display: inline-flex; align-items: center; justify-content: center; padding: 1px 7px; border-radius: 999px; font-size: 11px; font-weight: 700; line-height: 16px; letter-spacing: .5px; color: #fff; background: linear-gradient(135deg, #ff7a45, #ff4d4f); }
.top { display: inline-flex; align-items: center; justify-content: center; padding: 1px 7px; border-radius: 999px; font-size: 11px; font-weight: 700; line-height: 16px; letter-spacing: .5px; color: #fff; background: linear-gradient(135deg, #36cfc9, #1677ff); }
.meta { margin: 6px 0; display: flex; gap: 6px; flex-wrap: wrap; }
.au { color: #64748b; font-size: 12px; }
.time { color: #94a3b8; font-size: 12px; }
.tag { background: #eef2ff; color: #4f46e5; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
.tag.grey { background: #f1f5f9; color: #64748b; }
.snippet { margin: 6px 0 0; color: #475569; font-size: 14px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
:deep(mark) { background: #fde68a; color: #7c2d12; padding: 0 2px; border-radius: 3px; }

/* 暗色模式适配（原生 input / select / button 不继承 Element 变量，需显式覆盖背景、边框与文字色） */
html.dark .page { color: var(--el-text-color-primary); }
html.dark .q { background: var(--el-fill-color-blank); border-color: var(--el-border-color); color: var(--el-text-color-primary); }
html.dark .q:focus { border-color: #818cf8; }
html.dark .filters select { background: var(--el-fill-color-blank); border-color: var(--el-border-color); color: var(--el-text-color-primary); }
html.dark .back { background: var(--el-fill-color); border-color: var(--el-border-color); color: var(--el-text-color-regular); }
html.dark .back:hover { border-color: #818cf8; color: #a5b4fc; }
html.dark .hint { color: var(--el-text-color-secondary); }
html.dark .hint b { color: #a5b4fc; }
html.dark .tabs { border-bottom-color: var(--el-border-color-light); }
html.dark .tab { color: var(--el-text-color-secondary); }
html.dark .tab:hover { color: #a5b4fc; }
html.dark .tab.on { color: #a5b4fc; border-bottom-color: #818cf8; }
html.dark .author-banner { background: rgba(99,102,241,.16); border-color: rgba(129,140,248,.3); color: #a5b4fc; }
html.dark .author-banner b { color: #a5b4fc; }
html.dark .state { color: var(--el-text-color-secondary); }
html.dark .state.err { color: #f87171; }
html.dark .state .retry { background: transparent; border-color: #f87171; color: #f87171; }
html.dark .empty { color: var(--el-text-color-secondary); }
html.dark .empty h3 { color: var(--el-text-color-primary); }
html.dark .empty a { color: #a5b4fc; }
html.dark .card { background: var(--el-bg-color-overlay); border-color: var(--el-border-color-light); }
html.dark .card:hover { border-color: #818cf8; box-shadow: 0 4px 14px rgba(0,0,0,.4); }
html.dark .score { color: #a5b4fc; }
html.dark .au { color: var(--el-text-color-secondary); }
html.dark .time { color: var(--el-text-color-secondary); }
html.dark .tag { background: rgba(99,102,241,.18); color: #a5b4fc; }
html.dark .tag.grey { background: var(--el-fill-color); color: var(--el-text-color-secondary); }
html.dark .snippet { color: var(--el-text-color-regular); }
/* mark 为 v-html 动态生成的高亮标签，不带 scoped 属性，用 :deep() 命中 */
html.dark :deep(mark) { background: rgba(250,204,21,.24); color: #fde68a; }

/* ---------- 响应式布局（断点统一 1024 / 640）---------- */
@media (max-width: 1024px) {
  .page { padding: 16px; }
  .filters { flex-wrap: wrap; gap: 10px; }
  .tabs { flex-wrap: wrap; }
  .row { gap: 8px; }
  .title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
@media (max-width: 640px) {
  .page { padding: 12px; }
  .search .bar { flex-wrap: wrap; gap: 8px; }
  .q { flex: 1 1 100%; padding: 10px 12px; font-size: 14px; }
  .go { flex: 1; padding: 10px 14px; font-size: 14px; }
  .filters { gap: 8px; }
  .filters select { flex: 1 1 100%; }
  .back { flex: 1 1 100%; }
  .grid { grid-template-columns: 1fr; gap: 10px; }
  .card { padding: 12px; }
  .title { font-size: 15px; }
  .snippet { font-size: 13px; }
  .empty { padding: 28px 12px; }
}
</style>
