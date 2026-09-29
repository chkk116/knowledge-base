<template>
  <NodeViewWrapper class="kb-sheet-wrap" data-type="sheet-block" @mousedown.capture="onWrapMouseDown">
    <div class="kb-sheet" :class="{ editable: canEdit }">
      <div class="kb-sheet-bar">
        <span class="kb-sheet-title">
          <svg class="kb-sheet-ico" viewBox="0 0 24 24" width="15" height="15" fill="none" aria-hidden="true">
            <rect x="3" y="3" width="18" height="18" rx="2" fill="#10b981"/>
            <path d="M3 9h18M3 15h18M9 3v18M15 3v18" stroke="#fff" stroke-width="1.4"/>
          </svg>
          {{ node.attrs.title || '电子表格' }}
        </span>
        <span v-if="!canEdit" class="kb-sheet-status readonly">🔒 只读</span>
        <span v-else-if="activePeer" class="kb-sheet-status editing" :style="{ color: activePeer.color }">
          {{ activePeer.ro ? '👁' : '✎' }} {{ activePeer.name }}{{ activePeer.ro ? ' 正在查看' : ' 正在编辑' }}{{ activePeer.cell ? ' ' + activePeer.cell : '' }}
        </span>
        <span v-else class="kb-sheet-status editing">多人可同时编辑</span>
        <span v-if="peers.length" class="kb-sheet-peers">
          <span
            v-for="p in peers.slice(0, 4)"
            :key="p.clientId"
            class="kb-sheet-avatar"
            :style="{ background: p.color }"
            :title="p.name"
          >{{ (p.name || '?').slice(0, 1) }}</span>
          <span class="kb-sheet-peer-n">{{ peers.length }} 人在线</span>
        </span>
        <span class="kb-sheet-actions">
          <span class="kb-sheet-tip">点单元格直接输入 · 公式 / 填充 / 合并同 Excel</span>
        </span>
      </div>
      <div class="kb-sheet-stage" @mousemove="onStageMove">
        <div
          ref="sheetEl"
          class="kb-sheet-canvas"
          :class="{ readonly: !canEdit }"
          tabindex="0"
          @mousedown="onMouseDown"
        ></div>
        <!-- 远端协作者高亮层：表格是 canvas 渲染，无法承载 DOM 光标，故在其上叠一层彩色框。
             必须放在 sheetEl 之外——mountSheet() 会清空该容器的 innerHTML。 -->
        <div v-if="remoteBoxes.length" class="kb-sheet-overlay">
          <div
            v-for="b in remoteBoxes"
            :key="b.clientId"
            class="kb-sheet-remote"
            :style="{ left: b.x + 'px', top: b.y + 'px', width: b.w + 'px', height: b.h + 'px', borderColor: b.color }"
          >
            <span class="kb-sheet-remote-name" :style="{ background: b.color }">{{ b.name }}</span>
          </div>
        </div>
      </div>
      <div class="kb-sheet-hint">
        单元格改动会实时同步给协作者（含公式自动重算）；合并 / 冻结 / 行列增删等结构调整建议单人操作，避免同时改动。
      </div>
    </div>
  </NodeViewWrapper>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { NodeSelection } from '@tiptap/pm/state'
import { ElMessage } from 'element-plus'
// 主包走 ESM 源码（Vite 原生解析）；dist 的 UMD 会被 Vite 预构建成
// 「无 default 导出」的 ESM 导致运行时 SyntaxError，故 js/locale 一律不 import dist。
// CSS 是静态资源无 interop 问题，继续用 dist。代价：工具栏为英文（locale 文件也是 UMD，同理放弃）。
import Spreadsheet from 'x-data-spreadsheet'
import 'x-data-spreadsheet/dist/xspreadsheet.css'
import { createSheetCollab, sheetMapName, genSheetId, structSignature } from '../../utils/sheetCollab'

const props = defineProps(nodeViewProps)

// 可编辑 = 文档级写权限（不再有「编辑锁」：单元格文本走 Y.Map 单元格级协同）
const canEdit = computed(() => !!props.editor?.isEditable)

const sheetEl = ref(null)
let sheet = null            // x-data-spreadsheet 实例
let ro = null               // 尺寸观察
let lastH = 0

