<template>
  <div class="page">
    <div class="hd">
      <div>
        <h1>模拟练习</h1>
        <p class="sub">{{ subtitle }}</p>
      </div>
      <el-button text @click="$router.push('/exams')">返回考试列表</el-button>
    </div>

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="errMsg" class="tip">
      {{ errMsg }}
      <div style="margin-top:12px"><el-button @click="$router.push('/exams')">返回</el-button></div>
    </div>

    <!-- 结束 -->
    <div v-else-if="finished" class="done">
      <el-progress type="circle" :percentage="percent" :width="132" :status="percent >= 60 ? 'success' : 'warning'" />
      <div class="done-num">{{ correctCount }} / {{ done }} 题答对</div>
      <el-button type="primary" @click="restart">再练一轮</el-button>
      <el-button @click="$router.push('/exams')">返回考试列表</el-button>
    </div>

    <!-- 答题 -->
    <template v-else-if="cur">
      <div class="bar">
        <span class="idx">第 {{ idx + 1 }} / {{ questions.length }} 题</span>
        <el-tag size="small" effect="plain">{{ TYPE_CN[cur.type] || '单选' }}</el-tag>
        <span class="spacer" />
        <span class="stat">已答 {{ done }} · 正确 {{ correctCount }}</span>
      </div>

      <div class="q-card">
        <div class="q-con" v-html="cur.content"></div>

        <div v-if="['SINGLE','JUDGE'].includes(cur.type)" class="opts">
          <button v-for="o in cur.options" :key="o.label" type="button" class="opt"
                  :class="{ on: answer === o.label, ok: result && isCorrectLabel(o.label), bad: result && answer === o.label && !result.correct }"
                  :disabled="!!result" @click="choose(o.label)">
            <span class="k">{{ o.label }}</span><span class="ot" v-html="o.content"></span>
          </button>
        </div>
        <div v-else-if="cur.type === 'MULTI'" class="opts">
          <button v-for="o in cur.options" :key="o.label" type="button" class="opt"
                  :class="{ on: multi.includes(o.label), ok: result && isCorrectLabel(o.label), bad: result && multi.includes(o.label) && !isCorrectLabel(o.label) }"
                  :disabled="!!result" @click="choose(o.label)">
            <span class="k">{{ o.label }}</span><span class="ot" v-html="o.content"></span>
          </button>
        </div>
        <textarea v-else v-model="answer" class="fill" :disabled="!!result"
                  :placeholder="cur.type === 'FILL' ? '请输入答案' : '请输入你的作答'"></textarea>

        <div v-if="result" class="res" :class="{ ok: result.correct, manual: result.manual }">
          <div class="res-title">
            <template v-if="result.manual">参考答案与解析</template>
            <template v-else>{{ result.correct ? '回答正确' : '回答错误' }}</template>
          </div>
          <div v-if="result.correctAnswer?.length" class="ans-block">
            <span class="lab">正确答案：</span>
            <div class="ans-list">
              <div v-for="c in result.correctAnswer" :key="c.label" class="ans-item">
                <b>{{ c.label }}</b><span v-html="c.content"></span>
              </div>
            </div>
          </div>
          <div v-if="result.analysis" class="ana"><span class="lab">解析：</span><span v-html="result.analysis"></span></div>
          <div v-if="result.manual" class="tip-line">主观题不自动判分，请对照参考答案自评。</div>
        </div>
      </div>

      <div class="foot">
        <el-button v-if="!result" type="primary" :loading="checking" @click="confirm">确认答案</el-button>
        <el-button v-else type="primary" @click="next">下一题</el-button>
      </div>
    </template>
  </div>
</template>

