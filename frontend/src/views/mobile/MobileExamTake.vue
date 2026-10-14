<script setup>
/**
 * 移动端答题页：开始考试 → 取卷 → 逐题作答（单选/多选/判断/填空/简答）→ 限时 → 交卷 → 看结果
 * 后端约定（ExamService）：
 *   - GET /exams/{id} 必须先 POST /exams/{id}/start 建记录，否则报「请先开始考试」
 *   - 取卷返回 { paper:{...}, questions:[{id,type,content,score,options:[{id,label,content}]}] }
 *   - 交卷 items:[{questionId, answer: List<String>}]，answer 恒为「选项 label 数组」（填空/简答为 [文本]）
 */
import { onMounted, ref, computed, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getPaper, startExam, submitExam } from '../../api/training'

const route = useRoute()
const router = useRouter()
const pid = route.params.id

const loading = ref(true)
const paper = ref(null)
const questions = ref([])
const recordId = ref(null)
const cur = ref(0)
const answers = ref({})      // questionId -> 单选/判断:label 字符串；多选:label 数组；填空/简答:文本
const submitting = ref(false)
const finished = ref(false)
const result = ref(null)
const errMsg = ref('')      // 开始考试失败原因（如「请先完成课程全部章节」）

const TYPE_CN = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }

// 计时
const remain = ref(0)
let timer = null
function fmt(s) {
  const m = Math.floor(s / 60), ss = s % 60
  return `${String(m).padStart(2, '0')}:${String(ss).padStart(2, '0')}`
}
function tick() {
  if (remain.value > 0) remain.value--
  else { stopTimer(); if (!finished.value) doSubmit(true) }
}
function startTimer() {
  if (paper.value?.durationMin > 0) { remain.value = paper.value.durationMin * 60; timer = setInterval(tick, 1000) }
}
function stopTimer() { if (timer) { clearInterval(timer); timer = null } }

function setAns(q, v) { if (!finished.value) answers.value[q.id] = v }
function toggleMulti(q, label) {
  const a = Array.isArray(answers.value[q.id]) ? [...answers.value[q.id]] : []
  const i = a.indexOf(label)
  if (i >= 0) a.splice(i, 1); else a.push(label)
  answers.value[q.id] = a
}
function isAnswered(q) {
  const a = answers.value[q.id]
  if (a == null) return false
  if (Array.isArray(a)) return a.length > 0
  return String(a).length > 0
}
/** 统一转成后端要的 label 数组 */
function toAnswer(q) {
  const a = answers.value[q.id]
  if (a == null) return []
  if (Array.isArray(a)) return a
  return String(a).length ? [String(a)] : []
}
const canSubmit = computed(() => questions.value.length > 0 && questions.value.every(isAnswered))

async function doSubmit(auto) {
  if (submitting.value || finished.value) return
  submitting.value = true
  try {
    const items = questions.value.map((q) => ({ questionId: q.id, answer: toAnswer(q) }))
    const r = await submitExam(pid, recordId.value, items).then((x) => x.data || x).catch(() => null)
    result.value = r
    finished.value = true
    stopTimer()
    if (auto) window.__kbMsg?.warning('时间到，已自动交卷')
  } finally { submitting.value = false }
}

async function load() {
  loading.value = true
  try {
    // 必须先开始考试，才允许取卷
    const rec = await startExam(pid).then((x) => x.data || x).catch((e) => {
      errMsg.value = e?.response?.data?.error || e?.message || '无法开始考试'
      return null
    })
    if (!rec) return
    recordId.value = rec?.id ?? null
    const p = await getPaper(pid).then((x) => x.data || x).catch((e) => {
      errMsg.value = e?.response?.data?.error || e?.message || '取卷失败'
      return null
    })
    paper.value = p?.paper || null
    questions.value = p?.questions || []
    startTimer()
  } finally { loading.value = false }
}
onMounted(load)
onUnmounted(stopTimer)
</script>

