<template>
  <NodeViewWrapper class="kb-mindmap-wrap" data-type="mindmap-block">
    <div class="kb-mindmap" :class="{ editing }">
      <div class="kb-mindmap-bar">
        <span class="kb-mindmap-title">
          <svg class="kb-mindmap-ico" viewBox="0 0 24 24" width="15" height="15" fill="none" aria-hidden="true">
            <rect x="9" y="2" width="6" height="5" rx="1.5" fill="#6366f1"/>
            <rect x="2" y="16" width="6" height="5" rx="1.5" fill="#a5b4fc"/>
            <rect x="16" y="16" width="6" height="5" rx="1.5" fill="#a5b4fc"/>
            <path d="M12 7v4M12 11H5v5M12 11h7v5" stroke="#818cf8" stroke-width="1.5"/>
          </svg>
          {{ node.attrs.title || '思维导图' }}
        </span>
        <span v-if="lockInfo && !isMine" class="kb-mindmap-status locked">
          🔒 {{ lockInfo.uname }} 正在编辑 · {{ lockRemainText }}后可接管
        </span>
        <span v-else-if="editing" class="kb-mindmap-status editing">你正在编辑</span>
        <span class="kb-mindmap-actions">
          <button class="kb-mm-btn" @click="fit">适应视图</button>
          <button v-if="editing" class="kb-mm-btn" @click="addFreeTopic">+ 自由主题</button>
          <button v-if="!editing && canAcquire" class="kb-mm-btn primary" @click="acquire">编辑导图</button>
          <button
            v-if="!editing && lockInfo && !isMine"
            class="kb-mm-btn"
            @click="acquire"
          >{{ lockExpired ? '强制接管' : '接管（' + lockRemainText + '）' }}</button>
          <button v-if="editing" class="kb-mm-btn primary" @click="finish">完成编辑</button>
          <button v-if="editing" class="kb-mm-btn" @click="cancel">取消</button>
          <button
            class="kb-mm-btn help"
            :class="{ on: helpOpen }"
            title="使用说明"
            aria-label="使用说明"
            @click="helpOpen = !helpOpen"
          >?</button>
        </span>
      </div>

      <!-- 使用说明：常驻按钮触发，默认收起（与流程图同一套交互） -->
      <div v-if="helpOpen" class="kb-mm-help" @click.stop>
        <div class="fh-head">
          <span>思维导图使用说明</span>
          <button class="fh-close" @click="helpOpen = false">×</button>
        </div>
        <ul>
          <li><b>改文字</b>：双击节点，或选中后按 F2</li>
          <li><b>Tab</b>：保存当前主题并新建子主题（向右延伸）</li>
          <li><b>Enter</b>：保存当前主题并新建同级；选中根节点按 Enter 或点「+ 自由主题」＝不连线的新主题</li>
          <li><b>删除</b>：选中节点后按 Delete / Backspace，会连带删掉整个子树</li>
          <li><b>摆放</b>：节点可直接拖拽；空白处按住拖动平移画布，滚轮缩放</li>
          <li><b>视图</b>：点「适应视图」把全部内容收进画面</li>
          <li><b>保存</b>：点「完成编辑」落盘；「取消」放弃本次改动</li>
        </ul>
      </div>
      <div
        ref="mapEl"
        class="kb-mindmap-canvas"
        :class="{ readonly: !editing }"
        tabindex="0"
        @mousedown="onMouseDown"
        @keydown="onKeyDown"
      >
        <VueFlow
          :id="flowId"
          :nodes-draggable="editing"
          :nodes-connectable="false"
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
          class="mm-flow"
          @node-click="onNodeClick"
          @pane-click="onPaneClick"
          @node-drag="onNodeDrag"
          @node-drag-stop="onDragStop"
        >
          <template #node-mindNode="slotProps">
            <div
              class="mm-node"
              :class="{ editing: editingId === slotProps.id }"
              @dblclick.stop="startEdit(slotProps.id)"
            >
              <!-- 八向连接点：每个方向各有一个 source 和一个 target。
                   父子相对位置决定从哪一侧出入，故四个方向都必须能「出」也能「入」。
                   sourceHandle 只能指向 source 型 Handle、targetHandle 只能指向 target 型 Handle，
                   否则在 ConnectionMode.Strict 下会静默丢线。 -->
              <Handle type="source" :position="Position.Left" :id="HANDLE.leftSource" class="mm-handle mm-h-left" />
              <Handle type="target" :position="Position.Left" :id="HANDLE.leftTarget" class="mm-handle mm-h-left" />
              <Handle type="source" :position="Position.Top" :id="HANDLE.topSource" class="mm-handle mm-h-top" />
              <Handle type="target" :position="Position.Top" :id="HANDLE.topTarget" class="mm-handle mm-h-top" />
              <Handle type="source" :position="Position.Right" :id="HANDLE.rightSource" class="mm-handle mm-h-right" />
              <Handle type="target" :position="Position.Right" :id="HANDLE.rightTarget" class="mm-handle mm-h-right" />
              <Handle type="source" :position="Position.Bottom" :id="HANDLE.bottomSource" class="mm-handle mm-h-bottom" />
              <Handle type="target" :position="Position.Bottom" :id="HANDLE.bottomTarget" class="mm-handle mm-h-bottom" />
              <input
                v-if="editingId === slotProps.id"
                ref="editInput"
                v-model="editText"
                class="mm-edit"
                @keydown="onInputKeyDown"
                @blur="commitEdit(slotProps.id)"
              />
              <span v-else class="mm-label">{{ slotProps.data?.label }}</span>
            </div>
          </template>
        </VueFlow>
      </div>
    </div>
  </NodeViewWrapper>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import { VueFlow, useVueFlow, MarkerType, Handle, Position } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import { NodeSelection } from '@tiptap/pm/state'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../../store/auth'

