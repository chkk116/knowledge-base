// 思维导图块：Tiptap 原子节点，数据为由 Vue Flow 管理的图谱 JSON 字符串
// （{nodes:[{id,type:'mindNode',position:{x,y},data:{label}}], edges:[{id,source,target}]}）。
// 支持多中心「自由主题」：根节点之间互不相连即可各自成树——这是 simple-mind-map 开源版
// 做不到的。旧 simple-mind-map / mind-elixir 格式在 MindmapFlowBlock 内做一次性兼容转换。
// 协同策略：节点属性经 Yjs 自动同步；导图编辑走「编辑锁」——同一时刻仅一人可编辑
// （锁 = 节点属性 locked，内容 {uid, uname, ts}），其他人只读实时渲染。
import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import MindmapFlowBlock from '../components/editor/MindmapFlowBlock.vue'

export default Node.create({
  name: 'mindmapBlock',
  group: 'block',
  atom: true,
  selectable: true,
  // 注意：不要设 draggable——ProseMirror 的块拖拽会与导图内部节点拖拽冲突

  addAttributes() {
    return {
      data: { default: '' },   // Vue Flow 图谱 JSON 字符串（见上）
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
    return VueNodeViewRenderer(MindmapFlowBlock)
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
