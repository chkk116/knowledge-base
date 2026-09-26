<template>
  <div style="max-width: 960px; margin: 30px auto; font-family: sans-serif">
    <h3>思维导图块开发测试页（无需登录 · Vue Flow 版）</h3>
    <p style="color:#888;font-size:13px">
      点击「插入思维导图」会插入一个真实 Tiptap 节点（MindmapNode，内部为 Vue Flow NodeView）。
      用于验收：多中心自由主题、Tab/Enter/F2/Delete、拖拽、编辑锁与持久化。
    </p>
    <button style="margin-bottom: 10px" @click="editor?.chain().focus().insertMindmapBlock({ title: '思维导图' }).run()">
      插入思维导图
    </button>
    <editor-content :editor="editor" style="border:1px solid #ddd;min-height:60px;padding:8px" />
  </div>
</template>

<script setup>
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import MindmapNode from '../extensions/MindmapNode'

const editor = useEditor({
  editable: true,
  extensions: [
    StarterKit,
    MindmapNode,
  ],
  content: '<p>mindmap dev test</p>',
})
// 暴露给验收脚本：验证「新建种子 HTML」能否被 schema 正确解析
window.__ed = () => editor.value
</script>