const props = defineProps(nodeViewProps)
const auth = useAuthStore()
const uid = String(auth.user?.id ?? auth.user?.username ?? 'me')
const uname = auth.user?.displayName || auth.user?.username || '我'

const flowId = `mm-${Math.random().toString(36).slice(2, 10)}`
const {
  nodes, edges, addNodes, addEdges, setNodes, setEdges,
  removeNodes, findNode, fitView,
} = useVueFlow(flowId)

const mapEl = ref(null)
const editInput = ref(null)
const editing = ref(false)
const selectedId = ref(null)
const editingId = ref(null)
const editText = ref('')
const dirty = ref(false)
const helpOpen = ref(false)   // 使用说明面板：常驻按钮触发，默认收起
let snapshot = ''   // 进入编辑时的数据快照，取消时回滚用
let idc = 0         // 自增 id 计数器（新节点用 n{idc}）

const LOCK_TIMEOUT = 120000   // 锁超时：持锁端异常退出 2 分钟后允许强制接管

// 锁心跳：编辑中每 60s 续一次 locked.ts，避免 2 分钟超时把自己踢出（他人即可接管）
let lockHeartbeat = null
function startHeartbeat() {
  stopHeartbeat()
  lockHeartbeat = setInterval(() => {
    if (!editing.value) return stopHeartbeat()
    try { props.updateAttributes({ locked: JSON.stringify({ uid, uname, ts: Date.now() }) }) } catch { /* ignore */ }
  }, 60000)
}
function stopHeartbeat() { if (lockHeartbeat) { clearInterval(lockHeartbeat); lockHeartbeat = null } }

const edgeOptions = {
  // 保留圆角折线（与参考图一致）；真正决定观感的是连接点选边，不是线型
  type: 'smoothstep',
  style: { stroke: '#94a3b8', strokeWidth: 1.5 },
  markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' },
}

/** 八向连接点 id（模板与选边逻辑共用，避免字符串写歪）。
 *  约定：ls/ts/rs/bs = 出线点（source），lt/tt/rt/bt = 入线点（target）。 */
const HANDLE = {
  leftSource: 'ls',
  leftTarget: 'lt',
  topSource: 'ts',
  topTarget: 'tt',
  rightSource: 'rs',
  rightTarget: 'rt',
  bottomSource: 'bs',
  bottomTarget: 'bt',
}

/** 节点尺寸估算（仅用于「哪一侧更近」的判断，不影响真实 DOM 尺寸）。
 *  TODO: 这是近似值；Vue Flow 布局后会把真实尺寸写在 node.dimensions，
 *  换用真实尺寸可提升宽/高差异大的节点选边准确度。 */
