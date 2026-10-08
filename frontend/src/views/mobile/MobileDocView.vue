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
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { enhanceVideos, bindImgLightbox, absolutizeMedia } from '../../utils/mediaEnhance'
import { useRoute, useRouter } from 'vue-router'
import { getShareDoc } from '../../api/docShare'
import { getCachedDoc, cacheDoc } from '../../utils/offlineStore'
// 只读渲染用 Tiptap 实例：流程图 / 脑图 / 电子表格是 Vue 组件节点（NodeView），
// v-html 渲染不出来（会显示成空白），必须走编辑器内核才能画出来。
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

const spaceId = Number(route.params.spaceId)
const docId = Number(route.params.docId)

const doc = ref(null)
const loading = ref(true)
const fromCache = ref(false)
const scrolled = ref(false)

const html = computed(() => doc.value?.content || '')

// 只读编辑器：只为把图块（流程图 / 脑图 / 电子表格）正确画出来，不参与协同、不可编辑
const editor = useEditor({
  content: '',
  editable: false,
  extensions: [
    StarterKit.configure({ history: false }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Table, TableRow, TableHeader, TableCell,
    MindmapNode, FlowchartNode, SpreadsheetNode,
  ],
  editorProps: { attributes: { class: 'm-prose' } },
})
// 内容到位后灌进编辑器（setContent 第二个参数 false = 不触发 update/协同）
watch(html, (v) => {
  if (editor.value && v) editor.value.commands.setContent(v, false)
})
onBeforeUnmount(() => { try { editor.value?.destroy() } catch { /* ignore */ } })

// 正文里的图块类型——本页 v-html 渲染不了（它们是 Vue 组件节点），需要去编辑器看
const blockKinds = computed(() => {
  const h = html.value || ''
  const out = []
  if (/data-type="flowchart-block"/.test(h)) out.push('流程图')
  if (/data-type="mindmap-block"/.test(h)) out.push('脑图')
  if (/data-type="sheet-block"/.test(h)) out.push('电子表格')
  return out
})
function openInEditor() {
  const sid = route.params.spaceId
  const did = route.params.docId
  window.location.href = `/doc-shares/${sid}?doc=${did}`
}

// 图片灯箱：点正文图片全屏看原图
const lightboxSrc = ref('')

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

// 正文渲染后：图片点开放大 + 视频增强（16:9 占位防跳变、倍速/全屏/画中画控制条）
// 渲染根是 Tiptap 的 ProseMirror DOM（EditorContent），不是旧 v-html 容器——
// 旧 proseRef 已随模板删除，必须改取 editor.view.dom，否则灯箱/视频增强/媒体绝对化全部失效（外部复审 2026-10-08）
watch(html, async () => {
  await nextTick()
  const el = editor.value ? editor.value.view.dom : null
  if (!el) return
  absolutizeMedia(el)
  enhanceVideos(el)
  bindImgLightbox(el, (src) => { if (src) lightboxSrc.value = src })
}, { immediate: true })

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
          <!-- 走 Tiptap 只读实例渲染：这样流程图 / 脑图 / 电子表格这类组件节点才能画出来 -->
          <EditorContent :editor="editor" />
          <p v-if="!html" style="color:var(--m-text-dim)">（空文档）</p>
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

    <!-- 图片灯箱：点正文图片全屏预览，点任意处关闭 -->
    <div v-if="lightboxSrc" class="kb-lightbox" @click="lightboxSrc = ''">
      <img :src="lightboxSrc" alt="" @click.stop />
      <button class="lb-x" type="button" @click="lightboxSrc = ''">×</button>
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
/* 图块提示条 */
.dv-blocks {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin: -8px 0 16px; padding: 9px 12px; border-radius: 10px;
  background: var(--m-card-2); border: 1px solid var(--m-border);
}
.dv-blocks-t { flex: 1 1 auto; font-size: 12.5px; color: var(--m-text-dim); }
.dv-blocks-btn {
  flex: none; border: none; border-radius: 8px; padding: 6px 12px;
  background: var(--m-primary); color: #fff; font-size: 12.5px; cursor: pointer;
}

/* 图片灯箱（.kb-video-bar 样式在全局 responsive.css，v-html 动态节点吃不到 scoped） */
.kb-lightbox {
  position: fixed; inset: 0; z-index: 3000;
  background: rgba(0, 0, 0, .92);
  display: grid; place-items: center;
}
.kb-lightbox img { max-width: 100%; max-height: 100%; object-fit: contain; }
.kb-lightbox .lb-x {
  position: absolute; top: calc(env(safe-area-inset-top) + 12px); right: 14px;
  width: 38px; height: 38px; border-radius: 50%; border: none;
  background: rgba(255, 255, 255, .16); color: #fff; font-size: 20px;
}
</style>
