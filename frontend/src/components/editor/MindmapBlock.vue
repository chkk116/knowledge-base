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
          {{ lockInfo.uname }} 正在编辑<template v-if="lockExpired">（编辑异常退出，可强制接管）</template>
        </span>
        <span v-else-if="editing" class="kb-mindmap-status editing">你正在编辑</span>
        <span class="kb-mindmap-actions">
          <button v-if="!editing && canAcquire" class="kb-mm-btn primary" @click="acquire">编辑导图</button>
          <button
            v-if="!editing && lockInfo && !isMine && lockExpired"
            class="kb-mm-btn"
            @click="acquire"
          >强制接管</button>
          <button v-if="editing" class="kb-mm-btn primary" @click="finish">完成编辑</button>
          <button v-if="editing" class="kb-mm-btn" @click="cancel">取消</button>
        </span>
      </div>
      <div ref="mapEl" class="kb-mindmap-canvas" :class="{ readonly: !editing }"></div>
      <div v-if="!editing" class="kb-mindmap-hint">点「编辑导图」后：Tab 加子节点 · Enter 加同级 · F2 编辑文字 · 节点可直接拖拽</div>
      <div v-else class="kb-mindmap-hint editing-hint">Tab 加子节点 · Enter 加同级 · F2 / 双击节点编辑文字 · 拖到节点上调整结构、拖到空白处自由摆放 · Ctrl+Z 撤销 · 右键更多操作</div>
    </div>
  </NodeViewWrapper>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import MindMap from 'simple-mind-map'
import Drag from 'simple-mind-map/src/plugins/Drag.js'
import { useAuthStore } from '../../store/auth'

// 节点拖拽是插件，需静态注册一次（usePlugin 内部自带去重）
MindMap.usePlugin(Drag)

const props = defineProps(nodeViewProps)
const auth = useAuthStore()
const uid = String(auth.user?.id ?? auth.user?.username ?? 'me')
const uname = auth.user?.displayName || auth.user?.username || '我'

const mapEl = ref(null)
const editing = ref(false)
let instance = null
let dirty = false
let pendingData = null  // 编辑期间 data_change 收集的最新导图数据
let snapshot = ''   // 进入编辑时的数据快照，取消时回滚用
let cleanupFns = []

const LOCK_TIMEOUT = 120000   // 锁超时：持锁端异常退出 2 分钟后允许强制接管

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
// 编辑器只读（查看模式）时不能获取编辑权
const canAcquire = computed(() => props.editor.isEditable && (!editing.value) &&
  (!lockInfo.value || isMine.value || lockExpired.value))

/** 解析节点属性里的导图数据；兼容旧 mind-elixir 格式（一次性转换） */
function parseData() {
  let raw = null
  try {
    raw = props.node.attrs.data ? JSON.parse(props.node.attrs.data) : null
  } catch {
    raw = null
  }
  if (raw && raw.nodeData && typeof raw.nodeData.topic === 'string') {
    const conv = (n) => ({
      data: { text: String(n.topic ?? ''), expand: n.expand !== false },
      children: Array.isArray(n.children) ? n.children.map(conv) : [],
    })
    return conv(raw.nodeData)
  }
  if (raw && raw.data && typeof raw.data.text === 'string') return raw
  // 新导图预置两个示例子节点（对齐亿图脑图的新建体验：打开即有结构可改）
  // uid 不用手写：setData/init 时库会通过 createUidForAppointNodes 自动补全
  return {
    data: { text: props.node.attrs.title || '中心主题', expand: true },
    children: [
      { data: { text: '子主题 1', expand: true }, children: [] },
      { data: { text: '子主题 2', expand: true }, children: [] },
    ],
  }
}

