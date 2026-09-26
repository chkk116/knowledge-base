<template>
  <div style="max-width: 900px; margin: 30px auto; font-family: sans-serif">
    <h3>思维导图块开发测试页（无需登录）</h3>
    <button style="margin-bottom: 10px" @click="editor.chain().focus().insertMindmapBlock({ title: '思维导图' }).run()">
      插入思维导图
    </button>
    <editor-content :editor="editor" />
    <h4>裸 simple-mind-map 对照（无 Tiptap）</h4>
    <div ref="bareEl" style="height: 400px; border: 1px solid #ddd"></div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Collaboration from '@tiptap/extension-collaboration'
import * as Y from 'yjs'
import MindMap from 'simple-mind-map'
import Drag from 'simple-mind-map/src/plugins/Drag.js'
import MindmapNode from '../extensions/MindmapNode'

MindMap.usePlugin(Drag)

const ydoc = new Y.Doc()
const bareEl = ref(null)
let bare = null

onMounted(() => {
  if (!bareEl.value) return
  bare = new MindMap({
    el: bareEl.value,
    data: {
      data: { text: '裸测试根', expand: true },
      children: [
        { data: { text: '裸子节点 A', expand: true }, children: [] },
        { data: { text: '裸子节点 B', expand: true }, children: [] },
      ],
    },
    enableFreeDrag: true,
    enableShortcutOnlyWhenMouseInSvg: false,
    customCheckEnableShortcut: () => true,
  })
})

onBeforeUnmount(() => {
  try { bare?.destroy?.() } catch { /* ignore */ }
  bare = null
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
