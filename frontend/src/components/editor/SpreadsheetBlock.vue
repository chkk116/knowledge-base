<template>
  <NodeViewWrapper class="kb-sheet-wrap" data-type="sheet-block" @mousedown.capture="onWrapMouseDown">
    <div class="kb-sheet" :class="{ editing }">
      <div class="kb-sheet-bar">
        <span class="kb-sheet-title">
          <svg class="kb-sheet-ico" viewBox="0 0 24 24" width="15" height="15" fill="none" aria-hidden="true">
            <rect x="3" y="3" width="18" height="18" rx="2" fill="#10b981"/>
            <path d="M3 9h18M3 15h18M9 3v18M15 3v18" stroke="#fff" stroke-width="1.4"/>
          </svg>
          {{ node.attrs.title || '电子表格' }}
        </span>
        <span v-if="lockInfo && !isMine" class="kb-sheet-status locked">
          {{ lockInfo.uname }} 正在编辑<template v-if="lockExpired">（编辑异常退出，可强制接管）</template>
        </span>
        <span v-else-if="editing" class="kb-sheet-status editing">你正在编辑</span>
        <span class="kb-sheet-actions">
          <button v-if="!editing && canAcquire" class="kb-st-btn primary" @click="acquire">编辑</button>
          <button
            v-if="!editing && lockInfo && !isMine && lockExpired"
            class="kb-st-btn"
            @click="acquire"
          >强制接管</button>
          <button v-if="editing" class="kb-st-btn primary" @click="finish">完成编辑</button>
          <button v-if="editing" class="kb-st-btn" @click="cancel">取消</button>
        </span>
      </div>
      <div
        ref="sheetEl"
        class="kb-sheet-canvas"
        :class="{ readonly: !editing }"
        tabindex="0"
        @mousedown="onMouseDown"
      ></div>
      <div v-if="editing" class="kb-sheet-hint editing-hint">Excel 式操作：单元格输入 =SUM(A1:A10) 等公式 · 拖动选区右下角自动填充 · Ctrl+C/V 复制粘贴 · 拖行列边线调宽高 · 工具栏可合并 / 冻结 / 加边框 · 编辑期间其他人只读</div>
      <div v-else class="kb-sheet-hint">点「编辑」后：单元格输入公式（=SUM(...)）、拖拽填充、复制粘贴、合并冻结——与 Excel 同款操作；编辑期间其他人只读</div>
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
import { useAuthStore } from '../../store/auth'

const props = defineProps(nodeViewProps)
const auth = useAuthStore()
const uid = String(auth.user?.id ?? auth.user?.username ?? 'me')
const uname = auth.user?.displayName || auth.user?.username || '我'

const sheetEl = ref(null)
// 编辑态从锁属性派生（而非组件内存）：Collaboration 下属性更新可能重建 NodeView，
// 内存 ref 会被冲掉（实测：点「编辑」后 updateAttributes(locked) 导致重建，editing 丢回 false，
// 用户一打字 PM 就把块当 NodeSelection 替换掉——表格消失的根因）。派生后重建即自愈。
// 编辑态用本地 ref：acquire/finish 显式置位；下面的 locked watch 负责在
// NodeView 被 Collaboration 重建后恢复状态（immediate，锁是我的即恢复编辑态）
const editing = ref(false)
const dirty = ref(false)
let sheet = null        // Spreadsheet 实例
const LOCK_TIMEOUT = 120000

// ---------- 锁状态 ----------
const lockInfo = computed(() => {
  try {
    return props.node.attrs.locked ? JSON.parse(props.node.attrs.locked) : null
  } catch {
    return null
  }
})
const isMine = computed(() => !!lockInfo.value && String(lockInfo.value.uid) === uid)
const lockExpired = computed(
  () => !!lockInfo.value && Date.now() - Number(lockInfo.value.ts) > LOCK_TIMEOUT
)
const canAcquire = computed(() => props.editor.isEditable && !editing.value &&
  (!lockInfo.value || isMine.value || lockExpired.value))

// ---------- 数据解析 ----------
function parseData() {
  try {
    return props.node.attrs.data ? JSON.parse(props.node.attrs.data) : {}
  } catch {
    return {}
  }
}

