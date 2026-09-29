// 电子表格块：Tiptap 原子节点。
// 数据分两层：
//   1) data —— x-data-spreadsheet 引擎的工作簿 JSON 字符串（样式 / 合并 / 冻结 / 行列结构），
//      经节点属性由 Yjs 同步（整表粒度，LWW）。
//   2) 单元格文本 —— 由 utils/sheetCollab.js 驱动写入 Y.Map（key "r:c"），**单元格级**协同，
//      支持多人同时编辑不同格子；公式由各端本地按同步后的数据重算，结果自然一致。
// sid 为该块在 Y.Doc 中的稳定标识（Y.Map 的 key 前缀），首次挂载时生成并写回属性。
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
      data: { default: '' },   // 工作簿 JSON 字符串（样式 / 结构 / 兼容快照）
      sid: { default: '' },    // 协同标识：Y.Map('kb-sheet:'+sid) 存单元格文本
      // 已废弃：早期用编辑锁（一次一人）实现协同，现改为单元格级协同。保留定义以兼容老文档。
      locked: { default: '' },
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
