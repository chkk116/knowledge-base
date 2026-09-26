// 流程图块：Tiptap 原子节点，数据为由 Vue Flow 管理的图谱 JSON 字符串
// （{nodes:[{id,type:'fcNode',position:{x,y},data:{label,shape}}],
//   edges:[{id,source,target,sourceHandle,targetHandle,type:'fcEdge',data:{label}}]}）。
// 支持多种图形（矩形/圆/椭圆/菱形/文档/步骤），节点可自由拖拽摆放，从图形圆点拉线连接，
// 连线可加标签（如「是/否」）。协同策略同思维导图：节点属性经 Yjs 自动同步；编辑走「编辑锁」
// （锁 = 节点属性 locked，内容 {uid, uname, ts}），其他人只读实时渲染。
import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import FlowchartBlock from '../components/editor/FlowchartBlock.vue'

export default Node.create({
  name: 'flowchartBlock',
  group: 'block',
  atom: true,
  selectable: true,
  // 注意：不要设 draggable——ProseMirror 的块拖拽会与流程图内部节点拖拽冲突

  addAttributes() {
    return {
      data: { default: '' },   // Vue Flow 图谱 JSON 字符串（见上）
      locked: { default: '' }, // 编辑锁：{uid, uname, ts} JSON 字符串，空 = 无人编辑
      title: { default: '流程图' },
    }
  },

  parseHTML() {
    return [{ tag: 'div[data-type="flowchart-block"]' }]
  },

  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes({ 'data-type': 'flowchart-block' }, HTMLAttributes)]
  },

  addNodeView() {
    return VueNodeViewRenderer(FlowchartBlock)
  },

  addCommands() {
    return {
      insertFlowchartBlock:
        (attrs = {}) =>
        ({ commands }) =>
          commands.insertContent({ type: this.name, attrs }),
    }
  },
})
