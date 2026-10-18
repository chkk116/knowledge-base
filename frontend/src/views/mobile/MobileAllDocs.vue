<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { listAllShareDocs, listMyShares, setShareDocTags } from '../../api/docShare'
import { contentMatches, snippetOf, clearDocSearchCache } from '../../utils/docSearch'

const router = useRouter()
const loading = ref(true)
const docs = ref([])
const spaces = ref([])
const keyword = ref('')
const tagFilter = ref('')
const spaceFilter = ref('')
const typeFilter = ref('')
const timeFilter = ref('')
const sheetDoc = ref(null)

const TYPES = [
  { value: 'TEXT', label: '文字' },
  { value: 'IMAGE', label: '图片' },
  { value: 'VIDEO', label: '视频' },
  { value: 'AUDIO', label: '音频' },
  { value: 'FILE', label: '附件' }
]

function tagsOf(doc) {
  if (Array.isArray(doc?.tags)) return doc.tags.map((x) => String(x).trim()).filter(Boolean)
  return String(doc?.tags || '').split(',').map((x) => x.trim()).filter(Boolean)
}
function typeLabel(type) { return TYPES.find((x) => x.value === type)?.label || '文字' }
function fmtTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return `${date.getMonth() + 1}月${date.getDate()}日更新`
}

