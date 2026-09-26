<template>
  <NodeViewWrapper class="kb-fc-wrap" data-type="flowchart-block">
    <div class="kb-fc" :class="{ editing }">
      <div class="kb-fc-bar">
        <span class="kb-fc-title">
          <svg class="kb-fc-ico" viewBox="0 0 24 24" width="15" height="15" fill="none" aria-hidden="true">
            <rect x="2" y="4" width="8" height="6" rx="1.5" fill="#0ea5e9"/>
            <path d="M10 7h4M14 7l4-3v12l-4-3" stroke="#38bdf8" stroke-width="1.5" fill="none"/>
            <rect x="14" y="10" width="8" height="6" rx="1.5" fill="#7dd3fc"/>
          </svg>
          {{ node.attrs.title || '流程图' }}
        </span>
        <span v-if="lockInfo && !isMine" class="kb-fc-status locked">
          {{ lockInfo.uname }} 正在编辑<template v-if="lockExpired">（编辑异常退出，可强制接管）</template>
        </span>
        <span v-else-if="editing" class="kb-fc-status editing">你正在编辑</span>
        <span class="kb-fc-actions">
          <button class="kb-fc-btn" @click="fit">适应视图</button>
          <span v-if="editing" class="kb-fc-palette">
            <button
              v-for="s in SHAPES"
              :key="s.key"
              class="kb-fc-shape"
              :title="'添加' + s.label"
              @click="addShape(s.key)"
            >{{ s.label }}</button>
          </span>
          <button v-if="!editing && canAcquire" class="kb-fc-btn primary" @click="acquire">编辑</button>
          <button
            v-if="!editing && lockInfo && !isMine && lockExpired"
            class="kb-fc-btn"
            @click="acquire"
          >强制接管</button>
          <button v-if="editing" class="kb-fc-btn primary" @click="finish">完成编辑</button>
          <button v-if="editing" class="kb-fc-btn" @click="cancel">取消</button>
        </span>
      </div>
      <div
        ref="mapEl"
        class="kb-fc-canvas"
        :class="{ readonly: !editing }"
        tabindex="0"
        @mousedown="onMouseDown"
        @keydown="onKeyDown"
      >
        <VueFlow
          :id="flowId"
          :nodes-draggable="editing"
          :nodes-connectable="editing"
          :elements-selectable="editing"
          :pan-on-drag="true"
          :zoom-on-scroll="true"
          :select-nodes-on-drag="false"
          :delete-key-code="null"
          :selection-key-code="null"
          :multi-selection-key-code="null"
          :pan-activation-key-code="null"
          :min-zoom="0.3"
          :max-zoom="2"
          :default-edge-options="edgeOptions"
          :is-valid-connection="isValidConnection"
          class="fc-flow"
          @node-click="onNodeClick"
          @edge-click="onEdgeClick"
          @pane-click="onPaneClick"
          @node-drag-stop="onDragStop"
          @connect="onConnect"
        >
          <template #node-fcNode="sp">
            <div
              class="fc-node"
              :class="['shape-' + (sp.data?.shape || 'rect'), { editing: editingId === sp.id, selected: selectedId === sp.id }]"
              @dblclick.stop="startEdit(sp.id)"
            >
              <Handle :id="sp.id + '-t'" type="target" :position="Position.Top" />
              <Handle :id="sp.id + '-l'" type="target" :position="Position.Left" />
              <Handle :id="sp.id + '-b'" type="source" :position="Position.Bottom" />
              <Handle :id="sp.id + '-r'" type="source" :position="Position.Right" />
              <div class="fc-shape" />
              <div class="fc-body">
                <input
                  v-if="editingId === sp.id"
                  ref="editInput"
                  v-model="editText"
                  class="fc-edit"
                  @keydown="onInputKeyDown"
                  @blur="commitEdit(sp.id)"
                />
                <span v-else class="fc-label">{{ sp.data?.label }}</span>
              </div>
            </div>
          </template>

          <template #edge-fcEdge="esp">
            <BaseEdge
              :id="esp.id"
              :path="bezier(esp).path"
              :marker-end="esp.markerEnd"
              :style="edgeStyle"
            />
            <EdgeLabelRenderer>
              <div
                class="fc-edge-label"
                :class="{ selected: editing && esp.selected }"
                :style="labelStyle(bezier(esp))"
                @dblclick.stop="startEditEdge(esp.id)"
                @click.stop="onEdgeLabelClick(esp.id)"
              >{{ esp.data?.label || (editing ? '双击加标签' : '') }}</div>
            </EdgeLabelRenderer>
          </template>
        </VueFlow>
      </div>

      <!-- 编辑态：上下文操作条 -->
      <div v-if="editing" class="kb-fc-ctx">
        <template v-if="selectedEdgeId">
          <span class="kb-fc-ctx-label">连线标签：</span>
          <input
            ref="edgeLabelInput"
            v-model="edgeLabelEdit"
            class="kb-fc-edge-input"
            placeholder="如 是 / 否"
            @keydown.enter="applyEdgeLabel"
            @blur="applyEdgeLabel"
          />
          <button class="kb-fc-btn" @click="applyEdgeLabel">应用</button>
          <button class="kb-fc-btn danger" @click="deleteEdge(selectedEdgeId)">删除连线</button>
        </template>
        <template v-else-if="selectedId">
          <span class="kb-fc-ctx-label">已选图形：</span>
          <button class="kb-fc-btn danger" @click="deleteNode(selectedId)">删除节点</button>
          <span class="kb-fc-tip">从图形右侧 / 底部圆点拖到另一图形左侧 / 顶部圆点即可连线</span>
        </template>
        <template v-else>
          <span class="kb-fc-tip">从上方形状栏添加图形 · 双击图形改文字 · 拖圆点连线 · 点连线加「是/否」标签 · Delete 删除选中</span>
        </template>
      </div>
      <div v-else class="kb-fc-hint">点「编辑」后：形状栏添加图形 · 拖拽摆放 · 从图形圆点拉线连接 · 双击图形改字 · 点连线加标签（如 是/否）· Delete 删除</div>
    </div>
  </NodeViewWrapper>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import {
  VueFlow, useVueFlow, MarkerType, Handle, Position,
  BaseEdge, EdgeLabelRenderer, getBezierPath,
} from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { NodeSelection } from '@tiptap/pm/state'
import { useAuthStore } from '../../store/auth'

