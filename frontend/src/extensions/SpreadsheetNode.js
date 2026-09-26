// 电子表格块：Tiptap 原子节点，数据为 x-data-spreadsheet 引擎的工作簿 JSON 字符串
// （getData() 的同构对象：{styles,merges,rows,cols,...}）。
// 支持单元格公式（=SUM(A1:A10) 等）、自动填充、复制粘贴、行列拖拽、合并、冻结、数据校验——
// 外观与手感对齐 Excel。协同策略同思维导图/流程图：节点属性经 Yjs 自动同步；编辑走「编辑锁」
// （锁 = 节点属性 locked，内容 {uid, uname, ts}），其他人只读实时渲染（canvas 预览禁交互）。
import { Node, mergeAttributes } from '@tiptap/core'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import SpreadsheetBlock from '../components/editor/SpreadsheetBlock.vue'

export default Node.create({
  name: 'sheetBlock',
  group: 'block',
  atom: true,
  selectable: true,
  // 注意：不要设 draggable——ProseMirror 的块拖拽会与表格内部交互冲突

  addAttributes() {
    return {
      data: { default: '' },   // 工作簿 JSON 字符串（见上）
      locked: { default: '' }, // 编辑锁：{uid, uname, ts} JSON 字符串，空 = 无人编辑
      title: { default: '电子表格' },
    }
  },

  parseHTML() {
    return [{ tag: 'div[data-type="sheet-block"]' }]
  },

  renderHTML({ HTMLAttributes }) {
    return ['div', mergeAttributes({ 'data-type': 'sheet-block' }, HTMLAttributes)]
  },

  addNodeView() {
    return VueNodeViewRenderer(SpreadsheetBlock)
  },

  addCommands() {
    return {
      insertSheetBlock:
        (attrs = {}) =>
        ({ commands }) =>
          commands.insertContent({ type: this.name, attrs }),
    }
  },
})
