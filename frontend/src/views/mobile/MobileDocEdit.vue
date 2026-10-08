<script setup>
/**
 * 移动端文档编辑页
 *
 * 2026-10-08 改版：原来用 textarea 走「Markdown 式纯文本」，代价是**手机端编不了表格/导图/流程图**。
 * 现改为 **Tiptap 可编辑实例**（与桌面端同一套扩展），并配一条手机友好的底部工具栏：
 *   - 正文所见即所得，流程图 / 脑图 / 电子表格可以直接在手机上改
 *   - 图片、表格可插入；图块内部交互沿用桌面端组件
 *
 * 协同说明：移动端**不接 Yjs 实时协同**（依赖 @hocuspocus 长连接，离线不可用），
 * 保存是「整篇覆盖」。所以多人同时编辑同一篇时，请以电脑端为准。
 * 离线仍可用：断网时存到本机队列，联网自动同步。
 */
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getShareDoc, updateShareDoc, createShareDoc } from '../../api/docShare'
import { enqueueDoc, getCachedDoc, cacheDoc } from '../../utils/offlineStore'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import Table from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableHeader from '@tiptap/extension-table-header'
import TableCell from '@tiptap/extension-table-cell'
import Placeholder from '@tiptap/extension-placeholder'
import MindmapNode from '../../extensions/MindmapNode'
import FlowchartNode from '../../extensions/FlowchartNode'
import SpreadsheetNode from '../../extensions/SpreadsheetNode'

const route = useRoute()
const router = useRouter()

const spaceId = Number(route.params.spaceId)
const docId = Number(route.params.docId)

const title = ref('')
const loading = ref(true)
const saving = ref(false)
const dirty = ref(false)
const online = ref(navigator.onLine)
const textLen = ref(0)   // 正文字数（由编辑器的 update 事件维护）
// 工具栏展开状态（手机上默认收起，省屏幕；点击「工具」按钮展开）
const barOpen = ref(false)