<template>
  <div class="et" v-if="paper">
    <!-- 结果 -->
    <div v-if="finished && result" class="result">
      <div class="r-ico" :class="{ pass: result.passed }">
        <el-icon :size="40"><component :is="result.passed ? 'SuccessFilled' : 'CircleCloseFilled'" /></el-icon>
      </div>
      <div class="r-score">{{ result.score }}<span>分</span></div>
      <div class="r-state" :class="{ pass: result.passed }">{{ result.passed ? '恭喜，及格！' : '很遗憾，未及格' }}</div>
      <div class="r-meta">满分 {{ paper.totalScore }} · 及格 {{ paper.passScore }}</div>
      <button class="m-btn" @click="router.replace('/exam/mine')">查看我的成绩</button>
    </div>

    <!-- 答题 -->
    <template v-else>
      <div class="topbar">
        <button class="bk" @click="router.back()"><el-icon><ArrowLeft /></el-icon></button>
        <div class="t">{{ paper.title }}</div>
        <div class="timer" v-if="paper.durationMin > 0" :class="{ warn: remain <= 60 }">{{ fmt(remain) }}</div>
      </div>

      <div class="progress-dots">
        <button v-for="(q, i) in questions" :key="q.id" class="dot"
                :class="{ on: i === cur, done: isAnswered(q) }" @click="cur = i">{{ i + 1 }}</button>
      </div>

      <div class="body" v-if="questions[cur]">
        <div class="q">
          <div class="q-no">第 {{ cur + 1 }} 题 / {{ questions.length }}
            <i class="qt">{{ TYPE_CN[questions[cur].type] || '单选' }}</i>
            <span v-if="questions[cur].score" class="qs">{{ questions[cur].score }} 分</span>
          </div>
          <div class="q-con" v-html="questions[cur].content"></div>

          <!-- 单选 / 判断 -->
          <div v-if="['SINGLE','JUDGE'].includes(questions[cur].type)" class="opts">
            <button v-for="o in questions[cur].options" :key="o.label" class="opt"
                    :class="{ on: answers[questions[cur].id] === o.label }" @click="setAns(questions[cur], o.label)">
              <span class="k">{{ o.label }}</span><span class="ot" v-html="o.content"></span>
            </button>
          </div>
          <!-- 多选 -->
          <div v-else-if="questions[cur].type === 'MULTI'" class="opts">
            <button v-for="o in questions[cur].options" :key="o.label" class="opt"
                    :class="{ on: (answers[questions[cur].id] || []).includes(o.label) }" @click="toggleMulti(questions[cur], o.label)">
              <span class="k">{{ o.label }}</span><span class="ot" v-html="o.content"></span>
            </button>
          </div>
          <!-- 填空 / 简答 -->
          <textarea v-else class="fill" :placeholder="questions[cur].type === 'FILL' ? '请输入答案' : '请输入你的作答'"
                    :value="answers[questions[cur].id] || ''" @input="setAns(questions[cur], $event.target.value)"></textarea>
        </div>
      </div>

      <div class="footer">
        <button class="fb" :disabled="cur === 0" @click="cur--">上一题</button>
        <button v-if="cur < questions.length - 1" class="fb primary" @click="cur++">下一题</button>
        <button v-else class="fb primary" :disabled="!canSubmit || submitting" @click="doSubmit(false)">
          {{ submitting ? '交卷中…' : '交卷' }}
        </button>
      </div>
    </template>
  </div>
  <div v-else class="loading">
    <template v-if="loading">加载中…</template>
    <template v-else>
      <div class="err-ico"><el-icon :size="34"><WarningFilled /></el-icon></div>
      <div class="err-msg">{{ errMsg || '试卷不存在' }}</div>
      <button class="m-btn sm" style="margin-top:12px" @click="router.back()">返回</button>
    </template>
  </div>
</template>

<style scoped>
.et { min-height: 100%; background: var(--m-bg); display: flex; flex-direction: column; }
.topbar { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; gap: 8px;
  padding: 10px 12px; background: var(--m-card); border-bottom: 1px solid var(--m-border); }