const props = defineProps(nodeViewProps)
const auth = useAuthStore()
const uid = String(auth.user?.id ?? auth.user?.username ?? 'me')
const uname = auth.user?.displayName || auth.user?.username || '我'

const flowId = `fc-${Math.random().toString(36).slice(2, 10)}`
const {
  nodes, edges, addNodes, addEdges, setNodes, setEdges,
  removeNodes, removeEdges, findNode, fitView,
} = useVueFlow(flowId)

const SHAPES = [
  { key: 'rect', label: '矩形' },
  { key: 'circle', label: '圆' },
  { key: 'ellipse', label: '椭圆' },
  { key: 'diamond', label: '菱形' },
  { key: 'document', label: '文档' },
  { key: 'step', label: '步骤' },
]

const mapEl = ref(null)
const editInput = ref(null)
const edgeLabelInput = ref(null)
const editing = ref(false)
const selectedId = ref(null)
const selectedEdgeId = ref(null)
const editingId = ref(null)
const editText = ref('')
const edgeLabelEdit = ref('')
const dirty = ref(false)
let snapshot = ''
let idc = 0
let edgec = 0

const LOCK_TIMEOUT = 120000

const edgeOptions = {
  type: 'fcEdge',
  style: { stroke: '#94a3b8', strokeWidth: 1.5 },
  markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' },
}
const edgeStyle = { stroke: '#94a3b8', strokeWidth: 1.5 }

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