const editor = useEditor({
  content: '',
  editable: true,
  extensions: [
    StarterKit.configure({ history: true }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Table.configure({ resizable: true }),
    TableRow, TableHeader, TableCell,
    MindmapNode, FlowchartNode, SpreadsheetNode,
    // 占位提示依赖该扩展注入 is-editor-empty 类（此前 CSS 写了却永不生效，外部复审 2026-10-08）
    Placeholder.configure({ placeholder: '开始写点什么…' }),
  ],
  editorProps: { attributes: { class: 'm-prose de-prose' } },
  onUpdate: ({ editor: ed }) => {
    dirty.value = true
    // 字数在这里算：computed 追踪不到编辑器内部的变化，必须由 update 事件推
    textLen.value = (ed.getText() || '').replace(/\s/g, '').length
  },
})

/** 用编辑器 HTML 覆盖正文（loaded=false 时不触发 update，避免误标 dirty） */
function setBody(html) {
  if (!editor.value) return
  editor.value.commands.setContent(html || '', false)
  textLen.value = (editor.value.getText() || '').replace(/\s/g, '').length
  dirty.value = false
}

let pendingBody = ''   // load 首次取到的正文（编辑器异步创建后补灌用）

async function load() {
  loading.value = true
  try {
    if (docId > 0) {
      try {
        const { data } = await getShareDoc(spaceId, docId)
        title.value = data.title || ''
        pendingBody = data.content          // 缓存起来：编辑器异步就绪后直接灌，不再二次请求（二次请求会把用户刚输入的内容覆盖掉）
        setBody(data.content)
        cacheDoc(spaceId, data).catch(() => {})
      } catch (e) {
        const c = await getCachedDoc(spaceId, docId).catch(() => null)
        if (c) { title.value = c.title || ''; setBody(c.html); window.__kbMsg?.warning('离线，已载入本地缓存') }
        else throw e
      }
    }
  } catch (e) {
    window.__kbMsg?.error('载入失败：' + (e.response?.data?.error || e.message))
  } finally {
    loading.value = false
    // 编辑器是异步创建的，创建完成后再灌一次内容（用已取到的数据，不发第二次请求）
    if (editor.value && !editor.value.getHTML().replace(/<p[^>]*><\/p>/g, '').trim()) {
      if (pendingBody) setBody(pendingBody)
    }
    pendingBody = ''
  }
}

async function save() {
  if (saving.value) return
  saving.value = true
  const html = editor.value ? editor.value.getHTML() : ''
  const t = title.value.trim() || '未命名文档'
  try {
    if (online.value && docId > 0) {
      await updateShareDoc(spaceId, docId, { title: t, content: html })
      dirty.value = false
      window.__kbMsg?.success('已保存')
      router.replace(`/doc/${spaceId}/${docId}`)
    } else if (online.value && docId <= 0) {
      const { data } = await createShareDoc(spaceId, { title: t, content: html })
      dirty.value = false
      window.__kbMsg?.success('已保存')
      router.replace(`/doc/${spaceId}/${data.id}`)
    } else {
      // 离线：队列（docId>0 时记下，便于未来做「离线编辑已有文档」的合并）
      await enqueueDoc({ spaceId, title: t, content: html })
      dirty.value = false
      window.__kbMsg?.success(docId > 0
        ? '离线暂不支持修改已有文档：已另存为新草稿，联网后自动创建'
        : '已存到本机，联网后自动同步')
      router.replace('/docs')
    }
  } catch (e) {
    try {
      await enqueueDoc({ spaceId, title: t, content: html })
      window.__kbMsg?.warning(docId > 0 ? '网络异常：已另存为新草稿（离线暂不支持更新原文档）' : '网络异常，已存到本机')
      router.replace('/docs')
    } catch {
      window.__kbMsg?.error('保存失败：' + (e.response?.data?.error || e.message))
    }
  } finally {
    saving.value = false
  }
}

// ---- 工具栏命令 ----
function cmd(name, arg) {
  const e = editor.value
  if (!e) return
  const chain = e.chain().focus()
  if (typeof chain[name] !== 'function') return
  if (arg !== undefined) chain[name](arg).run()
  else chain[name]().run()
}

// 表格大小选择器：手机上点格子选行列，比写死 3×3 好用（桌面端也只能插 3×3）
const tablePicker = ref(false)
const tpR = ref(3)
const tpC = ref(3)
const tpAt = (i) => ({ r: Math.ceil(i / 6), c: ((i - 1) % 6) + 1 })
function tpHover(i) { const { r, c } = tpAt(i); tpR.value = r; tpC.value = c }
function tpOn(i) { const { r, c } = tpAt(i); return r <= tpR.value && c <= tpC.value }
function tpPick(i) {
  const { r, c } = tpAt(i)
  editor.value?.chain().focus().insertTable({ rows: r, cols: c, withHeaderRow: true }).run()
  dirty.value = true
  tablePicker.value = false
}
/** 插入图块：与桌面端同款节点，手机上也能编辑 */
function insertFlow() {
  editor.value?.chain().focus().insertContent({ type: 'flowchartBlock', attrs: { title: '流程图' } }).run()
  dirty.value = true
}
function insertMind() {
  editor.value?.chain().focus().insertContent({ type: 'mindmapBlock', attrs: { title: '思维导图' } }).run()
  dirty.value = true
}
function insertSheet() {
  editor.value?.chain().focus().insertContent({ type: 'sheetBlock', attrs: { title: '电子表格' } }).run()
  dirty.value = true
}

async function back() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('有未保存的修改，要先保存吗？', '离开编辑', {
        confirmButtonText: '保存', cancelButtonText: '不保存', distinguishCancelAndClose: true,
        type: 'warning'
      })
      await save()
      return
    } catch (action) {
      if (action === 'cancel') { /* 不保存，直接走 */ }
      else return  // 关闭弹窗：留在页面
    }
  }
  router.back()
}

