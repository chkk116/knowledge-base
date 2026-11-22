<script setup>
import { ref, computed, onBeforeUnmount, watch, onMounted } from 'vue'
import { useUnsavedGuard } from '../composables/useUnsavedGuard'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Placeholder from '@tiptap/extension-placeholder'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import Table from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableHeader from '@tiptap/extension-table-header'
import TableCell from '@tiptap/extension-table-cell'
import Collaboration from '@tiptap/extension-collaboration'
import CollaborationCursor from '@tiptap/extension-collaboration-cursor'
import { RefreshLeft, RefreshRight, ArrowDown, Share, QuestionFilled, Grid } from '@element-plus/icons-vue'
import { driver } from 'driver.js'
import 'driver.js/dist/driver.css'
import * as Y from 'yjs'
import { HocuspocusProvider } from '@hocuspocus/provider'
import { ElMessage, ElMessageBox } from 'element-plus'
import { updateShareDoc, saveShareDocVersion, listShareDocVersions, restoreShareDocVersion, deleteShareDocVersion } from '../api/docShare'
import { useAuthStore } from '../store/auth'
import DocDiffDialog from '../components/DocDiffDialog.vue'
import MindmapNode from '../extensions/MindmapNode'
import FlowchartNode from '../extensions/FlowchartNode'
import SpreadsheetNode from '../extensions/SpreadsheetNode'
import { fromNow, fmtDateTime } from '../utils/time'
import { exportDocument, buildSnapshotHtml } from '../features/docShare/exportDoc'
import { errMsg } from '../utils/errMsg'

const props = defineProps({
  shareId: { type: Number, required: true },
  docId: { type: Number, required: true },
  revision: { type: Number, default: 0 },
  canWrite: { type: Boolean, default: false },
  // 旧版 HTML 内容，仅用于首次协同时把历史内容种入 Yjs 文档（一次性迁移）
  legacyContent: { type: String, default: '' },
  // 图块专属模式：'mindmap' | 'flowchart' | 'sheet'——整篇文档就是单个图块时，
  // 右侧全屏渲染该组件（隐藏文字工具栏、去掉纸张版面、画布拉满）。
  // 专属文档不再提供「转为普通文档」（转完图块数据会丢，且要混排可直接新建普通文档插图块）。空串 = 普通文档。
  diagram: { type: String, default: '' },
})

const emit = defineEmits(['reload-document'])
const auth = useAuthStore()
const meName = computed(() => auth.user?.displayName || auth.user?.username || '我')

// 中继地址：优先 VITE_COLLAB_WS 覆盖；**留空则自动跟随当前访问域名**。
// 自动推导规则（.env.production / .env.test 默认留空，因此走这里）：
//   https 页面 → wss://{当前域名}/collab/   经 Nginx 反代升级为 wss。
//                 https 下若用 ws:// 会被浏览器按混合内容拦截，表现为协同"静默连不上"。
//   http  页面 → ws://{当前域名}:1234       内网直连 / 本地开发保持原有行为。
// 这样前端 build 一份产物即可在任意域名（www / test / 未来换域名）下使用，换域名无需重新打包。
const collabScheme = location.protocol === 'https:' ? 'wss' : 'ws'
const wsOverride = (import.meta.env.VITE_COLLAB_WS || '').trim()
const WS_URL = wsOverride
  || (location.protocol === 'https:'
    ? collabScheme + '://' + location.host + '/collab/'
    : collabScheme + '://' + location.hostname + ':1234')
const token = localStorage.getItem('kb_token') || ''
const authExpired = !token   // 登录过期/未登录：不建协同连接，页面提示重新登录

const status = ref('connecting') // connecting | connected | disconnected
const peers = ref([])
const saving = ref(false)
const dirty = ref(false)       // 有本地改动、尚未写入后端快照
// 只读打开时也弹「未保存」会让人以为丢东西：只读本来就不能保存，dirty 只是本地缓存了远端更新
useUnsavedGuard(() => props.canWrite && dirty.value, '文档还有未同步的修改，确定离开吗？')
const seeded = ref(false)
let seedRetries = 0
const wordCount = ref(0)

// 断线重连兜底：浏览器后台标签页会冻结 WS 心跳，中继侧超时断开后，
// HocuspocusProvider 的自带重连在后台往往发不出心跳，切回前台也不会自动恢复，
// 表现为"切走再切回就断了、得切文档靠组件重建才连上"。这里手动兜底：
//   1) 断开后延迟重连；
//   2) 标签页重新可见时立即重连。
let destroyed = false
let reloadRequested = false
let authRejected = false
const relayCanWrite = ref(false)
let reconnectTimer = null
function requestDocumentReload() {
  if (destroyed || reloadRequested) return
  reloadRequested = true
  if (reconnectTimer) clearTimeout(reconnectTimer)
  try { provider.disconnect() } catch (e) { /* disconnecting */ }
  emit('reload-document', props.docId)
}
function scheduleReconnect(delay = 1500) {
  if (destroyed || reloadRequested || authRejected) return
  if (reconnectTimer) clearTimeout(reconnectTimer)
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null
    if (destroyed) return
    try { provider.connect() } catch (e) { /* ignore */ }
  }, delay)
}
function onVisibility() {
  if (document.visibilityState === 'visible' && status.value !== 'connected') {
    try { provider.connect() } catch (e) { /* ignore */ }
  }
}

const CURSOR_COLORS = ['#f43f5e', '#f59e0b', '#10b981', '#3b82f6', '#8b5cf6', '#ec4899', '#14b8a6', '#ef4444']
function colorFor(seed) {
  let h = 0
  for (const c of String(seed)) h = (h * 31 + c.charCodeAt(0)) >>> 0
  return CURSOR_COLORS[h % CURSOR_COLORS.length]
}

// 创建 Yjs 文档与 Hocuspocus 中继连接。
// documentName 规则（与 collab-relay/index.js#parseName 严格对齐）：
//   revision = 0 → `${shareId}:${docId}`（**必须省略第三段**，中继正则第三段是 [1-9]\d*，
//                  写成 `:0` 会被判 invalid document 直接拒连）
//   revision > 0 → `${shareId}:${docId}:${revision}`
// revision 由后端 yjs_rev 提供；回滚历史版本时后端把它自增，旧 revision 的房间会被中继
// 按 yjs_rev 比对判为「document revision changed」拒连，从而强制以新 revision 重新加载恢复后的内容。
// 此前这里写死 `${shareId}:${docId}`，回滚后（yjs_rev 已 >0）还是连旧房间 → 页面内容不变。
const docName = (rev) => {
  const r = Number(rev) || 0
  return r > 0 ? `${props.shareId}:${props.docId}:${r}` : `${props.shareId}:${props.docId}`
}
const ydoc = new Y.Doc()
const provider = authExpired ? null : new HocuspocusProvider({
  url: WS_URL,
  name: docName(props.revision),
  token,
  document: ydoc,
  onStatus: (e) => {
    status.value = e.status
    // 只读端每次（重）连接后重建 awareness：只写 user（供表格块显示在线状态与他人看到的
    // 「正在查看」位置），不广播文本光标/选区，避免造成「别人能移动你的光标」的错觉。
    if (e.status === 'connected' && !props.canWrite) {
      try {
        // 注意 y-protocols 语义：setLocalState(null) 清空后 setLocalStateField 是 no-op
        // （getLocalState()===null 直接 return），必须一次性写入，否则 user 永远广播不出去
        provider.awareness.setLocalState({
          user: {
            name: meName.value,
            color: colorFor(meName.value),
            avatar: auth.user?.avatar || '',
          },
        })
      } catch (err) { /* ignore */ }
    }
    // 断线后兜底重连（后台标签页 WS 被冻结后切回前台不会自动恢复）
    if (e.status === 'disconnected') scheduleReconnect()
  },
  onAwarenessUpdate: ({ states }) => {
    peers.value = states.filter((s) => s.user).map((s) => s.user)
  },
  onSynced: () => seedIfEmpty(),
})