const NODE_W = 140
const NODE_H = 36

/**
 * 按父子相对位置选连接方向 —— 这是「连线不再统一绕到底部」的关键。
 * 做法：比较两个节点的中心点偏移，取主方向决定从哪一侧出入；拖动过程中实时重算。
 * 返回 { sourceHandle, targetHandle }，写回边的对应字段（不改变 source/target 本身）。
 */
function pickHandles(parentPos, childPos) {
  const dx = (childPos.x + NODE_W / 2) - (parentPos.x + NODE_W / 2)
  const dy = (childPos.y + NODE_H / 2) - (parentPos.y + NODE_H / 2)
  // 横向是主方向（脑图默认左→右）：|dx| 占优就走左右
  if (Math.abs(dx) >= Math.abs(dy)) {
    return dx >= 0
      ? { sourceHandle: HANDLE.rightSource, targetHandle: HANDLE.leftTarget }    // 子在右：父右出 → 子左入
      : { sourceHandle: HANDLE.leftSource, targetHandle: HANDLE.rightTarget }    // 子在左：父左出 → 子右入
  }
  return dy >= 0
    ? { sourceHandle: HANDLE.bottomSource, targetHandle: HANDLE.topTarget }      // 子在下：父下出 → 子上入
    : { sourceHandle: HANDLE.topSource, targetHandle: HANDLE.bottomTarget }      // 子在上：父上出 → 子下入
}

/**
 * 重算全部连线的连接点。纯几何计算，不动坐标、不改父子关系，
 * 因此不会覆盖用户手动摆放的位置。
 */
function refreshEdgeHandles() {
  if (!edges.value.length) return
  const changed = []
  edges.value.forEach((e) => {
    const s = findNode(e.source)
    const t = findNode(e.target)
    if (!s || !t) return
    const h = pickHandles(s.position, t.position)
    if (e.sourceHandle !== h.sourceHandle || e.targetHandle !== h.targetHandle) {
      changed.push({ id: e.id, ...h })
    }
  })
  if (changed.length) {
    edges.value = edges.value.map((e) => {
      const c = changed.find((x) => x.id === e.id)
      return c ? { ...e, sourceHandle: c.sourceHandle, targetHandle: c.targetHandle } : e
    })
  }
}

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

// 锁剩余时间倒计时（每秒 tick；持有者心跳刷新 ts，倒计时随之重置）
const nowTick = ref(Date.now())
let tickTimer = null
const lockRemainSec = computed(() => {
  const li = lockInfo.value
  if (!li) return 0
  return Math.max(0, Math.ceil((LOCK_TIMEOUT - (nowTick.value - Number(li.ts))) / 1000))
})
const lockRemainText = computed(() => {
  const sec = lockRemainSec.value
  if (sec <= 0) return '可接管'
  const m = Math.floor(sec / 60), ss = sec % 60
  return (m > 0 ? m + '分' : '') + ss + '秒'
})
// 编辑器只读（查看模式）时不能获取编辑权
const canAcquire = computed(() => props.editor.isEditable && !editing.value &&
  (!lockInfo.value || isMine.value || lockExpired.value))

// ---------- 数据解析 / 序列化 ----------

/** 把旧 simple-mind-map / mind-elixir 格式转成 Vue Flow 节点+边（左→右布局） */
function smmToFlow(root) {
  const ns = []
  const es = []
  let order = 0
  const walk = (node, depth, parentId) => {
    const id = 'n' + (++order)
    const label = String(node.data?.text ?? node.topic ?? '')
    ns.push({
      id, type: 'mindNode',
      position: { x: 40 + depth * 260, y: order * 80 + 20 },
      data: { label },
    })
    if (parentId) es.push({ id: 'e' + id, source: parentId, target: id })
    ;(node.children || []).forEach((c) => walk(c, depth + 1, id))
  }
  walk(root, 0, null)
  return { nodes: ns, edges: es }
}