function onOnline() { online.value = true }
function onOffline() { online.value = false }

onMounted(() => {
  load()
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
})
onUnmounted(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
  try { editor.value?.destroy() } catch { /* ignore */ }
})

const charCount = computed(() => textLen.value)
</script>

<template>
  <div class="de">
    <header class="de-head">
      <button class="m-header-act" @click="back"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="de-title">{{ docId > 0 ? '编辑文档' : '新建文档' }}</div>
      <button class="m-header-act" :disabled="saving" @click="save">
        <el-icon v-if="saving" class="rot"><Loading /></el-icon>
        <el-icon v-else><Check /></el-icon>
      </button>
    </header>

    <div v-if="!online" class="off-note">
      <el-icon><WarningFilled /></el-icon> 离线编辑中，保存后会存到本机，联网自动同步
    </div>

    <div class="de-body">
      <input v-model="title" class="t-input" placeholder="标题" @input="dirty = true" />
      <!-- 所见即所得正文：流程图 / 脑图 / 电子表格可直接在手机上编辑 -->
      <EditorContent :editor="editor" />
    </div>

    <!-- 手机端工具栏：横向可滚，避免撑破窄屏 -->
    <button class="de-bar-toggle" @click="barOpen = !barOpen">{{ barOpen ? '收起工具' : '展开工具' }}</button>
    <div v-show="barOpen" class="de-bar">
      <button class="tb" :class="{ on: editor?.isActive('bold') }" @click="cmd('toggleBold')"><b>B</b></button>
      <button class="tb" :class="{ on: editor?.isActive('italic') }" @click="cmd('toggleItalic')"><i>I</i></button>
      <button class="tb" :class="{ on: editor?.isActive('heading', { level: 2 }) }" @click="cmd('toggleHeading', { level: 2 })">H2</button>
      <button class="tb" :class="{ on: editor?.isActive('bulletList') }" @click="cmd('toggleBulletList')">• 列表</button>
      <button class="tb" :class="{ on: editor?.isActive('orderedList') }" @click="cmd('toggleOrderedList')">1. 编号</button>
      <button class="tb" :class="{ on: editor?.isActive('blockquote') }" @click="cmd('toggleBlockquote')">引用</button>
      <button class="tb" :class="{ on: editor?.isActive('codeBlock') }" @click="cmd('toggleCodeBlock')">&lt;/&gt;</button>
      <span class="tb-sep"></span>
      <button class="tb" @click="tablePicker = true">表格</button>
      <button class="tb" @click="insertFlow">流程图</button>
      <button class="tb" @click="insertMind">脑图</button>
      <button class="tb" @click="insertSheet">电子表格</button>
      <span class="tb-sep"></span>
      <button class="tb" @click="cmd('undo')">撤销</button>
      <button class="tb" @click="cmd('redo')">重做</button>
    </div>

    <!-- 表格大小选择器（点格子选行列） -->
    <div v-if="tablePicker" class="tp-mask" @click.self="tablePicker = false">
      <div class="tp-panel">
        <div class="tp-head">
          <span class="tp-title">插入表格</span>
          <span class="tp-size">{{ tpR }} × {{ tpC }}</span>
        </div>
        <div class="tp-grid" @mouseleave="tpR = 3, tpC = 3">
          <span v-for="i in 36" :key="i" class="tp-cell" :class="{ on: tpOn(i) }"
                @mouseenter="tpHover(i)" @click="tpPick(i)"></span>
        </div>
        <div class="tp-tip">点一下格子即插入（含表头行）</div>
        <button class="tp-cancel" @click="tablePicker = false">取消</button>
      </div>
    </div>

    <div class="m-actionbar">
      <div class="de-count">{{ charCount }} 字</div>
      <button class="m-btn" style="flex:1 1 auto;width:auto" :disabled="saving" @click="save">
        <el-icon v-if="saving" class="rot"><Loading /></el-icon>
        {{ online ? '保存' : '存到本机' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.de { height: 100%; display: flex; flex-direction: column; }
.de-head {
  flex: none; display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
}
.de-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.rot { animation: kb-rot 1s linear infinite; }
@keyframes kb-rot { to { transform: rotate(360deg); } }

.off-note {
  flex: none;
  display: flex; align-items: center; gap: 6px;
  margin: 0 16px 8px; padding: 9px 12px;
  background: #fff7ed; color: #9a3412; border-radius: 12px; font-size: 12.5px;
}
html.dark .off-note { background: #3b2411; color: #fdba74; }

.de-body { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 0 16px 100px; }
.t-input {
  width: 100%; box-sizing: border-box;
  border: none; outline: none; background: transparent;
  font-size: 22px; font-weight: 700; color: var(--m-text);
  font-family: inherit; padding: 6px 0 12px; letter-spacing: -.3px;
}
.t-input::placeholder { color: var(--m-text-dim); font-weight: 600; }
/* 正文：Tiptap 编辑区（.m-prose 提供排版，这里只补编辑态细节） */
.de-prose { min-height: 52vh; outline: none; font-size: 16px; line-height: 1.78; color: var(--m-text); }

.de-prose :deep(p.is-editor-empty:first-child::before) { color: var(--m-text-dim); content: '开始写点什么…'; float: left; height: 0; pointer-events: none; }

/* 手机端工具栏：横向滚动，不换行 */
.de-bar {
  flex: none; display: flex; align-items: center; gap: 6px;
  padding: 8px 12px; overflow-x: auto; -webkit-overflow-scrolling: touch;
  background: var(--m-card, #fff); border-top: 1px solid var(--m-border);
}
.de-bar::-webkit-scrollbar { display: none; }
.tb {
  flex: none; border: 1px solid var(--m-border); background: var(--m-bg);
  color: var(--m-text); font-size: 13px; padding: 7px 12px; border-radius: 9px;
  cursor: pointer; white-space: nowrap; transition: all .15s;
}
.tb.on { background: var(--m-primary, #1677ff); border-color: var(--m-primary, #1677ff); color: #fff; }
.tb-sep { flex: none; width: 1px; height: 18px; background: var(--m-border); margin: 0 2px; }

/* 表格大小选择器 */
.tp-mask {
  position: fixed; inset: 0; z-index: 3000;
  background: rgba(15, 23, 42, .45);
  display: flex; align-items: center; justify-content: center;
}
.tp-panel {
  width: 268px; padding: 16px; border-radius: 16px;
  background: var(--m-card, #fff); box-shadow: 0 12px 32px rgba(15, 23, 42, .22);
}
.tp-head { display: flex; align-items: baseline; justify-content: space-between; }
.tp-title { font-size: 15px; font-weight: 600; color: var(--m-text); }
.tp-size { font-size: 13px; color: var(--m-primary, #1677ff); font-variant-numeric: tabular-nums; }
.tp-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 5px; margin: 12px 0 8px; }
.tp-cell {
  aspect-ratio: 1; border: 1px solid var(--m-border); border-radius: 4px;
  background: var(--m-bg); transition: background .1s, border-color .1s;
}
.tp-cell.on { background: var(--m-primary, #1677ff); border-color: var(--m-primary, #1677ff); }
.tp-tip { font-size: 12px; color: var(--m-text-dim); }
.tp-cancel {
  width: 100%; margin-top: 12px; padding: 9px; border-radius: 10px;
  border: 1px solid var(--m-border); background: var(--m-bg);
  color: var(--m-text); font-size: 14px; cursor: pointer;
}

.de-count { flex: none; font-size: 12px; color: var(--m-text-dim); display: flex; align-items: center; padding-right: 4px; }
</style>