// ---------- 数据解析 / 序列化 ----------
function parseData() {
  let raw = null
  try {
    raw = props.node.attrs.data ? JSON.parse(props.node.attrs.data) : null
  } catch {
    raw = null
  }
  if (raw && Array.isArray(raw.nodes)) {
    const ns = raw.nodes.map((n) => ({
      id: n.id,
      type: 'fcNode',
      position: { x: n.position?.x ?? 0, y: n.position?.y ?? 0 },
      data: { label: n.data?.label ?? '', shape: n.data?.shape || 'rect' },
    }))
    const es = (raw.edges || []).map((e) => ({
      id: e.id,
      source: e.source,
      target: e.target,
      sourceHandle: e.sourceHandle ?? `${e.source}-b`,
      targetHandle: e.targetHandle ?? `${e.target}-t`,
      type: 'fcEdge',
      data: { label: e.data?.label ?? '' },
    }))
    return { nodes: ns, edges: es }
  }
  // 预置：一个「开始」矩形节点
  return {
    nodes: [{ id: 'n1', type: 'fcNode', position: { x: 120, y: 120 }, data: { label: '开始', shape: 'rect' } }],
    edges: [],
  }
}

function serialize() {
  const ns = nodes.value.map((n) => ({
    id: n.id,
    type: 'fcNode',
    position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    data: { label: n.data?.label ?? '', shape: n.data?.shape || 'rect' },
  }))
  const es = edges.value.map((e) => ({
    id: e.id,
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle,
    targetHandle: e.targetHandle,
    type: 'fcEdge',
    data: { label: e.data?.label ?? '' },
  }))
  return { nodes: ns, edges: es }
}

function maxIdc(ns) {
  let m = 0
  ns.forEach((n) => {
    const mm = /n(\d+)/.exec(n.id)
    if (mm) m = Math.max(m, +mm[1])
  })
  return m
}
function maxEdgec(es) {
  let m = 0
  es.forEach((e) => {
    const mm = /e(\d+)/.exec(e.id)
    if (mm) m = Math.max(m, +mm[1])
  })
  return m
}

// ---------- 渲染 / 加载 ----------
function loadFromProps() {
  const parsed = parseData()
  idc = maxIdc(parsed.nodes)
  edgec = maxEdgec(parsed.edges)
  setNodes(parsed.nodes)
  setEdges(parsed.edges)
  selectedId.value = parsed.nodes.length ? parsed.nodes[0].id : null
  selectedEdgeId.value = null
}

function fit() {
  ;[0, 200, 500].forEach((delay) => {
    setTimeout(() => {
      Promise.resolve(fitView({ padding: 0.2, duration: 0 })).catch(() => {})
    }, delay)
  })
}

// 计算贝塞尔路径与标签位置（连线 slot 用）
function bezier(esp) {
  const [path, labelX, labelY] = getBezierPath({
    sourceX: esp.sourceX,
    sourceY: esp.sourceY,
    sourcePosition: esp.sourcePosition,
    targetX: esp.targetX,
    targetY: esp.targetY,
    targetPosition: esp.targetPosition,
  })
  return { path, x: labelX, y: labelY }
}
function labelStyle(b) {
  return {
    position: 'absolute',
    transform: `translate(-50%, -50%) translate(${b.x}px, ${b.y}px)`,
    pointerEvents: 'all',
  }
}

// ---------- 形状 / 连线操作 ----------
function addShape(shape) {
  const nid = 'n' + (++idc)
  let maxX = 0
  let maxY = 0
  nodes.value.forEach((n) => {
    maxX = Math.max(maxX, n.position.x)
    maxY = Math.max(maxY, n.position.y)
  })
  // 新图形落在当前最右侧节点右边 200px 处，避免与已有图形重叠
  const x = nodes.value.length ? maxX + 200 : 120
  const y = nodes.value.length ? maxY : 120
  addNodes({
    id: nid,
    type: 'fcNode',
    position: { x, y },
    data: { label: '节点', shape },
  })
  selectedId.value = nid
  selectedEdgeId.value = null
  dirty.value = true
  fit()
  startEdit(nid)
}