/** 解析节点属性里的导图数据；兼容旧格式（一次性转换） */
function parseData() {
  let raw = null
  try {
    raw = props.node.attrs.data ? JSON.parse(props.node.attrs.data) : null
  } catch {
    raw = null
  }
  if (raw && Array.isArray(raw.nodes)) {
    // 已是 Vue Flow 格式
    const ns = raw.nodes.map((n) => ({
      id: n.id,
      type: 'mindNode',
      position: { x: n.position?.x ?? 0, y: n.position?.y ?? 0 },
      data: { label: n.data?.label ?? '' },
    }))
    const es = (raw.edges || []).map((e) => {
      const o = { id: e.id, source: e.source, target: e.target }
      if (e.sourceHandle) o.sourceHandle = e.sourceHandle
      if (e.targetHandle) o.targetHandle = e.targetHandle
      return o
    })
    return { nodes: ns, edges: es }
  }
  if (raw && raw.data && typeof raw.data.text === 'string') {
    return smmToFlow(raw)   // simple-mind-map 格式
  }
  if (raw && raw.nodeData && typeof raw.nodeData.topic === 'string') {
    return smmToFlow({ data: { text: raw.nodeData.topic, children: raw.nodeData.children } })  // mind-elixir 旧格式
  }
  // 预置：一个中心主题（打开即有结构可改；Enter 中心主题即可加自由主题）
  return {
    nodes: [{ id: 'n1', type: 'mindNode', position: { x: 60, y: 200 }, data: { label: props.node.attrs.title || '中心主题' } }],
    edges: [],
  }
}

