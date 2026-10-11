<script setup>
/**
 * 移动端「文章详情」——公开文章（Document）阅读页
 *
 * 与 MobileDocView（空间文档）的区别：
 *   - 数据源 /docs/{id}（公开文章），带作者、浏览量、点赞
 *   - 支持点赞 / 收藏（后端 /api/favorites、/api/likes）
 *   - 底部推荐「相关文档」（同分类 + 标签重叠）
 * 正文同样走 Tiptap 只读实例，这样流程图 / 脑图 / 电子表格图块才能画出来。
 */
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getDoc, getRelatedDocs, toggleLike, myLikeIds, toggleFavorite, myFavoriteIds } from '../../api/kb'
import { enhanceVideos, bindImgLightbox, absolutizeMedia } from '../../utils/mediaEnhance'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import Table from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableHeader from '@tiptap/extension-table-header'
import TableCell from '@tiptap/extension-table-cell'
import MindmapNode from '../../extensions/MindmapNode'
import FlowchartNode from '../../extensions/FlowchartNode'
import SpreadsheetNode from '../../extensions/SpreadsheetNode'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)

const doc = ref(null)
const loading = ref(true)
const related = ref([])
const liked = ref(false)
const faved = ref(false)
const likeCount = ref(0)
const scrolled = ref(false)
const lightboxSrc = ref('')

const html = computed(() => doc.value?.content || '')

const editor = useEditor({
  content: '',
  editable: false,
  extensions: [
    StarterKit.configure({ history: false }),
    TaskList, TaskItem.configure({ nested: true }),
    Table, TableRow, TableHeader, TableCell,
    MindmapNode, FlowchartNode, SpreadsheetNode,
  ],
  editorProps: { attributes: { class: 'm-prose' } },
})
watch(html, (v) => { if (editor.value && v) editor.value.commands.setContent(v, false) })
onBeforeUnmount(() => { try { editor.value?.destroy() } catch { /* ignore */ } })

watch(html, async () => {
  await nextTick()
  const el = editor.value ? editor.value.view.dom : null
  if (!el) return
  absolutizeMedia(el)
  enhanceVideos(el)
  bindImgLightbox(el, (src) => { if (src) lightboxSrc.value = src })
}, { immediate: true })

function fmtDate(s) {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}

function onScroll(e) { scrolled.value = e.target.scrollTop > 32 }

async function onLike() {
  try {
    const { data } = await toggleLike(id)
    liked.value = !!data?.liked
    likeCount.value = Math.max(0, likeCount.value + (liked.value ? 1 : -1))
  } catch { window.__kbMsg?.warning('操作失败，请稍后重试') }
}

async function onFav() {
  try {
    const { data } = await toggleFavorite(id)
    faved.value = !!data?.favorited
    window.__kbMsg?.success(faved.value ? '已收藏' : '已取消收藏')
  } catch { window.__kbMsg?.warning('操作失败，请稍后重试') }
}