// ---------- 协同 ----------
let ydoc = null             // Tiptap Collaboration 的 Y.Doc（无协同环境为 null）
let sid = ''                // 本块的稳定标识（Y.Map 的 key 前缀）
let collab = null           // 协同驱动实例
let localStructSig = ''     // 本地刚提交的结构签名（用于忽略自己触发的 data watch）
let structTimer = null      // 他人结构变更后的重建防抖

function resolveYdoc() {
  try {
    const ext = props.editor?.extensionManager?.extensions?.find((e) => e.name === 'collaboration')
    return ext?.options?.document || null
  } catch {
    return null
  }
}

/**
 * 把 sid 附进工作簿数据。**始终存对象（第 1 张表），不存数组** ——
 * 实测 getData() 返回的数组经节点属性往返后会被 Yjs 变成 {"0":{...}}，
 * 导致 firstSheet() 取不到 rows（结构/样式丢失）。本块只支持单表，故存对象即可。
 * 引擎会忽略未知的 sid 字段。
 */
function withSid(data) {
  const wb = Array.isArray(data) ? (data[0] || {}) : (data || {})
  return { ...wb, sid }
}

/**
 * 确定本块标识。
 * 优先节点属性；但属性可能因协同内容重建而丢失（例如编辑器用 HTML 快照 setContent 重建节点时），
 * 所以再用 **data JSON 里的 sid** 兜底 —— data 已验证能可靠持久化（刷新后仍在）。
 * 两者都没有才新生成，并把 sid 同时写进属性与 data，避免刷新后再丢（否则会分裂成两个 Y.Map）。
 */
function ensureSid() {
  let s = props.node.attrs.sid || ''
  if (!s) {
    const d = parseData()
    if (d && typeof d.sid === 'string') s = d.sid
  }
  if (!s) s = genSheetId()
  return s
}

/**
 * 把 sid 写回节点属性（同时写进 data，双保险）。
 * **必须等 NodeView 位置稳定后再调用** —— 在 onMounted 里直接调 updateAttributes 会因
 * getPos() 尚未就绪而**静默失败**（不抛异常、属性不变），导致 sid 无法持久化，
 * 刷新后再生成新 sid、数据分裂到两个 Y.Map（实测踩坑）。
 */
function persistSid() {
  if (!canEdit.value || !sid) return
  if (props.node.attrs.sid === sid && parseData().sid === sid) return
  try {
    props.updateAttributes({ sid, data: JSON.stringify({ ...parseData(), sid }) })
  } catch (e) {
    if (import.meta.env.DEV) window.__kbSheetErr = 'persistSid: ' + String((e && e.message) || e)
  }
}

/** 建立协同驱动（ydoc 缺失时降级为纯本地） */
function initCollab(mapSid) {
  collab?.destroy()
  collab = null
  ydoc = resolveYdoc()
  sid = mapSid || ensureSid()
  if (!ydoc || !sid) return
  const ymap = ydoc.getMap(sheetMapName(sid))
  collab = createSheetCollab({
    ymap,
    engine: {
      // 注意：sheet 可能被重建，这里必须动态读取当前实例
      cellText: (r, c, t) => { try { sheet?.cellText(r, c, t) } catch { /* 忽略 */ } },
      reRender: () => { try { sheet?.reRender() } catch { /* 忽略 */ } overlayTick.value++ },
    },
    getData: () => sheetData(),
    onStructChange: (full) => {
      // 结构 / 样式变更：整表快照写回节点属性（LWW）。记下签名避免自己触发重建。
      // 写回时带上 sid，保证 data 里始终有标识（见 ensureSid 的说明）。
      localStructSig = structSignature(full)
      try { props.updateAttributes({ data: JSON.stringify(withSid(full)) }) } catch { /* 忽略 */ }
    },
  })
}

/** sid 被其他端改写（老文档首次协同的竞态）：迁移数据并重建驱动 */
function migrateSid(newSid) {
  if (!ydoc || !newSid || newSid === sid) return
  try {
    const oldMap = ydoc.getMap(sheetMapName(sid))
    const newMap = ydoc.getMap(sheetMapName(newSid))
    if (newMap.size === 0 && oldMap.size > 0) {
      ydoc.transact(() => { oldMap.forEach((v, k) => newMap.set(k, v)) })
    }
  } catch { /* 忽略 */ }
  initCollab(newSid)
  nextTick(() => mountSheet())
}

