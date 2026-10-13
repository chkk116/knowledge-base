<template>
  <div class="exam" v-if="paper">
    <!-- 顶部信息条：蓝白 -->
    <header class="exam-head">
      <div class="exam-head-inner">
        <div class="exam-title">
          <el-icon class="back" @click="$router.push('/quiz')"><ArrowLeft /></el-icon>
          <div>
            <h1>{{ paper.paper.title }}</h1>
            <div class="exam-meta">
              <span>满分 {{ paper.paper.totalScore }}</span>
              <span>及格 {{ paper.paper.passScore }}</span>
              <span v-if="paper.paper.durationMin">题量 {{ paper.questions.length }}</span>
            </div>
          </div>
        </div>
        <div v-if="paper.paper.durationMin" class="exam-timer" :class="{ warn: remain <= 60 }">
          <el-icon><Clock /></el-icon>
          <span>{{ fmt(remain) }}</span>
        </div>
      </div>
    </header>

    <div class="exam-body skc-content-box">
      <!-- 答题卡进度 -->
      <div class="exam-progress">
        已答 <b>{{ answered }}</b> / {{ paper.questions.length }} 题
        <el-progress class="exam-bar" :percentage="progress" :stroke-width="6" color="#4379c6" :show-text="false" />
      </div>

      <div class="qs">
        <div v-for="(q, i) in paper.questions" :key="q.id" class="q">
          <div class="qt">
            <span class="q-no">{{ i + 1 }}</span>
            <span class="q-text">{{ q.content }}</span>
            <em class="q-type">{{ typeText(q.type) }} · {{ q.score }}分</em>
          </div>

          <div v-if="q.type === 'SINGLE' || q.type === 'JUDGE'" class="opts">
            <label v-for="o in q.options" :key="o.id" class="opt" :class="{ checked: pick[q.id] === o.label }">
              <input type="radio" :name="'q' + q.id" :value="o.label" v-model="pick[q.id]" />
              <span class="opt-mark">{{ o.label }}</span>
              <span class="opt-content">{{ o.content }}</span>
            </label>
          </div>

          <div v-else-if="q.type === 'MULTI'" class="opts">
            <label v-for="o in q.options" :key="o.id" class="opt" :class="{ checked: (multi[q.id] || []).includes(o.label) }">
              <input type="checkbox" :value="o.label" v-model="multi[q.id]" />
              <span class="opt-mark square">{{ o.label }}</span>
              <span class="opt-content">{{ o.content }}</span>
            </label>
          </div>

          <div v-else class="fill">
            <el-input
              v-model="text[q.id]"
              :type="q.type === 'ESSAY' ? 'textarea' : 'text'"
              :rows="q.type === 'ESSAY' ? 4 : 1"
              :placeholder="q.type === 'FILL' ? '请填写答案' : '请作答'"
            />
          </div>
        </div>
      </div>

      <div class="exam-foot">
        <el-button @click="$router.push('/quiz')">退出</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">交卷</el-button>
      </div>
    </div>
  </div>
  <div v-else class="exam-tip">加载试卷中…</div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Clock } from '@element-plus/icons-vue'
import { getPaper, startExam, submitExam } from '../api/training'
import { errMsg } from '../utils/errMsg'

const route = useRoute()
const router = useRouter()
const paper = ref(null)
const pick = reactive({})       // 单选/判断：questionId -> label
const multi = reactive({})      // 多选：questionId -> [label]
const text = reactive({})       // 填空/简答：questionId -> string
const recordId = ref(null)
const submitting = ref(false)
const remain = ref(0)
// 服务端固化的交卷截止时间（毫秒时间戳）：限时以它为准，不能每次进页面都按整场时长重算，
// 否则刷新/重进就能把本地倒计时重置成满格，界面显示的时间远多于真实剩余时间（服务端仍会拒卷）
const deadlineTs = ref(0)
let timer = null
let unmounted = false   // 卸载标记：异步回调里据此提前返回，防止卸载后重建定时器（H8）

const answered = computed(() => paper.value
  ? paper.value.questions.filter(q => {
      if (q.type === 'MULTI') return (multi[q.id] || []).length > 0
      if (q.type === 'SINGLE' || q.type === 'JUDGE') return !!pick[q.id]
      return !!text[q.id]
    }).length
  : 0)
