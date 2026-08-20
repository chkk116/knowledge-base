<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, RefreshLeft, DataLine, Document, User, Star, TrendCharts } from '@element-plus/icons-vue'
import { stats } from '../../api/admin'

const data = ref(null)
const loading = ref(false)
const error = ref('')
const chartLoading = ref(false)
const chartError = ref('')

// 图表容器 ref（局部加载 ECharts，仅在看板页按需加载）
const docPie = ref(null)
const interact = ref(null)
const today = ref(null)

const chartMap = {}
let echartsLib = null
let observer = null

// 局部加载 ECharts：看板页才注入本地脚本，不进主包，也不依赖运行时 CDN。
// 库文件位于 public/vendor/echarts.min.js（构建时随静态资源复制到部署目录，可离线/内网使用）。
function loadEcharts() {
  return new Promise((resolve, reject) => {
    if (window.echarts) return resolve(window.echarts)
    const s = document.createElement('script')
    s.src = import.meta.env.BASE_URL + 'vendor/echarts.min.js'
    s.async = true
    s.onload = () => (window.echarts ? resolve(window.echarts) : reject(new Error('echarts 未就绪')))
    s.onerror = () => reject(new Error('echarts 本地文件加载失败'))
    document.head.appendChild(s)
  })
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const { data: d } = await stats()
    data.value = d
    initCharts()
  } catch (e) {
    error.value = '加载统计数据失败，请稍后重试'
    ElMessage.error(error.value)
  } finally {
    loading.value = false
  }
}

async function initCharts() {
  // 已加载过 ECharts：图表容器常驻 DOM，直接重绘即可（无需切 chartLoading）。
  if (echartsLib) {
    await nextTick()
    renderAll()
    return
  }
  chartLoading.value = true
  try {
    echartsLib = await loadEcharts()
  } catch (e) {
    chartError.value = '图表组件加载失败（本地 vendor/echarts.min.js 缺失）。数字概览不受影响。'
    chartLoading.value = false
    return
  }
  chartLoading.value = false   // 先关闭加载态，让图表容器（v-else 分支）渲染、ref 就绪
  await nextTick()             // 等 DOM 更新完成再取 ref
  renderAll()
  // 跟随全局夜间模式切换重绘配色
  observer = new MutationObserver(() => renderAll())
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
}

function renderAll() {
  if (!echartsLib || !data.value) return
  const dark = document.documentElement.classList.contains('dark')
  drawPie(dark); drawInteract(dark); drawToday(dark)
}

const ensure = (el, key) => {
  if (!el) return null
  if (!chartMap[key]) chartMap[key] = echartsLib.init(el)
  return chartMap[key]
}
const txt = (dark) => ({ color: dark ? '#a0a4b2' : '#64748b' })
const tip = (dark) => ({
  backgroundColor: dark ? '#23232e' : '#fff',
  borderColor: dark ? '#2a2a35' : '#e2e8f0',
  textStyle: { color: dark ? '#e7e7ee' : '#334155' },
})

function drawPie(dark) {
  const c = ensure(docPie.value, 'pie')
  if (!c) return
  const d = data.value
  c.setOption({
    color: ['#10b981', '#f59e0b', '#94a3b8', '#ef4444'],
    tooltip: { trigger: 'item', ...tip(dark) },
    legend: { bottom: 0, textStyle: txt(dark) },
    series: [{
      name: '文档状态', type: 'pie', radius: ['44%', '68%'],
      label: { color: dark ? '#c7c8d4' : '#334155' },
      itemStyle: { borderRadius: 6, borderColor: dark ? '#17171f' : '#fff', borderWidth: 2 },
      data: [
        { name: '已发布', value: d.docPublished },
        { name: '待审核', value: d.docPending },
        { name: '草稿', value: d.docDraft },
        { name: '已封禁', value: d.docBlocked },
      ],
    }],
  }, true)
}

function drawInteract(dark) {
  const c = ensure(interact.value, 'inter')
  if (!c) return
  const d = data.value
  c.setOption({
    color: ['#6366f1', '#0ea5e9', '#f59e0b', '#ec4899'],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...tip(dark) },
    grid: { left: 8, right: 16, bottom: 8, top: 24, containLabel: true },
    xAxis: { type: 'category', data: ['评论', '点赞', '收藏', '通知'], axisLabel: txt(dark) },
    yAxis: { type: 'value', axisLabel: txt(dark), splitLine: { lineStyle: { color: dark ? '#26262f' : '#eef2f7' } } },
    series: [{
      type: 'bar', barWidth: '52%',
      data: [d.commentTotal, d.likeTotal, d.favTotal, d.notiTotal],
      itemStyle: { borderRadius: [8, 8, 0, 0] },
    }],
  }, true)
}