/** 初始化/重建 simple-mind-map 实例：编辑态全功能，只读态禁交互仅展示 */
function render() {
  cleanupFns.forEach((fn) => { try { fn() } catch { /* ignore */ } })
  cleanupFns = []
  if (instance) {
    try { instance.destroy() } catch { /* 旧实例可能已随 DOM 卸载 */ }
    instance = null
  }
  if (!mapEl.value) return
  const data = parseData()
  const isEditing = editing.value
  instance = new MindMap({
    el: mapEl.value,
    data,
    readonly: !isEditing,          // 只读开关（自动禁节点编辑/拖拽/右键）
    enableFreeDrag: isEditing,     // 自由拖拽：拖到空白处 = 自由摆放（亿图脑图风格）
    enableShortcutOnlyWhenMouseInSvg: false,
    // 快捷键监听在 window 上，默认要求 target 为 body/编辑框；
    // NodeView 内合成转发的事件 target 是 window，这里直接按编辑态放行
    customCheckEnableShortcut: () => isEditing,
  })

  if (isEditing) {
    instance.on('data_change', (d) => {
      dirty = true
      pendingData = d
    })
    // simple-mind-map 的快捷键监听在 window，而 ProseMirror 的事件监听在
    // contenteditable 上（两者都在冒泡链上）。NodeView 是 PM 的后代节点，
    // 若放任事件冒泡：mousedown 会被 PM 抢焦点、keydown 会被 PM 当正文输入。
    // 策略（比 mind-elixir 时代简单）：
    //  - mousedown：在容器层截断（PM 收不到 → 不抢焦点），导图库自身监听在
    //    容器内 SVG 上、先于容器层触发，不受影响；再把可能残留在 PM 里的
    //    焦点主动 blur 掉，让后续 keydown 走 body → window 的默认通道。
    //  - keydown：在容器层截断后，克隆重派发到 window（导图库靠它分发快捷键；
    //    配合 customCheckEnableShortcut 放行）。
    //  - mouseup/click/dblclick/contextmenu/wheel 全部放行（库自身监听，
    //    截断会打断内部事件链——mind-elixir 的教训）。
    const el = mapEl.value
    const onMouseDown = (e) => {
      e.stopPropagation()
      const active = document.activeElement
      if (active && active !== document.body && active.closest?.('.ProseMirror')) {
        try { active.blur() } catch { /* ignore */ }
      }
    }
    const onKeyDown = (e) => {
      e.stopPropagation()
      e.preventDefault()
      try {
        window.dispatchEvent(new KeyboardEvent(e.type, {
          key: e.key, code: e.code, location: e.location,
          ctrlKey: e.ctrlKey, shiftKey: e.shiftKey,
          altKey: e.altKey, metaKey: e.metaKey,
          repeat: e.repeat, isComposing: e.isComposing, cancelable: true,
        }))
      } catch { /* ignore */ }
    }
    const stopOnly = (e) => e.stopPropagation()
    el.addEventListener('mousedown', onMouseDown)
    el.addEventListener('keydown', onKeyDown)
    el.addEventListener('keyup', stopOnly)
    cleanupFns = [
      () => el.removeEventListener('mousedown', onMouseDown),
      () => el.removeEventListener('keydown', onKeyDown),
      () => el.removeEventListener('keyup', stopOnly),
    ]
  }
}

function acquire() {
  if (lockInfo.value && !isMine.value && !lockExpired.value) return
  snapshot = String(props.node.attrs.data || '')
  editing.value = true   // 先置编辑态再写锁，watch 触发时一次性以编辑模式渲染
  props.updateAttributes({
    locked: JSON.stringify({ uid, uname, ts: Date.now() }),
  })
}

function finish() {
  if (dirty && pendingData) {
    props.updateAttributes({
      data: JSON.stringify(pendingData),
      locked: '',
    })
  } else {
    props.updateAttributes({ locked: '' })
  }
  editing.value = false
  dirty = false
  pendingData = null
}

function cancel() {
  // 有改动时先回滚数据，避免半成品留在文档里
  props.updateAttributes({ data: snapshot, locked: '' })
  editing.value = false
  dirty = false
  pendingData = null
}

// 数据/锁/编辑态任一变化都重建实例（保证协作者看到的渲染与最新数据一致）
watch(
  [() => props.node.attrs.data, () => props.node.attrs.locked, editing],
  () => { nextTick(render) },
  { immediate: false }
)
onMounted(() => { nextTick(render) })
onBeforeUnmount(() => {
  // 编辑中直接关闭页面/组件卸载：已做的改动随最后一次 data_change 尽量落盘
  if (editing.value && dirty && pendingData) {
    try {
      props.updateAttributes({
        data: JSON.stringify(pendingData),
        locked: '',
      })
    } catch { /* 卸载晚期的属性更新可能失败，锁会因超时自动释放 */ }
  }
  cleanupFns.forEach((fn) => { try { fn() } catch { /* ignore */ } })
  cleanupFns = []
  try { instance?.destroy?.() } catch { /* ignore */ }
  instance = null
})
</script>

<style>
.kb-mindmap-wrap { margin: 10px 0; }
.kb-mindmap {
  border: 1px solid var(--border, #e5e7eb);
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
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
.kb-mindmap-canvas { height: 420px; position: relative; }
.kb-mindmap-hint {
  padding: 5px 12px;
  font-size: 12px;
  color: #94a3b8;
  background: #fafbfc;
  border-top: 1px dashed var(--border, #e5e7eb);
}
/* ProseMirror 选中态：外框高亮交给 .ProseMirror-selectednode，这里只留呼吸空间 */
.kb-mindmap-wrap.ProseMirror-selectednode .kb-mindmap { border-color: #6366f1; }
</style>
