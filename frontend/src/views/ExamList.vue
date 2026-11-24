<template>
  <div class="page">
    <div class="hd">
      <h1>在线考试</h1>
      <p class="sub">检验学习成果，交卷即出成绩（客观题自动阅卷）</p>
    </div>

    <ExamTabs active="exams" />

    <!-- 管理员设为「模拟卷」的那份试卷（试卷属性，在考试管理里拨开关） -->
    <div v-if="mock.paperId" class="mock">
      <div class="mock-info">
        <b>模拟卷：{{ mock.title }}</b>
        <span>{{ mock.questionCount }} 题 · 答一题看一题解析，不计成绩</span>
      </div>
      <el-button type="primary" plain @click="goMock">开始练习</el-button>
    </div>

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="!papers.length" class="empty">暂无可用试卷。</div>

    <div class="grid">
      <div v-for="p in papers" :key="p.id" class="card">
        <div class="top">
          <h3>{{ p.title }}</h3>
          <el-tag size="small" :type="p.status === 'PUBLISHED' ? 'success' : 'info'" effect="plain">{{ p.status === 'PUBLISHED' ? '已发布' : '草稿' }}</el-tag>
        </div>
        <p class="desc">{{ p.description || '暂无说明' }}</p>
        <div class="stats">
          <span>共 {{ p.questionCount }} 题</span>
          <span>满分 {{ p.totalScore }}</span>
          <span>及格 {{ p.passScore }}</span>
          <span v-if="p.durationMin">时长 {{ p.durationMin }} 分</span>
        </div>
        <div class="acts">
          <el-button type="primary" :disabled="p.status !== 'PUBLISHED' || !p.questionCount"
                   @click="start(p)">{{ p.questionCount ? '开始考试' : '未组卷' }}</el-button>
          <el-tag v-if="p.status === 'PUBLISHED' && !p.questionCount" size="small" type="danger" effect="plain">0 题，未组卷</el-tag>
          <el-button plain :disabled="p.status !== 'PUBLISHED' || p.practiceEnabled === false" @click="practice(p)">练习</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { listPapers, getMockPaper } from '../api/training'
import ExamTabs from '../components/ExamTabs.vue'

const router = useRouter()
const papers = ref([])
const loading = ref(false)
const mock = ref({ paperId: null, title: '', questionCount: 0 })

onMounted(() => {
  loading.value = true
  listPapers().then(r => { papers.value = r.data || r || [] }).catch(() => { papers.value = [] }).finally(() => loading.value = false)
  getMockPaper().then(r => { mock.value = r.data || r || mock.value }).catch(() => {})
})

// 开考须知（E1/N68）：此前点「开始考试」直接进考场，没有时长/计次/交卷规则的告知
const start = async (p) => {
  const rules = [
    `共 ${p.questionCount} 题，满分 ${p.totalScore}，及格 ${p.passScore}`,
    p.durationMin ? `限时 ${p.durationMin} 分钟，进入即开始计时` : '不限时',
    '交卷后立即出成绩，客观题自动阅卷',
    '一题未答就离开会自动作废本次考试，不占考试次数',
  ]
  try {
    await ElMessageBox.confirm(rules.join('；') + '。', '考试须知', {
      // 与列表按钮同一口径（N78）：入口写「开始考试」，弹窗确认也写「开始考试」
      type: 'info', confirmButtonText: '开始考试', cancelButtonText: '再看看',
    })
  } catch { return }
  router.push('/exams/' + p.id + '/take')
}
const practice = (p) => router.push({ path: '/exams/practice', query: { paperId: p.id } })
const goMock = () => router.push({ path: '/exams/practice', query: { paperId: mock.value.paperId } })
</script>

<style scoped>
.page { max-width: 1110px; margin: 0 auto; padding: 24px 16px 48px; }
.hd h1 { margin: 0; font-size: 24px; }
.hd .sub { color: #64748b; margin: 6px 0 0; font-size: 14px; }
.bar { margin: 16px 0; }
.tip, .empty { color: #94a3b8; padding: 40px 0; text-align: center; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 16px; }
.card { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 18px; }
.top { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
.top h3 { margin: 0; font-size: 17px; }
.desc { color: #64748b; font-size: 13px; line-height: 1.6; min-height: 38px; margin: 10px 0; }
.stats { display: flex; flex-wrap: wrap; gap: 12px; color: #94a3b8; font-size: 12px; margin-bottom: 14px; }
.acts { display: flex; gap: 10px; }
.mock { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 14px 16px; margin: 0 0 16px;
  background: #eef2ff; border: 1px solid #dbe3ff; border-radius: 14px; }
.mock-info { display: flex; flex-direction: column; gap: 4px; }
.mock-info b { font-size: 15px; color: #312e81; }
.mock-info span { font-size: 12px; color: #6366f1; }
:deep(html.dark) .card { background: #1b1b2e; border-color: #2c2c44; }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 1024px) {
  .page { padding: 20px 14px 40px; }
  .grid { grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 14px; }
}

@media (max-width: 640px) {
  .page { padding: 14px 12px 32px; }
  .hd h1 { font-size: 20px; }
  .hd .sub { font-size: 13px; }
  .bar { margin: 12px 0; }
  .grid { grid-template-columns: 1fr; gap: 12px; }
  .card { padding: 14px; }
  .top { flex-wrap: wrap; gap: 6px; }
  .top h3 { font-size: 15px; min-width: 0; overflow-wrap: anywhere; }
  .desc { font-size: 12.5px; min-height: 0; }
  .stats { gap: 8px 10px; font-size: 12px; margin-bottom: 12px; }
}
</style>