const progress = computed(() => paper.value
  ? Math.round(answered.value / paper.value.questions.length * 100)
  : 0)

const fmt = (s) => {
  const m = Math.floor(s / 60), ss = s % 60
  return m + ':' + String(ss).padStart(2, '0')
}
const typeText = (t) => ({ SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }[t] || t)

const load = () => {
  getPaper(route.params.id).then(r => {
    if (unmounted) return   // 卸载后迟到的响应：不建定时器、不触发自动交卷（2026-10-13 审查 H8）
    paper.value = r.data || r
    // 多选题选中集合预初始化：checkbox v-model 绑到 undefined 会被当布尔开关，后续 .includes 直接崩（2026-10-13 审查 C5）
    paper.value.questions.forEach(q => {
      if (q.type === 'MULTI' && !Array.isArray(multi[q.id])) multi[q.id] = []
    })
    if (paper.value.paper.durationMin) {
      if (timer) clearInterval(timer)   // 防止重复进入时叠出多个倒计时
      // 有服务端截止时间就按它算剩余；缺失（旧记录/不限时）才退回按整场时长计时
      remain.value = deadlineTs.value
        ? Math.max(0, Math.round((deadlineTs.value - Date.now()) / 1000))
        : paper.value.paper.durationMin * 60
      timer = setInterval(() => {
        remain.value--
        if (remain.value <= 0) { clearInterval(timer); submit() }
      }, 1000)
    }
  }).catch(e => { ElMessage.error(errMsg(e, '试卷加载失败')); router.push('/quiz') })
}
// 防泄题：必须先 start 成功（拿到 recordId）才拉题目；start 被前置校验拦截时绝不加载试卷
const begin = () => startExam(route.params.id)
  .then(r => {
    const rec = r.data || r
    recordId.value = rec.id
    // 复用进行中的记录时，服务端会回传该记录真实的 deadlineAt
    deadlineTs.value = rec.deadlineAt ? new Date(rec.deadlineAt).getTime() : 0
    load()
  })
  .catch(e => {
    ElMessage.error(errMsg(e, '开始考试失败'))
    router.push('/quiz')
  })

const buildItems = () => paper.value.questions.map(q => {
  let answer
  if (q.type === 'MULTI') answer = multi[q.id] || []
  else if (q.type === 'SINGLE' || q.type === 'JUDGE') answer = pick[q.id] ? [pick[q.id]] : []
  else answer = text[q.id] ? [text[q.id]] : []
  return { questionId: q.id, answer }
})

const submit = () => {
  if (!recordId.value) { ElMessage.warning('考试未开始'); return }
  if (submitting.value) return   // 倒计时归零自动交卷与手动点击可能同时触发，只放行一次
  submitting.value = true
  submitExam(route.params.id, recordId.value, buildItems())
    .then(() => { clearInterval(timer); router.push('/exams/records/' + recordId.value) })
    .catch(e => { submitting.value = false; ElMessage.error(errMsg(e, '交卷失败')) })
}

onMounted(() => { begin() })
onUnmounted(() => { unmounted = true; clearInterval(timer) })
</script>

