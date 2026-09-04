<template>
  <div class="quiz-home">
    <!-- 顶部横幅 -->
    <div class="quiz-banner">
      <div class="quiz-banner-inner">
        <div class="quiz-banner-l">
          <h1>答题闯关</h1>
          <p>检验学习成果，交卷即出成绩 · 客观题自动阅卷</p>
        </div>
        <el-button v-if="auth.loggedIn" round class="quiz-mine" @click="$router.push('/exams/mine')">
          <el-icon><Trophy /></el-icon> 我的成绩
        </el-button>
      </div>
    </div>

    <div class="quiz-body skc-content-box">
      <!-- 左侧分类 -->
      <aside class="quiz-side skc-left-box">
        <div class="side-title">答题分类</div>
        <ul class="side-list">
          <li :class="{ active: activeCat === '' }" @click="activeCat = ''">全部题库</li>
          <li v-for="c in categories" :key="c" :class="{ active: activeCat === c }" @click="activeCat = c">
            {{ c }}
          </li>
        </ul>
        <div class="side-stat">
          <div class="stat-num">{{ papers.length }}</div>
          <div class="stat-label">可答题库</div>
        </div>
      </aside>

      <!-- 右侧卡片列表 -->
      <section class="quiz-main">
        <div class="main-head">
          <span class="main-title">题库列表</span>
          <span class="main-sub">共 {{ filtered.length }} 套</span>
        </div>

        <div v-if="loading" class="quiz-tip">加载中…</div>
        <div v-else-if="!filtered.length" class="quiz-empty">暂无可用题库</div>

        <div v-else class="quiz-grid">
          <div v-for="p in filtered" :key="p.id" class="quiz-card">
            <div class="card-top">
              <span class="card-cat">{{ p.category || '综合' }}</span>
              <el-tag size="small" :type="p.status === 'PUBLISHED' ? 'success' : 'info'" effect="light">
                {{ p.status === 'PUBLISHED' ? '已发布' : '草稿' }}
              </el-tag>
            </div>
            <h3 class="card-title">{{ p.title }}</h3>
            <p class="card-desc">{{ p.description || '暂无说明' }}</p>
            <div class="card-stats">
              <div class="stat"><span class="num">{{ p.questionCount }}</span><span class="lab">题</span></div>
              <div class="stat"><span class="num">{{ p.totalScore }}</span><span class="lab">满分</span></div>
              <div class="stat"><span class="num">{{ p.passScore }}</span><span class="lab">及格</span></div>
              <div class="stat" v-if="p.durationMin"><span class="num">{{ p.durationMin }}</span><span class="lab">分钟</span></div>
            </div>
            <el-button
              class="card-btn"
              type="primary"
              :disabled="p.status !== 'PUBLISHED'"
              @click="$router.push('/exams/' + p.id + '/take')"
            >
              {{ p.status === 'PUBLISHED' ? '开始答题' : '未开放' }}
            </el-button>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { Trophy } from '@element-plus/icons-vue'
import { listPapers } from '../api/training'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const papers = ref([])
const loading = ref(false)
const activeCat = ref('')
const categories = computed(() => {
  const set = new Set(papers.value.map(p => p.category).filter(Boolean))
  return [...set]
})
const filtered = computed(() =>
  activeCat.value ? papers.value.filter(p => p.category === activeCat.value) : papers.value
)

onMounted(() => {
  loading.value = true
  listPapers()
    .then(r => { papers.value = r.data || r || [] })
    .catch(() => { papers.value = [] })
    .finally(() => { loading.value = false })
})
</script>

