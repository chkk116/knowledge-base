<template>
  <div style="max-width: 900px; margin: 30px auto; font-family: sans-serif">
    <h3>思维导图块开发测试页（无需登录）</h3>
    <button style="margin-bottom: 10px" @click="editor.chain().focus().insertMindmapBlock({ title: '思维导图' }).run()">
      插入思维导图
    </button>
    <editor-content :editor="editor" />
    <h4>裸 mind-elixir 对照（无 Tiptap）</h4>
    <div ref="bareEl" style="height: 400px; border: 1px solid #ddd"></div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Collaboration from '@tiptap/extension-collaboration'
import * as Y from 'yjs'
import MindmapNode from '../extensions/MindmapNode'

const ydoc = new Y.Doc()
const bareEl = ref(null)

onMounted(() => {
  if (!bareEl.value) return
  const inst = new MindElixir({
    el: bareEl.value,
    editable: true,
    draggable: true,
    contextMenu: true,
    toolBar: true,
    keypress: true,
    locale: 'zh_CN',
  })
  const d = MindElixir.new('裸测试根')
  d.nodeData.children = [
    { topic: '裸子节点 A', id: 'bareA', children: [] },
    { topic: '裸子节点 B', id: 'bareB', children: [] },
  ]
  inst.init(d)
})

const editor = useEditor({
  editable: true,
  extensions: [
    StarterKit.configure({ history: false }),
    Collaboration.configure({ document: ydoc }),
    MindmapNode,
  ],
  content: '<p>mindmap dev test</p>',
})
</script>