function drawToday(dark) {
  const c = ensure(today.value, 'today')
  if (!c) return
  const d = data.value
  c.setOption({
    color: ['#6366f1', '#0ea5e9'],
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...tip(dark) },
    legend: { bottom: 0, textStyle: txt(dark) },
    grid: { left: 8, right: 16, top: 24, bottom: 36, containLabel: true },
    xAxis: { type: 'category', data: ['今日新增'], axisLabel: txt(dark) },
    yAxis: { type: 'value', axisLabel: txt(dark), splitLine: { lineStyle: { color: dark ? '#26262f' : '#eef2f7' } } },
    series: [
      { name: '文档', type: 'bar', barWidth: 30, data: [d.docToday], itemStyle: { borderRadius: [8, 8, 0, 0] } },
      { name: '用户', type: 'bar', barWidth: 30, data: [d.userToday], itemStyle: { borderRadius: [8, 8, 0, 0] } },
    ],
  }, true)
}

const onResize = () => { Object.values(chartMap).forEach((c) => c && c.resize()) }

onMounted(() => { load(); window.addEventListener('resize', onResize) })
onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  if (observer) observer.disconnect()
  Object.values(chartMap).forEach((c) => c && c.dispose())
})

// 卡片分组配置：标题 + 主题色 + 组图标 + 字段
const groups = [
  {
    title: '内容概览', accent: '#6366f1', icon: Document,
    items: [
      { key: 'docTotal', label: '文档总数', unit: '篇' },
      { key: 'docPublished', label: '已发布', unit: '篇' },
      { key: 'docPending', label: '待审核', unit: '篇' },
      { key: 'docDraft', label: '草稿', unit: '篇' },
      { key: 'docBlocked', label: '已封禁', unit: '篇' },
      { key: 'docToday', label: '今日新增', unit: '篇' },
      { key: 'viewsTotal', label: '累计浏览', unit: '次' },
    ],
  },
  {
    title: '用户概览', accent: '#0ea5e9', icon: User,
    items: [
      { key: 'userTotal', label: '用户总数', unit: '人' },
      { key: 'userAdmin', label: '管理员', unit: '人' },
      { key: 'userBlocked', label: '已禁用', unit: '人' },
      { key: 'userToday', label: '今日新增', unit: '人' },
    ],
  },
  {
    title: '互动数据', accent: '#f59e0b', icon: Star,
    items: [
      { key: 'commentTotal', label: '评论总数', unit: '条' },
      { key: 'likeTotal', label: '点赞总数', unit: '次' },
      { key: 'favTotal', label: '收藏总数', unit: '次' },
      { key: 'notiTotal', label: '通知总数', unit: '条' },
    ],
  },
]
const num = (k) => (data.value && data.value[k] != null ? data.value[k] : 0)
</script>

<template>
  <div class="dash">
    <!-- 页头：渐变图标 + 标题 + 副标题 + 刷新 -->
    <div class="dash-head">
      <div class="dash-title">
        <div class="dt-icon"><el-icon><DataLine /></el-icon></div>
        <div class="dt-txt">
          <h2>数据看板</h2>
          <p class="sub">平台核心数据实时概览</p>
        </div>
      </div>
      <el-button type="primary" round :icon="RefreshLeft" :loading="loading" @click="load">刷新</el-button>
    </div>

    <div v-if="loading && !data" class="dash-state">
      <el-icon class="spin"><Loading /></el-icon> 统计加载中…
    </div>
    <div v-else-if="error" class="dash-state err">
      {{ error }}
      <el-button type="primary" size="small" round @click="load">重试</el-button>
    </div>

    <template v-else-if="data">
      <section v-for="g in groups" :key="g.title" class="dash-group">
        <div class="group-head" :style="{ '--accent': g.accent }">
          <span class="gh-bar"></span>
          <el-icon class="gh-ic"><component :is="g.icon" /></el-icon>
          <h3>{{ g.title }}</h3>
        </div>
        <div class="grid">
          <div v-for="it in g.items" :key="it.key" class="card" :style="{ '--accent': g.accent }">
            <div class="card-ic"><el-icon><component :is="g.icon" /></el-icon></div>
            <div class="val">{{ num(it.key) }}<span class="unit">{{ it.unit }}</span></div>
            <div class="lbl">{{ it.label }}</div>
          </div>
        </div>
      </section>

      <section class="dash-group">
        <div class="group-head" style="--accent:#10b981">
          <span class="gh-bar"></span>
          <el-icon class="gh-ic"><TrendCharts /></el-icon>
          <h3>数据可视化</h3>
        </div>
        <div class="chart-grid" v-loading="chartLoading" element-loading-text="图表组件加载中…">
          <div v-if="chartError" class="dash-state err" style="grid-column: 1 / -1">{{ chartError }}</div>
          <template v-else>
            <div class="chart-box">
              <p class="cap">文档状态分布</p>
              <div ref="docPie" class="chart"></div>
            </div>
            <div class="chart-box">
              <p class="cap">互动总量</p>
              <div ref="interact" class="chart"></div>
            </div>
            <div class="chart-box wide">
              <p class="cap">今日新增</p>
              <div ref="today" class="chart"></div>
            </div>
          </template>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.dash { padding: 4px 6px; }

