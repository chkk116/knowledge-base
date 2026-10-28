<template>
  <div class="page">
    <div class="hd">
      <h1>在线考试</h1>
      <p class="sub">检验学习成果，交卷即出成绩（客观题自动阅卷）</p>
    </div>

    <div class="bar" v-if="loggedIn">
      <el-button text type="primary" @click="goMine">我的成绩</el-button>
      <el-button text type="primary" @click="router.push('/my-quiz')">我的测验</el-button>
      <el-button text type="primary" @click="router.push('/exams/wrong')">错题本</el-button>
    </div>

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
          <el-button type="primary" :disabled="p.status !== 'PUBLISHED'" @click="start(p)">开始考试</el-button>
          <el-button plain :disabled="p.status !== 'PUBLISHED' || p.practiceEnabled === false" @click="practice(p)">练习</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listPapers, getMockPaper } from '../api/training'
import { useAuthStore } from '../store/auth'

const router = useRouter()
const auth = useAuthStore()
const loggedIn = computed(() => auth.loggedIn)
const papers = ref([])
const loading = ref(false)
const mock = ref({ paperId: null, title: '', questionCount: 0 })

onMounted(() => {
  loading.value = true
  listPapers().then(r => { papers.value = r.data || r || [] }).catch(() => { papers.value = [] }).finally(() => loading.value = false)
  getMockPaper().then(r => { mock.value = r.data || r || mock.value }).catch(() => {})
})

const start = (p) => router.push('/exams/' + p.id + '/take')
const practice = (p) => router.push({ path: '/exams/practice', query: { paperId: p.id } })
const goMock = () => router.push({ path: '/exams/practice', query: { paperId: mock.value.paperId } })
const goMine = () => router.push('/exams/mine')
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