// ---------- 在线协作者（Yjs awareness） ----------
// 单元格文本走 Y.Map 后不再有「编辑锁」，但用户仍需要看到「谁在线 / 谁在改这张表」，
// 因此用 awareness：①列出在线用户头像；②广播自己是否正在本表里（含所处单元格）。
const peers = ref([])          // 其它在线用户 [{clientId, name, color, here, ri, ci}]
const activePeer = ref(null)   // 正在编辑本表的协作者 {name, color, ri, ci, cell}
const overlayTick = ref(0)     // 引擎重绘 / 滚动后自增，驱动高亮层重算（sheet 实例非响应式）
let awareness = null
let awarenessHandler = null
let overlayTimer = null

function resolveAwareness() {
  try {
    // 1) 优先：宿主（DocShareEditor）注入的 provider —— 只读端也能拿到
    //    （CollaborationCursor 只在 canWrite 时挂载，只读端从扩展里取不到）
    const injected = props.editor?.storage?.kbCollab?.provider
    if (injected && injected.awareness) return injected.awareness
    // 2) 兜底：CollaborationCursor 扩展持有的 provider
    const ext = props.editor?.extensionManager?.extensions?.find((e) => e.name === 'collaborationCursor')
    return ext?.options?.provider?.awareness || null
  } catch {
    return null
  }
}

/** 0→A, 25→Z, 26→AA */
function colLabel(ci) {
  let s = ''
  let n = ci
  do { s = String.fromCharCode(65 + (n % 26)) + s; n = Math.floor(n / 26) - 1 } while (n >= 0)
  return s
}

/** 当前选中的格子（如 C3）；取不到返回空 */
function currentCellLabel() {
  try {
    const sel = sheet?.sheet?.data?.selector
    if (!sel || sel.ri == null || sel.ci == null) return ''
    return colLabel(sel.ci) + (sel.ri + 1)
  } catch {
    return ''
  }
}

/** 当前选中格的行列号；取不到返回 null */
function currentCell() {
  try {
    const sel = sheet?.sheet?.data?.selector
    if (!sel || sel.ri == null || sel.ci == null) return null
    return { ri: sel.ri, ci: sel.ci }
  } catch {
    return null
  }
}

/**
 * 广播「我在本表的哪个格子」（供他人画出高亮框）。
 * 传 null 表示离开本表。注意广播的是**行列号**而非标签，便于对方换算成像素位置。
 */
function setFocus(arg) {
  if (!awareness) return
  try {
    if (arg === null) { awareness.setLocalStateField('sheetFocus', null); return }
    const c = arg && typeof arg.ri === 'number' ? arg : currentCell()
    // 只读端也广播（ro 标记）—— 让可写端能看到「某人正在查看 C3」。
    awareness.setLocalStateField('sheetFocus', c ? { sid, ri: c.ri, ci: c.ci, ro: !canEdit.value } : null)
  } catch { /* ignore */ }
}

/**
 * 屏幕坐标（相对表格容器）→ 单元格。只读端的 canvas 是 pointer-events:none 点不到，
 * 只能靠 mousemove 反推位置，因此需要这个换算（引擎只提供正向的 cellRect）。
 */
function cellAtPoint(mx, my) {
  try {
    const d = sheet?.sheet?.data
    if (!d || !d.cols || !d.rows) return null
    const fw = d.cols.indexWidth
    const fh = d.rows.height
    const off = canvasOriginOffset()
    // 相对表格 canvas 的坐标（列头/行头占左上角，内容区从 (fw,fh) 起）
    const lx = mx - off.x
    const ly = my - off.y
    const vw = d.viewWidth ? d.viewWidth() : 0
    const vh = d.viewHeight ? d.viewHeight() : 0
    // 列头 / 行头 / 画布之外都不算命中（否则表格下方的空白条会广播出屏幕外的格子）
    if (lx < fw || ly < fh) return null
    if (vw && lx >= vw) return null
    if (vh && ly >= vh) return null
    const sx = (d.scroll && d.scroll.x) || 0
    const sy = (d.scroll && d.scroll.y) || 0
    const px = lx - fw + sx
    const py = ly - fh + sy
    let ci = -1; let acc = 0
    for (let i = 0; i < d.cols.len; i++) { acc += d.cols.getWidth(i); if (px < acc) { ci = i; break } }
    let ri = -1; acc = 0
    for (let i = 0; i < d.rows.len; i++) { acc += d.rows.getHeight(i); if (py < acc) { ri = i; break } }
    return (ri >= 0 && ci >= 0) ? { ri, ci } : null
  } catch {
    return null
  }
}