// 连线：从源 handle 拖到目标 handle
function onConnect(c) {
  if (c.source === c.target) return
  const eid = 'e' + (++edgec)
  addEdges({
    id: eid,
    source: c.source,
    target: c.target,
    sourceHandle: c.sourceHandle,
    targetHandle: c.targetHandle,
    type: 'fcEdge',
    data: { label: '' },
  })
  selectedEdgeId.value = eid
  selectedId.value = null
  edgeLabelEdit.value = ''
  dirty.value = true
  nextTick(() => edgeLabelInput.value?.focus())
}
function isValidConnection(c) {
  return c.source !== c.target
}

function deleteNode(id) {
  if (!id) return
  removeNodes(id, true)   // 连带删除相连连线
  selectedId.value = nodes.value.length ? nodes.value[0].id : null
  dirty.value = true
  fit()
}
function deleteEdge(id) {
  if (!id) return
  removeEdges(id)
  selectedEdgeId.value = null
  dirty.value = true
}

// 连线标签
function onEdgeClick({ edge }) {
  if (!editing.value) return
  selectedEdgeId.value = edge.id
  selectedId.value = null
  edgeLabelEdit.value = edge.data?.label ?? ''
  nextTick(() => edgeLabelInput.value?.focus())
}
function onEdgeLabelClick(id) {
  if (!editing.value) return
  selectedEdgeId.value = id
  selectedId.value = null
  const e = edges.value.find((x) => x.id === id)
  edgeLabelEdit.value = e?.data?.label ?? ''
  nextTick(() => edgeLabelInput.value?.focus())
}
function startEditEdge(id) {
  onEdgeLabelClick(id)
}
function applyEdgeLabel() {
  if (!selectedEdgeId.value) return
  const id = selectedEdgeId.value
  const val = edgeLabelEdit.value.trim()
  setEdges(edges.value.map((e) =>
    e.id === id ? { ...e, data: { ...(e.data || {}), label: val } } : e
  ))
  dirty.value = true
}

// ---------- 文字编辑 ----------
function startEdit(id) {
  if (!id) return
  const node = findNode(id)
  if (!node) return
  editingId.value = id
  editText.value = node.data?.label ?? ''
  selectedId.value = id
  selectedEdgeId.value = null
  nextTick(() => {
    editInput.value?.focus()
    editInput.value?.select()
  })
}
function commitEdit(id) {
  if (!id || editingId.value !== id) return
  const node = findNode(id)
  if (node) node.data.label = editText.value.trim() || '未命名'
  editingId.value = null
  dirty.value = true
}
function cancelEdit() {
  editingId.value = null
}
function onInputKeyDown(e) {
  e.stopPropagation()
  if (e.key === 'Enter' || e.key === 'Tab') {
    e.preventDefault()
    commitEdit(editingId.value)
  } else if (e.key === 'Escape') {
    e.preventDefault()
    cancelEdit()
  }
}

// ---------- 事件拦截 ----------
function onMouseDown(e) {
  e.stopPropagation()
  const active = document.activeElement
  if (active && active !== document.body && active.closest?.('.ProseMirror')) {
    try { active.blur() } catch { /* ignore */ }
  }
  if (editing.value) mapEl.value?.focus({ preventScroll: true })
}
function onKeyDown(e) {
  if (editingId.value) return
  if (!editing.value) return
  const k = e.key
  if (k !== 'Tab' && k !== 'Enter' && k !== 'F2' && k !== 'Delete' && k !== 'Backspace') return
  e.stopPropagation()
  e.preventDefault()
  if (k === 'Enter' || k === 'F2') {
    if (selectedId.value) startEdit(selectedId.value)
    else if (selectedEdgeId.value) startEditEdge(selectedEdgeId.value)
  } else if (k === 'Delete' || k === 'Backspace') {
    if (selectedEdgeId.value) deleteEdge(selectedEdgeId.value)
    else if (selectedId.value) deleteNode(selectedId.value)
  }
}