// ---------- 引擎挂载 / 重建 ----------
// 编辑态与只读态的构造选项不同（工具栏/右键菜单），切态即重建实例；
// 只读态用 CSS pointer-events:none 禁交互（canvas 预览，内容照常渲染）。
let ro = null            // 容器尺寸观察：初始布局延迟/窗口变化时以实际尺寸重建引擎
let lastH = 0
function mountSheet() {
  if (sheet) { try { sheet.destroy?.() } catch { /* 无 destroy 则随 innerHTML 清理 */ } }
  sheet = null
  if (!sheetEl.value) return
  const el = sheetEl.value
  el.innerHTML = ''
  sheet = new Spreadsheet(el, {
    mode: 'edit',
    showToolbar: editing.value,
    showGrid: true,
    showContextmenu: editing.value,
    // 高度取容器实际值（flex 链分配后的剩余高度），引擎内部按此绘制
    view: { height: () => (el.clientHeight || 420), width: () => (el.clientWidth || 800) },
    row: { len: 100, height: 25 },
    col: { len: 26, width: 100, indexWidth: 60, minWidth: 60 },
  })
    .loadData(parseData())
    .change(() => {
      if (!editing.value) return   // 只读端绝不写回
      dirty.value = true
    })
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

// ---------- 事件拦截（防 ProseMirror 抢焦点 / 吃键盘） ----------
// 只读态：块区域 pointer-events:none 会让点击穿透到 PM 产生 NodeSelection，
// 随后任意打字都会删块——在 wrapper 捕获层直接吞掉点击
function onWrapMouseDown(e) {
  if (!editing.value) { e.stopPropagation(); e.preventDefault() }
}
function onMouseDown(e) {
  e.stopPropagation()
  const active = document.activeElement
  if (active && active !== document.body && active.closest?.('.ProseMirror')) {
    try { active.blur() } catch { /* ignore */ }
  }
  if (editing.value && sheetEl.value) sheetEl.value.focus({ preventScroll: true })
}

// 键盘统一在 document 捕获阶段拦截（编辑态）：
//  - 焦点在 ProseMirror 时打字，PM 会用字符「替换」被 NodeSelection 选中的本块 → 表格消失（真实环境实测复现）
//  - x-data-spreadsheet 的键盘监听绑在 window 上；PM 根是 target 时容器冒泡层拦不到，必须在 document capture 层处理
const docKeyDownCapture = (e) => {
  // 只读态保护：焦点在 PM 且 NodeSelection 选中的是本块时，打字会把块替换成文本（删块）——
  // 拦截并提示去点「编辑」。普通模式下的正文打字不受影响（选中的不是本块就不拦）。
  if (!editing.value) {
    const ae = document.activeElement
    if (ae && ae.closest && ae.closest('.ProseMirror')) {
      try {
        const sel = props.editor.state.selection
        if (sel instanceof NodeSelection && sel.node.type.name === 'sheetBlock') {
          e.stopImmediatePropagation()
          e.preventDefault()
          if (Date.now() - roTipTs > 3000) {
            roTipTs = Date.now()
            ElMessage.warning('当前为只读预览，点击右上「编辑」后即可修改')
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

// ---------- 编辑锁生命周期 ----------
// 锁心跳：编辑中每 60s 续一次 locked.ts，避免 2 分钟超时把派生编辑态踢出
let lockHeartbeat = null
let roTipTs = 0   // 只读提示节流
function startHeartbeat() {
  stopHeartbeat()
  lockHeartbeat = setInterval(() => {
    if (!editing.value) return stopHeartbeat()
    try {
      props.updateAttributes({ locked: JSON.stringify({ uid, uname, ts: Date.now() }) })
    } catch { /* ignore */ }
  }, 60000)
}
function stopHeartbeat() {
  if (lockHeartbeat) { clearInterval(lockHeartbeat); lockHeartbeat = null }
}
function acquire() {
  if (lockInfo.value && !isMine.value && !lockExpired.value) return
  dirty.value = false
  editing.value = true   // 本地立即生效；locked 属性随后经 Yjs 同步（watch 同步态，重建也恢复）
  // 写锁 → 属性经 Yjs 同步，其他端看到「xxx 正在编辑」
  props.updateAttributes({
    locked: JSON.stringify({ uid, uname, ts: Date.now() }),
  })
  startHeartbeat()
  nextTick(() => mountSheet())   // 重建为带工具栏的编辑态
}
function sheetData() {
  try { return sheet ? sheet.getData() : parseData() } catch { return parseData() }
}
function finish() {
  if (dirty.value) {
    props.updateAttributes({
      data: JSON.stringify(sheetData()),
      locked: '',
    })
  } else {
    props.updateAttributes({ locked: '' })
  }
  dirty.value = false
  stopHeartbeat()
  // 锁清空 → editing 派生为 false，watch 以只读态重建实例（渲染最新数据）
}
function cancel() {
  // 释放锁即可：data 从未落盘，locked 清空后 watch 触发只读重建即回滚
  props.updateAttributes({ locked: '' })
  dirty.value = false
  stopHeartbeat()
}

// 数据/锁/编辑态变化：非编辑态时重建渲染最新数据（含协作者的改动）
watch(
  [() => props.node.attrs.data, () => props.node.attrs.locked, editing],
  () => {
    if (editing.value) return   // 编辑中不被外部刷新打断
    nextTick(() => mountSheet())
  },
  { immediate: false }
)

// 锁属性变化 → 同步编辑态（含 NodeView 重建后的恢复：immediate 时锁是我的即回到编辑态）
watch(
  () => props.node.attrs.locked,
  (v) => {
    try {
      const li = v ? JSON.parse(v) : null
      editing.value = !!li && String(li.uid) === uid
    } catch {
      editing.value = false
    }
  },
  { immediate: true }
)

onMounted(() => {
  document.addEventListener('keydown', docKeyDownCapture, true)
  nextTick(() => mountSheet())
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', docKeyDownCapture, true)
  stopHeartbeat()
  // 编辑中直接卸载：尽量把已做的改动落盘（锁会随落盘/超时释放）
  if (editing.value && dirty.value) {
    try {
      props.updateAttributes({ data: JSON.stringify(sheetData()), locked: '' })
    } catch { /* 卸载晚期属性更新可能失败 */ }
  }
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
.kb-sheet.editing { border-color: #10b981; }
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
.kb-sheet-status.locked { color: #d97706; }
.kb-sheet-status.editing { color: #10b981; }
.kb-sheet-actions { margin-left: auto; display: flex; gap: 8px; }
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
.kb-sheet-canvas { height: 420px; position: relative; }
.kb-sheet-canvas.readonly { pointer-events: none; }
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
