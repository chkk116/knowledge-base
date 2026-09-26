<template>
  <div style="max-width: 1000px; margin: 30px auto; font-family: sans-serif">
    <h3>Vue Flow 自由主题原型（隔离验证：多中心 + 自由摆放）</h3>
    <p style="color:#888;font-size:13px">
      两个独立中心主题、各自子节点、连线自由。拖拽节点可在画布任意移动——这正是 simple-mind-map 开源版做不到的。
    </p>
    <div style="height: 520px; border: 1px solid #ddd">
      <VueFlow :nodes="nodes" :edges="edges" fit-view-on-init>
      </VueFlow>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { VueFlow } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

const nodeStyle = (bg) => ({
  background: bg,
  color: '#fff',
  border: '1px solid rgba(0,0,0,.1)',
  borderRadius: '8px',
  padding: '6px 12px',
  fontWeight: 600,
})

// 两个独立中心主题 + 各自子节点 = 真正的"多中心自由主题"
const nodes = ref([
  { id: 'c1', position: { x: 40, y: 200 }, label: '主题 A（中心）', style: nodeStyle('#6366f1') },
  { id: 'a1', position: { x: 300, y: 110 }, label: 'A-1 子节点', style: nodeStyle('#a5b4fc') },
  { id: 'a2', position: { x: 300, y: 290 }, label: 'A-2 子节点', style: nodeStyle('#a5b4fc') },
  { id: 'c2', position: { x: 620, y: 70 }, label: '主题 B（中心）', style: nodeStyle('#10b981') },
  { id: 'b1', position: { x: 880, y: 70 }, label: 'B-1 子节点', style: nodeStyle('#6ee7b7') },
  { id: 'b2', position: { x: 880, y: 220 }, label: 'B-2 子节点', style: nodeStyle('#6ee7b7') },
])
const edges = ref([
  { id: 'c1-a1', source: 'c1', target: 'a1' },
  { id: 'c1-a2', source: 'c1', target: 'a2' },
  { id: 'c2-b1', source: 'c2', target: 'b1' },
  { id: 'c2-b2', source: 'c2', target: 'b2' },
])
</script>