<script setup>
/**
 * Web 端「模拟练习」（2026-10-21）：与移动端同一套接口，答一题判一题，不落成绩。
 * 入口：/exams 列表的「模拟练习」按钮（带 paperId），或直接带 category/count 随机抽题。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { practicePaperQuestions, practiceRandomQuestions, practiceCheck } from '../api/training'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const errMsg = ref('')
const title = ref('')
const questions = ref([])
const idx = ref(0)
const answer = ref('')
const multi = ref([])
const checking = ref(false)
const result = ref(null)
const done = ref(0)
const correctCount = ref(0)

const TYPE_CN = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }
const cur = computed(() => questions.value[idx.value] || null)
const finished = computed(() => !loading.value && !errMsg.value && idx.value >= questions.value.length)
const isMulti = computed(() => cur.value?.type === 'MULTI')
const isText = computed(() => ['FILL', 'ESSAY'].includes(cur.value?.type))
const percent = computed(() => (done.value ? Math.round((correctCount.value / done.value) * 100) : 0))
const subtitle = computed(() => (route.query.paperId ? title.value || '按试卷练习' : '从题库随机抽题，答一题立即判分看解析，不计入成绩'))

function isCorrectLabel(label) {
  return !!result.value?.correctAnswer?.some((c) => c.label === label)
}
function reset() { answer.value = ''; multi.value = []; result.value = null }

function choose(label) {
  if (result.value) return
  if (isMulti.value) {
    const i = multi.value.indexOf(label)
    if (i >= 0) multi.value.splice(i, 1); else multi.value.push(label)
  } else {
    answer.value = label
  }
}

async function load() {
  loading.value = true; errMsg.value = ''
  try {
    const pid = Number(route.query.paperId)
    if (pid) {
      const { data } = await practicePaperQuestions(pid)
      questions.value = data?.questions || []
      title.value = (data?.paper?.title || '') + ' · 练习'
    } else {
      const { data } = await practiceRandomQuestions(String(route.query.category || ''), Number(route.query.count) || 10)
      questions.value = data?.questions || []
      title.value = route.query.category ? `${route.query.category} · 模拟练习` : '模拟练习'
    }
    if (!questions.value.length) errMsg.value = '没有可练习的题目'
    idx.value = 0; done.value = 0; correctCount.value = 0; reset()
  } catch (e) {
    errMsg.value = e?.response?.data?.error || e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function confirm() {
  if (!cur.value || checking.value || result.value) return
  const ans = isMulti.value ? [...multi.value].sort() : (isText.value ? String(answer.value || '').trim() : answer.value)
  if ((Array.isArray(ans) && !ans.length) || (!Array.isArray(ans) && !ans)) {
    ElMessage.warning('先作答再确认'); return
  }
  checking.value = true
  try {
    const { data } = await practiceCheck(cur.value.id, Array.isArray(ans) ? ans : [ans])
    result.value = data || {}
    done.value += 1
    if (data?.correct) correctCount.value += 1
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || e?.message || '判分失败')
  } finally {
    checking.value = false
  }
}

function next() { idx.value += 1; reset() }
async function restart() { idx.value = 0; done.value = 0; correctCount.value = 0; reset(); if (!route.query.paperId) await load() }

onMounted(load)
</script>

<style scoped>
.page { max-width: 820px; margin: 0 auto; padding: 24px 16px 60px; }
.hd { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 14px; }
.hd h1 { margin: 0; font-size: 22px; }
.hd .sub { color: #64748b; font-size: 13px; margin: 6px 0 0; }
.tip { color: #94a3b8; padding: 40px 0; text-align: center; }
.bar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; color: #64748b; font-size: 13px; }
.spacer { flex: 1; }
.stat { color: #94a3b8; }
.q-card { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 20px; }
.q-con { font-size: 15.5px; line-height: 1.8; color: #0f172a; margin-bottom: 16px; }
.q-con :deep(img) { max-width: 100%; border-radius: 8px; }
.opts { display: flex; flex-direction: column; gap: 10px; }
.opt { display: flex; gap: 10px; align-items: flex-start; padding: 12px 14px; border: 1px solid #e9eef5; border-radius: 10px; background: #fff; cursor: pointer; text-align: left; font-family: inherit; }
.opt:hover { border-color: #c7d2fe; }
.opt.on { border-color: #6366f1; background: #eef2ff; }
.opt.ok { border-color: #16a34a; background: #f0fdf4; }
.opt.bad { border-color: #dc2626; background: #fef2f2; }
.opt:disabled { cursor: default; }
.k { flex: none; width: 24px; height: 24px; border-radius: 50%; background: #f1f5f9; color: #64748b; display: grid; place-items: center; font-size: 13px; font-weight: 600; }
.opt.on .k { background: #6366f1; color: #fff; }
.ot { flex: 1; font-size: 14px; line-height: 1.6; color: #0f172a; }
.fill { width: 100%; min-height: 120px; border: 1px solid #e9eef5; border-radius: 10px; padding: 12px; font-size: 14px; font-family: inherit; box-sizing: border-box; }
.res { margin-top: 16px; border-left: 3px solid #dc2626; background: #f8fafc; border-radius: 10px; padding: 12px 14px; }
.res.ok { border-left-color: #16a34a; }
.res.manual { border-left-color: #6366f1; }
.res-title { font-weight: 600; font-size: 14px; margin-bottom: 8px; }
.res.ok .res-title { color: #16a34a; }
.ans-block { display: flex; gap: 4px; font-size: 13px; }
.ans-list { display: flex; flex-direction: column; gap: 3px; }
.ans-item { display: flex; gap: 6px; line-height: 1.55; color: #16a34a; }
.lab { color: #94a3b8; flex: none; }
.ana { display: flex; gap: 4px; margin-top: 8px; font-size: 13px; color: #475569; line-height: 1.7; }
.tip-line { margin-top: 8px; font-size: 12px; color: #94a3b8; }
.foot { display: flex; justify-content: flex-end; margin-top: 14px; }
.done { text-align: center; padding: 50px 20px; display: flex; flex-direction: column; align-items: center; gap: 16px; }
.done-num { font-size: 20px; font-weight: 600; color: #0f172a; }
:deep(html.dark) .q-card { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .q-con, :deep(html.dark) .ot { color: #e5e7eb; }
:deep(html.dark) .opt { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .res { background: #20203a; }
</style>