function seedIfEmpty() {
  // 只读端绝不做 setContent：否则会修改协同文档并推送更新（即便中继拦截，本地也会脏写）
  if (seeded.value || !props.canWrite) return
  // editor 是异步创建的：onSynced（本地中继，很快）可能早于 editor 就绪——
  // 此前一次性 latch 会在这里永久跳过 → 图块文档打开后一直是空白。改为延迟重试（不置 latch）。
  if (!editor.value) {
    if (++seedRetries > 20) { seeded.value = true; return }
    setTimeout(seedIfEmpty, 300)
    return
  }
  seeded.value = true
  if (editor.value.isEmpty && props.legacyContent && props.legacyContent.trim()) {
    // 把旧版 HTML / 新建种子一次性注入协同文档，随后由中继落库为 yjs_state
    editor.value.commands.setContent(props.legacyContent)
  }
}

const editor = useEditor({
  editable: props.canWrite,
  extensions: [
    StarterKit.configure({ history: false }), // 协同模式禁用本地 history，由 Yjs 管理撤销
    Placeholder.configure({ placeholder: props.canWrite ? '开始输入正文，邀请的成员会实时看到你的编辑…' : '你当前为只读，无法编辑此文档' }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Table.configure({ resizable: true }),
    TableRow,
    TableHeader,
    TableCell,
    Collaboration.configure({ document: ydoc }),
    MindmapNode,   // 思维导图块（Vue Flow NodeView + 编辑锁，协同经 Yjs 属性同步）
    FlowchartNode, // 流程图块（多形状 + 连线 + 连线标签，同款编辑锁与 Yjs 同步）
    SpreadsheetNode, // 电子表格块（x-data-spreadsheet 引擎，公式/填充/冻结，同款编辑锁与 Yjs 同步）
    // 只读（canWrite=false）时不挂载协同光标扩展：
    // 避免本地光标/选区被广播到其他协作者屏幕上，造成「别人能移动你的光标」的错觉，
    // 同时只读端本身也不应产生可编辑光标。
    ...(props.canWrite && provider ? [CollaborationCursor.configure({
      provider,
      user: { name: meName.value, color: colorFor(meName.value), avatar: auth.user?.avatar || '' },
    })] : []),
  ],
  editorProps: {
    attributes: { class: 'kb-collab-body' },
  },
  onUpdate: ({ transaction }) => {
    // 远端同步进来的改动不算「本地未保存」：否则别人在编辑（或自己只是围观）时，
    // 离开页面也会被「还有未同步的修改」拦住，刷新/等待都消不掉（N43）。
    // y-prosemirror 给这类事务打了 y-sync$ 元数据；本地输入不经过它，撤销重做会带 isUndoRedoOperation。
    const ys = transaction?.getMeta?.('y-sync$')
    if (ys && ys.isChangeOrigin && !ys.isUndoRedoOperation) {
      const t = editor.value?.getText() || ''
      wordCount.value = t.replace(/\s/g, '').length
      return
    }
    dirty.value = true
    const text = editor.value?.getText() || ''
    wordCount.value = text.replace(/\s/g, '').length
    scheduleAutoSnapshot()
  },
  // 把 provider 暴露给 NodeView：表格块需要 awareness 来画远端光标与在线状态。
  // 不能只依赖 CollaborationCursor —— 它仅在 canWrite 时挂载，只读端会完全拿不到 awareness。
  onCreate: ({ editor: ed }) => {
    try {
      ed.storage.kbCollab = { provider }
      // 只读端不挂 CollaborationCursor（避免广播文本光标），但表格块的远端光标依赖 awareness，
      // 因此这里单独写入 user，让只读端也能被别人看到「正在查看哪个格子」。
      if (provider && !props.canWrite) {
        provider.awareness.setLocalStateField('user', {
          name: meName.value,
          color: colorFor(meName.value),
          avatar: auth.user?.avatar || '',
        })
      }
      // 协同内容同步完成后补一次快照：老文档（导图/流程图/表格）的 content 列是空的，
      // 只靠「编辑后触发」永远补不上，打开一次就补齐。
      if (provider && props.canWrite) {
        provider.on('synced', () => { scheduleAutoSnapshot() })
      }
    } catch (e) { /* ignore */ }
  },
})

const statusText = computed(() => ({
  connected: '已连接', connecting: '连接中…', disconnected: '连接已断开',
}[status.value] || status.value))

// 保存状态（N50）：原来只有「已连接」，用户分不清「连接着」和「存下来了」
const lastSavedAt = ref(0)
const saveText = computed(() => {
  if (!props.canWrite) return ''
  if (saving.value) return '保存中…'
  if (dirty.value) return '有未保存修改'
  if (!lastSavedAt.value) return ''
  const d = new Date(lastSavedAt.value)
  const p = (n) => String(n).padStart(2, '0')
  return '已保存 ' + p(d.getHours()) + ':' + p(d.getMinutes())
})

// ---------- 工具栏 ----------
function cmd(name) {
  const e = editor.value
  if (!e) return
  const c = e.chain().focus()
  switch (name) {
    case 'undo': c.undo().run(); break
    case 'redo': c.redo().run(); break
    case 'paragraph': c.setParagraph().run(); break
    case 'h1': c.toggleHeading({ level: 1 }).run(); break
    case 'h2': c.toggleHeading({ level: 2 }).run(); break
    case 'h3': c.toggleHeading({ level: 3 }).run(); break
    case 'bold': c.toggleBold().run(); break
    case 'italic': c.toggleItalic().run(); break
    case 'strike': c.toggleStrike().run(); break
    case 'inlineCode': c.toggleCode().run(); break
    case 'bullet': c.toggleBulletList().run(); break
    case 'ordered': c.toggleOrderedList().run(); break
    case 'task': c.toggleTaskList().run(); break
    case 'quote': c.toggleBlockquote().run(); break
    case 'codeBlock': c.toggleCodeBlock().run(); break
    case 'mindmap': c.insertMindmapBlock({ title: '思维导图' }).run(); break
    case 'flowchart': c.insertFlowchartBlock({ title: '流程图' }).run(); break
    case 'sheet': c.insertSheetBlock({ title: '电子表格' }).run(); break
    case 'hr': c.setHorizontalRule().run(); break
    case 'clear': c.unsetAllMarks().clearNodes().run(); break
    case 'table': c.insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run(); break
    case 'addRow': c.addRowAfter().run(); break
    case 'addColumn': c.addColumnAfter().run(); break
    case 'delRow': c.deleteRow().run(); break
    case 'delColumn': c.deleteColumn().run(); break
    case 'delTable': c.deleteTable().run(); break
  }
}

// 注意：attrs 不传时保持 undefined（Tiptap isActive 的默认参数只认 undefined，传 null 会崩）
const on = (name, attrs) => !!editor.value?.isActive(name, attrs)

/**
 * 图块（导图 / 流程图 / 电子表格）在静态 HTML 里只剩一个空 div —— 图形是 Vue 组件实时画的，
 * 而且 Vue Flow 的节点是 HTML div（只有连线是 <svg>），直接抓 svg 抓不到节点。
 *
 * 这里用 html-to-image 把整个图块容器转成 data:image/svg+xml 的 <img>：
 * 非协同预览、导出、App 阅读页都能直接显示 —— App 的 needsRichWebView 本来就认 <img>，
 * 所以原生端一行都不用改。
 */

let autoSnapshotTimer = null

// silent：自动快照不弹提示，否则每几秒跳一次 toast
async function saveSnapshot(silent = false) {
  if (!props.canWrite || !editor.value) return
  if (!silent) saving.value = true
  try {
    // 带上打开时的代次：别人刚改过会收到 409，提示刷新，而不是静默整篇覆盖
    await updateShareDoc(props.shareId, props.docId, {
      content: await buildSnapshotHtml(editor.value),
      baseRev: props.revision || 0,
    })
    dirty.value = false
    lastSavedAt.value = Date.now()
    if (!silent) ElMessage.success('已保存快照')
  } catch (e) {
    if (!silent) ElMessage.error(errMsg(e, '保存失败'))
  } finally {
    if (!silent) saving.value = false
  }
}

/**
 * 自动快照：原来只有工具栏那个按钮会写 content，没人点就一直是空 ——
 * 线上 3 篇导图/流程图/表格文档 content 长度全是 0，App 打开就是白屏。
 */
function scheduleAutoSnapshot() {
  if (!props.canWrite) return
  clearTimeout(autoSnapshotTimer)
  autoSnapshotTimer = setTimeout(() => { saveSnapshot(true) }, 6000)
}

// ---------- 导出：Markdown / HTML / Excel（实现在 features/docShare/exportDoc） ----------
function onExport(command) {
  exportDocument(command, editor.value, props.docId)
}

// ---------- 共享文档版本历史 ----------
const versionDrawer = ref(false)

// 版本对比
const diffDlg = ref(false)
const diffOld = ref('')
const diffNew = ref('')
const diffOldLabel = ref('')
function compareVersion(v) {
  diffOld.value = v.content || ''
  diffNew.value = editor.value ? editor.value.getHTML() : ''
  diffOldLabel.value = '#' + v.id + ' · ' + fmtDateTime(v.createdAt)
  diffDlg.value = true
}
const versions = ref([])
const versionLoading = ref(false)
const savingVersion = ref(false)

async function openVersions() {
  versionDrawer.value = true
  await loadVersions()
}
async function loadVersions() {
  versionLoading.value = true
  try {
    versions.value = (await listShareDocVersions(props.shareId, props.docId)).data || []
  } catch (e) {
    ElMessage.error(errMsg(e, '加载版本失败'))
  } finally {
    versionLoading.value = false
  }
}
async function saveCurrentVersion() {
  if (!props.canWrite) { ElMessage.warning('只读模式不可保存版本'); return }
  savingVersion.value = true
  try {
    await saveShareDocVersion(props.shareId, props.docId)
    ElMessage.success('已保存当前版本')
    await loadVersions()
  } catch (e) {
    ElMessage.error(errMsg(e, '保存版本失败'))
  } finally {
    savingVersion.value = false
  }
}
async function restoreVersion(v) {
  try {
    await ElMessageBox.confirm(
      '将把文档内容替换为该历史版本。注意：回滚不会自动备份当前内容，如需保留请先点「保存当前版本」。',
      '回滚版本', { type: 'warning' })
  } catch { return }
  try {
    // 后端会写回版本内容并把 yjs_rev 自增。
    await restoreShareDocVersion(props.shareId, props.docId, v.id)
    ElMessage.success('已恢复，正在重新加载文档…')
    versionDrawer.value = false
    // 通知父组件重拉文档：父组件的 :key 含 yjsRev，revision 变化会整体重建本组件
    // （新 Y.Doc + 新 provider + documentName 带新 revision），从而真正加载到恢复后的协同内容。
    // 不能只做 provider.disconnect()/connect()：那样 documentName 仍是旧 revision 的房间，
    // 中继按 yjs_rev 比对会判 document revision changed 拒连 → 页面内容不变。
    emit('reload-document', props.docId)
  } catch (e) {
    ElMessage.error(errMsg(e, '恢复失败'))
  }
}
/** 删除某个历史版本（只移除快照，不影响文档当前内容） */
async function removeVersion(v) {
  if (!props.canWrite) { ElMessage.warning('只读模式不可删除版本'); return }
  try {
    await ElMessageBox.confirm(
      `确定删除版本 #${v.id}（${v.title || '无标题'}）？该快照将被永久移除，不影响文档当前内容。`,
      '删除版本', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' })
  } catch { return }
  try {
    await deleteShareDocVersion(props.shareId, props.docId, v.id)
    ElMessage.success('已删除该版本')
    await loadVersions()
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}
const versionSnippet = (html) => {
  if (!html) return ''
  // DOMParser 替代 div.innerHTML（H9：游离 img 仍会加载触发 onerror）
  const t = (new DOMParser().parseFromString(html, 'text/html').body.textContent || '').replace(/\s+/g, ' ').trim()
  return t.length > 100 ? t.slice(0, 100) + '…' : t
}

function goLogin() {
  localStorage.removeItem('kb_token')
  location.href = '/login?redirect=' + encodeURIComponent(location.pathname)
}

// 漫游式功能引导（driver.js）：普通文档讲编辑器全貌；图块专属文档（导图/流程图/电子表格）
// 讲对应画布的操作。**只在用户点工具栏「引导」时播放，不自动弹出**（避免每次进文档被打断）。
/** 图块专属文档的引导步骤（锚点：块的「编辑」按钮 + 居中说明 + 转普通文档入口） */
function diagramTourSteps() {
  const kind = props.diagram
  const wrapSel = { mindmap: '.kb-mindmap-wrap', flowchart: '.kb-fc-wrap', sheet: '.kb-sheet-wrap' }[kind]
  const meta = {
    mindmap: { name: '思维导图', ops: '进入编辑后：双击节点或按 F2 改文字；Tab 建子主题（向右延伸）、Enter 建同级；选中根节点按 Enter 或点「+ 自由主题」＝互不相连的独立主题；节点可自由拖拽，Delete 删掉整个子树；工具条「?」随时可看操作说明。编辑期间其他人只读。' },
    flowchart: { name: '流程图', ops: '进入编辑后：左侧图形面板提供 12 种标准图形（矩形 / 菱形 / 椭圆 / 文档 / 数据库 / 子流程 等），点选即可添加、按住可拖到画布落点摆放；从图形右侧/底部圆点拖到另一图形左侧/顶部圆点连线；点连线可加「是/否」标签；工具条右下角「?」随时可看操作说明。编辑期间其他人只读。' },
    sheet: { name: '电子表格', ops: '进入编辑后：与 Excel 同款操作——单元格直接输公式（=SUM(...)）、拖动选区右下角填充、复制粘贴、合并冻结；顶部工具栏可设字体、填充色和数字格式（RMB / 百分比 / 日期等，选格式前先选中目标格子）。' },
  }[kind]
  const btns = Array.from(document.querySelectorAll(wrapSel + ' button'))
  const editBtn = btns.find((b) => b.textContent.trim().startsWith('编辑'))
  const helpBtn = btns.find((b) => b.classList.contains('help'))
  const steps = [
    { popover: { title: meta.name + ' · 专属文档', description: '这篇文档整页就是一张' + meta.name + '画布，右侧即为画布本体，无需插入操作。' } },
  ]
  if (editBtn) {
    steps.push({ element: editBtn, popover: { title: '第一步：点「编辑」', description: '获得编辑锁后才能修改——同一时刻仅一人可编辑，其他人只读实时查看。' } })
  }
  // 电子表格：编辑态下逐个讲解工具栏功能（用户重点）；未编辑时给简版并提示进入编辑后再看
  if (kind === 'sheet') {
    const tb = document.querySelector('.kb-sheet-canvas .x-spreadsheet-toolbar')
    const editingNow = !!tb && tb.offsetParent !== null   // display:none 判定编辑态
    if (editingNow) {
      const tip = (name) => document.querySelector(`.kb-sheet-wrap [data-tooltip="${name}"]`)
      const headers = document.querySelectorAll('.kb-sheet-wrap .x-spreadsheet-dropdown-header')
      const push = (elm, title, desc) => { if (elm) steps.push({ element: elm, popover: { title, description: desc } }) }
      push(tip('Undo (Ctrl+Z)'), '撤销 / 重做', '操作错了随时回退；旁边是重做，再往右是打印。')
      if (headers[0]) push(headers[0], '数字格式', '把选中格子设为数字 / 货币 / 百分比 / 日期等格式。注意：选格式前先选中目标格子（输完数字按回车后选中框会跳到下一格）。')
      if (headers[1]) push(headers[1], '字体 / 字号', '设置选中区域的字体和字号（旁边的下拉同理）。')
      push(tip('Font bold (Ctrl+B)'), '加粗 / 斜体 / 下划线 / 删除线', '常用文字样式快捷键齐全（Ctrl+B / I / U）。')
      push(tip('Text color'), '文字颜色', '改选中格子文字的颜色。')
      push(tip('Fill color'), '填充颜色', '给选中格子加背景底色。')
      push(tip('Merge cells'), '合并单元格', '把选中区域合并成一个大格，做表头常用。')
      push(tip('Borders'), '边框', '给选中区域加 / 换边框线。')
      push(tip('Horizontal align'), '对齐方式', '水平与垂直对齐调整。')
      push(tip('Text wrapping'), '自动换行', '内容过长时在格子内换行显示。')
      push(tip('Freeze cell'), '冻结 / 筛选', '冻结窗格固定表头；漏斗图标是筛选。')
      push(tip('Functions'), '函数', '插入常用函数（求和 Σ、平均值等），也可在单元格里直接输 =SUM(A1:A10)。')
    } else {
      steps.push({ popover: { title: '工具栏功能', description: '进入编辑后，顶部会出现完整工具栏：数字格式（货币 / 百分比 / 日期）、字体字号、加粗、文字与填充颜色、合并、边框、对齐、换行、筛选、求和函数等——届时再点右上「引导」可看逐项讲解。' } })
    }
  } else {
    steps.push({ popover: { title: '怎么用', description: meta.ops } })
  }
  // 「?」使用说明入口（图块内常驻按钮，随时可查快捷键与操作说明）
  if (helpBtn) {
    steps.push({
      element: helpBtn,
      popover: {
        title: '操作说明入口',
        description: '工具条上的「?」点开就是本画布的完整说明（快捷键、删除规则、保存方式等），不用记，随时查。',
      },
    })
  }
  return steps
}

function startTour() {
  const el = (sel) => document.querySelector(sel)
  const isDiagram = !!props.diagram
  const steps = isDiagram
    ? diagramTourSteps()
    : [
      { popover: { title: '欢迎使用协同文档', description: '用 30 秒了解一下编辑器的功能布局。' } },
      { element: el('[data-tour="undo-redo"]'), popover: { title: '撤销 / 重做', description: '编辑错了随时回退，由实时协同引擎保证多人一致。' } },
      { element: el('[data-tour="heading"]'), popover: { title: '标题与正文', description: '切换段落与各级标题，构建文档结构。' } },
      { element: el('[data-tour="format"]'), popover: { title: '引用与代码', description: '引用块、代码块、分割线，写技术文档常用。' } },
      { element: el('[data-tour="list"]'), popover: { title: '列表与待办', description: '无序/有序列表，以及可勾选的待办清单。' } },
      { element: el('[data-tour="table"]'), popover: { title: '表格', description: '插入表格后可增删行列。' } },
      { element: el('[data-tour="mindmap"]'), popover: { title: '思维导图 🆕', description: '在文档里直接画思维导图：插入后点「编辑导图」，双击节点改文字，Tab 建子主题、Enter 建同级；节点可拖拽，Delete 删子树；工具条「?」可看详细操作。编辑期间其他人只读。' } },
      { element: el('[data-tour="flowchart"]'), popover: { title: '流程图 🆕', description: '在文档里画流程图：插入后点「编辑」，左侧图形面板点选或拖入图形（矩形 / 菱形 / 数据库 / 子流程 等 12 种），拖圆点连线，点连线加「是/否」标签；右下角「?」可看详细操作。编辑期间其他人只读。' } },
      { element: el('[data-tour="sheet"]'), popover: { title: '电子表格 🆕', description: '在文档里插入 Excel 式表格：单元格可输公式（=SUM(...)）、拖拽填充、复制粘贴、合并冻结；插入后点「编辑」，编辑期间其他人只读。' } },
      { element: el('[data-tour="snapshot"]'), popover: { title: '快照与版本', description: '手动保存快照；「版本」里可查看 / 回滚任意历史版本。' } },
      { element: el('[data-tour="presence"]'), popover: { title: '协作状态', description: '这里能看到当前在线的协作者及其光标颜色。开始创作吧！' } },
    ]
  const d = driver({
    showProgress: true,
    progressText: '{{current}} / {{total}}',
    nextBtnText: '下一步',
    prevBtnText: '上一步',
    doneBtnText: '完成',
    steps,
  })
  d.drive()
}

onMounted(() => {
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  destroyed = true
  if (reconnectTimer) clearTimeout(reconnectTimer)
  if (autoSnapshotTimer) clearTimeout(autoSnapshotTimer)
  document.removeEventListener('visibilitychange', onVisibility)
  provider?.destroy()
  editor.value?.destroy()
})

// 只读保护：canWrite 为 false 时强制不可编辑、失焦，并清空本地 awareness（不广播光标/选区），
// 杜绝只读用户点击产生可编辑光标、或本地光标被同步到其他协作者屏幕上。
function enforceReadOnly() {
  if (editor.value) {
    editor.value.setEditable(false)
    editor.value.commands.blur()
  }
  // 彻底不广播本地光标/在线状态（CollaborationCursor 未挂载时仍可能因 awareness 连接残留本地状态）
  try { provider.awareness.setLocalState(null) } catch (e) { /* awareness 未就绪忽略 */ }
}
watch(() => props.canWrite, (w) => {
  if (!w) enforceReadOnly()
  else if (editor.value) editor.value.setEditable(true)
}, { immediate: true })

/**
 * 供父组件插入 @提及 文本（光标处）。
 * 只负责往正文里落字，通知由父组件调接口发出——插入成功与否不影响通知。
 * @returns {boolean} 是否插入成功（只读时返回 false）
 */
function insertMention(name) {
  if (!editor.value || !props.canWrite) return false
  try {
    editor.value.chain().focus().insertContent('@' + name + ' ').run()
    return true
  } catch { return false }
}
defineExpose({ insertMention })
</script>

<template>
  <div class="collab" :class="{ 'diagram-mode': !!diagram }">
    <!-- 顶部工具栏（飞书式：白底分组按钮 + 右侧在线状态） -->
    <div v-if="authExpired" class="auth-expired-tip">
      ⚠️ 登录已过期或未登录——协同与保存不可用，请
      <a href="/login" @click.prevent="goLogin">重新登录</a>后刷新本页。
    </div>
    <div class="collab-toolbar">
      <div class="tb-left" v-if="canWrite && !diagram">
        <div class="tb-group" data-tour="undo-redo">
          <button class="tb-btn" title="撤销" @click="cmd('undo')"><el-icon :size="15"><RefreshLeft /></el-icon></button>
          <button class="tb-btn" title="重做" @click="cmd('redo')"><el-icon :size="15"><RefreshRight /></el-icon></button>
        </div>
        <span class="tb-sep" />
        <div class="tb-group" data-tour="heading">
          <button class="tb-btn txt" :class="{ on: on('paragraph') }" title="正文" @click="cmd('paragraph')">正文</button>
          <button class="tb-btn" :class="{ on: on('heading', { level: 1 }) }" title="标题 1" @click="cmd('h1')"><b>H1</b></button>
          <button class="tb-btn" :class="{ on: on('heading', { level: 2 }) }" title="标题 2" @click="cmd('h2')"><b>H2</b></button>
          <button class="tb-btn" :class="{ on: on('heading', { level: 3 }) }" title="标题 3" @click="cmd('h3')"><b>H3</b></button>
        </div>
        <span class="tb-sep" />
        <div class="tb-group">
          <button class="tb-btn" :class="{ on: on('bold') }" title="加粗" @click="cmd('bold')"><b>B</b></button>
          <button class="tb-btn" :class="{ on: on('italic') }" title="斜体" @click="cmd('italic')"><i>I</i></button>
          <button class="tb-btn" :class="{ on: on('strike') }" title="删除线" @click="cmd('strike')"><s>S</s></button>
          <button class="tb-btn code-ic" :class="{ on: on('code') }" title="行内代码" @click="cmd('inlineCode')">&lt;/&gt;</button>
        </div>
        <span class="tb-sep" />
        <div class="tb-group" data-tour="list">
          <button class="tb-btn" :class="{ on: on('bulletList') }" title="无序列表" @click="cmd('bullet')">•••</button>
          <button class="tb-btn num" :class="{ on: on('orderedList') }" title="有序列表" @click="cmd('ordered')">1.</button>
          <button class="tb-btn chk" :class="{ on: on('taskList') }" title="待办清单" @click="cmd('task')">☑</button>
        </div>
        <span class="tb-sep" />
        <div class="tb-group" data-tour="format">
          <button class="tb-btn" :class="{ on: on('blockquote') }" title="引用" @click="cmd('quote')">❝</button>
          <button class="tb-btn code-ic" :class="{ on: on('codeBlock') }" title="代码块" @click="cmd('codeBlock')">{&nbsp;}</button>
          <button class="tb-btn dash" title="分割线" @click="cmd('hr')">—</button>
          <button class="tb-btn" title="清除格式" @click="cmd('clear')">✕格式</button>
        </div>
        <span class="tb-sep" />
        <div class="tb-group" data-tour="table">
          <button class="tb-btn" :class="{ on: on('table') }" title="插入 3×3 表格" @click="cmd('table')">▦ 表格</button>
          <template v-if="on('table')">
            <button class="tb-btn txt" title="在下方插入一行" @click="cmd('addRow')">+行</button>
            <button class="tb-btn txt" title="在右侧插入一列" @click="cmd('addColumn')">+列</button>
            <button class="tb-btn txt" title="删除当前行" @click="cmd('delRow')">删行</button>
            <button class="tb-btn txt" title="删除当前列" @click="cmd('delColumn')">删列</button>
            <button class="tb-btn txt danger" title="删除整个表格" @click="cmd('delTable')">删表</button>
          </template>
        </div>
        <span class="tb-sep" />
        <div class="tb-group" data-tour="mindmap">
          <button class="tb-btn" title="插入思维导图（带协同编辑锁）" @click="cmd('mindmap')"><el-icon :size="15"><Share /></el-icon> 导图</button>
        </div>
        <div class="tb-group" data-tour="flowchart">
          <button class="tb-btn" title="插入流程图（带协同编辑锁）" @click="cmd('flowchart')"><el-icon :size="15"><Share /></el-icon> 流程</button>
        </div>
        <div class="tb-group" data-tour="sheet">
          <button class="tb-btn" title="插入电子表格（Excel 式：公式/填充/冻结，带协同编辑锁）" @click="cmd('sheet')"><el-icon :size="15"><Grid /></el-icon> 表格</button>
        </div>
        <span class="tb-sep" />
        <button class="tb-btn txt" data-tour="snapshot" :class="{ saving }" title="把当前内容保存为 HTML 快照（含图块截图）" @click="saveSnapshot()">
          {{ saving ? '保存中…' : '存快照' }}
        </button>
      </div>

      <div class="tb-right" data-tour="presence">
        <el-button size="small" class="ver-btn" @click="startTour" title="漫游式功能引导">
          <el-icon :size="13" style="margin-right: 4px"><QuestionFilled /></el-icon>引导
        </el-button>
        <el-button size="small" class="ver-btn" data-tour="versions" @click="openVersions" title="查看 / 回滚历史版本">🕑 版本</el-button>
        <el-dropdown trigger="click" @command="onExport">
          <el-button size="small" class="export-btn">导出<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="md">导出 Markdown (.md)</el-dropdown-item>
              <el-dropdown-item command="html">导出 HTML (.html)</el-dropdown-item>
              <el-dropdown-item command="xlsx">导出 Excel (.xlsx)</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <div class="presence">
        <span class="ro-tag" v-if="!canWrite">只读模式</span>
        <span class="peer-count" v-if="peers.length > 1">{{ peers.length }} 人正在编辑</span>
        <span class="peer-stack" v-if="peers.length">
          <el-tooltip v-for="(p, i) in peers.slice(0, 6)" :key="(p.clientId ?? p.name) + i" :content="p.name" placement="bottom">
            <span class="peer" :style="p.avatar ? {} : { background: p.color }">
              <img v-if="p.avatar" :src="p.avatar" class="peer-img" :alt="p.name" />
              <template v-else>{{ (p.name || '?').slice(0, 1) }}</template>
            </span>
          </el-tooltip>
          <span class="peer more" v-if="peers.length > 6">+{{ peers.length - 6 }}</span>
        </span>
        <span class="conn" :class="status">
          <span class="dot" />{{ statusText }}
        </span>
        <span v-if="saveText" class="save-state" :class="{ dirty }">{{ saveText }}</span>
      </div>
    </div>

    <!-- 灰色画布 + 居中纸张（飞书式版面） -->
    <div class="collab-canvas" :class="{ ro: !canWrite }">
      <div class="page" @click.self="canWrite && !diagram && editor?.commands.focus('end')">
        <editor-content :editor="editor" class="page-inner" />
      </div>
    </div>

    <!-- 版本历史抽屉 -->
    <el-drawer v-model="versionDrawer" title="版本历史" direction="rtl" size="420px">
      <div class="ver-head">
        <el-button size="small" type="primary" :loading="savingVersion" :disabled="!canWrite" @click="saveCurrentVersion">
          保存当前版本
        </el-button>
        <span class="ver-tip">下面是历史快照，<b>当前内容不在列表里</b>；点「保存当前版本」把现在的内容留一档，回滚会替换当前内容（不会自动备份）。</span>
      </div>
      <div v-loading="versionLoading" class="ver-list">
        <el-empty v-if="!versions.length" description="暂无版本（编辑保存后会自动生成）" :image-size="90" />
        <div class="ver-item" v-for="v in versions" :key="v.id">
          <div class="ver-info">
            <div class="ver-t">
              #{{ v.id }} {{ v.title || '（无标题）' }}
              <el-tag v-if="v.kind === 'RESTORE'" size="small" type="info" effect="plain">回滚前备份</el-tag>
            </div>
            <div class="ver-meta">
              编辑于 {{ fromNow(v.createdAt) }}（{{ fmtDateTime(v.createdAt) }}）
              <template v-if="v.editorName"> · {{ v.editorName }}</template>
              <template v-else-if="v.editorId"> · 用户#{{ v.editorId }}</template>
            </div>
            <div class="ver-snip" v-if="versionSnippet(v.content)">{{ versionSnippet(v.content) }}</div>
          </div>
          <div class="ver-acts">
            <el-button size="small" @click="compareVersion(v)">对比</el-button>
            <el-button size="small" type="warning" @click="restoreVersion(v)">回滚</el-button>
            <el-button size="small" type="danger" plain :disabled="!canWrite" @click="removeVersion(v)">删除</el-button>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 版本对比：段落级 diff（新版绿色新增、旧版红色删除） -->
    <DocDiffDialog v-model="diffDlg" :old-html="diffOld" :new-html="diffNew"
                   :old-label="diffOldLabel" new-label="当前内容" />
  </div>
</template>

<style scoped>
/* min-height:0 必须有：否则 flex 子项的 min-height:auto 会被内容撑高，
   下游 .collab-canvas 的 overflow:auto 永远不触发（内容溢出盖住页脚）。 */
.collab { flex: 1; display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: hidden; }

/* ---------- 工具栏 ---------- */
.collab-toolbar {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 16px; background: #fff; border-bottom: 1px solid #eef0f3;
  position: relative; z-index: 5;
}
.tb-left { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.tb-right { display: flex; align-items: center; margin-left: 8px; }
.tb-right .export-btn { font-size: 13px; }
.tb-right .ver-btn { font-size: 13px; margin-right: 8px; }
.ver-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.ver-tip { color: #94a3b8; font-size: 12px; }
.ver-list { display: flex; flex-direction: column; gap: 10px; }
.ver-item { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 12px; border: 1px solid #eef2f7; border-radius: 10px; }
.ver-acts { display: flex; flex-direction: column; gap: 6px; flex-shrink: 0; }
.ver-acts :deep(.el-button + .el-button) { margin-left: 0; }
.ver-info { min-width: 0; flex: 1; }
.ver-t { font-weight: 600; color: #1e293b; font-size: 14px; }
.ver-meta { color: #94a3b8; font-size: 12px; margin-top: 3px; }
.ver-snip { color: #64748b; font-size: 13px; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.tb-group { display: flex; align-items: center; gap: 2px; }
.tb-sep { width: 1px; height: 18px; background: #e4e7ec; margin: 0 4px; }
.tb-btn {
  min-width: 30px; height: 30px; padding: 0 7px;
  border: none; background: transparent; border-radius: 6px;
  color: #4b5563; font-size: 13.5px; line-height: 1;
  cursor: pointer; display: inline-flex; align-items: center; justify-content: center;
  transition: background .12s, color .12s;
}
.tb-btn:hover { background: #f1f5f9; color: #1e293b; }
.tb-btn.on { background: #eef2ff; color: #4f46e5; }
.tb-btn.txt { font-size: 12.5px; padding: 0 8px; }
.tb-btn.txt.saving { color: #94a3b8; cursor: default; }
.tb-btn.txt.danger:hover { background: #fef2f2; color: #dc2626; }
.tb-btn.num { font-size: 12.5px; font-weight: 600; }
.tb-btn.chk { font-size: 14px; }
.tb-btn.dash { letter-spacing: -1px; }
.tb-btn.code-ic { font-size: 11.5px; font-family: ui-monospace, Consolas, monospace; }

/* ---------- 在线状态 ---------- */
.presence { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.peer-count { color: #64748b; font-size: 12.5px; white-space: nowrap; }
/* 飞书等距堆叠：line-height:0 消除 inline-flex 子元素空白节点把间距撑大，
   否则实际重叠量被放大 → 第一个头像被第二个挤掉过多（"被遮太多"根因） */
.peer-stack { display: inline-flex; align-items: center; padding-left: 8px; line-height: 0; }
/* 负 margin 统一加在直接子元素（含 el-tooltip 外层 wrapper），避免 tooltip 包裹导致堆叠错位穿样式 */
/* 重叠 8px：与 28px 头像 + 2px 白圈搭配，每个头像露出约 20px 主体 + 完整白圈，不会被压没 */
.peer-stack > * { margin-left: -8px; flex: none; position: relative; line-height: normal; }
.peer-stack > *:first-child { margin-left: 0; }
/* 后排头像压前排：z-index 按位置递增，hover 时升到最上层（飞书/企业微信通用做法） */
.peer-stack > *:nth-child(1) { z-index: 1; }
.peer-stack > *:nth-child(2) { z-index: 2; }
.peer-stack > *:nth-child(3) { z-index: 3; }
.peer-stack > *:nth-child(4) { z-index: 4; }
.peer-stack > *:nth-child(5) { z-index: 5; }
.peer-stack > *:nth-child(6) { z-index: 6; }
.peer-stack > *:hover { z-index: 10; }
/* 白圈用 box-shadow 画在头像外（不要用 border + overflow:hidden，
   否则重叠时 border 会被对面背景切掉，表现为"白边消失 + 头像穿样式"）。
   单层 2px 纯白圈，不要叠外层灰影（灰影会加深"压住"的视觉感）。 */
.peer {
  width: 28px; height: 28px; border-radius: 50%;
  color: #fff; font-size: 12px; font-weight: 600;
  display: inline-flex; align-items: center; justify-content: center;
  background: #94a3b8;
  box-shadow: 0 0 0 2px #fff;
  overflow: hidden; cursor: default;
}
.peer-img { width: 100%; height: 100%; object-fit: cover; display: block; }
.peer.more { background: #94a3b8; font-size: 10.5px; }
.conn { display: inline-flex; align-items: center; gap: 5px; font-size: 12.5px; color: #64748b; white-space: nowrap; }
.save-state { font-size: 12.5px; color: #94a3b8; white-space: nowrap; }
.save-state.dirty { color: #d97706; }
.conn .dot { width: 7px; height: 7px; border-radius: 50%; }
.conn.connected .dot { background: #10b981; }
.conn.connecting .dot { background: #f59e0b; }
.conn.disconnected .dot { background: #ef4444; }
.ro-tag { color: #94a3b8; font-size: 12.5px; background: #f1f5f9; border-radius: 4px; padding: 2px 8px; }

/* ---------- 画布与纸张 ---------- */
.collab-canvas { flex: 1; overflow: auto; background: #f2f3f5; }
.collab-canvas.ro .page { background: #fbfcfd; }
.page {
  max-width: 860px; min-height: calc(100% - 64px); margin: 24px auto 48px;
  background: #fff; border-radius: 8px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .05), 0 4px 16px rgba(15, 23, 42, .04);
  padding: 40px 64px 72px; box-sizing: border-box;
  cursor: text;
}
.page-inner { min-height: 100%; outline: none; }
/* 只读态物理锁：彻底禁止聚焦/选择，杜绝只读端产生本地光标或 selection */
.collab-canvas.ro .page-inner :deep(.kb-collab-body) { user-select: none; -webkit-user-select: none; cursor: default; }
.collab-canvas.ro .page-inner :deep(.ProseMirror) { caret-color: transparent; }

/* ---------- 图块专属模式（整篇文档 = 单个图块，画布占满右侧） ----------
   注意：本 style 块是 scoped 的，选择器对子组件（NodeView）内部元素不生效；
   diagram 全屏的高度/去纸张规则统一放文件末尾的非 scoped <style> 块。 */

/* ---------- 正文排版 ---------- */
.page-inner :deep(.kb-collab-body) { outline: none; color: #262a2e; font-size: 15.5px; line-height: 1.78; }
.page-inner :deep(.kb-collab-body > *) + * { margin-top: 6px; }
.page-inner :deep(.kb-collab-body h1) { font-size: 26px; font-weight: 700; color: #0f172a; margin: 26px 0 8px; line-height: 1.35; }
.page-inner :deep(.kb-collab-body h2) { font-size: 21px; font-weight: 700; color: #0f172a; margin: 22px 0 6px; line-height: 1.35; }
.page-inner :deep(.kb-collab-body h3) { font-size: 17.5px; font-weight: 600; color: #1e293b; margin: 18px 0 4px; line-height: 1.4; }
.page-inner :deep(.kb-collab-body p) { margin: 2px 0; }
.page-inner :deep(.kb-collab-body ul),
.page-inner :deep(.kb-collab-body ol) { padding-left: 1.5em; margin: 4px 0; }
.page-inner :deep(.kb-collab-body li) { margin: 3px 0; }
.page-inner :deep(.kb-collab-body li::marker) { color: #6366f1; }
.page-inner :deep(.kb-collab-body ul.task-list) { list-style: none; padding-left: 2px; }
.page-inner :deep(.kb-collab-body ul.task-list li) { display: flex; gap: 8px; align-items: flex-start; }
.page-inner :deep(.kb-collab-body ul.task-list li > label) { margin-top: 5px; user-select: none; }
.page-inner :deep(.kb-collab-body ul.task-list li > div) { flex: 1; }
.page-inner :deep(.kb-collab-body input[type='checkbox']) { accent-color: #6366f1; width: 15px; height: 15px; cursor: pointer; }
.page-inner :deep(.kb-collab-body blockquote) {
  border-left: 3px solid #a5b4fc; background: #f8faff; color: #4b5563;
  margin: 10px 0; padding: 8px 16px; border-radius: 0 6px 6px 0;
}
.page-inner :deep(.kb-collab-body pre) {
  background: #1e293b; color: #e2e8f0; padding: 14px 18px; border-radius: 8px;
  overflow: auto; font-size: 13.5px; line-height: 1.65; margin: 10px 0;
  font-family: ui-monospace, Consolas, 'JetBrains Mono', monospace;
}
.page-inner :deep(.kb-collab-body code) {
  font-family: ui-monospace, Consolas, monospace; font-size: .9em;
  background: #f1f5f9; color: #4f46e5; border-radius: 4px; padding: 1px 5px;
}
.page-inner :deep(.kb-collab-body pre code) { background: none; color: inherit; padding: 0; }
.page-inner :deep(.kb-collab-body hr) { border: none; border-top: 1px solid #e2e8f0; margin: 18px auto; width: 72%; }
.page-inner :deep(.kb-collab-body a) { color: #4f46e5; }

/* 表格（飞书式：细边框 + 浅灰表头 + 选中高亮 + 列宽拖拽把手） */
.page-inner :deep(.kb-collab-body .tableWrapper) { overflow: visible; margin: 10px 0; }
.page-inner :deep(.kb-collab-body table) { border-collapse: separate; border-spacing: 0; table-layout: fixed; width: 100%; margin: 0; border: 1px solid #e2e8f0; border-right: 0; border-bottom: 0; }
.page-inner :deep(.kb-collab-body td),
.page-inner :deep(.kb-collab-body th) {
  border-right: 1px solid #e2e8f0; border-bottom: 1px solid #e2e8f0;
  padding: 7px 12px; vertical-align: top;
  position: relative; min-width: 2em; box-sizing: border-box;
}
.page-inner :deep(.kb-collab-body th) { background: #f7f8fa; font-weight: 600; text-align: left; }
.page-inner :deep(.kb-collab-body td > p),
.page-inner :deep(.kb-collab-body th > p) { margin: 0; }
.page-inner :deep(.kb-collab-body .selectedCell::after) {
  content: ''; position: absolute; inset: 0;
  background: rgba(99, 102, 241, .12); pointer-events: none;
}
.page-inner :deep(.kb-collab-body .column-resize-handle) {
  position: absolute; right: -2px; top: 0; bottom: 0; width: 4px;
  background: #6366f1; cursor: col-resize; z-index: 2;
}
.page-inner :deep(.kb-collab-body.resize-cursor) { cursor: col-resize; }

/* 占位文案 */
.page-inner :deep(.kb-collab-body p.is-editor-empty:first-child::before) {
  content: attr(data-placeholder); color: #b6bdc7; float: left; height: 0; pointer-events: none;
}

/* ---------- 飞书式多人光标：彩条 + 上浮名牌 ---------- */
.page-inner :deep(.collaboration-cursor__caret) {
  border-left: 2px solid; border-right: none; margin: 0 -1px 0 0;
  pointer-events: none; position: relative; word-break: normal; display: inline;
  /* 提升 stacking 优先级，避免被表格单元格内容/边框压住；同时加双层 drop-shadow 让 2px 竖线在表格线上醒目 */
  z-index: 2147483647;
  filter: drop-shadow(0 0 1px rgba(0, 0, 0, .35)) drop-shadow(0 0 1px rgba(255, 255, 255, .7));
}
.page-inner :deep(.collaboration-cursor__label) {
  /* 关键：label 放在 caret 紧贴的下方/右侧（完全在 cell 内部），
     避开 td/th 的 position:relative 形成的局部层叠 + .collab-canvas overflow:auto 切内容的问题。
     在 table 内即使 z-index 2147483647 也无法跨 cell 盖内容，所以改在 cell 内部。 */
  position: absolute; left: -2px; top: 100%; transform: translateY(2px);
  font-size: 12px; line-height: 1; font-weight: 500;
  color: #fff; padding: 4px 8px; border-radius: 0 4px 4px 4px;
  white-space: nowrap; user-select: none; opacity: .92;
  box-shadow: 0 2px 8px rgba(15, 23, 42, .18);
  z-index: 2147483647;
  animation: cur-in .16s ease-out;
}
@keyframes cur-in { from { opacity: 0; transform: translateY(-4px); } to { opacity: .92; transform: translateY(2px); } }

/* 本地选区（靛紫浅色，避免与系统默认蓝冲突） */
.page-inner :deep(.kb-collab-body ::selection) { background: #c7d2fe; }

/* ---------- 暗黑模式 ---------- */
html.dark .collab-toolbar { background: #17171f; border-bottom-color: #2a2a35; }
html.dark .tb-btn { color: #c2c6d2; }
html.dark .tb-btn:hover { background: #232347; color: #e7e7ee; }
html.dark .tb-btn.on { background: #232347; color: #a5b4fc; }
html.dark .tb-btn.txt.danger:hover { background: #2a1a1a; color: #f87171; }
html.dark .tb-sep { background: #2a2a35; }
html.dark .peer-count { color: #9aa0ad; }
html.dark .conn { color: #9aa0ad; }
html.dark .ro-tag { color: #9aa0ad; background: #23232e; }
html.dark .collab-canvas { background: #0e0e12; }
html.dark .collab-canvas.ro .page { background: #13131a; }
html.dark .page {
  background: #17171f;
  box-shadow: 0 1px 2px rgba(0, 0, 0, .4), 0 4px 16px rgba(0, 0, 0, .35);
}
html.dark .page-inner :deep(.kb-collab-body) { color: #e7e7ee; }
html.dark .page-inner :deep(.kb-collab-body h1),
html.dark .page-inner :deep(.kb-collab-body h2) { color: #f1f5f9; }
html.dark .page-inner :deep(.kb-collab-body h3) { color: #e7e7ee; }
html.dark .page-inner :deep(.kb-collab-body li::marker) { color: #a5b4fc; }
html.dark .page-inner :deep(.kb-collab-body blockquote) { background: #1d1d27; color: #b8b9c4; border-left-color: #3b3b6b; }
html.dark .page-inner :deep(.kb-collab-body pre) { background: #0d1117; color: #e2e8f0; }
html.dark .page-inner :deep(.kb-collab-body code) { background: #23232e; color: #a5b4fc; }
html.dark .page-inner :deep(.kb-collab-body hr) { border-top-color: #2a2a35; }
html.dark .page-inner :deep(.kb-collab-body a) { color: #a5b4fc; }
html.dark .page-inner :deep(.kb-collab-body table) { border-color: #2a2a35; }
html.dark .page-inner :deep(.kb-collab-body th) { background: #1d1d27; }
html.dark .page-inner :deep(.kb-collab-body td),
html.dark .page-inner :deep(.kb-collab-body th) { border-right-color: #2a2a35; border-bottom-color: #2a2a35; }
html.dark .page-inner :deep(.kb-collab-body .selectedCell::after) { background: rgba(129, 140, 248, .18); }

/* ===================== 响应式布局（追加在末尾） ===================== */
/* 平板 / 窄屏：工具栏整条独占一行并允许换行（不许被截断），
   右侧「导出 / 版本 / 在线状态」换行到第二行；纸张内边距收紧 */
@media (max-width: 1024px) {
  .collab-toolbar { flex-wrap: wrap; row-gap: 6px; padding: 6px 10px; }
  .tb-left { flex: 1 1 100%; }
  .tb-right { margin-left: 0; }
  .presence { margin-left: auto; }
  .page { margin: 16px auto 32px; padding: 32px 36px 56px; }
  /* 宽表格在窄屏自己横向滚动，不撑破纸张 */
  .page-inner :deep(.kb-collab-body .tableWrapper) { overflow-x: auto; }
  .ver-item { flex-wrap: wrap; }
}

/* 手机：工具按钮再缩一档、在线状态独占一行；纸张贴边、正文降 1px */
@media (max-width: 640px) {
  .collab-toolbar { padding: 6px 8px; gap: 6px; }
  .tb-left { gap: 4px; }
  .tb-btn { min-width: 28px; height: 28px; padding: 0 6px; font-size: 12.5px; }
  .tb-btn.txt { padding: 0 7px; font-size: 12px; }
  .tb-sep { margin: 0 2px; }
  .presence { width: 100%; margin-left: 0; flex-wrap: wrap; gap: 6px 8px; }
  .peer-count { font-size: 12px; }
  .page { margin: 10px auto 24px; padding: 20px 16px 40px; }
  .page-inner :deep(.kb-collab-body) { font-size: 14.5px; }
  .page-inner :deep(.kb-collab-body h1) { font-size: 22px; }
  .page-inner :deep(.kb-collab-body h2) { font-size: 19px; }
  .page-inner :deep(.kb-collab-body h3) { font-size: 16.5px; }
  .page-inner :deep(.kb-collab-body pre) { padding: 12px 14px; font-size: 12.5px; }
  .page-inner :deep(.kb-collab-body td),
  .page-inner :deep(.kb-collab-body th) { padding: 6px 8px; }
  .ver-head { gap: 8px; }
  .ver-item { flex-wrap: wrap; padding: 10px; }
}
</style>

<style>
/* ========== 图块专属模式（非 scoped：需命中 NodeView 子组件内部元素） ==========
   整篇文档 = 单个导图/流程图/电子表格块时，画布经 flex 链精确撑满右侧剩余高度。 */
/* 去掉"居中纸张"感：画布铺满 */
.diagram-mode .collab-canvas { display: flex; flex-direction: column; }
.diagram-mode .page {
  flex: 1; display: flex; flex-direction: column;
  max-width: none; margin: 0; padding: 0; min-height: 0;
  background: transparent; border-radius: 0; box-shadow: none;
}
.diagram-mode .page-inner { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.diagram-mode .page-inner .ProseMirror { flex: 1; min-height: 0; outline: none; display: flex; flex-direction: column; }
/* 三种图块 wrap/壳/canvas 全程 flex，canvas 吃掉 bar/hint 之外的剩余高度 */
.diagram-mode .kb-mindmap-wrap,
.diagram-mode .kb-fc-wrap,
.diagram-mode .kb-sheet-wrap { flex: 1; display: flex; flex-direction: column; min-height: 0; margin: 0; }
.diagram-mode .kb-mindmap,
.diagram-mode .kb-fc,
.diagram-mode .kb-sheet { flex: 1; display: flex; flex-direction: column; min-height: 0; border: none; border-radius: 0; }
.diagram-mode .page-inner .ProseMirror > p { display: none; margin: 0; height: 0; }
/* 提示条在专属模式下隐藏（画布顶到视口底部）：图块提示条已改为工具条「?」按钮，不再常驻 */
.diagram-mode .kb-sheet-hint { display: none; }
/* 表格块的 canvas 外多了一层 .kb-sheet-stage（放远端光标高亮层）——它也必须进 flex 链，
   否则链条断在这里，canvas 掉回 min-height:320px，下面留一大片空白（「表格只有一截」）。 */
.diagram-mode .kb-sheet-stage { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.diagram-mode .kb-mindmap-canvas,
.diagram-mode .kb-fc-canvas,
.diagram-mode .kb-sheet-canvas { flex: 1; min-height: 320px; height: auto; }
</style>