let moveThrottle = 0
/** 鼠标在表格区域移动 → 广播所在格子（节流）。可写/只读端都走这条路，比只在点击时广播灵敏得多。 */
function onStageMove(e) {
  if (!awareness) return
  const now = Date.now()
  if (now - moveThrottle < 180) return
  moveThrottle = now
  const el = e.currentTarget
  if (!el) return
  const r = el.getBoundingClientRect()
  const c = cellAtPoint(e.clientX - r.left, e.clientY - r.top)
  if (c) setFocus(c)
}

/** 读取在线用户，并判断谁正在编辑本表 */
function syncPeers() {
  if (!awareness) return
  const states = awareness.getStates?.() || new Map()
  const me = awareness.clientID
  const list = []
  let act = null
  states.forEach((st, cid) => {
    const u = st && st.user
    if (!u || cid === me) return
    const name = u.name || '协作者'
    const color = u.color || '#10b981'
    const f = st.sheetFocus
    // here = 该协作者此刻正位于**本表格块**内
    const here = !!(f && f.sid === sid && typeof f.ri === 'number' && typeof f.ci === 'number')
    const ro = !!(f && f.ro)
    list.push({ clientId: cid, name, color, here, ro, ri: here ? f.ri : null, ci: here ? f.ci : null })
    if (here) act = { clientId: cid, name, color, ro, ri: f.ri, ci: f.ci, cell: colLabel(f.ci) + (f.ri + 1) }
  })
  peers.value = list
  activePeer.value = act
  overlayTick.value++
}

/**
 * 远端协作者的高亮框（在 canvas 上叠 DOM 层绘制）。
 * 坐标换算依引擎绘制逻辑：内容区原点在 canvas 内的 (cols.indexWidth, rows.height)，再减滚动偏移；
 * canvas 本身又位于工具栏下方，故还要叠加 canvasOriginOffset()。
 */
/**
 * 表格 canvas 相对 .kb-sheet-canvas（= overlay 原点）的偏移。
 * 引擎把工具栏画在 canvas 上方（可写态 40px + 1px border），只加 indexWidth/rows.height
 * 会漏掉这段竖直偏移 → 高亮框整体偏上、鼠标反查格子也偏。这里动态量取，读写态都正确。
 * overlay 与 canvas 同原点（.kb-sheet-stage 无内边距）。
 */
function canvasOriginOffset() {
  try {
    const root = sheetEl.value
    if (!root) return { x: 0, y: 0 }
    const cv = root.querySelector('.x-spreadsheet-table')
    if (!cv) return { x: 0, y: 0 }
    const rr = root.getBoundingClientRect()
    const cr = cv.getBoundingClientRect()
    return { x: cr.left - rr.left, y: cr.top - rr.top }
  } catch {
    return { x: 0, y: 0 }
  }
}

/** 单元格 → 相对表格容器（含行头/列头 + 工具栏）的像素矩形；引擎未就绪时返回 null */
function cellScreenRect(ri, ci) {
  try {
    const d = sheet?.sheet?.data
    if (!d || !d.cols || !d.rows) return null
    const fw = d.cols.indexWidth
    const fh = d.rows.height
    const sx = (d.scroll && d.scroll.x) || 0
    const sy = (d.scroll && d.scroll.y) || 0
    const r = d.cellRect(ri, ci)
    const off = canvasOriginOffset()
    return { x: off.x + fw + r.left - sx, y: off.y + fh + r.top - sy, w: r.width, h: r.height }
  } catch {
    return null
  }
}