const allTags = computed(() => {
  const map = new Map()
  for (const doc of docs.value) for (const tag of tagsOf(doc)) {
    const key = tag.toLocaleLowerCase()
    if (!map.has(key)) map.set(key, tag)
  }
  return [...map.values()].sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const shown = computed(() => {
  const q = keyword.value.trim().toLocaleLowerCase()
  const now = Date.now()
  const days = Number(timeFilter.value || 0)
  const from = days ? now - days * 86400000 : 0
  return docs.value.filter((doc) => {
    const tags = tagsOf(doc)
    // 标题 / 标签 / 正文 三处任一命中即可（正文检索见 utils/docSearch.js）
    if (q
      && !String(doc.title || '').toLocaleLowerCase().includes(q)
      && !tags.some((t) => t.toLocaleLowerCase().includes(q))
      && !contentMatches(doc, q)) return false
    if (tagFilter.value && !tags.some((t) => t.toLocaleLowerCase() === tagFilter.value.toLocaleLowerCase())) return false
    if (spaceFilter.value && String(doc.shareId) !== spaceFilter.value) return false
    if (typeFilter.value && doc.contentType !== typeFilter.value) return false
    if (from) {
      const stamp = new Date(doc.updatedAt || doc.createdAt || 0).getTime()
      if (!stamp || stamp < from) return false
    }
    return true
  })
})

const filtersActive = computed(() => !!(keyword.value.trim() || tagFilter.value || spaceFilter.value || typeFilter.value || timeFilter.value))

/** 关键词命中正文时，在标题下方显示一小段上下文 */
function hitSnippet(doc) {
  const q = keyword.value.trim().toLocaleLowerCase()
  if (!q) return ''
  // 标题/标签已经命中就不必再显示正文片段了
  if (String(doc.title || '').toLocaleLowerCase().includes(q)) return ''
  if (tagsOf(doc).some((t) => t.toLocaleLowerCase().includes(q))) return ''
  return snippetOf(doc, q)
}

async function load() {
  loading.value = true
  try {
    const [docRes, spaceRes] = await Promise.all([listAllShareDocs(true), listMyShares()])
    docs.value = Array.isArray(docRes.data) ? docRes.data : []
    spaces.value = Array.isArray(spaceRes.data) ? spaceRes.data : []
    clearDocSearchCache()   // 列表刷新后丢弃旧的正文缓存
  } catch (e) {
    docs.value = []
    spaces.value = []
    window.__kbMsg?.error('文档列表加载失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  } finally {
    loading.value = false
  }
}

function clearFilters() {
  keyword.value = ''
  tagFilter.value = ''
  spaceFilter.value = ''
  typeFilter.value = ''
  timeFilter.value = ''
}
function openDoc(doc) { router.push(`/doc/${doc.shareId}/${doc.id}`) }
function canTag(doc) { return ['OWNER', 'ADMIN', 'WRITE'].includes(String(doc.myPerm || '').toUpperCase()) }

async function editTags(doc) {
  sheetDoc.value = null
  if (!canTag(doc)) {
    window.__kbMsg?.warning('你只有阅读权限，不能修改此文档的标签')
    return
  }
  try {
    const result = await ElMessageBox.prompt('输入多个标签时用逗号分隔；每篇最多 12 个标签，每个标签最多 24 个字。', '管理标签', {
      confirmButtonText: '保存', cancelButtonText: '取消',
      inputValue: tagsOf(doc).join(', '),
      inputPlaceholder: '例如：项目资料，待阅读，会议纪要',
      inputType: 'text'
    })
    const tags = String(result.value || '').split(/[,，、]/).map((x) => x.trim()).filter(Boolean)
    const normalized = [...new Map(tags.map((tag) => [tag.toLocaleLowerCase(), tag])).values()]
    if (normalized.length > 12 || normalized.some((tag) => tag.length > 24)) {
      window.__kbMsg?.warning('最多 12 个标签，每个标签不超过 24 个字')
      return
    }
    const { data } = await setShareDocTags(doc.shareId, doc.id, normalized)
    const target = docs.value.find((x) => x.id === doc.id && x.shareId === doc.shareId)
    if (target) target.tags = data.tags || ''
    window.__kbMsg?.success(normalized.length ? '标签已保存' : '已清除标签')
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') window.__kbMsg?.error('保存标签失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  }
}

onMounted(load)
</script>

<template>
  <div class="all-docs">
    <header class="m-header solid">
      <button class="m-header-act" type="button" aria-label="返回" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">全部文档</div>
      <button class="m-header-act" type="button" aria-label="刷新" @click="load"><el-icon><Refresh /></el-icon></button>
    </header>

    <div class="body filter-body">
      <label class="searchbox">
        <el-icon><Search /></el-icon>
        <input v-model="keyword" type="search" placeholder="搜索标题、标签或正文" />
        <button v-if="keyword" class="clear-search" type="button" aria-label="清除搜索" @click.prevent="keyword = ''"><el-icon><Close /></el-icon></button>
      </label>
      <div class="filters">
        <select v-model="tagFilter" aria-label="按标签筛选">
          <option value="">全部标签</option>
          <option v-for="tag in allTags" :key="tag" :value="tag">{{ tag }}</option>
        </select>
        <select v-model="spaceFilter" aria-label="按空间筛选">
          <option value="">全部空间</option>
          <option v-for="space in spaces" :key="space.id" :value="String(space.id)">{{ space.name }}</option>
        </select>
        <select v-model="typeFilter" aria-label="按类型筛选">
          <option value="">全部类型</option>
          <option v-for="type in TYPES" :key="type.value" :value="type.value">{{ type.label }}</option>
        </select>
        <select v-model="timeFilter" aria-label="按更新时间筛选">
          <option value="">全部时间</option>
          <option value="7">近 7 天</option>
          <option value="30">近 30 天</option>
          <option value="90">近 90 天</option>
        </select>
      </div>
      <div class="result-line">
        <span>共 {{ shown.length }} 篇<template v-if="filtersActive"> · 已筛选</template></span>
        <button v-if="filtersActive" type="button" @click="clearFilters">清除条件</button>
      </div>
    </div>

    <div class="body list-body">
      <div v-if="loading" class="m-card loading-card">
        <div v-for="i in 4" :key="i" class="skeleton-row"><div class="m-skel" style="height:16px;width:62%"></div><div class="m-skel" style="height:12px;width:44%;margin-top:9px"></div></div>
      </div>
      <div v-else-if="shown.length" class="m-card doc-list">
        <div v-for="doc in shown" :key="`${doc.shareId}:${doc.id}`" class="doc-row">
          <button class="doc-main" type="button" @click="openDoc(doc)">
            <span class="doc-title">{{ doc.title || '未命名文档' }}</span>
            <span class="doc-meta">
              <span class="space-name">{{ doc.spaceName || '文档空间' }}</span>
              <span class="dot-sep">·</span>
              <span>{{ typeLabel(doc.contentType) }}</span>
              <span class="dot-sep">·</span>
              <span>{{ fmtTime(doc.updatedAt || doc.createdAt) }}</span>
            </span>
            <span v-if="tagsOf(doc).length" class="tag-list">
              <span v-for="tag in tagsOf(doc)" :key="tag" class="tag-pill"># {{ tag }}</span>
            </span>
            <!-- 正文命中时给一小段上下文，说明「为什么这条会被搜到」 -->
            <span v-if="hitSnippet(doc)" class="doc-hit">{{ hitSnippet(doc) }}</span>
          </button>
          <button class="more-btn" type="button" aria-label="文档操作" @click="sheetDoc = doc"><el-icon><MoreFilled /></el-icon></button>
        </div>
      </div>
      <div v-else class="m-card empty">
        <div class="empty-icon"><el-icon :size="30"><Document /></el-icon></div>
        <div>{{ filtersActive ? '没有符合条件的文档' : '还没有可显示的文档' }}</div>
        <button v-if="filtersActive" class="reset-btn" type="button" @click="clearFilters">清除筛选</button>
      </div>
      <div class="bottom-space"></div>
    </div>

    <transition name="sheet-up">
      <div v-if="sheetDoc" class="m-sheet-mask" @click.self="sheetDoc = null">
        <div class="m-sheet">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">{{ sheetDoc.title || '未命名文档' }}</div>
          <button class="m-sheet-item" type="button" @click="openDoc(sheetDoc); sheetDoc = null"><el-icon><View /></el-icon>阅读文档</button>
          <button class="m-sheet-item" type="button" @click="editTags(sheetDoc)"><el-icon><CollectionTag /></el-icon>管理标签</button>
          <button class="m-sheet-item cancel" type="button" @click="sheetDoc = null">取消</button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.all-docs { min-height: 100%; }
.body { padding: 0 16px; }
.filter-body { padding-top: 10px; }
.searchbox {
  display: flex; align-items: center; gap: 8px; min-height: 44px; box-sizing: border-box;
  padding: 0 12px; border-radius: 13px; background: var(--m-card); color: var(--m-text-dim);
  box-shadow: 0 1px 2px rgba(0,0,0,.04), 0 6px 18px rgba(15,23,42,.04);
}
.searchbox input { flex: 1; min-width: 0; height: 42px; border: 0; outline: 0; background: transparent; color: var(--m-text); font: inherit; font-size: 14px; }
.clear-search { display: inline-grid; place-items: center; width: 30px; height: 30px; border: 0; background: transparent; color: var(--m-text-dim); }
.filters { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-top: 10px; }
.filters select {
  min-width: 0; height: 38px; padding: 0 10px; border: 1px solid var(--m-border); border-radius: 10px;
  background: var(--m-card); color: var(--m-text); font: inherit; font-size: 12.5px;
}
.result-line { display: flex; justify-content: space-between; align-items: center; min-height: 37px; padding: 0 2px; color: var(--m-text-dim); font-size: 12px; }
.result-line button, .reset-btn { border: 0; background: transparent; color: var(--m-primary); font: inherit; font-size: 12px; }
.list-body { padding-top: 2px; }
.doc-list { overflow: hidden; }
.doc-row { display: flex; min-width: 0; align-items: stretch; }
.doc-row + .doc-row { border-top: 1px solid var(--m-border); }
.doc-main { flex: 1; min-width: 0; padding: 12px 6px 12px 14px; border: 0; background: transparent; text-align: left; color: var(--m-text); cursor: pointer; }
.doc-main:active, .more-btn:active { background: var(--m-card-2); }
.doc-title { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; font-weight: 600; }
.doc-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; margin-top: 5px; color: var(--m-text-dim); font-size: 11px; }
.space-name { max-width: 125px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--m-primary); }
.dot-sep { opacity: .55; }
.tag-list { display: flex; flex-wrap: wrap; gap: 5px; margin-top: 7px; }
.tag-pill { max-width: 150px; padding: 3px 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; border: 0; border-radius: 999px; background: var(--m-primary-soft); color: var(--m-primary); font: inherit; font-size: 10.5px; }
/* 正文命中的上下文片段：比标题淡、比 meta 深，一眼看出「为什么搜到这条」 */
.doc-hit { display: block; margin-top: 3px; font-size: 12px; line-height: 1.45; color: var(--m-text-dim); overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; word-break: break-word; }
.more-btn { flex: none; width: 42px; border: 0; background: transparent; color: var(--m-text-dim); }
.empty { padding: 30px 16px; text-align: center; color: var(--m-text-dim); font-size: 13px; }
.empty-icon { margin-bottom: 6px; color: var(--m-primary); }
.reset-btn { margin-top: 10px; }
.loading-card { padding: 2px 14px; }
.skeleton-row { padding: 13px 0; }
.skeleton-row + .skeleton-row { border-top: 1px solid var(--m-border); }
.bottom-space { height: 22px; }
html.dark .searchbox { box-shadow: 0 4px 14px rgba(0,0,0,.2); }
</style>
