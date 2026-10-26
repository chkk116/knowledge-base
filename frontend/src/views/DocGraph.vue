<template>
  <div class="graph-page">
    <div class="gp-head">
      <div>
        <h3>知识图谱</h3>
        <p class="gp-sub">
          共 {{ nodes.length }} 篇文档、{{ edges.length }} 条引用关系。正文里写 <code>[[文档标题]]</code> 即建立关联；
          节点大小按被引用/引用次数放大，点击节点直接打开文档。
        </p>
      </div>
      <div class="gp-actions">
        <el-input v-model="kw" placeholder="按标题过滤" clearable size="small" style="width: 180px" />
        <el-checkbox v-model="onlyLinked" size="small">只看有链接的</el-checkbox>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div v-loading="loading" class="gp-canvas">
      <el-empty v-if="!loading && !visibleNodes.length" description="暂无可展示的文档关系" />
      <VueFlow
        v-else
        :nodes="flowNodes"
        :edges="flowEdges"
        :min-zoom="0.15"
        :max-zoom="2"
        fit-view-on-init
        @node-click="onNodeClick"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { VueFlow } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import api from '../api/request'

const router = useRouter()
const loading = ref(false)
const nodes = ref([])
const edges = ref([])
const kw = ref('')
const onlyLinked = ref(true)

async function load() {
  loading.value = true
  try {
    const { data } = await api.get('/doc-shares/graph')
    nodes.value = (data && data.nodes) || []
    edges.value = (data && data.edges) || []
  } catch (e) {
    nodes.value = []
    edges.value = []
  } finally {
    loading.value = false
  }
}

/** 过滤：标题关键字 + 是否只看有链接的节点 */
const visibleNodes = computed(() => {
  const k = kw.value.trim().toLowerCase()
  return nodes.value.filter((n) => {
    if (onlyLinked.value && !n.degree) return false
    if (!k) return true
    return String(n.title || '').toLowerCase().includes(k)
  })
})

const visibleIds = computed(() => new Set(visibleNodes.value.map((n) => n.id)))

const visibleEdges = computed(() =>
  edges.value.filter((e) => visibleIds.value.has(e.source) && visibleIds.value.has(e.target))
)

/**
 * 极简力导向布局（不引第三方依赖）：
 * 斥力 + 边弹簧 + 向心力，迭代 260 次后收敛。节点数在几十到几百量级，一次算完即可。
 */
function layout(ns, es) {
  const W = 1200
  const H = 800
  const pos = new Map()
  ns.forEach((n, i) => {
    // 初始放在圆周上，避免全部重合导致斥力爆炸
    const a = (i / Math.max(1, ns.length)) * Math.PI * 2
    const r = 120 + (i % 7) * 26
    pos.set(n.id, { x: W / 2 + Math.cos(a) * r, y: H / 2 + Math.sin(a) * r })
  })
  const ids = ns.map((n) => n.id)
  const adj = es.map((e) => [e.source, e.target])
  for (let iter = 0; iter < 260; iter++) {
    const cool = 1 - iter / 300
    // 斥力（两两）
    for (let i = 0; i < ids.length; i++) {
      for (let j = i + 1; j < ids.length; j++) {
        const a = pos.get(ids[i])
        const b = pos.get(ids[j])
        let dx = a.x - b.x
        let dy = a.y - b.y
        let d2 = dx * dx + dy * dy
        if (d2 < 1) { d2 = 1; dx = Math.random() - 0.5; dy = Math.random() - 0.5 }
        const f = 5200 / d2
        const d = Math.sqrt(d2)
        const fx = (dx / d) * f
        const fy = (dy / d) * f
        a.x += fx; a.y += fy
        b.x -= fx; b.y -= fy
      }
    }
    // 边弹簧
    for (const [s, t] of adj) {
      const a = pos.get(s)
      const b = pos.get(t)
      if (!a || !b) continue
      const dx = b.x - a.x
      const dy = b.y - a.y
      const d = Math.sqrt(dx * dx + dy * dy) || 1
      const target = 150
      const f = (d - target) * 0.012
      const fx = (dx / d) * f
      const fy = (dy / d) * f
      a.x += fx; a.y += fy
      b.x -= fx; b.y -= fy
    }
    // 向心 + 冷却
    for (const id of ids) {
      const p = pos.get(id)
      p.x += (W / 2 - p.x) * 0.002 * cool
      p.y += (H / 2 - p.y) * 0.002 * cool
    }
  }
  return pos
}

/** 布局只算一次：放进 computed 会让每次搜索输入都重跑 O(n²)×260 次迭代（审查 r15 High） */
const positions = ref(new Map())
watch([visibleNodes, visibleEdges], ([ns, es]) => {
  positions.value = layout(ns, es)
}, { immediate: true })

const flowNodes = computed(() => {
  const ns = visibleNodes.value
  const pos = positions.value
  const maxDeg = Math.max(1, ...ns.map((n) => n.degree || 0))
  return ns.map((n) => {
    const p = pos.get(n.id) || { x: 0, y: 0 }
    const scale = (n.degree || 0) / maxDeg
    const size = 13 + Math.round(scale * 9)
    return {
      id: n.id,
      position: { x: p.x, y: p.y },
      label: n.title || '(无标题)',
      data: { docId: n.docId, shareId: n.shareId, spaceName: n.spaceName },
      style: {
        background: n.degree ? '#4f46e5' : '#94a3b8',
        color: '#fff',
        border: '1px solid rgba(0,0,0,.08)',
        borderRadius: '10px',
        padding: '4px 10px',
        fontSize: size + 'px',
        maxWidth: '220px',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap'
      }
    }
  })
})

const flowEdges = computed(() =>
  visibleEdges.value.map((e) => ({ id: e.id, source: e.source, target: e.target, animated: false, style: { stroke: '#c7d2fe' } }))
)

function onNodeClick(ev) {
  const d = ev && ev.node && ev.node.data
  if (!d || !d.shareId || !d.docId) return
  router.push({ path: '/doc-shares/' + d.shareId, query: { doc: d.docId } })
}

onMounted(load)
</script>

<style scoped>
.graph-page { padding: 16px 20px 0; }
.gp-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 10px; }
.gp-head h3 { margin: 0 0 4px; font-size: 17px; }
.gp-sub { margin: 0; color: #8a8f9c; font-size: 12px; line-height: 1.6; }
.gp-sub code { background: #f1f2f6; padding: 1px 4px; border-radius: 4px; }
.gp-actions { display: flex; align-items: center; gap: 10px; }
.gp-canvas { height: calc(100vh - 210px); min-height: 420px; border: 1px solid #e6e8ef; border-radius: 10px; background: #fbfbfd; overflow: hidden; }
</style>