/** 远端协作者高亮框（超出容器的部分由 overlay 的 overflow:hidden 裁掉） */
function computeRemoteBoxes() {
  const out = []
  if (!sheet || !peers.value.length) return out
  peers.value.forEach((p) => {
    if (!p.here || p.ri == null || p.ci == null) return
    const b = cellScreenRect(p.ri, p.ci)
    if (b) out.push({ clientId: p.clientId, name: p.name, color: p.color, ...b })
  })
  return out
}
const remoteBoxes = computed(() => {
  // 依赖 overlayTick 以便滚动/重绘后重算（sheet 实例不是响应式的）
  void overlayTick.value
  return computeRemoteBoxes()
})

// ---------- 数据解析 ----------
function parseData() {
  try {
    return props.node.attrs.data ? JSON.parse(props.node.attrs.data) : {}
  } catch {
    return {}
  }
}
function sheetData() {
  try { return sheet ? sheet.getData() : parseData() } catch { return parseData() }
}

// ---------- 引擎挂载 / 重建 ----------
function mountSheet() {
  if (sheet) { try { sheet.destroy?.() } catch { /* 无 destroy 则随 innerHTML 清理 */ } }
  sheet = null
  if (!sheetEl.value) return
  const el = sheetEl.value
  el.innerHTML = ''
  sheet = new Spreadsheet(el, {
    mode: 'edit',
    showToolbar: canEdit.value,
    showGrid: true,
    showContextmenu: canEdit.value,
    // 高度取容器实际值（flex 链分配后的剩余高度），引擎内部按此绘制
    view: { height: () => (el.clientHeight || 420), width: () => (el.clientWidth || 800) },
    row: { len: 100, height: 25 },
    col: { len: 26, width: 100, indexWidth: 60, minWidth: 60 },
  })
  // 协同环境下以 Y.Map 为准（含他人已填的单元格），否则用节点数据
  sheet.loadData(collab ? collab.buildData(parseData()) : parseData())
  sheet.change(onEngineChange)

  // 尺寸观察：mount 那一刻 flex 布局可能尚未稳定（读取高度偏差 → 底部空隙），
  // 尺寸变化超过阈值时按新尺寸重建一次（防抖）
  if (ro) ro.disconnect()
  lastH = el.clientHeight
  ro = new ResizeObserver(() => {
    const h = el.clientHeight
    if (!sheet || h < 80 || Math.abs(h - lastH) <= 20) return
    lastH = h
    clearTimeout(mountSheet._t)
    mountSheet._t = setTimeout(() => mountSheet(), 300)
  })
  ro.observe(el)
}

/** 引擎数据变化（本地编辑）→ 交给驱动：文本写 Y.Map、结构样式写回节点属性 */
function onEngineChange() {
  if (!canEdit.value) return
  try { collab?.onLocalChange() } catch { /* 忽略 */ }
  // 本地编辑即视为「在本表活跃」，并把所处格子广播出去（供他人看到「xx 正在编辑 C3」）
  setFocus()
}

// ---------- 事件拦截（防 ProseMirror 抢焦点 / 吃键盘） ----------
// 只读态：块区域 pointer-events:none 会让点击穿透到 PM 产生 NodeSelection，
// 随后任意打字都会删块——在 wrapper 捕获层直接吞掉点击
function onWrapMouseDown(e) {
  if (!canEdit.value) { e.stopPropagation(); e.preventDefault() }
}
function onMouseDown(e) {
  e.stopPropagation()
  const active = document.activeElement
  if (active && active !== document.body && active.closest?.('.ProseMirror')) {
    try { active.blur() } catch { /* ignore */ }
  }
  if (canEdit.value && sheetEl.value) sheetEl.value.focus({ preventScroll: true })
  // 广播「我在本表」+ 所处格子（等引擎更新完选区再读）
  if (canEdit.value) setTimeout(() => setFocus(), 0)
}

