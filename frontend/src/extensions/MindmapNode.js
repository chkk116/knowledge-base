// 思维导图块：Tiptap 原子节点，数据为 simple-mind-map 导图 JSON 字符串
// （纯节点树：{data:{text,uid}, children:[...]}，uid 由库在 setData 时自动补全）。
// 协同策略：节点属性经 Yjs 自动同步；导图编辑走「编辑锁」——同一时刻仅一人可编辑
// （锁 = 节点属性 locked，内容 {uid, uname, ts}），其他人只读实时渲染。
import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import MindmapBlock from '../components/editor/MindmapBlock.vue'

export default Node.create({
  name: 'mindmapBlock',
  group: 'block',
  atom: true,
  selectable: true,
  // 注意：不要设 draggable——ProseMirror 的块拖拽会与导图内部节点拖拽冲突

  addAttributes() {
    return {
      data: { default: '' },   // simple-mind-map getData() 的 JSON 字符串
      locked: { default: '' }, // 编辑锁：{uid, uname, ts} JSON 字符串，空 = 无人编辑
      title: { default: '思维导图' },
    }
  },

  parseHTML() {
    return [{ tag: 'div[data-type="mindmap-block"]' }]
  },

  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes({ 'data-type': 'mindmap-block' }, HTMLAttributes)]
  },

  addNodeView() {
    return VueNodeViewRenderer(MindmapBlock)
  },

  addCommands() {
    return {
      insertMindmapBlock:
        (attrs = {}) =>
        ({ commands }) =>
          commands.insertContent({ type: this.name, attrs }),
    }
  },
})
