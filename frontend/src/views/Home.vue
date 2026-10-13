<script setup>
import { ref, onMounted, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Monitor, DataLine, Collection, Document } from '@element-plus/icons-vue'
import { listDocs, getCategoryTree, allTags, toggleFavorite, myFavoriteIds, toggleLike, myLikeIds, getUserCard, listAnnouncements, homeOverview } from '../api/kb'
import AnnouncementBar from '../components/AnnouncementBar.vue'
import { useAuthStore } from '../store/auth'
import { useUiStore } from '../store/ui'
import { fromNow, fmtDateTime } from '../utils/time'
import { splitTags } from '../utils/text'
import { errMsg } from '../utils/errMsg'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const ui = useUiStore()
const q = ref('')
const author = ref('')        // 按作者搜索（首页搜索框未展示作者输入时使用空值）
const category = ref('')
const categoryTree = ref([])
const tag = ref('')           // 当前选中的标签筛选
const tagOptions = ref([])   // 标签云（{tag,count}）
const posts = ref([])        // 当前页（服务端返回的一页）
const results = ref([])      // 搜索结果（本地分页）
const loading = ref(false)
const error = ref('')        // 列表/搜索加载失败提示
const mode = ref('list')     // 'list' = 全部帖子（服务端分页） | 'search' = 搜索结果

const currentPage = ref(1)
const pageSize = ref(16)
const total = ref(0)
const sort = ref('latest')       // 'latest' 最新 | 'hot' 最热（浏览量）
const favIds = ref(new Set())    // 我已收藏的文档 id 集合（用于卡片星标填充态）
const likeIds = ref(new Set())   // 我点过赞的文档 id 集合（用于卡片 👍 填充态）

// 列表模式：直接展示服务端返回的当页；搜索模式：本地按页大小切片
const paged = computed(() => {
  if (mode.value === 'search') {
    const start = (currentPage.value - 1) * pageSize.value
    return results.value.slice(start, start + pageSize.value)
  }
  return posts.value
})

// 统计文案：列表模式用服务端总数，搜索模式用结果数
const totalText = computed(() =>
  mode.value === 'search' ? ('共 ' + total.value + ' 条结果') : ('共 ' + total.value + ' 篇文档')
)

// 去掉 HTML 标签，得到纯文本摘要（避免卡片里出现 <p><strong> 等源码）
const stripHtml = (html) => {
  if (!html) return ''
  // DOMParser 解析进游离文档，不加载任何资源，<img onerror> 不会触发；
  // div.innerHTML 会真实加载 detached 图片触发 onerror（2026-10-13 审查 H9）
  return (new DOMParser().parseFromString(html, 'text/html').body.textContent || '')
    .replace(/\s+/g, ' ').trim()
}
const snippetOf = (c) => {
  const text = stripHtml(c)
  return text.length > 90 ? text.slice(0, 90) + '…' : text
}

const loadCategories = async () => {
  try { categoryTree.value = (await getCategoryTree()).data || [] } catch (e) {}
}

const loadTags = async () => {
  try { tagOptions.value = (await allTags()).data || [] } catch (e) {}
}

// ---- 首页轻量数据概览（文档总数 / 本周新增 / 成员数；管理员附待审核）----
const ov = ref(null)
const loadOverview = async () => {
  try { ov.value = (await homeOverview()).data || null } catch (e) { /* 静默：概览条不阻塞首页 */ }
}

// ---- 标签云折叠：默认只展示前 10 个热门标签，其余收进「更多」----
const showAllTags = ref(false)
const sortedTags = computed(() => [...tagOptions.value].sort((a, b) => (b.count || 0) - (a.count || 0)))
const visibleTags = computed(() => {
  const sorted = sortedTags.value
  if (showAllTags.value || sorted.length <= 10) return sorted
  const top = sorted.slice(0, 10)
  // 当前选中的标签即使在长尾里也保持可见，避免点选后「消失」
  if (tag.value && !top.some(t => t.tag === tag.value)) {
    const sel = sorted.find(t => t.tag === tag.value)
    if (sel) top.push(sel)
  }
  return top
})
const hiddenTagCount = computed(() => Math.max(0, tagOptions.value.length - 10))

