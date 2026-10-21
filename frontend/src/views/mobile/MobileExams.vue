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

// 模拟练习（2026-10-21）：题库随机抽题，答一题判一题
const simOpen = ref(false)
const simCount = ref(10)
const simCat = ref('')

function startSim() {
  const q = { count: simCount.value }
  if (simCat.value.trim()) q.category = simCat.value.trim()
  simOpen.value = false
  router.push({ path: '/exam/practice', query: q })
}

async function load() {
  loading.value = true
  try {
    const r = await listPapers().then((x) => (Array.isArray(x.data) ? x.data : [])).catch(() => [])
    papers.value = r
  } finally { loading.value = false }
}
function open(id) { modePaper.value = id; modeOpen.value = true }

// 答题模式选择（2026-10-21）：考试 = 计时计分；练习 = 逐题判分看解析、不落成绩
const modeOpen = ref(false)
const modePaper = ref(null)
const modeTitle = ref('')
function goExam() { const id = modePaper.value; modeOpen.value = false; router.push(`/exam/${id}`) }
function goPracticePaper() {
  const id = modePaper.value; modeOpen.value = false
  router.push({ path: '/exam/practice', query: { paperId: id } })
}
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
      <!-- 模拟练习入口：不落成绩、答一题判一题 -->
      <button class="sim-card" type="button" @click="simOpen = true">
        <span class="sim-ico"><el-icon :size="20"><Lightning /></el-icon></span>
        <span class="sim-body">
          <b>模拟练习</b>
          <span>从题库随机抽题，答一题看一题解析，不计入成绩</span>
        </span>
        <el-icon class="m-row-extra"><Right /></el-icon>
      </button>
      <div v-if="loading" class="m-card">
        <div v-for="i in 3" :key="i" style="padding:14px"><div class="m-skel" style="height:46px"></div></div>
      </div>
      <div v-else-if="papers.length" class="m-card">
        <button v-for="p in papers" :key="p.id" class="m-row" type="button" @click="open(p.id)">          <span class="m-ico orange"><el-icon><EditPen /></el-icon></span>
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

    <!-- 模式选择：考试 / 练习 -->
    <transition name="sheet-up">
      <div v-if="modeOpen" class="sheet-mask" @click.self="modeOpen = false">
        <div class="sheet">
          <div class="sheet-title">选择答题模式</div>
          <button class="mode-item" type="button" @click="goExam">
            <span class="mode-ico"><el-icon :size="18"><Timer /></el-icon></span>
            <span class="mode-body">
              <b>考试模式</b>
              <span>限时作答，交卷后统一出分并计入成绩</span>
            </span>
            <el-icon class="m-row-extra"><Right /></el-icon>
          </button>
          <button class="mode-item" type="button" @click="goPracticePaper">
            <span class="mode-ico practice"><el-icon :size="18"><Reading /></el-icon></span>
            <span class="mode-body">
              <b>练习模式</b>
              <span>不限时，答一题立即判分并显示答案解析，不计成绩</span>
            </span>
            <el-icon class="m-row-extra"><Right /></el-icon>
          </button>
          <div class="sheet-actions">
            <button class="ghost-btn" type="button" @click="modeOpen = false">取消</button>
          </div>
        </div>
      </div>
    </transition>

    <!-- 模拟练习设置 -->
    <transition name="sheet-up">
      <div v-if="simOpen" class="sheet-mask" @click.self="simOpen = false">
        <div class="sheet">
          <div class="sheet-title">模拟练习</div>
          <div class="sheet-tip">从题库随机抽题，答一题立即判分看解析，不计入成绩。</div>
          <div class="field">
            <label>题数</label>
            <div class="chips">
              <button v-for="n in [5, 10, 20]" :key="n" type="button" class="chip" :class="{ on: simCount === n }" @click="simCount = n">{{ n }} 题</button>
            </div>
          </div>
          <div class="field">
            <label>分类（可选，留空为全部）</label>
            <input v-model="simCat" class="ipt" type="text" placeholder="例如：网络安全" />
          </div>
          <div class="sheet-actions">
            <button class="ghost-btn" type="button" @click="simOpen = false">取消</button>
            <button class="primary-btn" type="button" @click="startSim">开始练习</button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.exams { min-height: 100%; }
.body { padding: 0 16px; }
.sim-card { display: flex; align-items: center; gap: 12px; width: 100%; margin: 12px 0 10px; padding: 14px;
  border: 1px solid var(--m-border); border-radius: 14px; background: var(--m-card); text-align: left; font-family: inherit; }
.sim-ico { flex: none; width: 38px; height: 38px; border-radius: 12px; display: grid; place-items: center;
  background: var(--m-primary-soft); color: var(--m-primary); }
.sim-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.sim-body b { font-size: 14.5px; color: var(--m-text); }
.sim-body span { font-size: 11.5px; color: var(--m-text-dim); line-height: 1.4; }
.sheet-mask { position: fixed; inset: 0; z-index: 60; background: rgba(0, 0, 0, .35); display: flex; align-items: flex-end; }
.sheet { width: 100%; background: var(--m-bg, #f5f6f7); border-radius: 16px 16px 0 0; padding: 16px 14px calc(16px + env(safe-area-inset-bottom)); }
.sheet-title { font-size: 16px; font-weight: 500; color: var(--m-text); margin-bottom: 6px; }
.sheet-tip { font-size: 12px; color: var(--m-text-dim); line-height: 1.5; margin-bottom: 12px; }
.field { margin-bottom: 12px; }
.field label { display: block; font-size: 12px; color: var(--m-text-dim); margin-bottom: 6px; }
.chips { display: flex; gap: 8px; }
.chip { padding: 8px 16px; border: 1px solid var(--m-border); border-radius: 999px; background: var(--m-card);
  color: var(--m-text); font-size: 13px; font-family: inherit; }
.chip.on { background: var(--m-primary); border-color: var(--m-primary); color: #fff; }
.ipt { width: 100%; height: 40px; padding: 0 12px; border: 1px solid var(--m-border); border-radius: 10px;
  background: var(--m-card); color: var(--m-text); font-size: 14px; font-family: inherit; box-sizing: border-box; }
.sheet-actions { display: flex; gap: 10px; margin-top: 4px; }
.mode-item { display: flex; align-items: center; gap: 12px; width: 100%; padding: 14px; margin-bottom: 10px;
  border: 1px solid var(--m-border); border-radius: 12px; background: var(--m-card); text-align: left; font-family: inherit; }
.mode-ico { flex: none; width: 34px; height: 34px; border-radius: 10px; display: grid; place-items: center;
  background: var(--m-primary-soft); color: var(--m-primary); }
.mode-ico.practice { background: rgba(22, 163, 74, .1); color: var(--m-success); }
.mode-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.mode-body b { font-size: 14.5px; color: var(--m-text); }
.mode-body span { font-size: 11.5px; color: var(--m-text-dim); line-height: 1.4; }
.ghost-btn { flex: none; width: 88px; height: 44px; border: 1px solid var(--m-border); border-radius: 12px;
  background: transparent; color: var(--m-text); font-size: 14px; font-family: inherit; }
.primary-btn { flex: 1; height: 44px; border: none; border-radius: 12px; background: var(--m-primary);
  color: #fff; font-size: 15px; font-family: inherit; }
</style>
