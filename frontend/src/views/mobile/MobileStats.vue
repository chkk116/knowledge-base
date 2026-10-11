<script setup>
/**
 * 移动端「我的数据」
 *
 * 大厂 App 常见的「使用报告 / 学习统计」：本地统计（不上传）+ 平台概览。
 * 数据来源：
 *   - utils/readStats.js（本机：阅读次数、连续天数、AI 次数、最近读过）
 *   - GET /api/docs/overview（平台：文档总数、本周新增、成员数）
 *   - GET /api/doc-shares/storage（我的已用存储）
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getStats, clearStats } from '../../utils/readStats'
import { homeOverview } from '../../api/kb'
import { getStorage } from '../../api/docShare'

const router = useRouter()
const s = ref(getStats())
const overview = ref(null)
const used = ref(0)

function fmtSize(b) {
  const n0 = Number(b) || 0
  if (!n0) return '0 B'
  const u = ['B', 'KB', 'MB', 'GB', 'TB']
  let i = 0, n = n0
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++ }
  return (n >= 10 || i === 0 ? Math.round(n) : n.toFixed(1)) + ' ' + u[i]
}

function fmtDay(t) {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  const dd = 86400000
  if (diff < dd) return '今天'
  if (diff < dd * 2) return '昨天'
  if (diff < dd * 7) return Math.floor(diff / dd) + ' 天前'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

function openDoc(it) { router.push(`/doc/${it.spaceId}/${it.docId}`) }

async function onClear() {
  try {
    await ElMessageBox.confirm('将清空本机的阅读统计（不影响服务器数据）', '清空统计', {
      confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  clearStats()
  s.value = getStats()
  window.__kbMsg?.success('已清空')
}

onMounted(() => {
  s.value = getStats()
  homeOverview().then((r) => { overview.value = r.data }).catch(() => {})
  getStorage().then((r) => { used.value = r.data?.used || 0 }).catch(() => {})
})
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">我的数据</div>
      <span class="h-ph"></span>
    </header>

    <div class="body">
      <!-- 连续天数主卡 -->
      <div class="hero">
        <div class="hero-num">{{ s.streak }}</div>
        <div class="hero-lbl">连续使用天数</div>
        <div class="hero-sub">累计活跃 {{ s.totalDays }} 天 · 本周阅读 {{ s.weekRead }} 次</div>
      </div>

      <!-- 四格 -->
      <div class="grid">
        <div class="g-item"><div class="g-num">{{ s.todayRead }}</div><div class="g-lbl">今日阅读</div></div>
        <div class="g-item"><div class="g-num">{{ s.weekRead }}</div><div class="g-lbl">本周阅读</div></div>
        <div class="g-item"><div class="g-num">{{ s.totalDocs }}</div><div class="g-lbl">读过的文档</div></div>
        <div class="g-item"><div class="g-num">{{ s.weekAi }}</div><div class="g-lbl">本周 AI 提问</div></div>
      </div>

      <!-- 14 天曲线 -->
      <div class="m-card sec">
        <div class="m-sec-title">最近 14 天阅读</div>
        <div class="bars">
          <div v-for="(d, i) in s.series" :key="d.day" class="bar-wrap">
            <div class="bar" :style="{ height: Math.max(3, Math.round((d.docs / Math.max(1, s.maxSeries || 0)) * 56)) + 'px' }"
                 :class="{ zero: !d.docs }"></div>
            <div class="bar-lbl">{{ i % 3 === 0 || i === s.series.length - 1 ? d.md : '' }}</div>
          </div>
        </div>
        <div class="bars-note">本周累计阅读约 {{ s.weekWords }} 字 · AI 提问 {{ s.weekAi }} 次</div>
      </div>

      <!-- 平台数据 -->
      <div class="m-card sec" v-if="overview">
        <div class="m-sec-title">平台数据</div>
        <div class="kv"><span>文档总数</span><b>{{ overview.totalDocs || 0 }}</b></div>
        <div class="kv"><span>本周新增</span><b>{{ overview.weekNew || 0 }}</b></div>
        <div class="kv"><span>成员数</span><b>{{ overview.members || 0 }}</b></div>
        <div class="kv" v-if="overview.pendingReview != null"><span>待审核</span><b>{{ overview.pendingReview }}</b></div>
        <div class="kv"><span>我的已用存储</span><b>{{ fmtSize(used) }}</b></div>
      </div>

      <!-- 最近读过 -->
      <div class="m-card sec" v-if="s.recentDocs.length">
        <div class="m-sec-title">最近读过</div>
        <button v-for="it in s.recentDocs" :key="it.key" class="m-row" type="button" @click="openDoc(it)">
          <span class="m-ico blue"><el-icon><Document /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">{{ it.title }}</span>
            <span class="m-row-desc">{{ fmtDay(it.t) }}</span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>

      <div v-else class="m-card sec">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><TrendCharts /></el-icon></span>
          <div>还没有阅读记录</div>
          <button class="m-btn sm" style="margin-top:4px" @click="router.push('/docs')">去读一篇</button>
        </div>
      </div>

      <button class="clear" @click="onClear">清空本机统计</button>
      <div class="tip">统计仅保存在本机，不会上传服务器</div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100%; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.body { padding: 4px 16px 28px; }

.hero {
  border-radius: var(--m-radius-lg); padding: 22px 18px 20px; margin-bottom: 12px;
  background: var(--m-primary-soft); text-align: center;
}
.hero-num { font-size: 44px; font-weight: 700; line-height: 1; color: var(--m-primary); font-variant-numeric: tabular-nums; }
.hero-lbl { margin-top: 6px; font-size: 14px; font-weight: 600; color: var(--m-text); }
.hero-sub { margin-top: 4px; font-size: 12.5px; color: var(--m-text-sub); }

.grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 12px; }
.g-item { background: var(--m-card); border-radius: var(--m-radius); padding: 12px 4px; text-align: center; box-shadow: var(--m-shadow); }
.g-num { font-size: 19px; font-weight: 700; color: var(--m-text); font-variant-numeric: tabular-nums; }
.g-lbl { margin-top: 3px; font-size: 11px; color: var(--m-text-dim); }

.sec { padding: 4px 14px 8px; }
.sec .m-sec-title { padding-left: 0; }

.bars { display: flex; align-items: flex-end; gap: 4px; height: 76px; padding: 8px 0 0; }
.bar-wrap { flex: 1 1 0; display: flex; flex-direction: column; align-items: center; justify-content: flex-end; height: 100%; }
.bar { width: 100%; max-width: 14px; border-radius: 4px; background: var(--m-primary); transition: height .25s; }
.bar.zero { background: var(--m-border); }
.bar-lbl { margin-top: 4px; font-size: 9.5px; color: var(--m-text-dim); height: 12px; white-space: nowrap; transform: scale(.92); }
.bars-note { margin-top: 6px; font-size: 12px; color: var(--m-text-dim); }

.kv { display: flex; align-items: center; justify-content: space-between; padding: 11px 0; font-size: 14.5px; color: var(--m-text-sub); }
.kv + .kv { border-top: 1px solid var(--m-border); }
.kv b { color: var(--m-text); font-variant-numeric: tabular-nums; }

.clear {
  width: 100%; margin-top: 16px; height: 44px; border-radius: 12px;
  border: 1px solid var(--m-border); background: var(--m-card);
  color: var(--m-danger); font-size: 15px; cursor: pointer;
}
.tip { margin-top: 10px; text-align: center; font-size: 12px; color: var(--m-text-dim); }
</style>
