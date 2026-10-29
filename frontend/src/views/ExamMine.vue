<template>
  <div class="page">
    <div class="hd">
      <h1>我的成绩</h1>
      <p class="sub">你参加过的每一场考试与最终得分（客观题交卷即出成绩）</p>
    </div>

    <ExamTabs active="mine" />

    <!-- 概览统计 -->
    <div class="stats">
      <div class="stat">
        <div class="v">{{ stats.count }}</div>
        <div class="k">参考次数</div>
      </div>
      <div class="stat">
        <div class="v">{{ stats.avg }}<span class="u">分</span></div>
        <div class="k">平均得分</div>
      </div>
      <div class="stat">
        <div class="v">{{ stats.passRate }}<span class="u">%</span></div>
        <div class="k">及格率</div>
      </div>
      <div class="stat">
        <div class="v">{{ stats.best }}<span class="u">分</span></div>
        <div class="k">最高得分</div>
      </div>
    </div>

    <!-- 导航已由顶部 ExamTabs 承担，不再单独放「返回考试列表」 -->

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="!rows.length" class="empty">
      <p>你还没有参加过任何考试。</p>
      <el-button type="primary" @click="goList">去考试</el-button>
    </div>

    <el-table v-else v-stack-table :data="rows" class="tbl" stripe>
      <el-table-column label="试卷" min-width="200">
        <template #default="{ row }">
          <span class="pt">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column label="得分" width="110" align="center">
        <template #default="{ row }">
          <span class="score" :class="row.pendingCount > 0 ? 'wait' : (row.passed ? 'ok' : 'no')">{{ row.score }}</span>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="100" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.pendingCount > 0" size="small" type="warning" effect="plain">待阅卷</el-tag>
          <el-tag v-else size="small" :type="row.passed ? 'success' : 'danger'" effect="plain">{{ row.passed ? '及格' : '未及格' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="题数" width="90" align="center" class-name="stack-hide">
        <template #default="{ row }">{{ row.questionCount > 0 ? row.questionCount + ' 题' : '—' }}</template>
      </el-table-column>
      <el-table-column label="用时" width="120" align="center" class-name="stack-hide">
        <template #default="{ row }">{{ fmtDuration(row.durationSec) }}</template>
      </el-table-column>
      <el-table-column label="交卷时间" min-width="150">
        <template #default="{ row }">{{ fmtDateTime(row.submittedAt || row.startedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="180" align="center" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="primary" link @click="viewDetail(row)">查看详情</el-button>
          <el-button size="small" type="primary" link :disabled="!published.has(row.paperId)" @click="retake(row)">重考</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { myExams, listPapers } from '../api/training'
import { fmtDateTime } from '../utils/time'
import ExamTabs from '../components/ExamTabs.vue'

const router = useRouter()
const rows = ref([])
const loading = ref(false)
const paperMap = ref({})      // paperId -> title
const published = ref(new Set()) // 已发布可重考的试卷

onMounted(() => {
  loading.value = true
  Promise.all([
    myExams().then(r => r.data || []),
    listPapers().then(r => r.data || []).catch(() => []),
  ])  .then(([recs, papers]) => {
    const info = {}       // paperId -> { title, questionCount }
    const pub = new Set()
    papers.forEach(p => { info[p.id] = p; pub.add(p.id) })
    paperMap.value = info
    published.value = pub
    rows.value = (recs || []).map(r => {
      const p = info[r.paperId]
      return {
        ...r,
        title: r.paperTitle || (p && p.title) || '未知试卷',
        // questionCount 后端未回填，用试卷本身的题数兜底
        questionCount: (r.questionCount || (p && p.questionCount)) || 0,
        score: r.score ?? 0,
        durationSec: r.durationSec ?? 0,
      }
    })
  }).catch(() => { rows.value = [] }).finally(() => loading.value = false)
})

const stats = computed(() => {
  const list = rows.value
  const count = list.length
  if (!count) return { count: 0, avg: 0, passRate: 0, best: 0 }
  const sum = list.reduce((a, r) => a + (r.score || 0), 0)
  const passed = list.filter(r => r.passed).length
  const best = list.reduce((a, r) => Math.max(a, r.score || 0), 0)
  return {
    count,
    avg: Math.round(sum / count),
    passRate: Math.round((passed / count) * 100),
    best,
  }
})

const fmtDuration = (s) => {
  s = s || 0
  const m = Math.floor(s / 60)
  const sec = s % 60
  return m > 0 ? `${m} 分 ${sec} 秒` : `${sec} 秒`
}

const goList = () => router.push('/exams')
const viewDetail = (row) => router.push('/exams/records/' + row.id)
const retake = (row) => router.push('/exams/' + row.paperId + '/take')
</script>

<style scoped>
.page { max-width: 1110px; margin: 0 auto; padding: 24px 16px 48px; }
.hd h1 { margin: 0; font-size: 24px; }
.hd .sub { color: #64748b; margin: 6px 0 0; font-size: 14px; }

.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin: 18px 0 4px; }
.stat { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 16px; text-align: center; }
.stat .v { font-size: 26px; font-weight: 800; color: #4f46e5; line-height: 1.1; }
.stat .v .u { font-size: 14px; font-weight: 600; color: #94a3b8; margin-left: 2px; }
.stat .k { color: #94a3b8; font-size: 13px; margin-top: 4px; }

.bar { margin: 16px 0; }
.tip, .empty { color: #94a3b8; padding: 40px 0; text-align: center; }
.empty .el-button { margin-top: 12px; }

.tbl { margin-top: 8px; border-radius: 12px; overflow: hidden; }
.pt { font-weight: 600; color: #1e293b; }
.score { font-size: 18px; font-weight: 800; }
.score.ok { color: #16a34a; }
.score.no { color: #dc2626; }
.score.wait { color: #b45309; }

:deep(html.dark) .stat { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .stat .v { color: #818cf8; }
:deep(html.dark) .stat .v .u { color: #64748b; }
:deep(html.dark) .pt { color: #e2e8f0; }
:deep(html.dark) .tbl { background: #1b1b2e; }

/* ===================== 响应式布局 ===================== */
@media (max-width: 1024px) {
  .page { padding: 20px 14px 40px; }
  .stats { grid-template-columns: repeat(4, 1fr); gap: 12px; }
  .stat { padding: 14px 10px; }
  .stat .v { font-size: 22px; }
}

@media (max-width: 760px) {
  .stats { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 640px) {
  .page { padding: 14px 12px 32px; }
  .hd h1 { font-size: 20px; }
  .hd .sub { font-size: 13px; }
  .stats { gap: 10px; }
  .stat { padding: 12px 8px; border-radius: 12px; }
  .stat .v { font-size: 20px; }
  .stat .k { font-size: 12px; }
  .bar { margin: 12px 0; }
}
</style>