<style scoped>
.quiz-home { min-height: 100%; background: #f4f6f9; }
html.dark .quiz-home { background: #0e0e1a; }

/* 顶部横幅：蓝白渐变 */
.quiz-banner {
  background: linear-gradient(120deg, #4379c6 0%, #5b8fd6 60%, #7ba1d7 100%);
  color: #fff;
}
.quiz-banner-inner {
  max-width: 1200px; margin: 0 auto; padding: 28px 16px;
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
}
.quiz-banner h1 { margin: 0; font-size: 24px; font-weight: 700; }
.quiz-banner p { margin: 8px 0 0; font-size: 14px; opacity: .9; }
.quiz-mine {
  background: rgba(255,255,255,.18); border-color: rgba(255,255,255,.5);
  color: #fff; backdrop-filter: blur(4px);
}
.quiz-mine:hover { background: rgba(255,255,255,.3); color: #fff; }

/* 主体：skc-content-box 居中 1200px */
.quiz-body { display: flex; gap: 20px; padding: 20px 16px 48px; align-items: flex-start; }

/* 左侧分类栏 */
.quiz-side {
  width: 220px; flex: 0 0 220px;
  background: #fff; border-radius: 14px; padding: 16px;
  box-shadow: 0 2px 10px rgba(67,121,198,.06);
}
html.dark .quiz-side { background: #1b1b2e; }
.side-title { font-size: 14px; font-weight: 600; color: #25396f; margin-bottom: 12px; }
html.dark .side-title { color: #cdd6f4; }
.side-list { list-style: none; margin: 0; padding: 0; }
.side-list li {
  padding: 9px 12px; border-radius: 9px; cursor: pointer; font-size: 14px;
  color: #5a6b86; margin-bottom: 4px; transition: all .15s;
}
html.dark .side-list li { color: #a6adc8; }
.side-list li:hover { background: #eef3fb; color: #4379c6; }
html.dark .side-list li:hover { background: #23233a; color: #a5b4fc; }
.side-list li.active { background: #4379c6; color: #fff; font-weight: 500; }
.side-stat { margin-top: 18px; padding-top: 16px; border-top: 1px solid #eef2f7; text-align: center; }
html.dark .side-stat { border-top-color: #2c2c44; }
.stat-num { font-size: 26px; font-weight: 700; color: #4379c6; }
html.dark .stat-num { color: #a5b4fc; }
.stat-label { font-size: 12px; color: #94a3b8; margin-top: 2px; }

/* 右侧列表 */
.quiz-main { flex: 1; min-width: 0; }
.main-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 16px; }
.main-title { font-size: 18px; font-weight: 700; color: #25396f; }
html.dark .main-title { color: #cdd6f4; }
.main-sub { font-size: 13px; color: #94a3b8; }
.quiz-tip, .quiz-empty { color: #94a3b8; padding: 50px 0; text-align: center; }

.quiz-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 16px; }
.quiz-card {
  background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 18px;
  display: flex; flex-direction: column; transition: all .18s;
  box-shadow: 0 2px 10px rgba(67,121,198,.05);
}
html.dark .quiz-card { background: #1b1b2e; border-color: #2c2c44; }
.quiz-card:hover { transform: translateY(-3px); box-shadow: 0 8px 22px rgba(67,121,198,.14); border-color: #cfe0f5; }
.card-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
.card-cat {
  font-size: 12px; color: #4379c6; background: #eef3fb; padding: 2px 10px; border-radius: 20px;
}
html.dark .card-cat { background: #23233a; color: #a5b4fc; }
.card-title { margin: 0 0 8px; font-size: 17px; color: #25396f; line-height: 1.4; }
html.dark .card-title { color: #cdd6f4; }
.card-desc { color: #64748b; font-size: 13px; line-height: 1.6; min-height: 38px; margin: 0 0 14px; }
html.dark .card-desc { color: #8b95ad; }
.card-stats { display: flex; gap: 14px; margin-bottom: 14px; }
.card-stats .stat { display: flex; flex-direction: column; align-items: center; }
.card-stats .num { font-size: 17px; font-weight: 700; color: #4379c6; }
html.dark .card-stats .num { color: #a5b4fc; }
.card-stats .lab { font-size: 11px; color: #94a3b8; margin-top: 2px; }
.card-btn { width: 100%; }

/* ===================== 响应式布局（追加在末尾） ===================== */
/* 平板 / 窄屏：左栏由竖排侧栏改为顶部横向分类条，卡片网格降列宽 */
@media (max-width: 1024px) {
  .quiz-banner-inner { padding: 20px 14px; }
  .quiz-body { flex-direction: column; gap: 14px; padding: 16px 14px 32px; }
  .quiz-side { width: 100%; flex: none; display: flex; gap: 8px; flex-wrap: wrap; align-items: center; padding: 12px; }
  .side-list { display: flex; gap: 6px; flex-wrap: wrap; }
  .side-list li { margin-bottom: 0; }
  .side-stat { margin: 0 0 0 auto; padding: 0 0 0 14px; border: 0; }
  .quiz-grid { grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 12px; }
}

/* 手机：单列卡片、内边距与字号收紧 */
@media (max-width: 640px) {
  .quiz-banner-inner { flex-wrap: wrap; padding: 16px 12px; }
  .quiz-banner h1 { font-size: 19px; }
  .quiz-banner p { font-size: 12.5px; }
  .quiz-body { padding: 12px 12px 24px; gap: 12px; }
  .quiz-side { padding: 10px; gap: 6px; }
  .side-stat { padding-left: 10px; }
  .stat-num { font-size: 20px; }
  .main-head { flex-wrap: wrap; gap: 6px; margin-bottom: 12px; }
  .main-title { font-size: 16px; }
  .quiz-grid { grid-template-columns: 1fr; gap: 10px; }
  .quiz-card { padding: 14px; }
  .card-title { font-size: 15.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .card-desc { font-size: 12.5px; min-height: 0; margin-bottom: 10px; }
  .card-stats { gap: 10px; margin-bottom: 10px; }
}
</style>