// 键盘统一在 document 捕获阶段拦截（可编辑态）：
//  - 焦点在 ProseMirror 时打字，PM 会用字符「替换」被 NodeSelection 选中的本块 → 表格消失（真实环境实测复现）
//  - x-data-spreadsheet 的键盘监听绑在 window 上；PM 根是 target 时容器冒泡层拦不到，必须在 document capture 层处理
let roTipTs = 0   // 只读提示节流
const docKeyDownCapture = (e) => {
  // 只读态保护：焦点在 PM 且 NodeSelection 选中的是本块时，打字会把块替换成文本（删块）——拦截并提示。
  if (!canEdit.value) {
    const ae = document.activeElement
    if (ae && ae.closest && ae.closest('.ProseMirror')) {
      try {
        const sel = props.editor.state.selection
        if (sel instanceof NodeSelection && sel.node.type.name === 'sheetBlock') {
          e.stopImmediatePropagation()
          e.preventDefault()
          if (Date.now() - roTipTs > 3000) {
            roTipTs = Date.now()
            ElMessage.warning('当前为只读，无编辑权限')
          }
        }
      } catch { /* ignore */ }
    }
    return
  }
  const ae = document.activeElement
  // 引擎自己的单元格 textarea 编辑中：放行（原生输入 + 它自带的 Enter 提交；PM 因无焦点自动忽略这些事件）
  if (ae && ae.tagName === 'TEXTAREA' && ae.closest && ae.closest('.x-spreadsheet')) return
  const inPM = !!(ae && ae.closest && ae.closest('.ProseMirror'))
  const inMe = !!e.target && !!sheetEl.value && sheetEl.value.contains(e.target)
  if (!inPM && !inMe) return
  // document capture 阶段拦截：PM 根再也收不到该按键，块不会被替换删除
  e.stopImmediatePropagation()
  // 焦点在本块（选中格子后直接打字 / 容器持有焦点）：克隆重派发到 window，交给引擎处理
  // KeyboardEvent 构造器不支持 keyCode，用 defineProperty 保留（引擎按 keyCode 分发）
  if (inMe || inPM) {
    const clone = new KeyboardEvent('keydown', { key: e.key, code: e.code, bubbles: true, cancelable: true })
    Object.defineProperty(clone, 'keyCode', { get: () => e.keyCode })
    Object.defineProperty(clone, 'which', { get: () => e.keyCode })
    window.dispatchEvent(clone)
  }
}

// 节点数据变化：结构 / 样式被**他人**改动（合并、冻结、行高、格式）→ 重建引擎以反映。
// 自己刚提交的变更通过签名比对跳过，避免打断正在编辑的单元格。
watch(
  () => props.node.attrs.data,
  () => {
    if (!sheet) return
    const sig = structSignature(parseData())
    if (sig === localStructSig) return
    clearTimeout(structTimer)
    structTimer = setTimeout(() => nextTick(() => mountSheet()), 250)
  }
)

// 块标识变化：
//  ① 被其他端写入 / 改写 → 迁移并重建驱动；
//  ② 被外部重建清空 → 补写。典型场景：编辑器 onSynced 时用 HTML 快照 setContent 重建节点，
//     会把刚写入的 sid 冲掉；不补写则每次刷新都生成新 sid，数据分裂到多个 Y.Map（实测踩到）。
watch(
  () => props.node.attrs.sid,
  (v) => {
    if (v) {
      if (v !== sid) migrateSid(v)
      return
    }
    if (sid && canEdit.value) {
      try { props.updateAttributes({ sid }) } catch { /* 只读或卸载晚期忽略 */ }
    }
  }
)

// 把当前数据（含最新单元格文本）落回节点属性（『转为普通文档』前由编辑器派发事件触发，
// 防转换时拿到空 data 导致表格内容丢失）
function flushData() {
  if (!canEdit.value) return
  try { props.updateAttributes({ data: JSON.stringify(withSid(sheetData())) }) } catch { /* ignore */ }
}