onMounted(async () => {
  try {
    const { data } = await getDoc(id)
    doc.value = data
    likeCount.value = data?.likes || 0
    // 收藏 / 点赞状态（失败不阻塞阅读）
    myFavoriteIds().then((r) => { faved.value = (r.data || []).map(Number).includes(id) }).catch(() => {})
    myLikeIds().then((r) => { liked.value = (r.data || []).map(Number).includes(id) }).catch(() => {})
    getRelatedDocs(id, 5).then((r) => { related.value = r.data || [] }).catch(() => {})
  } catch (e) {
    window.__kbMsg?.error('无法打开文章：' + (e.response?.data?.error || e.message || '未知错误'))
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="pv">
    <header class="pv-head" :class="{ solid: scrolled }">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="pv-title" :class="{ show: scrolled }">{{ doc?.title || '' }}</div>
      <button class="m-header-act" @click="onFav">
        <el-icon :size="19" :color="faved ? 'var(--m-warn)' : ''"><component :is="faved ? 'StarFilled' : 'Star'" /></el-icon>
      </button>
    </header>

    <div class="pv-scroll" @scroll="onScroll">
      <div v-if="loading" class="body">
        <div class="m-skel" style="height:26px;width:76%"></div>
        <div class="m-skel" style="height:14px;width:44%;margin-top:12px"></div>
        <div style="margin-top:24px">
          <div v-for="i in 6" :key="i" class="m-skel" style="height:15px;margin-bottom:11px"
               :style="{ width: [95, 88, 92, 74, 90, 62][i - 1] + '%' }"></div>
        </div>
      </div>

      <template v-else-if="doc">
        <div class="body">
          <h1 class="pv-h1">{{ doc.title || '未命名文档' }}</h1>
          <div class="pv-meta">
            <span v-if="doc.category" class="m-chip gray">{{ doc.category }}</span>
            <span>{{ doc.ownerName || '匿名' }}</span>
            <span class="dot">·</span>
            <span>{{ fmtDate(doc.updatedAt || doc.createdAt) }}</span>
            <span class="dot">·</span>
            <span>{{ doc.views || 0 }} 次浏览</span>
          </div>
        </div>

        <div class="body">
          <EditorContent :editor="editor" />
          <p v-if="!html" style="color:var(--m-text-dim)">（空文档）</p>
        </div>

        <!-- 相关推荐 -->
        <div v-if="related.length" class="body">
          <div class="m-sec-title">相关文档</div>
          <div class="m-card">
            <button v-for="r in related" :key="r.id" class="m-row" type="button"
                    @click="router.push('/post/' + r.id)">
              <span class="m-ico blue"><el-icon><Document /></el-icon></span>
              <span class="m-row-body">
                <span class="m-row-title">{{ r.title }}</span>
                <span class="m-row-desc">{{ r.ownerName || '匿名' }} · {{ r.views || 0 }} 次浏览</span>
              </span>
              <el-icon class="m-row-extra"><Right /></el-icon>
            </button>
          </div>
        </div>
        <div style="height:92px"></div>
      </template>

      <div v-else class="m-empty">
        <span class="ico"><el-icon :size="30"><Document /></el-icon></span>
        <div>文章不存在或已下架</div>
        <button class="m-btn sm" style="margin-top:8px" @click="router.back()">返回</button>
      </div>
    </div>

    <!-- 底部操作条 -->
    <div v-if="doc" class="m-actionbar pv-bar">
      <button class="bar-btn" :class="{ on: liked }" @click="onLike">
        <el-icon :size="19"><component :is="liked ? 'StarFilled' : 'Star'" /></el-icon>
        <span>{{ likeCount || '点赞' }}</span>
      </button>
      <button class="bar-btn" :class="{ on: faved }" @click="onFav">
        <el-icon :size="19"><component :is="faved ? 'Check' : 'CollectionTag'" /></el-icon>
        <span>{{ faved ? '已收藏' : '收藏' }}</span>
      </button>
    </div>

    <div v-if="lightboxSrc" class="kb-lightbox" @click="lightboxSrc = ''">
      <img :src="lightboxSrc" alt="" @click.stop />
      <button class="lb-x" type="button" @click="lightboxSrc = ''">×</button>
    </div>
  </div>
</template>

<style scoped>
.pv { height: 100%; display: flex; flex-direction: column; }
.body { padding: 0 18px; }
.pv-head {
  position: sticky; top: 0; z-index: 30; flex: none;
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg); transition: background-color .2s, box-shadow .2s;
}
.pv-head.solid { background: var(--m-card); box-shadow: 0 1px 0 var(--m-border); }
.pv-title {
  flex: 1 1 auto; min-width: 0; text-align: center; font-size: 15.5px; font-weight: 600;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; opacity: 0; transition: opacity .2s;
}
.pv-title.show { opacity: 1; }
.pv-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; -webkit-overflow-scrolling: touch; }
.pv-h1 { font-size: 22px; line-height: 1.38; margin: 4px 0 8px; letter-spacing: -.4px; }
.pv-meta { display: flex; align-items: center; gap: 7px; flex-wrap: wrap; font-size: 12.5px; color: var(--m-text-dim); margin-bottom: 16px; }
.pv-meta .dot { opacity: .5; }
.m-sec-title { padding: 0 0 8px; }

.pv-bar { gap: 10px; }
.bar-btn {
  flex: 1 1 0; height: 44px; border-radius: 12px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  border: 1px solid var(--m-border); background: var(--m-card); color: var(--m-text-sub); font-size: 14.5px;
}
.bar-btn.on { color: var(--m-warn); border-color: var(--m-warn); background: var(--m-card); }

.kb-lightbox { position: fixed; inset: 0; z-index: 3000; background: rgba(0, 0, 0, .92); display: grid; place-items: center; }
.kb-lightbox img { max-width: 100%; max-height: 100%; object-fit: contain; }
.kb-lightbox .lb-x {
  position: absolute; top: calc(env(safe-area-inset-top) + 12px); right: 14px;
  width: 38px; height: 38px; border-radius: 50%; border: none;
  background: rgba(255, 255, 255, .16); color: #fff; font-size: 20px;
}
</style>
