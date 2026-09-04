<template>
  <div class="page">
    <div class="hd">
      <h1>培训课程</h1>
      <p class="sub">体系化学习路径，按需选修、逐章完成</p>
    </div>

    <div class="filters" v-if="categories.length">
      <span class="chip" :class="{ on: !activeCat }" @click="activeCat = ''">全部</span>
      <span v-for="c in categories" :key="c" class="chip" :class="{ on: activeCat === c }" @click="activeCat = c">{{ c }}</span>
    </div>

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="!courses.length" class="empty">暂无课程，去管理后台添加吧。</div>

    <div class="grid">
      <div v-for="c in courses" :key="c.id" class="card" @click="go(c.id)">
        <div class="cover" :style="coverStyle(c)">
          <span class="lv" :class="'lv' + c.level">{{ levelText(c.level) }}</span>
        </div>
        <div class="body">
          <h3>{{ c.title }}</h3>
          <p class="sum">{{ c.summary || '暂无简介' }}</p>
          <div class="meta">
            <span>{{ c.ownerName || '官方' }}</span>
            <span v-if="c.category">· {{ c.category }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listCourses, courseCategories } from '../api/training'

const router = useRouter()
const courses = ref([])
const categories = ref([])
const activeCat = ref('')
const loading = ref(false)

const load = () => {
  loading.value = true
  listCourses(activeCat.value).then(r => { courses.value = r.data || r || [] })
    .catch(() => { courses.value = [] })
    .finally(() => { loading.value = false })
}
const loadCats = () => courseCategories().then(r => { categories.value = r.data || r || [] }).catch(() => {})

onMounted(() => { load(); loadCats() })
watch(activeCat, load)

const go = (id) => router.push('/study/courses/' + id)
const levelText = (l) => ({ 1: '入门', 2: '进阶', 3: '高级' }[l] || '入门')
const coverStyle = (c) => c.cover
  ? { backgroundImage: `url(${c.cover})` }
  : { background: 'linear-gradient(135deg,#6366f1,#4f46e5)' }
</script>

<style scoped>
.page { max-width: 1110px; margin: 0 auto; padding: 24px 16px 48px; }
.hd h1 { margin: 0; font-size: 24px; font-weight: 700; }
.hd .sub { color: #64748b; margin: 6px 0 0; font-size: 14px; }
.filters { display: flex; flex-wrap: wrap; gap: 8px; margin: 18px 0 22px; }
.chip { padding: 6px 14px; border-radius: 999px; background: #f1f5f9; color: #475569; cursor: pointer; font-size: 13px; transition: .15s; }
.chip.on { background: #6366f1; color: #fff; }
.tip, .empty { color: #94a3b8; padding: 40px 0; text-align: center; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 18px; }
.card { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; overflow: hidden; cursor: pointer; transition: .18s; }
.card:hover { transform: translateY(-3px); box-shadow: 0 12px 30px rgba(99,102,241,.14); }
.cover { height: 130px; background-size: cover; background-position: center; position: relative; }
.lv { position: absolute; left: 10px; top: 10px; background: rgba(0,0,0,.45); color: #fff; font-size: 12px; padding: 2px 9px; border-radius: 999px; }
.body { padding: 14px 16px 16px; }
.body h3 { margin: 0 0 8px; font-size: 16px; }
.sum { margin: 0; color: #64748b; font-size: 13px; line-height: 1.6; height: 42px; overflow: hidden; }
.meta { margin-top: 12px; color: #94a3b8; font-size: 12px; }
:deep(.dark) .page, .page { }
:deep(html.dark) .card { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .chip { background: #23234a; color: #a5b4fc; }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 1024px) {
  .page { padding: 20px 14px 40px; }
  .grid { grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 14px; }
}

@media (max-width: 640px) {
  .page { padding: 14px 12px 32px; }
  .hd h1 { font-size: 20px; }
  .hd .sub { font-size: 13px; }
  .filters { gap: 6px; margin: 14px 0 16px; }
  .chip { padding: 5px 12px; font-size: 12.5px; }
  .grid { grid-template-columns: 1fr; gap: 12px; }
  .cover { height: 110px; }
  .body { padding: 12px 14px 14px; }
  .body h3 { font-size: 15px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .sum { font-size: 12.5px; height: 40px; }
}
</style>