onMounted(() => {
  document.addEventListener('keydown', docKeyDownCapture, true)
  window.addEventListener('kb:flush-diagram', flushData)
  initCollab()
  // 在线协作者：awareness 由 CollaborationCursor 扩展持有（NodeView 只能这样拿到 provider）
  awareness = resolveAwareness()
  if (awareness) {
    awarenessHandler = () => syncPeers()
    try { awareness.on('change', awarenessHandler) } catch { /* ignore */ }
    syncPeers()
  }
  // 引擎的滚动由它内部管理（不触发 DOM scroll 事件），用轻量定时器跟随重算高亮位置
  overlayTimer = setInterval(() => {
    if (peers.value.some((p) => p.here)) overlayTick.value++
  }, 400)
  // 开发态调试钩子：供自动化测试直接驱动引擎（走 setCellText(...,'input')，
  // 与用户输入同样会触发引擎 change，因此能真实覆盖「本地 → Y.Map」同步链路）。
  // import.meta.env.DEV 在生产构建中为 false，整块会被 tree-shake 掉。
  if (import.meta.env.DEV) {
    window.__kbSheetDebug = {
      sid: () => sid,
      canEdit: () => canEdit.value,
      getData: () => sheetData(),
      peers: () => peers.value,
      activePeer: () => activePeer.value,
      remoteBoxes: () => remoteBoxes.value,
      currentCell: () => currentCell(),
      cellScreenRect: (ri, ci) => cellScreenRect(ri, ci),
      /** 屏幕坐标（相对 .kb-sheet-stage）→ 格子；供自动化校验鼠标反查 */
      cellAtPoint: (mx, my) => cellAtPoint(mx, my),
      /** 本端当前广播出去的格子（awareness.sheetFocus） */
      localFocus: () => {
        try { return awareness?.getLocalState?.()?.sheetFocus || null } catch { return null }
      },
      /** 逐格探针：引擎 scroll / cellRect / 我方换算 / 引擎选区 inline 偏移 */
      probe: (ri, ci) => {
        const d = sheet?.sheet?.data
        const q = (s) => document.querySelector(s)
        const rr = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2) } }
        const sel = q('.kb-sheet-canvas .x-spreadsheet-selector-area')
        return {
          scroll: d?.scroll ? { x: d.scroll.x, y: d.scroll.y } : null,
          cellRect: d?.cellRect ? d.cellRect(ri, ci) : null,
          mine: cellScreenRect(ri, ci),
          selInline: sel ? { left: sel.style.left, top: sel.style.top } : null,
          selRect: rr(sel),
          sheet: rr(q('.kb-sheet-canvas .x-spreadsheet-sheet')),
          overlayer: rr(q('.kb-sheet-canvas .x-spreadsheet-overlayer')),
          overlayerContent: rr(q('.kb-sheet-canvas .x-spreadsheet-overlayer-content')),
          tableCanvas: rr(q('.kb-sheet-canvas .x-spreadsheet-table')),
          offset: canvasOriginOffset(),
        }
      },
      /** 几何量测：stage / canvas 容器 / 表格 canvas / 工具栏 的相对关系 */
      geom: () => {
        const q = (s) => document.querySelector(s)
        const r = (el) => { if (!el) return null; const b = el.getBoundingClientRect(); return { x: +b.x.toFixed(2), y: +b.y.toFixed(2), w: +b.width.toFixed(2), h: +b.height.toFixed(2) } }
        return {
          stage: r(q('.kb-sheet-stage')),
          canvasBox: r(q('.kb-sheet-canvas')),
          tableCanvas: r(q('.kb-sheet-canvas .x-spreadsheet-table')),
          toolbar: r(q('.kb-sheet-canvas .x-spreadsheet-toolbar')),
          offset: canvasOriginOffset(),
        }
      },
      /** 节点属性快照（排查 sid/data 持久化） */
      attrs: () => {
        const raw = props.node.attrs.data || ''
        let dataSid = null
        try { dataSid = JSON.parse(raw || '{}').sid || null } catch { /* ignore */ }
        return { sid: props.node.attrs.sid || '', dataLen: raw.length, dataSid, dataHead: raw.slice(0, 160) }
      },
      /** 模拟用户在某格输入（触发 change → 协同同步） */
      typeCell: (r, c, text) => {
        try { sheet?.sheet?.data?.setCellText(r, c, text, 'input'); return true } catch { return false }
      },
      /** 当前 Y.Map 快照（若在协同环境） */
      ymapSnapshot: () => {
        try {
          const m = ydoc?.getMap(sheetMapName(sid))
          const o = {}
          m?.forEach((v, k) => { o[k] = String(v) })
          return o
        } catch { return null }
      },
    }
  }
  nextTick(() => {
    // 写回 sid（必须等 NodeView 位置稳定，见 persistSid 说明）
    persistSid()
    // 首次进入协同：把本地文本播种进 Y.Map 并开始监听远程变更
    try { collab?.bootstrap(parseData()) } catch { /* 忽略 */ }
    mountSheet()
  })
})

