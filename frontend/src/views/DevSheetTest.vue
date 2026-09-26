<template>
  <div style="max-width: 960px; margin: 30px auto; font-family: sans-serif">
    <h3>电子表格块开发测试页（无需登录 · x-data-spreadsheet 版）</h3>
    <p style="color:#888;font-size:13px">
      点击「插入电子表格」会插入一个真实 Tiptap 节点（SpreadsheetNode，内部为 x-data-spreadsheet 引擎）。
      用于验收：公式、自动填充、编辑锁与持久化。
    </p>
    <button style="margin-bottom: 10px" @click="editor?.chain().focus().insertSheetBlock({ title: '电子表格' }).run()">
      插入电子表格
    </button>
    <editor-content :editor="editor" style="border:1px solid #ddd;min-height:60px;padding:8px" />
  </div>
</template>

<script setup>
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import SpreadsheetNode from '../extensions/SpreadsheetNode'

const editor = useEditor({
  editable: true,
  extensions: [
    StarterKit,
    SpreadsheetNode,
  ],
  content: '<p>sheet dev test</p>',
})
// 暴露给验收脚本
window.__ed = () => editor.value
</script>