// ---- 卡片分类徽章：按分类关键词映射图标与配色（视觉锚点）----
const CAT_RULES = [
  [/linux|运维|服务器|网络|系统|部署|clamav|windows|麒麟|ubuntu|终端|安全|防火墙/i, { icon: Monitor, cls: 'ops' }],
  [/mysql|java|vue|前端|api|rest|代码|python|sql|搜索|设计|脚本|程序|js|html/i, { icon: DataLine, cls: 'dev' }],
  [/制度|规范|方案|报告|演练|复盘|总结|公告|管理|流程|制度/i, { icon: Collection, cls: 'biz' }],
]
const catBadge = (cat) => {
  for (const [re, v] of CAT_RULES) { if (cat && re.test(cat)) return v }
  return { icon: Document, cls: 'def' }
}

// 拆分逗号分隔的标签串为数组（用于卡片/详情展示与点击）

// 点击标签：切换筛选（再点取消），回到列表模式加载
const onTagClick = (t) => {
  tag.value = (tag.value === t) ? '' : t
  mode.value = 'list'
  q.value = ''
  category.value = ''
  authorId.value = null
  currentPage.value = 1
  loadPosts()
}

// 按作者筛选（来自帖子详情「他的全部文章」）
const authorId = ref(null)
const authorName = ref('')
const clearAuthor = () => {
  authorId.value = null
  authorName.value = ''
  mode.value = 'list'
  currentPage.value = 1
  loadPosts()
}

// 服务端分页：只拉当前页，不拉全量（最省资源）
const loadPosts = async () => {
  loading.value = true
  error.value = ''
  try {
    const res = await listDocs(currentPage.value - 1, pageSize.value, sort.value, tag.value, category.value, authorId.value || undefined)
    posts.value = res.data.content || []
    total.value = res.data.totalElements || 0
  } catch (e) {
    error.value = '帖子加载失败，请稍后重试'
  } finally { loading.value = false }
}

// 切换分类：进入列表模式，清除关键词与标签，按分类重新加载（解决之前纯分类筛选无效的问题）
const onCategoryChange = () => {
  mode.value = 'list'
  q.value = ''
  tag.value = ''
  authorId.value = null
  currentPage.value = 1
  loadPosts()
}

const onPageChange = (p) => {
  currentPage.value = p
  if (mode.value === 'list') loadPosts()
}

const onSizeChange = (s) => {
  pageSize.value = s
  currentPage.value = 1
  if (mode.value === 'list') loadPosts()
  else total.value = results.value.length
}

// 空输入点搜索：输入框抖动 + 轻提示，不跳转（避免整页被 /search 替换造成"刷新"观感）
const shakeBox = ref(false)
const onEmptySearch = () => {
  shakeBox.value = false
  requestAnimationFrame(() => { shakeBox.value = true })
  setTimeout(() => { shakeBox.value = false }, 500)
  ElMessage.warning('请输入内容')
}

const doSearch = () => {
  // 空输入不跳转：原路由跳转逻辑保留，仅空输入时给抖动反馈
  if (!q.value.trim() && !category.value && !author.value.trim()) { onEmptySearch(); return }
  const query = {}
  if (q.value.trim()) query.q = q.value.trim()
  if (category.value) query.category = category.value
  router.push({ path: '/search', query })
}

const reset = () => {
  mode.value = 'list'
  results.value = []
  currentPage.value = 1
  q.value = ''
  category.value = ''
  tag.value = ''
  loadPosts()
}

const openPost = (id) => router.push('/post/' + id)

// 切换排序（列表模式）：重置到第一页并按新排序拉取
const onSortChange = () => {
  currentPage.value = 1
  if (mode.value === 'list') loadPosts()
}

// 登录后拉取我已收藏 / 已点赞的文档 id，用于卡片 ★ 与 👍 填充态
const loadMyMarks = async () => {
  if (!auth.loggedIn) { favIds.value = new Set(); likeIds.value = new Set(); return }
  try { favIds.value = new Set((await myFavoriteIds()).data || []) } catch (e) {}
  try { likeIds.value = new Set((await myLikeIds()).data || []) } catch (e) {}
}

