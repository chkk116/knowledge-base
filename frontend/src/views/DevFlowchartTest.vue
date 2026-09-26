<template>
  <div style="max-width: 960px; margin: 30px auto; font-family: sans-serif">
    <h3>流程图块开发测试页（无需登录 · Vue Flow 版）</h3>
    <p style="color:#888;font-size:13px">
      点击「插入流程图」会插入一个真实 Tiptap 节点（FlowchartNode，内部为 Vue Flow NodeView）。
      用于验收：多形状添加、拖拽、连线、连线标签（是/否）、编辑锁与持久化。
    </p>
    <button style="margin-bottom: 10px" @click="editor?.chain().focus().insertFlowchartBlock({ title: '流程图' }).run()">
      插入流程图
    </button>
    <editor-content :editor="editor" style="border:1px solid #ddd;min-height:60px;padding:8px" />
  </div>
</template>

<script setup>
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import FlowchartNode from '../extensions/FlowchartNode'

const editor = useEditor({
  editable: true,
  extensions: [
    StarterKit,
    FlowchartNode,
  ],
  content: '<p>flowchart dev test</p>',
})
</script>
