<template>
  <NodeViewWrapper class="kb-sheet-wrap" data-type="sheet-block">
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
        @mousedown="onMouseDown"
        @keydown="onKeyDown"
      ></div>
      <div v-if="editing" class="kb-sheet-hint editing-hint">Excel 式操作：单元格输入 =SUM(A1:A10) 等公式 · 拖动选区右下角自动填充 · Ctrl+C/V 复制粘贴 · 拖行列边线调宽高 · 工具栏可合并 / 冻结 / 加边框 · 编辑期间其他人只读</div>
      <div v-else class="kb-sheet-hint">点「编辑」后：单元格输入公式（=SUM(...)）、拖拽填充、复制粘贴、合并冻结——与 Excel 同款操作；编辑期间其他人只读</div>
    </div>
  </NodeViewWrapper>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
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
function mountSheet() {
  if (sheet) { try { sheet.destroy?.() } catch { /* 无 destroy 则随 innerHTML 清理 */ } }
  sheet = null
  if (!sheetEl.value) return
  sheetEl.value.innerHTML = ''
  const el = sheetEl.value
  sheet = new Spreadsheet(el, {
    mode: 'edit',
    showToolbar: editing.value,
    showGrid: true,
    showContextmenu: editing.value,
    view: { height: () => 420, width: () => (el.clientWidth || 800) },
    row: { len: 100, height: 25 },
    col: { len: 26, width: 100, indexWidth: 60, minWidth: 60 },
  })
    .loadData(parseData())
    .change((d) => {
      if (!editing.value) return   // 只读端绝不写回
      dirty.value = true
    })
}

// ---------- 事件拦截（防 ProseMirror 抢焦点 / 吃键盘） ----------
function onMouseDown(e) {
  e.stopPropagation()
  const active = document.activeElement
  if (active && active !== document.body && active.closest?.('.ProseMirror')) {
    try { active.blur() } catch { /* ignore */ }
  }
}
// 表格内部键盘（方向键/Enter/Delete/输入框打字）全留给引擎：只阻断冒泡到 PM，
// 不 preventDefault（否则输入框打字、快捷键全废）
function onKeyDown(e) {
  if (editing.value) e.stopPropagation()
}

// ---------- 编辑锁生命周期 ----------
function acquire() {
  if (lockInfo.value && !isMine.value && !lockExpired.value) return
  editing.value = true
  dirty.value = false
  props.updateAttributes({
    locked: JSON.stringify({ uid, uname, ts: Date.now() }),
  })
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
  editing.value = false
  dirty.value = false
  // watch 会以只读态重建实例（渲染最新数据）
}
function cancel() {
  // props.data 未被改动，watch 触发只读重建即回滚
  editing.value = false
  dirty.value = false
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

onMounted(() => {
  nextTick(() => mountSheet())
})
onBeforeUnmount(() => {
  // 编辑中直接卸载：尽量把已做的改动落盘（锁会随落盘/超时释放）
  if (editing.value && dirty.value) {
    try {
      props.updateAttributes({ data: JSON.stringify(sheetData()), locked: '' })
    } catch { /* 卸载晚期属性更新可能失败 */ }
  }
  if (sheet) { try { sheet.destroy?.() } catch { /* ignore */ } }
  sheet = null
  editing.value = false
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
</style>
