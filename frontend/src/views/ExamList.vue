<template>
  <div class="page">
    <div class="hd">
      <h1>在线考试</h1>
      <p class="sub">检验学习成果，交卷即出成绩（客观题自动阅卷）</p>
    </div>

    <div class="bar" v-if="loggedIn">
      <el-button text type="primary" @click="goMine">我的成绩</el-button>
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
        <el-button type="primary" :disabled="p.status !== 'PUBLISHED'" @click="start(p)">开始考试</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listPapers } from '../api/training'
import { useAuthStore } from '../store/auth'

const router = useRouter()
const auth = useAuthStore()
const loggedIn = computed(() => auth.loggedIn)
const papers = ref([])
const loading = ref(false)

onMounted(() => { loading.value = true; listPapers().then(r => { papers.value = r.data || r || [] }).catch(() => { papers.value = [] }).finally(() => loading.value = false) })

const start = (p) => router.push('/exams/' + p.id + '/take')
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
