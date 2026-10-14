<script setup>
/**
 * 移动端「我的考试」：历史成绩列表
 * 复用 api/training.js 的 myExams
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { myExams } from '../../api/training'

const router = useRouter()
const loading = ref(true)
const rows = ref([])

function fmtDate(s) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d)) return ''
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load() {
  loading.value = true
  try {
    const r = await myExams().then((x) => (Array.isArray(x.data) ? x.data : [])).catch(() => [])
    rows.value = r
  } finally { loading.value = false }
}
function again(r) { if (r.paperId) router.push(`/exam/${r.paperId}`) }
onMounted(load)
</script>

<template>
  <div class="mine">
    <div class="m-header">
      <div class="m-title">我的考试</div>
      <div class="m-sub">历史成绩</div>
    </div>
    <div class="body">
      <div v-if="loading" class="m-card">
        <div v-for="i in 3" :key="i" style="padding:14px"><div class="m-skel" style="height:40px"></div></div>
      </div>
      <div v-else-if="rows.length" class="m-card">
        <div v-for="r in rows" :key="r.id" class="rec">
          <span class="r-ico" :class="{ pass: r.passed }">
            <el-icon><component :is="r.passed ? 'SuccessFilled' : 'CircleCloseFilled'" /></el-icon>
          </span>
          <span class="r-body">
            <span class="r-title">{{ r.paperTitle || ('考试 #' + r.paperId) }}</span>
            <span class="r-desc">{{ fmtDate(r.createdAt) }}</span>
          </span>
          <span class="r-score" :class="{ pass: r.passed }">{{ r.score }}<i>分</i></span>
          <button v-if="r.paperId" class="r-again" @click="again(r)">再考</button>
        </div>
      </div>
      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Trophy /></el-icon></span>
          <div>还没有考试记录</div>
          <button class="m-btn sm" style="margin-top:4px" @click="router.push('/exams')">去考试</button>
        </div>
      </div>
    </div>
    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.mine { min-height: 100%; }
.body { padding: 0 16px; }
.rec { display: flex; align-items: center; gap: 10px; padding: 12px 4px; border-bottom: 1px solid var(--m-border); }
.rec:last-child { border-bottom: none; }
.r-ico { flex: none; width: 30px; height: 30px; border-radius: 50%; display: grid; place-items: center;
  background: #fde8ef; color: var(--m-danger); }
.r-ico.pass { background: #e8f8ef; color: var(--m-success); }
.r-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.r-title { font-size: 14.5px; color: var(--m-text); font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.r-desc { font-size: 11.5px; color: var(--m-text-dim); }
.r-score { font-size: 19px; font-weight: 700; color: var(--m-danger); font-variant-numeric: tabular-nums; }
.r-score.pass { color: var(--m-success); }
.r-score i { font-size: 11px; font-style: normal; margin-left: 2px; font-weight: 500; }
.r-again { flex: none; border: 1px solid var(--m-border); background: var(--m-card); color: var(--m-primary);
  font-size: 12px; padding: 5px 10px; border-radius: 8px; cursor: pointer; }
</style>
