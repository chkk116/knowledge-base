<template>
  <div class="page">
    <div class="hd">
      <div>
        <h1>刷题 · 我的测验</h1>
        <p class="sub" v-if="!done">第 {{ idx + 1 }} / {{ questions.length }} 题 · 答对 {{ right }} 题</p>
        <p class="sub" v-else>本轮结束 · 答对 {{ right }} / {{ questions.length }}</p>
      </div>
      <el-button text @click="$router.push('/my-quiz')">退出</el-button>
    </div>

    <div v-if="loading" class="tip">出题中…</div>
    <div v-else-if="error" class="tip err">{{ error }}</div>

    <template v-else-if="!done && cur">
      <div class="card">
        <div class="q-hd">
          <el-tag size="small" effect="plain">{{ TYPE_CN[cur.type] || cur.type }}</el-tag>
        </div>
        <div class="q-body" v-html="cur.content"></div>

        <div class="opts">
          <label
            v-for="o in cur.options"
            :key="o.label"
            class="opt"
            :class="{
              on: selected.includes(o.label),
              ok: result && result.correctAnswer?.includes(o.label),
              bad: result && selected.includes(o.label) && !result.correctAnswer?.includes(o.label)
            }"
          >
            <input
              :type="cur.type === 'MULTI' ? 'checkbox' : 'radio'"
              :checked="selected.includes(o.label)"
              :disabled="!!result"
              @change="pick(o.label)"
            />
            <span class="lab">{{ o.label }}</span>
            <span class="txt" v-html="o.content"></span>
          </label>
        </div>

        <div v-if="result" class="res" :class="result.correct ? 'good' : 'bad'">
          <div class="res-t">
            {{ result.manual ? '已提交（主观题需人工判分）' : (result.correct ? '✅ 答对了' : '❌ 答错了') }}
            <span class="ans">正确答案：{{ (result.correctAnswer || []).join('、') || '—' }}</span>
          </div>
          <div v-if="result.analysis" class="res-a" v-html="result.analysis"></div>
        </div>

        <div class="acts">
          <el-button v-if="!result" type="primary" :disabled="!selected.length" @click="submit">提交</el-button>
          <el-button v-else type="primary" @click="next">
            {{ idx + 1 >= questions.length ? '看结果' : '下一题' }}
          </el-button>
        </div>
      </div>
    </template>

    <div v-else-if="done" class="card sum">
      <div class="sum-n">{{ right }} / {{ questions.length }}</div>
      <div class="sum-t">答对率 {{ rate }}%</div>
      <div class="acts">
        <el-button type="primary" @click="start">再刷一轮</el-button>
        <el-button @click="$router.push('/my-quiz')">回我的测验</el-button>
        <el-button text type="primary" @click="$router.push('/exams/wrong')">去错题本</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { selfExamStats, startPractice, practiceCheck } from '../api/training'

const TYPE_CN = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', SUBJECTIVE: '主观' }
const loading = ref(true)
const error = ref('')
const questions = ref([])
const sessionId = ref('')
const idx = ref(0)
const selected = ref([])
const result = ref(null)
const right = ref(0)
const done = ref(false)

const cur = computed(() => questions.value[idx.value] || null)
const rate = computed(() => questions.value.length ? Math.round(right.value * 100 / questions.value.length) : 0)

async function start() {
  loading.value = true
  error.value = ''
  questions.value = []
  idx.value = 0
  selected.value = []
  result.value = null
  right.value = 0
  done.value = false
  try {
    // 分类由后端按当前用户生成（个人-{uid}），这里取一次再按该分类抽题
    const { data: st } = await selfExamStats()
    if (!st.questions) {
      error.value = '你还没有自己的题目，先去「AI 问答」里让 Agent 出几道题'
      return
    }
    const { data } = await startPractice({ category: st.category, count: 10 })
    sessionId.value = data.sessionId
    questions.value = data.questions || []
    if (!questions.value.length) error.value = '没有抽到题目'
  } catch (e) {
    error.value = e?.response?.data?.error || e.message || '开始失败'
  } finally {
    loading.value = false
  }
}

function pick(label) {
  if (result.value) return
  if (cur.value.type === 'MULTI') {
    const i = selected.value.indexOf(label)
    if (i >= 0) selected.value.splice(i, 1)
    else selected.value.push(label)
  } else {
    selected.value = [label]
  }
}

async function submit() {
  try {
    // correctAnswer 可能是 [{label,content}] 或 ['A']，统一成 label 数组
    const { data } = await practiceCheck(sessionId.value, cur.value.id, [...selected.value])
    data.correctAnswer = (data.correctAnswer || []).map((x) => (typeof x === 'string' ? x : x.label))
    result.value = data
    if (data.correct) right.value += 1
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '提交失败（会话可能已过期，重新开始）')
  }
}

function next() {
  if (idx.value + 1 >= questions.value.length) { done.value = true; return }
  idx.value += 1
  selected.value = []
  result.value = null
}

onMounted(start)
</script>

<style scoped>
.page { max-width: 760px; margin: 0 auto; padding: 18px 16px 60px; }
.hd { display: flex; align-items: flex-start; justify-content: space-between; }
.hd h1 { font-size: 19px; margin: 0 0 4px; }
.sub { margin: 0; font-size: 13px; color: #6b7280; }
.tip { padding: 40px 16px; text-align: center; color: #6b7280; }
.tip.err { color: #b91c1c; }
.card { background: #fff; border: 1px solid #ebeef5; border-radius: 12px; padding: 16px; margin-top: 12px; }
.q-hd { margin-bottom: 8px; }
.q-body { font-size: 15px; line-height: 1.8; color: #1f2937; }
.opts { display: flex; flex-direction: column; gap: 8px; margin: 14px 0; }
.opt { display: flex; gap: 8px; align-items: flex-start; padding: 10px 12px; border: 1px solid #e5e7eb; border-radius: 10px; cursor: pointer; }
.opt.on { border-color: #1677ff; background: #f3f8ff; }
.opt.ok { border-color: #16a34a; background: #f0fdf4; }
.opt.bad { border-color: #dc2626; background: #fef2f2; }
.lab { font-weight: 600; color: #374151; }
.txt { flex: 1; line-height: 1.7; }
.res { margin-top: 8px; padding: 10px 12px; border-radius: 10px; }
.res.good { background: #f0fdf4; }
.res.bad { background: #fef2f2; }
.res-t { font-size: 13.5px; font-weight: 600; }
.ans { font-weight: 400; color: #6b7280; margin-left: 8px; }
.res-a { margin-top: 6px; font-size: 13px; color: #374151; line-height: 1.7; }
.acts { display: flex; gap: 8px; margin-top: 14px; }
.sum { text-align: center; padding: 36px 16px; }
.sum-n { font-size: 34px; font-weight: 700; }
.sum-t { color: #6b7280; margin-top: 4px; }
.sum .acts { justify-content: center; }
</style>