function onNodeClick({ node }) {
  if (!editing.value) return
  selectedId.value = node.id
  selectedEdgeId.value = null
  mapEl.value?.focus({ preventScroll: true })
}
function onPaneClick() {
  if (!editing.value) return
  selectedId.value = null
  selectedEdgeId.value = null
}
function onDragStop() {
  if (editing.value) dirty.value = true
}

// ---------- 编辑锁生命周期 ----------
function acquire() {
  if (lockInfo.value && !isMine.value && !lockExpired.value) return
  snapshot = JSON.stringify(serialize())
  editing.value = true
  selectedId.value = nodes.value.length ? nodes.value[0].id : null
  selectedEdgeId.value = null
  props.updateAttributes({
    locked: JSON.stringify({ uid, uname, ts: Date.now() }),
  })
  fit()
}
function finish() {
  if (dirty.value) {
    props.updateAttributes({
      data: JSON.stringify(serialize()),
      locked: '',
    })
  } else {
    props.updateAttributes({ locked: '' })
  }
  editing.value = false
  editingId.value = null
  selectedId.value = null
  selectedEdgeId.value = null
  dirty.value = false
}
function cancel() {
  loadFromProps()
  fit()
  editing.value = false
  editingId.value = null
  selectedId.value = null
  selectedEdgeId.value = null
  dirty.value = false
}

watch(
  [() => props.node.attrs.data, () => props.node.attrs.locked, editing],
  () => {
    if (editing.value) return
    nextTick(() => loadFromProps())
  },
  { immediate: false }
)

// 只读态键盘保护：焦点在 PM 且 NodeSelection 选中本块时，打字会删块——拦截
const guardKey = (e) => {
  if (editing.value) return   // 编辑态由容器拦截逻辑处理
  const ae = document.activeElement
  if (!ae || !ae.closest || !ae.closest('.ProseMirror')) return
  try {
    const sel = props.editor.state.selection
    if (sel instanceof NodeSelection && sel.node.type.name === 'flowchartBlock') {
      e.stopImmediatePropagation()
      e.preventDefault()
    }
  } catch { /* ignore */ }
}

onMounted(() => {
  document.addEventListener('keydown', guardKey, true)
  nextTick(() => {
    loadFromProps()
    fit()
  })
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', guardKey, true)
  if (editing.value && dirty.value) {
    try {
      props.updateAttributes({ data: JSON.stringify(serialize()), locked: '' })
    } catch { /* 卸载晚期属性更新可能失败，锁会随超时自动释放 */ }
  }
  editing.value = false
})
</script>