onBeforeUnmount(() => {
  clearTimeout(structTimer)
  window.removeEventListener('kb:flush-diagram', flushData)
  document.removeEventListener('keydown', docKeyDownCapture, true)
  setFocus(null)   // 离开本表：撤销 awareness 里的「正在编辑」标记
  if (awareness && awarenessHandler) {
    try { awareness.off('change', awarenessHandler) } catch { /* ignore */ }
  }
  awarenessHandler = null
  awareness = null
  if (overlayTimer) { clearInterval(overlayTimer); overlayTimer = null }
  collab?.destroy()
  collab = null
  if (ro) { ro.disconnect(); ro = null }
  if (sheet) { try { sheet.destroy?.() } catch { /* ignore */ } }
  sheet = null
})
</script>

<style>
.kb-sheet-wrap { margin: 10px 0; }
.kb-sheet {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}
.kb-sheet.editable { border-color: #10b981; }
.kb-sheet-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 12px;
  background: #f8fafc;
  border-bottom: 1px solid var(--border, #e5e7eb);
  font-size: 13px;
}
.kb-sheet-title { font-weight: 600; color: #334155; display: inline-flex; align-items: center; gap: 6px; }
.kb-sheet-ico { flex: none; }
.kb-sheet-status { font-size: 12px; }
.kb-sheet-status.readonly { color: #94a3b8; }
.kb-sheet-status.editing { color: #10b981; }
/* 在线协作者头像 */
.kb-sheet-peers { display: inline-flex; align-items: center; gap: 4px; }
.kb-sheet-avatar {
  width: 20px; height: 20px; border-radius: 50%;
  color: #fff; font-size: 11px; font-weight: 600;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1.5px solid #fff; box-shadow: 0 0 0 1px rgba(15, 23, 42, .06);
  user-select: none;
}
.kb-sheet-peer-n { font-size: 12px; color: #94a3b8; margin-left: 2px; }
.kb-sheet-actions { margin-left: auto; display: flex; gap: 8px; align-items: center; }
.kb-sheet-tip { font-size: 12px; color: #94a3b8; }
.kb-st-btn {
  border: 1px solid var(--border, #e5e7eb);
  background: #fff;
  color: #475569;
  border-radius: 6px;
  padding: 3px 10px;
  font-size: 12px;
  cursor: pointer;
}
.kb-st-btn.primary { background: #10b981; border-color: #10b981; color: #fff; }
.kb-st-btn:hover { opacity: 0.88; }
.kb-sheet-stage { position: relative; }
.kb-sheet-canvas { height: 420px; position: relative; }
.kb-sheet-canvas.readonly { pointer-events: none; }
/* 远端协作者高亮层（叠在 canvas 之上，不拦交互） */
.kb-sheet-overlay { position: absolute; inset: 0; pointer-events: none; overflow: hidden; z-index: 3; }
.kb-sheet-remote {
  position: absolute; box-sizing: border-box;
  border: 2px solid #10b981; border-radius: 2px;
  transition: left .12s ease, top .12s ease, width .12s ease, height .12s ease;
}
.kb-sheet-remote-name {
  position: absolute; left: -2px; top: -17px;
  padding: 0 5px; border-radius: 3px 3px 3px 0;
  color: #fff; font-size: 11px; line-height: 16px; white-space: nowrap; font-weight: 600;
}
.kb-sheet-canvas .x-spreadsheet { width: 100%; }
.kb-sheet-hint {
  padding: 5px 12px;
  font-size: 12px;
  color: #94a3b8;
  background: #fafbfc;
  border-top: 1px dashed var(--border, #e5e7eb);
}
.kb-sheet-wrap.ProseMirror-selectednode .kb-sheet { border-color: #10b981; }

/* 引擎 UI 隔离：文档正文的表格样式（.kb-collab-body table/td：fixed 宽 100%、
   padding、边框、position:relative）会穿透到引擎的色板/日期面板（同为 table 布局），
   导致颜色选择器错乱——就近还原（注意不要动 background，色块背景是 inline style） */
.kb-sheet-canvas table {
  width: auto !important;
  table-layout: auto !important;
  border: none !important;
  margin: 0 !important;
}
.kb-sheet-canvas table td,
.kb-sheet-canvas table th {
  padding: 0 !important;
  border: none !important;
  position: static !important;
  min-width: 0 !important;
  vertical-align: middle !important;
  box-sizing: content-box;
}
</style>