/** 当前画布序列化为可落盘 JSON（仅保留必要字段） */
function serialize() {
  const ns = nodes.value.map((n) => ({
    id: n.id,
    type: 'mindNode',
    position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    data: { label: n.data?.label ?? '' },
  }))
  const es = edges.value.map((e) => {
    const o = { id: e.id, source: e.source, target: e.target }
    // 连接点随位置变化，落盘保留可让重开后先按原方向渲染（随后仍会 refreshEdgeHandles 校准）
    if (e.sourceHandle) o.sourceHandle = e.sourceHandle
    if (e.targetHandle) o.targetHandle = e.targetHandle
    return o
  })
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

// ---------- 渲染 / 加载 ----------

function loadFromProps() {
  const parsed = parseData()
  idc = maxIdc(parsed.nodes)
  setNodes(parsed.nodes)
  setEdges(parsed.edges)
  selectedId.value = parsed.nodes.length ? parsed.nodes[0].id : null
  nextTick(() => refreshEdgeHandles())   // 节点测量完成后按相对位置定连接点
}

function fit() {
  // fitView 依赖节点 DOM 测量：新建节点当场 fit 会因拿不到其尺寸而把它排除在
  // 包围盒外（fitView 还会 resolve(true) 不再重试），节点被留在画布外裁掉。
  // 因此 0/200/500ms 三次补 fit（duration:0 立即生效），测量完成后必能收进视口。
  ;[0, 200, 500].forEach((delay) => {
    setTimeout(() => {
      Promise.resolve(fitView({ padding: 0.2, duration: 0 })).catch(() => { /* ignore */ })
    }, delay)
  })
}

// ---------- 编辑语义（Tab / Enter / F2 / Delete） ----------

function getParentId(id) {
  const e = edges.value.find((x) => x.target === id)
  return e ? e.source : null
}
function getChildren(id) {
  return edges.value.filter((x) => x.source === id).map((x) => x.target)
}

/** 自由主题：不连任何线的新根节点（多中心），落在现有内容右下方的空白处 */
function addFreeTopic() {
  const nid = 'n' + (++idc)
  let maxX = 60, maxY = 200
  nodes.value.forEach((n) => {
    maxX = Math.max(maxX, n.position.x)
    maxY = Math.max(maxY, n.position.y)
  })
  addNodes({
    id: nid, type: 'mindNode',
    position: nodes.value.length ? { x: maxX + 140, y: maxY + 60 } : { x: 60, y: 200 },
    data: { label: '自由主题' },
  })
  selectedId.value = nid
  dirty.value = true
  fit()
  startEdit(nid)
}

function addChild(id) {
  if (!id) return addFreeTopic()
  const parent = findNode(id)
  if (!parent) return
  const nid = 'n' + (++idc)
  const nKids = getChildren(id).length
  addNodes({
    id: nid, type: 'mindNode',
    position: { x: parent.position.x + 260, y: parent.position.y + nKids * 80 + 20 },
    data: { label: '新主题' },
  })
  addEdges({ id: 'e' + nid, source: id, target: nid })
  selectedId.value = nid
  dirty.value = true
  refreshEdgeHandles()
  fit()
  startEdit(nid)
}

function addSibling(id) {
  if (!id) return addFreeTopic()
  const node = findNode(id)
  if (!node) return
  const parentId = getParentId(id)
  if (!parentId) {
    // 根节点 → 新增「自由主题」（与现有导图平级，不挂任何父节点）
    const nid = 'n' + (++idc)
    addNodes({
      id: nid, type: 'mindNode',
      position: { x: node.position.x + 40, y: node.position.y + 150 },
      data: { label: '自由主题' },
    })
    selectedId.value = nid
    dirty.value = true
    fit()
    startEdit(nid)
    return
  }
  const parent = findNode(parentId)
  const nid = 'n' + (++idc)
  // 放到父节点现有子节点的最下方，避免连按 Enter 时兄弟节点全叠在同一坐标
  let bottomY = node.position.y
  getChildren(parentId).forEach((kidId) => {
    const k = findNode(kidId)
    if (k) bottomY = Math.max(bottomY, k.position.y)
  })
  addNodes({
    id: nid, type: 'mindNode',
    position: { x: parent.position.x + 260, y: bottomY + 90 },
    data: { label: '新主题' },
  })
  addEdges({ id: 'e' + nid, source: parentId, target: nid })
  selectedId.value = nid
  dirty.value = true
  refreshEdgeHandles()
  fit()
  startEdit(nid)
}

function deleteSelected(id) {
  if (!id) return
  const toRemove = new Set([id])
  let changed = true
  while (changed) {
    changed = false
    for (const e of edges.value) {
      if (toRemove.has(e.source) && !toRemove.has(e.target)) {
        toRemove.add(e.target)
        changed = true
      }
    }
  }
  removeNodes(Array.from(toRemove))   // 默认会连带删除相关连线
  selectedId.value = nodes.value.length ? nodes.value[0].id : null
  dirty.value = true
  refreshEdgeHandles()
  fit()
}

// ---------- 文字编辑 ----------

function startEdit(id) {
  if (!id) return
  const node = findNode(id)
  if (!node) return
  editingId.value = id
  editText.value = node.data?.label ?? ''
  nextTick(() => {
    editInput.value?.focus()
    editInput.value?.select()
  })
}
function commitEdit(id) {
  // 只提交「自己这个节点」的编辑：连建时旧输入框卸载会触发 blur，
  // 此时 editingId 已指向新建节点，不守卫会把新节点的编辑态误关掉
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
  if (e.key === 'Enter') {
    e.preventDefault()
    const id = editingId.value
    commitEdit(id)
    addSibling(id)   // 输入中按 Enter：保存并直接建同级，免确认连建
  } else if (e.key === 'Tab') {
    e.preventDefault()
    const id = editingId.value
    commitEdit(id)
    addChild(id)     // 输入中按 Tab：保存并直接建子节点
  } else if (e.key === 'Escape') {
    e.preventDefault()
    cancelEdit()
  }
}

// ---------- 事件拦截（防 ProseMirror 抢焦点 / 抢输入） ----------

// 容器层拦截：mousedown 阻止冒泡到 PM（避免 PM 抢焦点）；keydown 仅处理我们关心的
// 快捷键，其余放行。Vue Flow 快捷键已通过 delete-key-code/selection-key-code=null 关闭，
// 所有 Tab/Enter/F2/Delete 由我们自行分发，行为与 simple-mind-map 版一致。
function onMouseDown(e) {
  e.stopPropagation()
  const active = document.activeElement
  if (active && active !== document.body && active.closest?.('.ProseMirror')) {
    try { active.blur() } catch { /* ignore */ }
  }
  if (editing.value) mapEl.value?.focus({ preventScroll: true })
}
function onKeyDown(e) {
  if (editingId.value) return            // 正在编辑文字：交给输入框处理
  if (!editing.value) return            // 查看模式：不拦截，交给文档
  const k = e.key
  if (k !== 'Tab' && k !== 'Enter' && k !== 'F2' && k !== 'Delete' && k !== 'Backspace') return
  e.stopPropagation()
  e.preventDefault()
  if (k === 'Tab') addChild(selectedId.value)
  else if (k === 'Enter') addSibling(selectedId.value)
  else if (k === 'F2') startEdit(selectedId.value)
  else if (k === 'Delete' || k === 'Backspace') deleteSelected(selectedId.value)
}

function onNodeClick({ node }) {
  if (!editing.value) return
  selectedId.value = node.id
  mapEl.value?.focus({ preventScroll: true })
}
function onPaneClick() {
  if (!editing.value) return
  selectedId.value = null
}
function onDragStop() {
  if (editing.value) dirty.value = true
  refreshEdgeHandles()   // 位置变了 → 重新选边，连线跟着节点走
}
/** 拖动过程中实时重算连接点（节流：每帧最多一次，避免频繁触发重渲染） */
let dragRaf = 0
function onNodeDrag() {
  if (dragRaf) return
  dragRaf = requestAnimationFrame(() => {
    dragRaf = 0
    refreshEdgeHandles()
  })
}

// ---------- 编辑锁生命周期 ----------

function acquire() {
  if (lockInfo.value && !isMine.value && !lockExpired.value) {
    ElMessage.warning('「' + lockInfo.value.uname + '」正在编辑，约 ' + lockRemainText.value + ' 后可接管（对方关闭页面或掉线会提前释放）')
    return
  }
  snapshot = JSON.stringify(serialize())
  editing.value = true
  startHeartbeat()
  selectedId.value = nodes.value.length ? nodes.value[0].id : null
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
  stopHeartbeat()
  editingId.value = null
  selectedId.value = null
  dirty.value = false
}
function cancel() {
  // 取消：回滚到进入编辑前的数据（props 尚未被改动，直接重载即可）
  loadFromProps()
  fit()
  editing.value = false
  stopHeartbeat()
  editingId.value = null
  selectedId.value = null
  dirty.value = false
}

// 数据/锁/编辑态变化：非编辑态时同步渲染最新数据（含协作者的改动）
watch(
  [() => props.node.attrs.data, () => props.node.attrs.locked, editing],
  () => {
    if (editing.value) return   // 编辑中不被外部刷新打断
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
    if (sel instanceof NodeSelection && sel.node.type.name === 'mindmapBlock') {
      e.stopImmediatePropagation()
      e.preventDefault()
    }
  } catch { /* ignore */ }
}

// 把编辑中数据从引擎内存落回节点属性（『转为普通文档』前由编辑器派发事件触发，
// 防转换时拿到空 data 导致表格/图块内容丢失）
function flushData() {
  if (editing.value && dirty.value) {
    try { props.updateAttributes({ data: JSON.stringify(serialize()) }) } catch { /* ignore */ }
  }
}

onMounted(() => {
  tickTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
  document.addEventListener('keydown', guardKey, true)
  nextTick(() => {
    loadFromProps()
    fit()
  })
  window.addEventListener('kb:flush-diagram', flushData)
})
onBeforeUnmount(() => {
  if (dragRaf) { cancelAnimationFrame(dragRaf); dragRaf = 0 }
  clearInterval(tickTimer)
  window.removeEventListener('kb:flush-diagram', flushData)
  stopHeartbeat()
  document.removeEventListener('keydown', guardKey, true)
  // 编辑中直接卸载：尽量把已做的改动落盘
  if (editing.value && dirty.value) {
    try {
      props.updateAttributes({ data: JSON.stringify(serialize()), locked: '' })
    } catch { /* 卸载晚期属性更新可能失败，锁会随超时自动释放 */ }
  }
  editing.value = false
  stopHeartbeat()
})
</script>

<style>
.kb-mindmap-wrap { margin: 10px 0; }
.kb-mindmap {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
  position: relative;
}
.kb-mindmap.editing { border-color: #6366f1; }
.kb-mindmap-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 12px;
  background: #f8fafc;
  border-bottom: 1px solid var(--border, #e5e7eb);
  font-size: 13px;
}
.kb-mindmap-title { font-weight: 600; color: #334155; display: inline-flex; align-items: center; gap: 6px; }
.kb-mindmap-ico { flex: none; }
.kb-mindmap-status { font-size: 12px; }
.kb-mindmap-status.locked { color: #d97706; }
.kb-mindmap-status.editing { color: #6366f1; }
.kb-mindmap-actions { margin-left: auto; display: flex; gap: 8px; }
.kb-mm-btn {
  border: 1px solid var(--border, #e5e7eb);
  background: #fff;
  color: #475569;
  border-radius: 6px;
  padding: 3px 10px;
  font-size: 12px;
  cursor: pointer;
}
.kb-mm-btn.primary { background: #6366f1; border-color: #6366f1; color: #fff; }
.kb-mm-btn:hover { opacity: 0.88; }
.kb-mm-btn.help {
  width: 24px; height: 24px; padding: 0; border-radius: 50%;
  font-weight: 700; color: #64748b; line-height: 1;
}
.kb-mm-btn.help.on { background: #e0e7ff; border-color: #a5b4fc; color: #4f46e5; }
.kb-mindmap-canvas { height: 420px; position: relative; outline: none; }
.kb-mindmap-canvas.readonly { background:
  radial-gradient(circle, #eef2f7 1px, transparent 1px) 0 0 / 22px 22px; }

/* 使用说明面板（按钮触发，默认收起） */
.kb-mm-help {
  position: absolute; right: 10px; top: 42px; z-index: 8;
  width: 320px; max-width: calc(100% - 20px);
  background: #fff; border: 1px solid #e2e8f0; border-radius: 10px;
  box-shadow: 0 8px 22px rgba(15, 23, 42, .12);
  padding: 10px 12px 8px;
  font-size: 12px; color: #475569;
}
html.dark .kb-mm-help { background: #1b1b24; border-color: #2a2a35; color: #c7cad4; }
.fh-head {
  display: flex; align-items: center; justify-content: space-between;
  font-weight: 600; color: #334155; margin-bottom: 6px;
}
html.dark .fh-head { color: #e7e7ee; }
.fh-close {
  border: none; background: transparent; cursor: pointer;
  font-size: 16px; line-height: 1; color: #94a3b8;
}
.fh-close:hover { color: #475569; }
.kb-mm-help ul { margin: 0; padding-left: 16px; }
.kb-mm-help li { line-height: 1.9; }
.kb-mm-help b { color: #0f172a; font-weight: 600; }
html.dark .kb-mm-help b { color: #f1f5f9; }
.mm-flow { width: 100%; height: 100%; background: transparent; }

/* 节点外观 */
.mm-node {
  position: relative;   /* 八向 Handle 的定位基准 */
  background: #6366f1;
  color: #fff;
  border: 1px solid rgba(0,0,0,.1);
  border-radius: 8px;
  padding: 6px 12px;
  font-weight: 600;
  font-size: 13px;
  line-height: 1.3;
  min-width: 60px;
  text-align: center;
  cursor: grab;
  user-select: none;
  box-shadow: 0 1px 2px rgba(15,23,42,.12);
}
.kb-mindmap.editing .mm-node { cursor: grab; }
.vue-flow__node.selected .mm-node {
  outline: 2px solid #f59e0b;
  outline-offset: 2px;
}
.mm-node.editing { cursor: text; }
/* 八向连接点：只作为连线锚点，视觉上隐藏（脑图不显示小圆点） */
.mm-handle {
  width: 1px;
  height: 1px;
  min-width: 0;
  min-height: 0;
  border: none;
  background: transparent;
  opacity: 0;
  pointer-events: none;
}
.mm-label { display: inline-block; white-space: pre-wrap; word-break: break-word; max-width: 220px; }
.mm-edit {
  width: 140px;
  border: none;
  outline: none;
  background: #fff;
  color: #1e293b;
  border-radius: 5px;
  padding: 4px 6px;
  font-size: 13px;
  font-weight: 600;
  text-align: center;
}
/* ProseMirror 选中态：外框高亮 */
.kb-mindmap-wrap.ProseMirror-selectednode .kb-mindmap { border-color: #6366f1; }
</style>
