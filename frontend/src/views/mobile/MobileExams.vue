<script setup>
/**
 * 移动端「考试中心」：试卷列表 + 进入答题
 * 复用 api/training.js 的 listPapers / myExams
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listPapers } from '../../api/training'

const router = useRouter()
const loading = ref(true)
const papers = ref([])

async function load() {
  loading.value = true
  try {
    const r = await listPapers().then((x) => (Array.isArray(x.data) ? x.data : [])).catch(() => [])
    papers.value = r
  } finally { loading.value = false }
}
function open(id) { router.push(`/exam/${id}`) }
onMounted(load)
</script>

<template>
  <div class="exams">
    <div class="m-header">
      <div>
        <div class="m-title">考试中心</div>
        <div class="m-sub">检验你的学习成果</div>
      </div>
      <button class="m-header-act" @click="router.push('/exam/mine')">我的成绩</button>
    </div>
    <div class="body">
      <div v-if="loading" class="m-card">
        <div v-for="i in 3" :key="i" style="padding:14px"><div class="m-skel" style="height:46px"></div></div>
      </div>
      <div v-else-if="papers.length" class="m-card">
        <button v-for="p in papers" :key="p.id" class="m-row" type="button" @click="open(p.id)">
          <span class="m-ico orange"><el-icon><EditPen /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">{{ p.title }}</span>
            <span class="m-row-desc">
              <template v-if="p.questionCount">{{ p.questionCount }} 题</template>
              <template v-if="p.durationMin"> · {{ p.durationMin }} 分钟</template>
              <template v-if="p.passScore != null"> · 及格 {{ p.passScore }} 分</template>
            </span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>
      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><EditPen /></el-icon></span>
          <div>暂无考试</div>
        </div>
      </div>
    </div>
    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.exams { min-height: 100%; }
.body { padding: 0 16px; }
</style>
