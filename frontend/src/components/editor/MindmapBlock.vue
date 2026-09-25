<template>
  <NodeViewWrapper class="kb-mindmap-wrap" data-type="mindmap-block">
    <div class="kb-mindmap" :class="{ editing }">
      <div class="kb-mindmap-bar">
        <span class="kb-mindmap-title">🧠 {{ node.attrs.title || '思维导图' }}</span>
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
      <div v-if="!editing" class="kb-mindmap-hint">点击「编辑导图」进入拖拽编辑，期间其他人只读</div>
    </div>
  </NodeViewWrapper>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { NodeViewWrapper, nodeViewProps } from '@tiptap/vue-3'
import MindElixir from 'mind-elixir'
import 'mind-elixir/style'
import { useAuthStore } from '../../store/auth'

const props = defineProps(nodeViewProps)
const auth = useAuthStore()
const uid = String(auth.user?.id ?? auth.user?.username ?? 'me')
const uname = auth.user?.displayName || auth.user?.username || '我'

const mapEl = ref(null)
const editing = ref(false)
let instance = null
let dirty = false
let snapshot = ''   // 进入编辑时的数据快照，取消时回滚用

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

/** 初始化/重建 mind-elixir 实例：编辑态全功能，只读态禁交互仅展示 */
function render() {
  if (instance) {
    try { instance.destroy() } catch { /* 旧实例可能已随 DOM 卸载 */ }
    instance = null
  }
  if (!mapEl.value) return
  let data
  try {
    data = props.node.attrs.data ? JSON.parse(props.node.attrs.data) : null
  } catch {
    data = null
  }
  const isEditing = editing.value
  instance = new MindElixir({
    el: mapEl.value,
    editable: isEditing,          // v5 官方只读开关
    draggable: isEditing,
    contextMenu: isEditing,
    toolBar: isEditing,
    keypress: isEditing,
    nodeMenu: isEditing,
    allowUndo: isEditing,
    overflowHidden: false,
    locale: 'zh_CN',
  })
  instance.init(data || MindElixir.new(props.node.attrs.title || '中心主题'))
  if (isEditing) {
    instance.bus.addListener('operation', () => { dirty = true })
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
  if (dirty && instance) {
    props.updateAttributes({
      data: JSON.stringify(instance.getData()),
      locked: '',
    })
  } else {
    props.updateAttributes({ locked: '' })
  }
  editing.value = false
  dirty = false
}

function cancel() {
  // 有改动时先回滚数据，避免半成品留在文档里
  props.updateAttributes({ data: snapshot, locked: '' })
  editing.value = false
  dirty = false
}

// 数据/锁/编辑态任一变化都重建实例（保证协作者看到的渲染与最新数据一致）
watch(
  [() => props.node.attrs.data, () => props.node.attrs.locked, editing],
  () => { nextTick(render) },
  { immediate: false }
)
onMounted(() => { nextTick(render) })
onBeforeUnmount(() => {
  // 编辑中直接关闭页面/组件卸载：已做的改动随最后一次 operation 丢失前尽量落盘
  if (editing.value && dirty && instance) {
    try {
      props.updateAttributes({
        data: JSON.stringify(instance.getData()),
        locked: '',
      })
    } catch { /* 卸载晚期的属性更新可能失败，锁会因超时自动释放 */ }
  }
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
.kb-mindmap-title { font-weight: 600; color: #334155; }
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
.kb-mindmap-canvas.readonly { pointer-events: auto; }
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
