<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/auth'
import { pointsLogPaged } from '../api/auth'
import { getLevel } from '../utils/level'

const auth = useAuthStore()
const router = useRouter()

const list = ref([])
const total = ref(0)
const earned = ref(0)
const spent = ref(0)
const loading = ref(false)

const page = ref(1)            // el-pagination 用 1-based
const size = ref(20)
const activeType = ref('')

const typeOptions = [
  { value: '', label: '全部' },
  { value: 'LOGIN', label: '每日登录' },
  { value: 'PUBLISH', label: '发布文章' },
  { value: 'HOT', label: '热帖奖励' },
  { value: 'ADJUST', label: '管理员调整' },
  { value: 'SYSTEM', label: '系统调整' },
]

// 类型标识色：低饱和企业色板，只作为行首小色点使用，不做整块彩色底
const typeMeta = {
  LOGIN:   { label: '每日登录',   color: '#6366f1' },
  PUBLISH: { label: '发布文章',   color: '#0f7b3d' },
  HOT:     { label: '热帖奖励',   color: '#a16207' },
  ADJUST:  { label: '管理员调整', color: '#475569' },
  SYSTEM:  { label: '系统调整',   color: '#6b7280' },
}
const typeText = (t) => typeMeta[t]?.label || t || '积分变动'
const typeColor = (t) => typeMeta[t]?.color || '#6b7280'

const levelInfo = computed(() => getLevel(auth.user?.points || 0))
const gapToNext = computed(() => {
  const n = levelInfo.value.next
  return n == null ? null : Math.max(0, n - (auth.user?.points || 0))
})

async function load() {
  loading.value = true
  try {
    const { data } = await pointsLogPaged(page.value - 1, size.value, activeType.value)
    list.value = Array.isArray(data.content) ? data.content : []
    total.value = data.total || 0
    earned.value = data.earned || 0
    spent.value = data.spent || 0
  } catch (e) {
    list.value = []
    total.value = 0
    earned.value = 0
    spent.value = 0
  } finally {
    loading.value = false
  }
}

function onTypeChange(v) { activeType.value = v; page.value = 1; load() }
function onSizeChange(s) { size.value = s; page.value = 1; load() }
function onPageChange(p) { page.value = p; load() }

onMounted(load)

/* ---------- 日期处理：列表按天分组，行内只显示时刻 ---------- */
function parseDate(s) {
  if (!s) return null
  const d = new Date(String(s).replace('T', ' ').replace(/Z$/, ''))
  return isNaN(d.getTime()) ? null : d
}
const pad = n => String(n).padStart(2, '0')