// 收藏 / 取消收藏（卡片上，@click.stop 不触发打开详情）
const onToggleFav = async (d) => {
  if (!auth.loggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  try {
    const { data } = await toggleFavorite(d.id)
    const next = new Set(favIds.value)
    if (data.favorited) next.add(d.id); else next.delete(d.id)
    favIds.value = next
    ElMessage.success(data.favorited ? '已收藏' : '已取消收藏')
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

// 点赞 / 取消点赞（卡片上，@click.stop 不触发打开详情），即时刷新该卡片点赞数
const onToggleLike = async (d) => {
  if (!auth.loggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  try {
    const { data } = await toggleLike(d.id)
    const next = new Set(likeIds.value)
    if (data.liked) next.add(d.id); else next.delete(d.id)
    likeIds.value = next
    d.likes = data.likes
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

// 公告（右侧边栏卡片 + 置顶居中弹窗的数据源）；关闭状态存在 UI store（内存态）：
// 关掉后整块侧栏 display:none，帖子列表 flex:1 自动撑满原公告位置；
// SPA 切页保持关闭，但整页刷新（F5）即恢复显示（符合「除刷新外保持关闭」的语义）。
const announcements = ref([])
const onAnnClose = () => { ui.closeAnnCard() }
const loadAnnouncements = async () => {
  try { announcements.value = (await listAnnouncements()).data || [] } catch (e) { /* 静默 */ }
}

onMounted(async () => {
  loadCategories(); loadTags(); loadAnnouncements(); loadOverview()
  if (route.query.tag) tag.value = String(route.query.tag)
  if (route.query.author) {
    authorId.value = Number(route.query.author)
    try { const c = (await getUserCard(authorId.value)).data; authorName.value = c.displayName || c.username } catch (e) {}
    mode.value = 'list'
  }
  loadPosts(); loadMyMarks()
})
</script>

<template>
  <div class="page">
    <section class="search">
      <div class="bar">
        <input v-model="q" class="q" :class="{ shake: shakeBox }" placeholder="搜索知识库…" @keyup.enter="doSearch" />
        <button type="button" class="go" @click="doSearch">搜索</button>
        <button type="button" class="go ghost" v-if="mode === 'search'" @click="reset">查看全部</button>
      </div>
      <div class="filters">
        <select v-model="sort" @change="onSortChange">
          <option value="latest">最新</option>
          <option value="hot">最热（浏览量）</option>
          <option value="comments">最多评论</option>
          <option value="favorites">最多收藏</option>
        </select>
        <el-tree-select v-model="category" :data="categoryTree" node-key="name"
          :props="{ label: 'name', children: 'children' }"
          placeholder="全部分类" clearable @change="onCategoryChange" @clear="onCategoryChange"
          style="width: 200px" />
        <span class="hint">{{ totalText }}</span>
      </div>
      <div class="ov-bar" v-if="ov">
        <span class="ov-item"><b>{{ ov.totalDocs }}</b> 篇文档</span>
        <span class="ov-dot"></span>
        <span class="ov-item">本周新增 <b>{{ ov.weekNew }}</b></span>
        <span class="ov-dot"></span>
        <span class="ov-item"><b>{{ ov.members }}</b> 位成员</span>
        <template v-if="ov.pendingReview != null">
          <span class="ov-dot"></span>
          <span class="ov-item warn">待审核 <b>{{ ov.pendingReview }}</b></span>
        </template>
      </div>
      <div class="tagcloud" v-if="tagOptions.length">
        <span class="lbl">标签</span>
        <button type="button" v-for="t in visibleTags" :key="t.tag"
                class="chip" :class="{ on: tag === t.tag }"
                @click="onTagClick(t.tag)">{{ t.tag }}<i v-if="t.count">·{{ t.count }}</i></button>
        <button v-if="hiddenTagCount > 0 || showAllTags" type="button"
                class="chip more" @click="showAllTags = !showAllTags">
          {{ showAllTags ? '收起' : '更多 ' + hiddenTagCount }}
        </button>
      </div>
      <div class="author-banner" v-if="authorId">
        <span>作者：<b>{{ authorName || ('#' + authorId) }}</b> 的全部文章</span>
        <button type="button" class="clear" @click="clearAuthor">×</button>
      </div>
    </section>

    <div class="content">
    <section class="list">
      <p v-if="loading" class="state">加载中…</p>
      <div v-else-if="error" class="state err">
        {{ error }}
        <button type="button" class="retry" @click="loadPosts">重试</button>
      </div>
      <ul class="grid" v-else-if="paged.length">
        <li v-for="d in paged" :key="d.id" class="card" @click="openPost(d.id)">
          <div class="row">
            <span class="cat-badge" :class="'b-' + catBadge(d.category).cls" :title="d.category">
              <el-icon><component :is="catBadge(d.category).icon" /></el-icon>
            </span>
            <span class="title">{{ d.title }}</span>
              <span class="side">
                <span class="top" v-if="d.pinned" title="置顶">置顶</span>
                <span class="hot" v-if="d.hot" title="热帖">HOT</span>
              <button type="button" class="fav" :class="{ on: favIds.has(d.id) }"
                      :title="favIds.has(d.id) ? '取消收藏' : '收藏'"
                      @click.stop="onToggleFav(d)">{{ favIds.has(d.id) ? '★' : '☆' }}</button>
              <span class="score" v-if="d.score != null">相关度 {{ Number(d.score).toFixed(2) }}</span>
            </span>
          </div>
          <div class="meta">
            <span class="author" v-if="d.ownerName">
              <svg class="ic-svg" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>{{ d.ownerName }}
            </span>
            <span class="time" v-if="d.createdAt || d.updatedAt">发布于 {{ fromNow(d.createdAt || d.updatedAt) }}（{{ fmtDateTime(d.createdAt || d.updatedAt) }}）</span>
            <span class="tag" v-if="d.category">{{ d.category }}</span>
            <span class="tag grey" v-for="t in splitTags(d.tags)" :key="t" @click.stop="onTagClick(t)">{{ t }}</span>
            <span class="stat"><svg class="ic-svg" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>{{ d.views || 0 }}</span>
            <button type="button" class="stat like" :class="{ on: likeIds.has(d.id) }"
                    :title="likeIds.has(d.id) ? '取消点赞' : '点赞'"
                    @click.stop="onToggleLike(d)"><svg class="ic-svg" viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M2 21h2V9H2v12zm20-10.09c0-.58-.47-1.05-1.05-1.05H13.8l.94-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.3 1.3 6.72 7.9A1.98 1.98 0 0 0 5.3 9.3v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-1.91z"/></svg>{{ d.likes || 0 }}</button>
          </div>
          <p class="snippet">{{ d.snippet || snippetOf(d.content) }}</p>
        </li>
      </ul>
      <p v-else class="state">{{ mode === 'search' ? '没有找到相关文档。' : '暂无文档。' }}</p>

      <el-pagination v-if="!loading && total > 0"
        class="pager" background
        layout="prev, pager, next, sizes, total"
        :total="total" :page-size="pageSize" :page-sizes="[6, 16, 30, 60]"
        :current-page="currentPage"
        @current-change="onPageChange" @size-change="onSizeChange" />
    </section>

    <!-- 右侧边栏：公告卡片（外企内网 / Confluence 式，不占顶部黄金位；无公告时整块收起；关闭后整块 display:none，帖子列表撑满该位置） -->
    <aside class="side-rail" :class="{ 'rail-hidden': ui.annCardClosed }" v-if="announcements.length">
      <AnnouncementBar :items="announcements" @close="onAnnClose" />
    </aside>
    </div>
  </div>
</template>

<style scoped>
.page { max-width: 1080px; margin: 0 auto; padding: 24px; color: #0f172a; }
/* 主内容区 + 右侧边栏两栏布局 */
.content { display: flex; gap: 20px; align-items: flex-start; margin-top: 4px; }
.list { flex: 1; min-width: 0; }
.side-rail { width: 280px; flex-shrink: 0; position: sticky; top: 24px; }
.side-rail.rail-hidden { display: none; }
.search .bar { display: flex; gap: 8px; }
.q { flex: 1; padding: 12px 14px; font-size: 15px; border: 1px solid #cbd5e1; border-radius: 10px; outline: none; }
.q:focus { border-color: #4f46e5; }
.q.shake { border-color: #ef4444; animation: kb-shake .45s; }
@keyframes kb-shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-7px); }
  40% { transform: translateX(7px); }
  60% { transform: translateX(-5px); }
  80% { transform: translateX(5px); }
}
.go { padding: 12px 18px; border: none; border-radius: 10px; background: #4f46e5; color: #fff; font-size: 15px; cursor: pointer; }
.go.ghost { background: #e2e8f0; color: #334155; }
.filters { display: flex; align-items: center; gap: 12px; margin: 10px 0; }
.tagcloud { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 4px 0 2px; }
.tagcloud .lbl { color: #94a3b8; font-size: 13px; }
.tagcloud .chip.more { border: 1px dashed #cbd5e1; background: transparent; color: #94a3b8; }
.tagcloud .chip.more:hover { color: #6366f1; border-color: #c7d2fe; }
/* 轻量数据概览条 */
.ov-bar { display: flex; align-items: center; gap: 10px; margin: 10px 0 2px; font-size: 13px; color: #64748b; }
.ov-bar b { color: #334155; font-weight: 600; }
.ov-item.warn b { color: #dc2626; }
.ov-dot { width: 3px; height: 3px; border-radius: 50%; background: #cbd5e1; }
html.dark .ov-bar { color: #7b8190; }
html.dark .ov-bar b { color: #e7e7ee; }
html.dark .ov-item.warn b { color: #f87171; }
html.dark .ov-dot { background: #3a3b45; }
html.dark .tagcloud .chip.more { border-color: #3a3b45; color: #7b8190; }
.chip { border: 1px solid #e2e8f0; background: #f8fafc; color: #475569; border-radius: 999px; padding: 4px 12px; font-size: 13px; cursor: pointer; transition: .15s; }
.chip:hover { border-color: #4f46e5; color: #4f46e5; }
.chip.on { background: #4f46e5; border-color: #4f46e5; color: #fff; }
.chip i { font-style: normal; opacity: .7; margin-left: 2px; }
.author-banner { display: flex; align-items: center; gap: 10px; margin: 8px 0 2px; padding: 8px 14px; background: #eef2ff; border: 1px solid #e0e7ff; border-radius: 10px; color: #4338ca; font-size: 13px; }
.author-banner .clear { margin-left: auto; border: none; background: none; color: #6366f1; font-size: 16px; cursor: pointer; line-height: 1; }
.author-banner .clear:hover { color: #4338ca; }
.tag.grey { cursor: pointer; }
.tag.grey:hover { background: #e2e8f0; color: #334155; }
.filters select { padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 8px; }
.hint { color: #64748b; font-size: 13px; }
.state { color: #64748b; padding: 12px 0; }
.state.err { color: #ef4444; display: flex; align-items: center; gap: 10px; }
.state .retry { border: 1px solid #ef4444; color: #ef4444; background: #fff; border-radius: 6px; padding: 3px 12px; cursor: pointer; font-size: 13px; }
.state .retry:hover { background: #ef4444; color: #fff; }
.grid { list-style: none; padding: 0; margin: 8px 0 0; display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.card { border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; cursor: pointer; transition: .15s;
  /* 屏外卡片跳过布局/绘制：长列表滚动不卡、保留的显示列表内存骤降（旧机友好，零行为风险） */
  content-visibility: auto; contain-intrinsic-size: auto 150px; }
.card:hover { border-color: #4f46e5; box-shadow: 0 4px 14px rgba(79, 70, 229,.12); }
.row { display: flex; align-items: center; gap: 9px; }
.row .title { flex: 1; min-width: 0; }
/* 分类徽章：扁平化——淡色底 + 单色图标，去掉渐变与投影（原来是彩色渐变方块，视觉太重） */
.cat-badge { width: 34px; height: 34px; border-radius: 9px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; }
.cat-badge .el-icon { font-size: 18px; }
.b-ops { background: #e0f2fe; color: #0284c7; }
.b-dev { background: #e0e7ff; color: #4f46e5; }
.b-biz { background: #fef3c7; color: #d97706; }
.b-def { background: #f1f5f9; color: #64748b; }
html.dark .b-ops { background: rgb(2 132 199 / .18); color: #7dd3fc; }
html.dark .b-dev { background: rgb(79 70 229 / .2); color: #a5b4fc; }
html.dark .b-biz { background: rgb(217 119 6 / .2); color: #fcd34d; }
html.dark .b-def { background: rgb(100 116 139 / .2); color: #94a3b8; }
.title { font-weight: 600; font-size: 16px; }
.score { color: #4f46e5; font-size: 12px; }
.side { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.hot { display: inline-flex; align-items: center; justify-content: center; padding: 1px 7px; border-radius: 999px; font-size: 11px; font-weight: 700; line-height: 16px; letter-spacing: .5px; color: #dc2626; background: #fee2e2; }
.top { display: inline-flex; align-items: center; justify-content: center; padding: 1px 7px; border-radius: 999px; font-size: 11px; font-weight: 700; line-height: 16px; letter-spacing: .5px; color: #0284c7; background: #e0f2fe; }
.fav { border: none; background: none; cursor: pointer; font-size: 18px; color: #cbd5e1; padding: 0 2px; line-height: 1; transition: .15s; }
.fav:hover { color: #f59e0b; transform: scale(1.15); }
.fav.on { color: #f59e0b; }
.meta { margin: 6px 0; display: flex; gap: 6px; flex-wrap: wrap; }
.author { display: inline-flex; align-items: center; gap: 4px; color: #475569; font-size: 12px; font-weight: 600; }
.author::after { content: '·'; margin-left: 2px; color: #cbd5e1; font-weight: 400; }
.time { color: #94a3b8; font-size: 12px; }
.tag { background: #eef2ff; color: #4f46e5; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
.tag.grey { background: #f1f5f9; color: #64748b; }
.stat { color: #94a3b8; font-size: 12px; align-self: center; }
.like { border: none; background: none; cursor: pointer; padding: 0 2px; color: #94a3b8; font-size: 12px; transition: .15s; }
.like:hover { color: #ef4444; transform: scale(1.1); }
.like.on { color: #ef4444; }
.ic-svg { vertical-align: -2px; }
.snippet { margin: 6px 0 0; color: #475569; font-size: 14px; line-height: 1.5; }
.pager { margin-top: 18px; justify-content: center; }

/* 暗色模式适配：theme.css 已覆盖 .page/.card/.snippet/.tag/.chip/.state/.author 等通用类，
   此处只补它未覆盖的少数项（次要按钮、错误重试按钮、点赞计数） */
html.dark .go.ghost { background: #2a2a35; color: #c7c8d4; }
html.dark .go.ghost:hover { background: #33333f; color: #e7e7ee; }
html.dark .state .retry { background: var(--el-fill-color-blank); }
html.dark .like { color: #8b8fa0; }

/* ---------- 响应式布局（断点统一 1024 / 640）---------- */
@media (max-width: 1024px) {
  .page { padding: 16px; }
  /* 内容 + 右侧公告栏：单列，公告栏移到列表下方 */
  .content { flex-direction: column; gap: 14px; }
  .side-rail { width: 100%; position: static; }
  .filters { flex-wrap: wrap; gap: 10px; }
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
  .filters :deep(.el-select) { width: 100% !important; }
  /* minmax(0, 1fr) + min-width:0 让卡片真能缩到容器宽以下；
     否则 1fr 会被 min-content 撑开（标题/标签/按钮连写一行时超 375px 视口） */
  .grid { grid-template-columns: minmax(0, 1fr); gap: 10px; }
  .card { padding: 12px; min-width: 0; }
  .row { gap: 8px; min-width: 0; }
  .title { font-size: 15px; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .side { flex-wrap: wrap; row-gap: 4px; }
  .meta { flex-wrap: wrap; gap: 6px; font-size: 12px; }
  .snippet { font-size: 13px; }
  .author-banner { padding: 8px 12px; }
}
</style>