<style scoped>
.exam { min-height: 100%; background: #f4f6f9; }
html.dark .exam { background: #0e0e1a; }
.exam-tip { text-align: center; color: #94a3b8; padding: 60px 0; }

/* 顶部信息条 */
.exam-head { background: #fff; border-bottom: 1px solid #e8eef6; position: sticky; top: 0; z-index: 10; }
html.dark .exam-head { background: #1b1b2e; border-bottom-color: #2c2c44; }
.exam-head-inner { max-width: 860px; margin: 0 auto; padding: 14px 16px; display: flex; align-items: center; justify-content: space-between; }
.exam-title { display: flex; align-items: center; gap: 12px; }
.back { font-size: 20px; color: #4379c6; cursor: pointer; }
html.dark .back { color: #a5b4fc; }
.exam-title h1 { margin: 0; font-size: 19px; color: #25396f; }
html.dark .exam-title h1 { color: #cdd6f4; }
.exam-meta { color: #94a3b8; font-size: 13px; display: flex; gap: 14px; margin-top: 4px; }
.exam-timer { display: flex; align-items: center; gap: 6px; font-size: 18px; font-weight: 700; color: #4379c6; font-variant-numeric: tabular-nums; }
html.dark .exam-timer { color: #a5b4fc; }
.exam-timer.warn { color: #ef6d59; }

/* 主体 */
.exam-body { padding: 20px 16px 60px; }
.exam-progress { color: #5a6b86; font-size: 13px; margin-bottom: 16px; display: flex; align-items: center; gap: 10px; }
html.dark .exam-progress { color: #a6adc8; }
.exam-progress b { color: #4379c6; }
html.dark .exam-progress b { color: #a5b4fc; }
.exam-bar { flex: 1; max-width: 280px; }

.q { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 18px 20px; margin-bottom: 14px; box-shadow: 0 2px 10px rgba(67,121,198,.05); }
html.dark .q { background: #1b1b2e; border-color: #2c2c44; }
.qt { font-size: 15px; line-height: 1.6; margin-bottom: 14px; color: #25396f; display: flex; gap: 10px; align-items: flex-start; }
html.dark .qt { color: #cdd6f4; }
.q-no { flex: 0 0 24px; height: 24px; border-radius: 7px; background: #4379c6; color: #fff; font-size: 13px; font-weight: 700; display: inline-flex; align-items: center; justify-content: center; }
html.dark .q-no { background: #a5b4fc; color: #1b1b2e; }
.q-text { flex: 1; }
.q-type { color: #94a3b8; font-style: normal; font-size: 12px; font-weight: 400; white-space: nowrap; }

.opts { display: flex; flex-direction: column; gap: 10px; padding-left: 34px; }
.opt { display: flex; gap: 10px; align-items: center; cursor: pointer; font-size: 14px; padding: 10px 12px; border: 1px solid #eef2f7; border-radius: 10px; transition: all .15s; }
html.dark .opt { border-color: #2c2c44; }
.opt:hover { border-color: #cfe0f5; background: #f7faff; }
html.dark .opt:hover { background: #23233a; border-color: #3a3a5c; }
.opt input { display: none; }
.opt-mark { flex: 0 0 26px; height: 26px; border-radius: 50%; border: 1.5px solid #c3d2e6; color: #4379c6; font-size: 13px; font-weight: 700; display: inline-flex; align-items: center; justify-content: center; transition: all .15s; }
.opt-mark.square { border-radius: 7px; }
html.dark .opt-mark { border-color: #3a3a5c; color: #a5b4fc; }
.opt.checked { border-color: #4379c6; background: #eef3fb; }
html.dark .opt.checked { border-color: #a5b4fc; background: #23233a; }
.opt.checked .opt-mark { background: #4379c6; color: #fff; border-color: #4379c6; }
html.dark .opt.checked .opt-mark { background: #a5b4fc; border-color: #a5b4fc; color: #1b1b2e; }
.opt-content { line-height: 1.5; }

.fill { padding-left: 34px; }
.exam-foot { display: flex; justify-content: flex-end; gap: 12px; margin-top: 22px; }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 1024px) {
  .exam-head-inner { padding: 12px 14px; }
  .exam-body { padding: 16px 14px 48px; }
  .q { padding: 16px; }
  .opts, .fill { padding-left: 20px; }
}

@media (max-width: 640px) {
  .exam-head-inner { padding: 10px 12px; gap: 10px; }
  .exam-title { gap: 8px; min-width: 0; }
  .exam-title h1 { font-size: 16px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .exam-meta { gap: 10px; font-size: 12px; flex-wrap: wrap; }
  .exam-timer { font-size: 16px; }

  /* 答题进度条：独占一行，不再挤压标题 */
  .exam-progress { flex-wrap: wrap; gap: 8px; font-size: 12.5px; }
  .exam-bar { flex: 1 1 100%; max-width: 100%; }

  .exam-body { padding: 14px 12px 40px; }
  .q { padding: 14px 12px; margin-bottom: 12px; }
  .qt { font-size: 14px; gap: 8px; flex-wrap: wrap; }
  .q-type { margin-left: 32px; }

  /* 选项：整行可点、长内容自动换行 */
  .opts { padding-left: 0; gap: 8px; }
  .opt { padding: 10px; font-size: 13.5px; }
  .opt-content { flex: 1; min-width: 0; overflow-wrap: anywhere; }
  .fill { padding-left: 0; }

  .exam-foot { gap: 10px; }
  .exam-foot :deep(.el-button) { flex: 1 1 calc(50% - 5px); margin-left: 0; }
}
</style>