<style>
.kb-fc-wrap { margin: 10px 0; }
.kb-fc {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}
.kb-fc.editing { border-color: #0ea5e9; }
.kb-fc-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 12px;
  background: #f8fafc;
  border-bottom: 1px solid var(--border, #e5e7eb);
  font-size: 13px;
  flex-wrap: wrap;
}
.kb-fc-title { font-weight: 600; color: #334155; display: inline-flex; align-items: center; gap: 6px; }
.kb-fc-ico { flex: none; }
.kb-fc-status { font-size: 12px; }
.kb-fc-status.locked { color: #d97706; }
.kb-fc-status.editing { color: #0ea5e9; }
.kb-fc-actions { margin-left: auto; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.kb-fc-btn {
  border: 1px solid var(--border, #e5e7eb);
  background: #fff;
  color: #475569;
  border-radius: 6px;
  padding: 3px 10px;
  font-size: 12px;
  cursor: pointer;
}
.kb-fc-btn.primary { background: #0ea5e9; border-color: #0ea5e9; color: #fff; }
.kb-fc-btn.danger { background: #fff; border-color: #fca5a5; color: #dc2626; }
.kb-fc-btn:hover { opacity: 0.88; }
.kb-fc-palette { display: inline-flex; gap: 4px; padding-left: 4px; border-left: 1px dashed var(--border, #e5e7eb); }
.kb-fc-shape {
  border: 1px solid var(--border, #e5e7eb);
  background: #fff;
  color: #334155;
  border-radius: 6px;
  padding: 3px 8px;
  font-size: 12px;
  cursor: pointer;
}
.kb-fc-shape:hover { background: #e0f2fe; border-color: #7dd3fc; }
.kb-fc-canvas { height: 420px; position: relative; outline: none; }
.kb-fc-canvas.readonly { background:
  radial-gradient(circle, #eef2f7 1px, transparent 1px) 0 0 / 22px 22px; }
.kb-fc-hint, .kb-fc-ctx {
  padding: 5px 12px;
  font-size: 12px;
  color: #94a3b8;
  background: #fafbfc;
  border-top: 1px dashed var(--border, #e5e7eb);
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.kb-fc-ctx-label { color: #475569; }
.kb-fc-tip { color: #94a3b8; }
.kb-fc-edge-input {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 5px;
  padding: 3px 8px;
  font-size: 12px;
  width: 140px;
  outline: none;
}
.kb-fc-edge-input:focus { border-color: #0ea5e9; }
.fc-flow { width: 100%; height: 100%; background: transparent; }

/* 节点外观 */
.fc-node {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  min-width: 64px;
  min-height: 40px;
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: grab;
  user-select: none;
}
.kb-fc.editing .fc-node { cursor: grab; }
.fc-node .fc-shape {
  position: absolute;
  inset: 0;
  background: #0ea5e9;
  box-shadow: 0 1px 2px rgba(15,23,42,.12);
}
.fc-node .fc-body {
  position: relative;
  z-index: 2;
  padding: 6px 12px;
  text-align: center;
  max-width: 200px;
}
.fc-label { display: inline-block; white-space: pre-wrap; word-break: break-word; }
.fc-edit {
  width: 120px;
  border: none;
  outline: none;
  background: #fff;
  color: #1e293b;
  border-radius: 5px;
  padding: 3px 6px;
  font-size: 13px;
  font-weight: 600;
  text-align: center;
}
.fc-node.selected .fc-shape { outline: 2px solid #f59e0b; outline-offset: 3px; }
.fc-node.editing { cursor: text; }

/* 形状变体 */
.shape-rect .fc-shape { border-radius: 4px; }
.shape-circle .fc-shape { border-radius: 50%; }
.shape-circle { min-width: 72px; min-height: 72px; }
.shape-ellipse .fc-shape { border-radius: 50%; }
.shape-diamond .fc-shape { clip-path: polygon(50% 0, 100% 50%, 50% 100%, 0 50%); }
.shape-document .fc-shape { clip-path: polygon(0 0, 100% 0, 100% 84%, 50% 100%, 0 84%); }
.shape-step .fc-shape { clip-path: polygon(14% 0, 100% 0, 86% 100%, 0 100%); }

/* 连线手柄 */
.fc-node .vue-flow__handle {
  width: 9px;
  height: 9px;
  background: #0ea5e9;
  border: 2px solid #fff;
  border-radius: 50%;
}
.fc-node .vue-flow__handle:hover { background: #0284c7; }

/* 连线标签 */
.fc-edge-label {
  font-size: 11px;
  color: #475569;
  background: #fff;
  border: 1px solid #cbd5e1;
  border-radius: 4px;
  padding: 1px 6px;
  cursor: pointer;
  white-space: nowrap;
}
.fc-edge-label.selected { border-color: #f59e0b; color: #b45309; }

/* ProseMirror 选中态：外框高亮 */
.kb-fc-wrap.ProseMirror-selectednode .kb-fc { border-color: #0ea5e9; }
</style>
