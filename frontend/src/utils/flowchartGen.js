/**
 * 流程图生成：把「节点 + 连线」自动排版成 Vue Flow 图谱 JSON，再包成共享文档里的 flowchart 块 HTML。
 *
 * 为什么布局放代码里算：让 LLM 自己编坐标不可靠（会重叠、会超出画布），
 * 只让它给结构（谁连谁、什么形状），排版交给算法 —— 结果稳定得多。
 *
 * 产物对齐 extensions/FlowchartNode.js 的约定：
 *   <div data-type="flowchart-block" data='{"nodes":[...],"edges":[...]}' title="流程图">
 * 节点形状取值与 FlowchartBlock.vue 的图形面板保持一致。
 */

const SHAPE_OK = ['ellipse', 'rect', 'diamond', 'doc', 'db', 'sub']
const NODE_W = 168
const NODE_H = 56
const GAP_X = 90
const GAP_Y = 64

/** 分层排版：BFS 按层级（入度 0 为第一层），层内横向排列 */
function layout(ids, edges) {
  const indeg = {}
  ids.forEach((id) => { indeg[id] = 0 })
  edges.forEach((e) => { if (indeg[e.to] != null) indeg[e.to]++ })

  const adj = {}
  edges.forEach((e) => {
    if (!adj[e.from]) adj[e.from] = []
    if (ids.includes(e.to) && !adj[e.from].includes(e.to)) adj[e.from].push(e.to)
  })

  const layer = {}
  const start = ids.filter((id) => indeg[id] === 0)
  if (!start.length && ids.length) start.push(ids[0])
  start.forEach((id) => { layer[id] = 0 })

  let guard = 0
  const q = [...start]
  while (q.length && guard++ < 5000) {
    const cur = q.shift()
    for (const nx of adj[cur] || []) {
      // 首次访问即定层（标准 BFS 最短路分层）：回边自动被忽略。
      // 旧写法「更长路径就覆盖」在有环/自环图（如 审批→驳回→重提）上会让 layer 无界增长，
      // 节点被摆到几十万像素外，流程图等于渲染失败（外部复审 2026-10-08）。
      if (layer[nx] == null) { layer[nx] = (layer[cur] || 0) + 1; q.push(nx) }
    }
  }
  ids.forEach((id) => { if (layer[id] == null) layer[id] = 0 })

  const byLayer = {}
  ids.forEach((id) => {
    const l = layer[id]
    if (!byLayer[l]) byLayer[l] = []
    byLayer[l].push(id)
  })

  const pos = {}
  Object.keys(byLayer).map(Number).sort((a, b) => a - b).forEach((l) => {
    byLayer[l].forEach((id, i) => {
      pos[id] = { x: 60 + i * (NODE_W + GAP_X), y: 60 + l * (NODE_H + GAP_Y) }
    })
  })
  return pos
}

/** 把 Agent 给的 {nodes, edges} 规范化成 Vue Flow 图谱 */
export function buildFlowchartGraph(nodes, edges) {
  const clean = (Array.isArray(nodes) ? nodes : [])
    .map((n, i) => ({
      id: String(n && n.id != null ? n.id : i + 1).slice(0, 40),
      label: String((n && (n.label || n.text)) || '').slice(0, 60),
      shape: SHAPE_OK.includes(n && n.shape) ? n.shape : 'rect'
    }))
    // 重复 id 会产出重复 Vue Flow 节点且位置重叠（pos 被覆盖），必须去重（外部复审 2026-10-08）
    .filter((n, i, arr) => arr.findIndex((x) => x.id === n.id) === i)
    .slice(0, 40)

  const ids = clean.map((n) => n.id)
  const normEdges = (Array.isArray(edges) ? edges : [])
    .map((e) => ({
      from: String((e && e.from) != null ? e.from : ''),
      to: String((e && e.to) != null ? e.to : ''),
      label: e && e.label ? String(e.label).slice(0, 20) : ''
    }))
    .filter((e) => ids.includes(e.from) && ids.includes(e.to))
    .filter((e) => e.from !== e.to)                                    // 自环
    .filter((e, i, arr) => arr.findIndex((x) => x.from === e.from && x.to === e.to) === i)   // 重复边
    .slice(0, 60)   // 连线上限与分层共用同一份，避免「分层用全量、渲染用截断」不一致

  const pos = layout(ids, normEdges)

  const ns = clean.map((n) => ({
    id: n.id,
    type: 'fcNode',
    position: pos[n.id] || { x: 60, y: 60 },
    data: { label: n.label, shape: n.shape }
  }))
  const es = normEdges.slice(0, 60).map((e, i) => ({
    id: 'e' + (i + 1),
    source: e.from,
    target: e.to,
    type: 'fcEdge',
    data: { label: e.label }
  }))

  return { nodes: ns, edges: es }
}

/** 生成共享文档里的 flowchart 块 HTML（属性用单引号包裹，内部 JSON 的双引号可原样保留） */
export function flowchartHtml(title, graph) {
  // label 全部来自 LLM（提示注入面）：先转义 & 再处理引号，否则 &#39; 等实体形式
  // 会在二次解析链路里还原并突破单引号属性边界（外部复审 2026-10-08）
  const safeTitle = String(title || '流程图').replace(/&/g, '&amp;').replace(/["'<>]/g, '').slice(0, 80)
  const json = JSON.stringify(graph)
    .replace(/&/g, '&amp;')
    .replace(/'/g, '&#39;')
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
  return `<div data-type="flowchart-block" data='${json}' title="${safeTitle}"></div>`
}