/* 页头 */
.dash-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 22px; }
.dash-title { display: flex; align-items: center; gap: 14px; }
.dt-icon {
  width: 44px; height: 44px; border-radius: 8px; color: #fff; font-size: 22px;
  display: flex; align-items: center; justify-content: center;
  background: #6366f1;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .08);
}
.dt-txt h2 { margin: 0; font-size: 22px; color: #0f172a; letter-spacing: .5px; }
html.dark .dt-txt h2 { color: #f1f5f9; }
.dt-txt .sub { margin: 3px 0 0; font-size: 13px; color: #94a3b8; }

/* 状态条 */
.dash-state { padding: 48px 20px; text-align: center; color: #94a3b8; display: flex; align-items: center; justify-content: center; gap: 10px; }
.dash-state.err { color: #ef4444; }
.spin { animation: dash-spin .8s linear infinite; }
@keyframes dash-spin { to { transform: rotate(360deg); } }

/* 分组标题 */
.dash-group { margin-bottom: 26px; }
.group-head { display: flex; align-items: center; gap: 9px; margin-bottom: 14px; }
.gh-bar { width: 4px; height: 18px; border-radius: 2px; background: var(--accent); }
.gh-ic { color: var(--accent); font-size: 18px; }
.group-head h3 { margin: 0; font-size: 16px; font-weight: 700; color: #1e293b; letter-spacing: .5px; }
html.dark .group-head h3 { color: #e7e7ee; }

/* 统计卡片 */
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(168px, 1fr)); gap: 14px; }
.card {
  position: relative; overflow: hidden;
  background: #fff; border: 1px solid #eef2f7; border-radius: 16px;
  padding: 18px 16px 16px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, .04);
  transition: transform .18s ease, box-shadow .18s ease;
}
.card::before {
  content: ''; position: absolute; left: 0; top: 0; right: 0; height: 4px;
  background: linear-gradient(90deg, var(--accent), color-mix(in srgb, var(--accent) 55%, transparent));
}
.card:hover { transform: translateY(-4px); box-shadow: 0 12px 28px rgba(15, 23, 42, .10); }
html.dark .card { background: #17171f; border-color: #2a2a35; }
html.dark .card:hover { box-shadow: 0 12px 28px rgba(0, 0, 0, .5); }
.card-ic {
  width: 38px; height: 38px; border-radius: 10px; color: var(--accent); font-size: 19px;
  display: flex; align-items: center; justify-content: center;
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  margin-bottom: 12px;
}
html.dark .card-ic { background: color-mix(in srgb, var(--accent) 22%, transparent); }
.card .val { font-size: 30px; font-weight: 800; color: #0f172a; line-height: 1.05; }
html.dark .card .val { color: #f1f5f9; }
.card .unit { font-size: 13px; font-weight: 500; color: #94a3b8; margin-left: 5px; }
.card .lbl { margin-top: 7px; font-size: 13px; color: #64748b; }
html.dark .card .lbl { color: #9aa0ad; }

/* 图表卡片 */
.chart-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.chart-box {
  background: #fff; border: 1px solid #eef2f7; border-radius: 16px; padding: 16px 16px 8px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, .04);
}
.chart-box.wide { grid-column: 1 / -1; }
html.dark .chart-box { background: #17171f; border-color: #2a2a35; }
.cap { margin: 0 0 10px; font-size: 14px; font-weight: 600; color: #334155; letter-spacing: .3px; }
html.dark .cap { color: #d5d7e0; }
.chart { width: 100%; height: 280px; }
.chart-box.wide .chart { height: 240px; }

@media (max-width: 900px) {
  .chart-grid { grid-template-columns: 1fr; }
}
</style>