function dayKey(s) {
  const d = parseDate(s)
  if (!d) return '未知日期'
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
const WEEK = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']
function dayLabel(key) {
  const d = parseDate(key)
  if (!d) return key
  const today = new Date()
  const isToday = d.toDateString() === today.toDateString()
  const y = new Date(today.getTime() - 86400000)
  const isYesterday = d.toDateString() === y.toDateString()
  const suffix = isToday ? '今天' : isYesterday ? '昨天' : WEEK[d.getDay()]
  return `${key} ${suffix}`
}
function fmtTime(s) {
  const d = parseDate(s)
  return d ? `${pad(d.getHours())}:${pad(d.getMinutes())}` : '—'
}

// 当前页按天分组（后端已按时间倒序，这里保持原顺序即可）
const grouped = computed(() => {
  const map = new Map()
  for (const l of list.value) {
    const k = dayKey(l.createdAt)
    if (!map.has(k)) map.set(k, [])
    map.get(k).push(l)
  }
  return Array.from(map, ([key, items]) => ({ key, items }))
})

function goBack() { router.push('/profile') }
</script>

<template>
  <div class="pl-page">
    <!-- 面包屑 -->
    <nav class="pl-crumb">
      <a href="javascript:void(0)" @click="goBack">个人资料</a>
      <span class="pl-crumb-sep">/</span>
      <span class="pl-crumb-cur">积分明细</span>
    </nav>

    <header class="pl-head">
      <h1 class="pl-h1">积分明细</h1>
      <p class="pl-desc">记录每一笔积分的增减来源与变动后余额</p>
    </header>

    <!-- 概览：中性底 + 分隔线，不用渐变 -->
    <section class="pl-overview">
      <div class="pl-ov-primary">
        <div class="pl-ov-label">当前积分</div>
        <div class="pl-ov-value">{{ auth.user?.points || 0 }}</div>
        <div class="pl-ov-level">
          <span class="pl-lv-tag">Lv{{ levelInfo.level }} · {{ levelInfo.title }}</span>
          <span class="pl-lv-tip" v-if="gapToNext != null">距下一等级 {{ gapToNext }} 分</span>
          <span class="pl-lv-tip" v-else>已达最高等级</span>
        </div>
        <div class="pl-lv-bar"><i :style="{ width: levelInfo.progress + '%' }" /></div>
      </div>
      <dl class="pl-ov-kpis">
        <div class="pl-kpi">
          <dt>累计获得</dt>
          <dd class="up">+{{ earned }}</dd>
        </div>
        <div class="pl-kpi">
          <dt>累计支出</dt>
          <dd class="down">-{{ spent }}</dd>
        </div>
        <div class="pl-kpi">
          <dt>记录总数</dt>
          <dd>{{ total }}</dd>
        </div>
      </dl>
    </section>

    <!-- 工具条：分段筛选 -->
    <div class="pl-toolbar">
      <div class="pl-seg" role="tablist">
        <button v-for="o in typeOptions" :key="o.value" type="button" role="tab"
                class="pl-seg-item" :class="{ on: activeType === o.value }"
                @click="onTypeChange(o.value)">
          {{ o.label }}
        </button>
      </div>
    </div>

    <!-- 明细表 -->
    <section class="pl-table">
      <div class="pl-thead">
        <span class="c-type">类型</span>
        <span class="c-reason">说明</span>
        <span class="c-time">时间</span>
        <span class="c-delta">变动</span>
        <span class="c-bal">变动后余额</span>
      </div>

      <div v-if="loading" class="pl-state">加载中…</div>
      <div v-else-if="list.length === 0" class="pl-state">
        <div class="pl-empty-t">暂无积分变动记录</div>
        <div class="pl-empty-s">发布文章、每日登录都会产生积分记录</div>
      </div>

      <template v-else>
        <template v-for="g in grouped" :key="g.key">
          <div class="pl-group">
            <span class="pl-group-day">{{ dayLabel(g.key) }}</span>
            <span class="pl-group-n">{{ g.items.length }} 笔</span>
          </div>
          <div class="pl-row" v-for="l in g.items" :key="l.id">
            <span class="c-type">
              <i class="pl-dot" :style="{ background: typeColor(l.type) }" />
              <span class="pl-type-txt">{{ typeText(l.type) }}</span>
            </span>
            <span class="c-reason">{{ l.reason || '—' }}</span>
            <span class="c-time">{{ fmtTime(l.createdAt) }}</span>
            <span class="c-delta" :class="l.delta >= 0 ? 'up' : 'down'">
              {{ l.delta >= 0 ? '+' : '-' }}{{ Math.abs(l.delta) }}
            </span>
            <span class="c-bal">{{ l.balance }}</span>
          </div>
        </template>
      </template>
    </section>

    <div v-if="total > 0" class="pl-pager">
      <el-pagination
        :current-page="page"
        :page-size="size"
        :total="total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>
  </div>
</template>

<style scoped>
/* 设计基调：企业控制台账单页。中性色底 + 单一主色 #6366f1，
   层级靠 1px 分隔线、留白与字重表达，不使用渐变/发光/多彩色块。 */
.pl-page {
  --pl-primary: #6366f1;
  --pl-line: #e7e9ee;
  --pl-line-soft: #f0f2f5;
  --pl-bg-soft: #fafbfc;
  --pl-text: #1c2028;
  --pl-text-2: #4e5666;
  --pl-text-3: #8a919f;
  --pl-up: #0f7b3d;
  --pl-down: #c5372c;
  max-width: 1020px;
  margin: 0 auto;
  padding-bottom: 32px;
  color: var(--pl-text);
}

/* ---------- 面包屑 / 标题 ---------- */
.pl-crumb { font-size: 13px; color: var(--pl-text-3); margin: 2px 0 14px; }
.pl-crumb a { color: var(--pl-text-2); text-decoration: none; }
.pl-crumb a:hover { color: var(--pl-primary); }
.pl-crumb-sep { margin: 0 8px; color: #c4c9d4; }
.pl-crumb-cur { color: var(--pl-text); }

.pl-head { margin-bottom: 18px; }
.pl-h1 {
  font-size: 20px; font-weight: 600; line-height: 1.4;
  margin: 0; color: var(--pl-text); letter-spacing: .2px;
}
.pl-desc { font-size: 13px; color: var(--pl-text-3); margin: 6px 0 0; }

/* ---------- 概览 ---------- */
.pl-overview {
  display: flex; flex-wrap: wrap; align-items: stretch;
  background: #fff;
  border: 1px solid var(--pl-line);
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .03);
  overflow: hidden;
}
.pl-ov-primary {
  flex: 1 1 300px; min-width: 260px;
  padding: 20px 24px;
  background: var(--pl-bg-soft);
  border-right: 1px solid var(--pl-line);
}
.pl-ov-label { font-size: 13px; color: var(--pl-text-3); }
.pl-ov-value {
  font-size: 34px; font-weight: 600; line-height: 1.15; margin-top: 6px;
  color: var(--pl-text); font-variant-numeric: tabular-nums; letter-spacing: -.5px;
}
.pl-ov-level { display: flex; align-items: center; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
.pl-lv-tag {
  font-size: 12px; font-weight: 500; color: var(--pl-primary);
  background: #eef2ff; border: 1px solid #d3e2fb;
  padding: 2px 8px; border-radius: 4px;
}
.pl-lv-tip { font-size: 12px; color: var(--pl-text-3); }
.pl-lv-bar {
  height: 4px; border-radius: 2px; background: #e7ebf2;
  margin-top: 12px; overflow: hidden;
}
.pl-lv-bar i { display: block; height: 100%; background: var(--pl-primary); border-radius: 2px; }

.pl-ov-kpis {
  flex: 1 1 380px; display: flex; margin: 0;
}
.pl-kpi {
  flex: 1; padding: 20px 24px;
  display: flex; flex-direction: column; justify-content: center;
}
.pl-kpi + .pl-kpi { border-left: 1px solid var(--pl-line-soft); }
.pl-kpi dt { font-size: 13px; color: var(--pl-text-3); }
.pl-kpi dd {
  margin: 8px 0 0; font-size: 22px; font-weight: 600;
  color: var(--pl-text); font-variant-numeric: tabular-nums;
}
.pl-kpi dd.up { color: var(--pl-up); }
.pl-kpi dd.down { color: var(--pl-down); }

/* ---------- 分段筛选 ---------- */
.pl-toolbar { display: flex; align-items: center; margin: 20px 0 12px; }
.pl-seg {
  display: inline-flex; flex-wrap: wrap; gap: 2px;
  background: #f2f4f7; border: 1px solid var(--pl-line);
  border-radius: 6px; padding: 3px;
}
.pl-seg-item {
  border: none; background: transparent; cursor: pointer;
  font-size: 13px; color: var(--pl-text-2);
  padding: 6px 14px; border-radius: 4px;
  transition: background .15s ease, color .15s ease;
}
.pl-seg-item:hover { color: var(--pl-text); }
.pl-seg-item.on {
  background: #fff; color: var(--pl-primary); font-weight: 500;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .06);
}

/* ---------- 明细表 ---------- */
.pl-table {
  background: #fff;
  border: 1px solid var(--pl-line);
  border-radius: 8px;
  box-shadow: 0 1px 2px rgba(16, 24, 40, .03);
  overflow: hidden;
}
.pl-thead, .pl-row {
  display: grid;
  grid-template-columns: 148px minmax(0, 1fr) 76px 104px 128px;
  align-items: center;
  gap: 16px;
  padding: 0 20px;
}
.pl-thead {
  height: 42px;
  background: var(--pl-bg-soft);
  border-bottom: 1px solid var(--pl-line);
  font-size: 12px; font-weight: 500; color: var(--pl-text-3);
  letter-spacing: .3px;
}
.pl-group {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 20px;
  background: #fcfcfd;
  border-bottom: 1px solid var(--pl-line-soft);
  font-size: 12px;
}
.pl-group-day { color: var(--pl-text-2); font-weight: 500; font-variant-numeric: tabular-nums; }
.pl-group-n { color: var(--pl-text-3); }

.pl-row {
  min-height: 52px;
  border-bottom: 1px solid var(--pl-line-soft);
  font-size: 14px;
  transition: background .12s ease;
}
.pl-row:last-child { border-bottom: none; }
.pl-row:hover { background: #f7f9fc; }

.c-type { display: flex; align-items: center; gap: 8px; min-width: 0; }
.pl-dot { width: 6px; height: 6px; border-radius: 50%; flex: none; }
.pl-type-txt {
  color: var(--pl-text-2); font-size: 13px;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.c-reason {
  color: var(--pl-text); min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.c-time { color: var(--pl-text-3); font-size: 13px; font-variant-numeric: tabular-nums; }
.c-delta {
  text-align: right; font-weight: 600; font-size: 15px;
  font-variant-numeric: tabular-nums;
}
.c-delta.up { color: var(--pl-up); }
.c-delta.down { color: var(--pl-down); }
.c-bal {
  text-align: right; color: var(--pl-text-2); font-size: 14px;
  font-variant-numeric: tabular-nums;
}

.pl-state { padding: 56px 20px; text-align: center; color: var(--pl-text-3); font-size: 14px; }
.pl-empty-t { font-size: 15px; color: var(--pl-text-2); }
.pl-empty-s { font-size: 13px; margin-top: 6px; }

/* ---------- 分页（对齐主色） ---------- */
.pl-pager { display: flex; justify-content: flex-end; margin-top: 18px; }
.pl-pager :deep(.el-pagination.is-background .el-pager li.is-active) {
  background: var(--pl-primary); color: #fff;
}
.pl-pager :deep(.el-pagination.is-background .el-pager li:hover) { color: var(--pl-primary); }

/* ---------- 窄屏 ---------- */
@media (max-width: 860px) {
  .pl-ov-primary { border-right: none; border-bottom: 1px solid var(--pl-line); }
  .pl-ov-kpis { flex: 1 1 100%; }
  .pl-kpi { padding: 16px; }
  .pl-kpi dd { font-size: 19px; }

  .pl-thead { display: none; }
  .pl-row {
    grid-template-columns: 1fr auto;
    grid-template-areas:
      'type delta'
      'reason bal'
      'time bal';
    row-gap: 4px; padding: 14px 16px; min-height: 0;
  }
  .c-type { grid-area: type; }
  .c-reason { grid-area: reason; white-space: normal; }
  .c-time { grid-area: time; }
  .c-delta { grid-area: delta; }
  .c-bal { grid-area: bal; align-self: end; }
  .pl-pager { justify-content: center; }
}
</style>