.bk { width: 32px; height: 32px; border: none; background: transparent; color: var(--m-text); cursor: pointer; display: grid; place-items: center; }
.t { flex: 1; font-size: 15px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--m-text); }
.timer { font-size: 14px; font-variant-numeric: tabular-nums; color: var(--m-primary); font-weight: 600; }
.timer.warn { color: var(--m-danger); }
.progress-dots { display: flex; flex-wrap: wrap; gap: 6px; padding: 10px 12px; }
.dot { width: 28px; height: 28px; border-radius: 8px; border: 1px solid var(--m-border); background: var(--m-card);
  color: var(--m-text-sub); font-size: 12px; cursor: pointer; }
.dot.on { border-color: var(--m-primary); color: var(--m-primary); font-weight: 700; }
.dot.done { background: var(--m-primary-soft); color: var(--m-primary); border-color: transparent; }
.body { flex: 1; padding: 4px 16px 16px; overflow-y: auto; }
.q-no { font-size: 12.5px; color: var(--m-text-dim); margin-bottom: 8px; }
.qt { font-style: normal; margin-left: 8px; padding: 1px 8px; border-radius: 999px; background: var(--m-card-2); color: var(--m-text-sub); font-size: 11px; }
.qs { margin-left: 8px; color: var(--m-primary); }
.q-con { font-size: 15px; line-height: 1.7; color: var(--m-text); margin-bottom: 14px; }
.opts { display: flex; flex-direction: column; gap: 10px; }
.opt { display: flex; align-items: flex-start; gap: 10px; padding: 12px 14px; border: 1px solid var(--m-border);
  border-radius: 12px; background: var(--m-card); cursor: pointer; text-align: left; }
.opt.on { border-color: var(--m-primary); background: var(--m-primary-soft); }
.k { flex: none; width: 24px; height: 24px; border-radius: 50%; background: var(--m-card-2); color: var(--m-text-sub);
  display: grid; place-items: center; font-size: 13px; font-weight: 600; }
.opt.on .k { background: var(--m-primary); color: #fff; }
.ot { flex: 1; font-size: 14px; line-height: 1.5; color: var(--m-text); }
.fill { width: 100%; min-height: 120px; border: 1px solid var(--m-border); border-radius: 12px; padding: 12px;
  font-size: 14px; font-family: inherit; color: var(--m-text); background: var(--m-card); resize: vertical; }
.footer { display: flex; gap: 10px; padding: 10px 16px calc(10px + env(safe-area-inset-bottom)); background: var(--m-card); border-top: 1px solid var(--m-border); }
.fb { flex: 1; height: 44px; border: none; border-radius: 12px; background: var(--m-card-2); color: var(--m-text); font-size: 15px; cursor: pointer; font-family: inherit; }
.fb.primary { background: var(--m-primary); color: #fff; font-weight: 600; }
.fb:disabled { opacity: .5; }
.result { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 30px; gap: 8px; }
.r-ico { width: 76px; height: 76px; border-radius: 50%; display: grid; place-items: center; background: var(--m-card-2); color: var(--m-danger); }
.r-ico.pass { color: var(--m-success); }
.r-score { font-size: 44px; font-weight: 800; color: var(--m-text); line-height: 1; }
.r-score span { font-size: 16px; font-weight: 500; color: var(--m-text-dim); margin-left: 4px; }
.r-state { font-size: 16px; font-weight: 600; color: var(--m-danger); }
.r-state.pass { color: var(--m-success); }
.r-meta { font-size: 12.5px; color: var(--m-text-dim); }
.result .m-btn { width: 100%; max-width: 280px; margin-top: 14px; }
.loading { padding: 60px 32px; text-align: center; color: var(--m-text-dim); display: flex; flex-direction: column; align-items: center; }
.err-ico { color: var(--m-warn); margin-bottom: 10px; }
.err-msg { font-size: 14px; color: var(--m-text-sub); line-height: 1.6; }
</style>
