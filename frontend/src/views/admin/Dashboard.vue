<script setup>
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Loading, RefreshLeft, DataLine, Document, User, Star, TrendCharts, View, ChatLineRound, Collection } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { stats } from '../../api/admin'

const router = useRouter()

const data = ref(null)
const loading = ref(false)
const error = ref('')
const chartLoading = ref(false)
const chartError = ref('')

// 图表容器 ref（局部加载 ECharts，仅在看板页按需加载）
const docPie = ref(null)
const interact = ref(null)
const trendLine = ref(null)

const chartMap = {}
let echartsLib = null
let observer = null

// 局部加载 ECharts：看板页才注入本地脚本，不进主包，也不依赖运行时 CDN。
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
  chartLoading.value = false
  await nextTick()
  renderAll()
  observer = new MutationObserver(() => renderAll())
  observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
}

function renderAll() {
  if (!echartsLib || !data.value) return
  const dark = document.documentElement.classList.contains('dark')
  drawPie(dark); drawInteract(dark); drawTrend(dark)
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

function drawTrend(dark) {
  const c = ensure(trendLine.value, 'trend')
  if (!c) return
  const d = data.value
  c.setOption({
    color: ['#6366f1', '#0ea5e9', '#f59e0b'],
    tooltip: { trigger: 'axis', ...tip(dark) },
    legend: { bottom: 0, textStyle: txt(dark) },
    grid: { left: 8, right: 16, top: 24, bottom: 36, containLabel: true },
    xAxis: { type: 'category', boundaryGap: false, data: d.trendLabels || [], axisLabel: txt(dark) },
    yAxis: { type: 'value', minInterval: 1, axisLabel: txt(dark), splitLine: { lineStyle: { color: dark ? '#26262f' : '#eef2f7' } } },
    series: [
      { name: '文档', type: 'line', smooth: true, symbolSize: 6, data: d.docTrend7 || [], lineStyle: { width: 2.5 }, areaStyle: { opacity: .08 } },
      { name: '用户', type: 'line', smooth: true, symbolSize: 6, data: d.userTrend7 || [], lineStyle: { width: 2.5 }, areaStyle: { opacity: .08 } },
      { name: '评论', type: 'line', smooth: true, symbolSize: 6, data: d.commentTrend7 || [], lineStyle: { width: 2.5 }, areaStyle: { opacity: .08 } },
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

// ===== 派生数据 =====
const num = (k) => (data.value && data.value[k] != null ? data.value[k] : 0)

// 顶部 KPI：value + 环比（今日 vs 昨日）+ 迷你趋势线
const kpis = computed(() => {
  const d = data.value
  if (!d) return []
  const mk = (icon, label, value, unit, spark, today, yesterday) => {
    const t = today != null && yesterday != null
      ? (today - yesterday)
      : null
    const trend = t == null ? null
      : (t > 0 ? 'up' : (t < 0 ? 'down' : 'flat'))
    const sp = spark || []
    return { icon, label, value, unit, spark: sp, sparkPath: sparkPath(sp, 72, 26), trend, delta: t == null ? null : Math.abs(t) }
  }
  return [
    mk(Document, '文档总数', d.docTotal, '篇', d.docTrend7, d.docToday, d.docYesterday),
    mk(User, '用户总数', d.userTotal, '人', d.userTrend7, d.userToday, d.userYesterday),
    mk(Star, '评论总数', d.commentTotal, '条', d.commentTrend7, d.commentToday, d.commentYesterday),
    mk(View, '累计浏览', d.viewsTotal, '次', null, null, null),
    mk(Collection, '今日发布', d.docToday, '篇', d.docTrend7, d.docToday, d.docYesterday),
  ]
})

// 迷你趋势线 SVG path（不依赖 ECharts，KPI 卡轻量展示）
function sparkPath(arr, w, h) {
  if (!arr || arr.length < 2) return ''
  // 仅保留有效数字（后端可能返回 null/undefined → 造成 NaN → polyline 报 "Expected number"）
  const nums = arr.filter((v) => typeof v === 'number' && !isNaN(v))
  if (nums.length < 2) return ''
  const max = Math.max(...nums, 1)
  const min = Math.min(...nums, 0)
  const range = (max - min) || 1
  const step = w / (nums.length - 1)
  return nums.map((v, i) => {
    const x = (i * step).toFixed(1)
    const y = (h - 3 - ((v - min) / range) * (h - 6)).toFixed(1)
    return (i === 0 ? 'M' : 'L') + x + ' ' + y
  }).join(' ')
}

// 活跃用户排行：用户名 + 评论数
const topUsers = computed(() => (data.value && data.value.topUsers) || [])
// 热门文章排行
const topDocs = computed(() => (data.value && data.value.topDocs) || [])
// 实时动态
const activities = computed(() => (data.value && data.value.activities) || [])

const typeText = (t) => ({ doc: '发布', comment: '评论' }[t] || t)

/** 点击直达：发布 → 文档详情；评论 → 文档详情并定位到该条评论（?c=评论id） */
function openActivity(a) {
  if (!a || !a.docId) return
  if (a.type === 'comment') router.push({ path: '/post/' + a.docId, query: { c: a.commentId } })
  else router.push('/post/' + a.docId)
}
/** 可跳转的条目才显示手型与提示 */
const canOpen = (a) => !!(a && a.docId)
</script>

<template>
  <div class="dash">
    <!-- 页头：标题 + 副标题（今日活跃）+ 刷新 -->
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
      <!-- 顶部 KPI 横条：图标 + 数值 + 环比涨跌 + 迷你趋势线 -->
      <section class="kpi-row">
        <div v-for="k in kpis" :key="k.label" class="kpi-card">
          <div class="kpi-top">
            <div class="kpi-icon"><el-icon><component :is="k.icon" /></el-icon></div>
            <div class="kpi-meta">
              <div class="kpi-label">{{ k.label }}</div>
              <div class="kpi-value">{{ k.value }}<span class="unit">{{ k.unit }}</span></div>
            </div>
          </div>
          <div class="kpi-bottom">
            <span v-if="k.trend" class="kpi-trend" :class="k.trend">
              <template v-if="k.trend === 'up'">▲</template>
              <template v-else-if="k.trend === 'down'">▼</template>
              <template v-else>—</template>
              {{ k.delta }}<span class="kpi-trend-tip">较昨日</span>
            </span>
            <span v-else class="kpi-trend flat">累计</span>
            <svg v-if="k.sparkPath" class="kpi-spark" viewBox="0 0 72 26" preserveAspectRatio="none">
              <path :d="k.sparkPath" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
            </svg>
          </div>
        </div>
      </section>

      <!-- 板块一：内容态势 —— 状态统计 + 状态分布图 + 热门文章排行 -->
      <section class="domain-section" style="--accent:#6366f1">
        <div class="ds-head">
          <span class="ds-dot"></span>
          <span class="ds-title">内容态势</span>
          <span class="ds-sub">沉淀规模与结构分布</span>
        </div>
        <div class="ds-main">
          <div class="ds-tiles-col">
            <div class="mini-tile" v-for="it in [
              { label: '文档总数', value: num('docTotal'), unit: '篇' },
              { label: '已发布', value: num('docPublished'), unit: '篇' },
              { label: '待审核', value: num('docPending'), unit: '篇' },
              { label: '草稿', value: num('docDraft'), unit: '篇' },
              { label: '已封禁', value: num('docBlocked'), unit: '篇' },
              { label: '累计浏览', value: num('viewsTotal'), unit: '次' },
            ]" :key="it.label">
              <span class="tile-lbl">{{ it.label }}</span>
              <span class="tile-val">{{ it.value }}<small>{{ it.unit }}</small></span>
            </div>
          </div>
          <div class="ds-chart" v-loading="chartLoading" element-loading-text="图表组件加载中…">
            <p class="cap">文档状态分布</p>
            <div v-if="chartError" class="chart-err">{{ chartError }}</div>
            <div v-else ref="docPie" class="chart"></div>
          </div>
          <div class="ds-list">
            <p class="cap">热门文章 <small class="cap-sub">按访问量 Top5</small></p>
            <div v-if="!topDocs.length" class="rank-empty">暂无数据</div>
            <div v-for="(d, i) in topDocs" :key="i" class="rank-item">
              <span class="rank-no" :class="{ top: i < 3 }">{{ i + 1 }}</span>
              <div class="rank-name">
                <span class="rank-title" :title="d.title">{{ d.title }}</span>
                <span class="rank-extra">{{ d.category || '未分类' }}</span>
              </div>
              <span class="rank-val">{{ d.views }}<small>次</small></span>
            </div>
          </div>
        </div>
      </section>

      <!-- 板块二：增长趋势 —— 用户统计 + 近7天趋势线 + 实时动态 -->
      <section class="domain-section" style="--accent:#0ea5e9">
        <div class="ds-head">
          <span class="ds-dot"></span>
          <span class="ds-title">增长趋势</span>
          <span class="ds-sub">近 7 天新增与实时动态</span>
        </div>
        <div class="ds-main">
          <div class="ds-tiles-col">
            <div class="mini-tile" v-for="it in [
              { label: '用户总数', value: num('userTotal'), unit: '人' },
              { label: '管理员', value: num('userAdmin'), unit: '人' },
              { label: '已禁用', value: num('userBlocked'), unit: '人' },
              { label: '今日新增用户', value: num('userToday'), unit: '人' },
              { label: '今日新增评论', value: num('commentToday'), unit: '条' },
              { label: '评论总数', value: num('commentTotal'), unit: '条' },
            ]" :key="it.label">
              <span class="tile-lbl">{{ it.label }}</span>
              <span class="tile-val">{{ it.value }}<small>{{ it.unit }}</small></span>
            </div>
          </div>
          <div class="ds-chart" v-loading="chartLoading" element-loading-text="图表组件加载中…">
            <p class="cap">近 7 天新增趋势</p>
            <div v-if="chartError" class="chart-err">{{ chartError }}</div>
            <div v-else ref="trendLine" class="chart"></div>
          </div>
          <div class="ds-list">
            <p class="cap">实时动态 <small class="cap-sub">最新发布与评论</small></p>
            <div v-if="!activities.length" class="rank-empty">暂无动态</div>
            <div v-for="(a, i) in activities" :key="i" class="act-item"
                 :class="{ clickable: canOpen(a) }"
                 :title="canOpen(a) ? (a.type === 'comment' ? '点击查看该文档并定位到这条评论' : '点击打开该文档') : ''"
                 @click="openActivity(a)">
              <span class="act-dot" :class="a.type"></span>
              <div class="act-main">
                <span class="act-tag" :class="a.type">{{ typeText(a.type) }}</span>
                <span class="act-text" :title="a.text" v-html="a.text"></span>
              </div>
              <span class="act-time">{{ a.time }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 板块三：互动生态 —— 互动统计 + 互动总量图 + 活跃用户排行 -->
      <section class="domain-section" style="--accent:#f59e0b">
        <div class="ds-head">
          <span class="ds-dot"></span>
          <span class="ds-title">互动生态</span>
          <span class="ds-sub">评论 / 点赞 / 收藏 / 通知</span>
        </div>
        <div class="ds-main">
          <div class="ds-tiles-col">
            <div class="mini-tile" v-for="it in [
              { label: '评论总数', value: num('commentTotal'), unit: '条' },
              { label: '点赞总数', value: num('likeTotal'), unit: '次' },
              { label: '收藏总数', value: num('favTotal'), unit: '次' },
              { label: '通知总数', value: num('notiTotal'), unit: '条' },
            ]" :key="it.label">
              <span class="tile-lbl">{{ it.label }}</span>
              <span class="tile-val">{{ it.value }}<small>{{ it.unit }}</small></span>
            </div>
          </div>
          <div class="ds-chart" v-loading="chartLoading" element-loading-text="图表组件加载中…">
            <p class="cap">互动总量</p>
            <div v-if="chartError" class="chart-err">{{ chartError }}</div>
            <div v-else ref="interact" class="chart"></div>
          </div>
          <div class="ds-list">
            <p class="cap">活跃用户 <small class="cap-sub">按评论数 Top5</small></p>
            <div v-if="!topUsers.length" class="rank-empty">暂无数据</div>
            <div v-for="(u, i) in topUsers" :key="i" class="rank-item">
              <span class="rank-no" :class="{ top: i < 3 }">{{ i + 1 }}</span>
              <div class="rank-name">
                <span class="rank-title">{{ u.username }}</span>
                <span class="rank-extra">评论活跃</span>
              </div>
              <span class="rank-val">{{ u.count }}<small>条</small></span>
            </div>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.dash { padding: 4px 6px; }

/* 页头 */
.dash-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.dash-title { display: flex; align-items: center; gap: 14px; }
.dt-icon {
  width: 44px; height: 44px; border-radius: 12px; font-size: 22px;
  display: flex; align-items: center; justify-content: center;
  background: #e0e7ff; color: #4f46e5;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .06);
}
html.dark .dt-icon { background: rgb(79 70 229 / .2); color: #a5b4fc; }
.dt-txt h2 { margin: 0; font-size: 22px; color: #0f172a; letter-spacing: .5px; }
html.dark .dt-txt h2 { color: #f1f5f9; }
.dt-txt .sub { margin: 3px 0 0; font-size: 13px; color: #94a3b8; }

/* 状态条 */
.dash-state { padding: 48px 20px; text-align: center; color: #94a3b8; display: flex; align-items: center; justify-content: center; gap: 10px; }
.dash-state.err { color: #ef4444; }
.spin { animation: dash-spin .8s linear infinite; }
@keyframes dash-spin { to { transform: rotate(360deg); } }

/* ===== KPI 横条（对照 Farm2 kpi-card） ===== */
.kpi-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 14px; margin-bottom: 20px; }
.kpi-card {
  position: relative; overflow: hidden;
  background: #fff; border: 1px solid #eef2f7; border-radius: 14px;
  padding: 14px 16px 12px;
  box-shadow: 0 1px 3px rgba(16, 24, 40, .06);
  display: flex; flex-direction: column; justify-content: space-between;
  transition: box-shadow .22s, transform .22s;
}
.kpi-card::before {
  content: ''; position: absolute; inset: 0 0 auto 0; height: 3px;
  background: linear-gradient(90deg, #6366f1, transparent);
}
.kpi-card:hover { transform: translateY(-3px); box-shadow: 0 10px 24px rgba(16, 24, 40, .10); }
html.dark .kpi-card { background: #17171f; border-color: #2a2a35; }
html.dark .kpi-card:hover { box-shadow: 0 10px 24px rgba(0, 0, 0, .5); }
.kpi-top { display: flex; align-items: center; gap: 12px; }
.kpi-icon {
  width: 38px; height: 38px; border-radius: 11px; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 19px; color: #6366f1; background: rgba(99, 102, 241, .10);
}
html.dark .kpi-icon { background: rgba(129, 140, 248, .20); }
.kpi-meta { min-width: 0; }
.kpi-label { font-size: 12px; color: #94a3b8; white-space: nowrap; }
html.dark .kpi-label { color: #9aa0ad; }
.kpi-value { font-size: 25px; font-weight: 700; line-height: 1.15; color: #0f172a; font-variant-numeric: tabular-nums; letter-spacing: -.5px; }
html.dark .kpi-value { color: #f1f5f9; }
.kpi-value .unit { font-size: 13px; font-weight: 500; margin-left: 2px; color: #94a3b8; }
.kpi-bottom { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 10px; height: 26px; }
.kpi-trend { display: inline-flex; align-items: center; gap: 2px; font-size: 12px; font-weight: 600; font-variant-numeric: tabular-nums; flex-shrink: 0; }
.kpi-trend.up { color: #4fbe87; }
.kpi-trend.down { color: #ef6d59; }
.kpi-trend.flat { color: #b6becd; }
.kpi-trend-tip { font-weight: 400; color: #b6becd; margin-left: 2px; }
.kpi-spark { flex: 1; height: 26px; min-width: 40px; color: #6366f1; }
html.dark .kpi-spark { color: #818cf8; }

/* ===== 板块容器（对照 Farm2 domain-section） ===== */
.domain-section {
  --accent: #6366f1;
  background: color-mix(in srgb, var(--accent) 5%, #fff);
  border: 1px solid color-mix(in srgb, var(--accent) 16%, transparent);
  border-radius: 16px;
  padding: 14px 16px 16px;
  margin-bottom: 16px;
}
html.dark .domain-section {
  background: color-mix(in srgb, var(--accent) 7%, #17171f);
  border-color: color-mix(in srgb, var(--accent) 22%, #2a2a35);
}
.ds-head { display: flex; align-items: center; gap: 8px; padding: 0 2px 12px; }
.ds-dot { width: 9px; height: 9px; border-radius: 50%; background: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 18%, transparent); flex-shrink: 0; }
.ds-title { font-size: 15px; font-weight: 700; letter-spacing: .5px; color: #1e293b; }
html.dark .ds-title { color: #e7e7ee; }
.ds-sub { font-size: 12px; color: #98a2b3; }
.ds-main { display: grid; grid-template-columns: 210px 1fr 1fr; gap: 14px; align-items: stretch; }

/* 左侧统计块 */
.ds-tiles-col { display: flex; flex-direction: column; gap: 8px; }
.mini-tile {
  background: #fff; border: 1px solid #eef2f7; border-radius: 10px;
  padding: 8px 12px;
  display: flex; align-items: baseline; justify-content: space-between;
}
html.dark .mini-tile { background: #1b1b24; border-color: #2a2a35; }
.tile-lbl { font-size: 12px; color: #64748b; }
html.dark .tile-lbl { color: #9aa0ad; }
.tile-val { font-size: 17px; font-weight: 700; color: #0f172a; font-variant-numeric: tabular-nums; }
html.dark .tile-val { color: #f1f5f9; }
.tile-val small { font-size: 11px; font-weight: 400; color: #94a3b8; margin-left: 2px; }

/* 中列图表 */
.ds-chart, .ds-list { background: #fff; border: 1px solid #eef2f7; border-radius: 12px; padding: 12px 14px 8px; }
html.dark .ds-chart, html.dark .ds-list { background: #17171f; border-color: #2a2a35; }
.cap { margin: 0 0 8px; font-size: 13px; font-weight: 600; color: #334155; display: flex; align-items: baseline; gap: 6px; }
html.dark .cap { color: #d5d7e0; }
.cap-sub { font-size: 11px; font-weight: 400; color: #98a2b3; }
.chart { width: 100%; height: 250px; }
.chart-err { color: #ef4444; font-size: 12px; padding: 40px 0; text-align: center; }

/* 右侧排行 */
.rank-empty { color: #98a2b3; font-size: 12px; text-align: center; padding: 24px 0; }
.rank-item { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 5px 0; }
.rank-no {
  width: 20px; height: 20px; line-height: 20px; text-align: center; border-radius: 50%;
  background: #f1f5f9; color: #64748b; font-size: 11px; flex-shrink: 0;
}
html.dark .rank-no { background: #26262f; color: #9aa0ad; }
.rank-no.top { background: linear-gradient(135deg, #f1c40f, #e67e22); color: #fff; }
.rank-name { flex: 1; min-width: 0; display: flex; flex-direction: column; justify-content: center; }
.rank-title { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; color: #334155; }
html.dark .rank-title { color: #d5d7e0; }
.rank-extra { font-size: 11px; color: #98a2b3; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.rank-val { font-weight: 700; color: var(--accent); font-variant-numeric: tabular-nums; white-space: nowrap; }
.rank-val small { font-weight: 400; font-size: 11px; margin-left: 2px; color: #98a2b3; }

/* 实时动态 */
.act-item { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 5px 0; }
.act-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.act-dot.doc { background: #6366f1; }
.act-dot.comment { background: #10b981; }
.act-main { flex: 1; min-width: 0; display: flex; align-items: center; gap: 6px; }
.act-tag {
  font-size: 10px; line-height: 1; padding: 3px 6px; border-radius: 4px; flex-shrink: 0; font-weight: 600;
}
.act-tag.doc { color: #6366f1; background: rgba(99, 102, 241, .10); }
.act-tag.comment { color: #10b981; background: rgba(16, 185, 129, .10); }
.act-text { flex: 1; min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; color: #334155; }
.act-text :deep(img.kb-emoji) { height: 18px; vertical-align: middle; margin: 0 1px; }
html.dark .act-text { color: #d5d7e0; }
.act-time { font-size: 11px; color: #98a2b3; white-space: nowrap; }
/* 可点击的动态条目：整行手型 + 悬停高亮，让「能点」一眼可见 */
.act-item { border-radius: 6px; transition: background .15s; }
.act-item.clickable { cursor: pointer; }
.act-item.clickable:hover { background: rgba(99, 102, 241, .08); }
.act-item.clickable:hover .act-text { color: #4f46e5; }
html.dark .act-item.clickable:hover { background: rgba(129, 140, 248, .14); }
html.dark .act-item.clickable:hover .act-text { color: #a5b4fc; }

@media (max-width: 1200px) {
  .ds-main { grid-template-columns: 1fr; }
}
@media (max-width: 900px) {
  .kpi-row { grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); }
}

/* ===== 手机（≤640）：收紧内边距与字号，图表降高 ===== */
@media (max-width: 640px) {
  .dash { padding: 2px 0; }
  .dash-head { margin-bottom: 14px; flex-wrap: wrap; gap: 10px; }
  .dt-icon { width: 36px; height: 36px; font-size: 18px; }
  .dt-txt h2 { font-size: 18px; }
  .dt-txt .sub { font-size: 12px; }
  .dash-state { padding: 32px 12px; flex-wrap: wrap; }

  .kpi-row { grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; margin-bottom: 14px; }
  .kpi-card { padding: 12px 12px 10px; border-radius: 12px; }
  .kpi-top { gap: 10px; }
  .kpi-icon { width: 32px; height: 32px; font-size: 16px; border-radius: 9px; }
  .kpi-value { font-size: 21px; }
  .kpi-bottom { margin-top: 8px; }

  .domain-section { padding: 12px; border-radius: 12px; margin-bottom: 12px; }
  .ds-head { padding: 0 0 10px; flex-wrap: wrap; }
  .ds-title { font-size: 14px; }
  .ds-main { gap: 10px; }
  .ds-chart, .ds-list { padding: 10px 12px 8px; border-radius: 10px; }
  .mini-tile { padding: 6px 10px; }
  .tile-val { font-size: 15px; }
  .chart { height: 200px; }
  .cap { font-size: 12px; margin-bottom: 6px; flex-wrap: wrap; }
  .rank-item, .act-item { font-size: 12px; gap: 6px; }
  .act-main { flex-wrap: wrap; }
}
</style>
