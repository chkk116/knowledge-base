<script setup>
/**
 * 移动端「练习模式」（2026-10-21）。
 *
 * 与考试（MobileExamTake）的区别：
 *   - 不创建考试记录、不限时、不计成绩；
 *   - 答一题判一题：确认后立即显示对错 + 正确答案 + 解析，再进下一题。
 *
 * 两种入口：
 *   /exam/practice?paperId=12          按试卷练（题目顺序同试卷）
 *   /exam/practice?category=xxx&count=10  题库随机抽题（「模拟练习」入口）
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { practicePaperQuestions, practiceRandomQuestions, practiceCheck } from '../../api/training'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const errMsg = ref('')
const title = ref('练习模式')
const questions = ref([])
const idx = ref(0)
const answer = ref('')          // 单选/判断：label；填空/简答：文本
const multi = ref([])           // 多选：label 数组
const checking = ref(false)
const result = ref(null)        // { correct, manual, correctAnswer, analysis }
const done = ref(0)
const correctCount = ref(0)

const cur = computed(() => questions.value[idx.value] || null)
const finished = computed(() => !loading.value && !errMsg.value && (idx.value >= questions.value.length))
const TYPE_CN = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }
const isMulti = computed(() => cur.value?.type === 'MULTI')
const isText = computed(() => ['FILL', 'ESSAY'].includes(cur.value?.type))
const picked = computed(() => (isMulti.value ? multi.value : answer.value))

function resetAnswer() { answer.value = ''; multi.value = []; result.value = null }

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
  loading.value = true
  errMsg.value = ''
  try {
    const pid = Number(route.query.paperId)
    if (pid) {
      const { data } = await practicePaperQuestions(pid)
      questions.value = data?.questions || []
      title.value = (data?.paper?.title || '试卷') + ' · 练习'
    } else {
      const { data } = await practiceRandomQuestions(String(route.query.category || ''), Number(route.query.count) || 10)
      questions.value = data?.questions || []
      title.value = route.query.category ? `${route.query.category} · 模拟练习` : '模拟练习'
    }
    if (!questions.value.length) errMsg.value = '没有可练习的题目'
    idx.value = 0; done.value = 0; correctCount.value = 0; resetAnswer()
  } catch (e) {
    errMsg.value = e?.response?.data?.error || e?.message || '加载失败'
  } finally {
    loading.value = false
  }
}

/** 确认本题：主观题直接看参考答案，客观题走服务端判分（答案只在判分时下发） */
async function confirm() {
  if (!cur.value || checking.value || result.value) return
  const ans = isMulti.value ? [...multi.value].sort() : (isText.value ? String(answer.value || '').trim() : answer.value)
  if ((Array.isArray(ans) && !ans.length) || (!Array.isArray(ans) && !ans)) {
    window.__kbMsg?.warning('先作答再确认'); return
  }
  checking.value = true
  try {
    const { data } = await practiceCheck(cur.value.id, Array.isArray(ans) ? ans : [ans])
    result.value = data || {}
    done.value += 1
    if (data?.correct) correctCount.value += 1
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || e?.message || '判分失败')
  } finally {
    checking.value = false
  }
}

function next() {
  idx.value += 1
  resetAnswer()
}

async function restart() {
  idx.value = 0; done.value = 0; correctCount.value = 0; resetAnswer()
  if (route.query.paperId) return
  await load()
}

onMounted(load)
</script>

<template>
  <div class="pr">
    <header class="pad">
      <button class="bk" type="button" aria-label="返回" @click="router.back()"><el-icon><ArrowLeft /></el-icon></button>
      <div class="tt">{{ title }}</div>
      <div class="cnt" v-if="questions.length">{{ Math.min(idx + 1, questions.length) }} / {{ questions.length }}</div>
    </header>

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="errMsg" class="tip">
      <el-icon :size="34" class="warn"><WarningFilled /></el-icon>
      <div>{{ errMsg }}</div>
      <button class="pill" type="button" @click="router.back()">返回</button>
    </div>

    <!-- 结束 -->
    <div v-else-if="finished" class="done-box">
      <div class="done-ico"><el-icon :size="36"><SuccessFilled /></el-icon></div>
      <div class="done-title">练习完成</div>
      <div class="done-num">{{ correctCount }}<span> / {{ done }} 题答对</span></div>
      <button class="pill primary" type="button" :disabled="!route.query.paperId" @click="restart">再练一轮</button>
      <button class="pill" type="button" @click="router.replace('/exams')">返回考试中心</button>
    </div>

    <!-- 答题 -->
    <div v-else-if="cur" class="body">
      <div class="q-card">
        <div class="q-no">
          第 {{ idx + 1 }} 题
          <i class="qt">{{ TYPE_CN[cur.type] || '单选' }}</i>
        </div>
        <div class="q-con" v-html="cur.content"></div>

        <div v-if="['SINGLE','JUDGE'].includes(cur.type)" class="opts">
          <button v-for="o in cur.options" :key="o.label" type="button" class="opt"
                  :class="{ on: answer === o.label, ok: result && result.correctAnswer?.some(c => c.label === o.label),
                            bad: result && answer === o.label && !result.correct }"
                  @click="choose(o.label)">
            <span class="k">{{ o.label }}</span><span class="ot" v-html="o.content"></span>
          </button>
        </div>
        <div v-else-if="cur.type === 'MULTI'" class="opts">
          <button v-for="o in cur.options" :key="o.label" type="button" class="opt"
                  :class="{ on: multi.includes(o.label), ok: result && result.correctAnswer?.some(c => c.label === o.label),
                            bad: result && multi.includes(o.label) && !result.correctAnswer?.some(c => c.label === o.label) }"
                  @click="choose(o.label)">
            <span class="k">{{ o.label }}</span><span class="ot" v-html="o.content"></span>
          </button>
        </div>
        <textarea v-else v-model="answer" class="fill" :disabled="!!result"
                  :placeholder="cur.type === 'FILL' ? '请输入答案' : '请输入你的作答'"></textarea>

        <!-- 判分结果 -->
        <div v-if="result" class="res" :class="{ ok: result.correct, manual: result.manual }">
          <div class="res-title">
            <template v-if="result.manual">参考答案与解析</template>
            <template v-else>{{ result.correct ? '回答正确' : '回答错误' }}</template>
          </div>
          <div v-if="result.correctAnswer?.length" class="res-line">
            <span class="lab">正确答案：</span>
            <div class="ans-list">
              <div v-for="c in result.correctAnswer" :key="c.label" class="ans-item">
                <b>{{ c.label }}</b><span v-html="c.content"></span>
              </div>
            </div>
          </div>
          <div v-if="result.analysis" class="res-line">
            <span class="lab">解析：</span><span class="ana" v-html="result.analysis"></span>
          </div>
          <div v-if="result.manual" class="res-tip">主观题不自动判分，请对照参考答案自评。</div>
        </div>
      </div>
    </div>

    <div class="foot" v-if="!loading && !errMsg && !finished && cur">
      <button v-if="!result" class="fb primary" type="button" :disabled="checking" @click="confirm">
        {{ checking ? '判分中…' : '确认答案' }}
      </button>
      <button v-else class="fb primary" type="button" @click="next">下一题</button>
    </div>
  </div>
</template>

<style scoped>
.pr { min-height: 100%; background: var(--m-bg); display: flex; flex-direction: column; }
.pad { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; gap: 8px; padding: 10px 12px;
  background: var(--m-card); border-bottom: 1px solid var(--m-border); }
.bk { width: 32px; height: 32px; border: none; background: transparent; color: var(--m-text); display: grid; place-items: center; }
.tt { flex: 1; font-size: 15px; font-weight: 600; color: var(--m-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cnt { font-size: 12.5px; color: var(--m-text-dim); font-variant-numeric: tabular-nums; }
.tip { padding: 70px 32px; text-align: center; color: var(--m-text-dim); display: flex; flex-direction: column; align-items: center; gap: 10px; }
.warn { color: var(--m-warn); }
.body { flex: 1; padding: 12px 16px 20px; overflow-y: auto; }
.q-card { background: var(--m-card); border-radius: 14px; padding: 14px; }
.q-no { font-size: 12.5px; color: var(--m-text-dim); margin-bottom: 8px; }
.qt { font-style: normal; margin-left: 8px; padding: 1px 8px; border-radius: 999px; background: var(--m-card-2); color: var(--m-text-sub); font-size: 11px; }
.q-con { font-size: 15px; line-height: 1.7; color: var(--m-text); margin-bottom: 14px; }
.opts { display: flex; flex-direction: column; gap: 10px; }
.opt { display: flex; align-items: flex-start; gap: 10px; padding: 12px 14px; border: 1px solid var(--m-border);
  border-radius: 12px; background: var(--m-card); text-align: left; font-family: inherit; }
.opt.on { border-color: var(--m-primary); background: var(--m-primary-soft); }
.opt.ok { border-color: var(--m-success); background: rgba(22, 163, 74, .08); }
.opt.bad { border-color: var(--m-danger); background: rgba(220, 38, 38, .08); }
.k { flex: none; width: 24px; height: 24px; border-radius: 50%; background: var(--m-card-2); color: var(--m-text-sub);
  display: grid; place-items: center; font-size: 13px; font-weight: 600; }
.opt.on .k { background: var(--m-primary); color: #fff; }
.ot { flex: 1; font-size: 14px; line-height: 1.5; color: var(--m-text); }
.fill { width: 100%; min-height: 110px; border: 1px solid var(--m-border); border-radius: 12px; padding: 12px;
  font-size: 14px; font-family: inherit; color: var(--m-text); background: var(--m-card); resize: vertical; box-sizing: border-box; }
.res { margin-top: 16px; border-radius: 12px; padding: 12px 14px; background: var(--m-card-2); border-left: 3px solid var(--m-danger); }
.res.ok { border-left-color: var(--m-success); }
.res.manual { border-left-color: var(--m-primary); }
.res-title { font-size: 14px; font-weight: 600; color: var(--m-text); margin-bottom: 8px; }
.res.ok .res-title { color: var(--m-success); }
.res-line { font-size: 13px; line-height: 1.6; margin-top: 6px; display: flex; gap: 4px; }
.lab { flex: none; color: var(--m-text-dim); }
.ans-list { display: flex; flex-direction: column; gap: 4px; }
.ans-item { display: flex; gap: 6px; }
.ans-item b { color: var(--m-success); flex: none; }
.ana { color: var(--m-text-sub); }
.res-tip { margin-top: 8px; font-size: 11.5px; color: var(--m-text-dim); }
.foot { padding: 10px 16px calc(10px + env(safe-area-inset-bottom)); background: var(--m-card); border-top: 1px solid var(--m-border); }
.fb { width: 100%; height: 44px; border: none; border-radius: 12px; font-size: 15px; font-family: inherit; }
.fb.primary { background: var(--m-primary); color: #fff; font-weight: 600; }
.fb:disabled { opacity: .5; }
.done-box { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 30px; }
.done-ico { width: 74px; height: 74px; border-radius: 50%; display: grid; place-items: center; background: var(--m-primary-soft); color: var(--m-primary); }
.done-title { font-size: 17px; font-weight: 600; color: var(--m-text); }
.done-num { font-size: 34px; font-weight: 800; color: var(--m-text); }
.done-num span { font-size: 14px; font-weight: 400; color: var(--m-text-dim); }
.pill { min-width: 180px; height: 44px; border: 1px solid var(--m-border); border-radius: 12px; background: var(--m-card);
  color: var(--m-text); font-size: 14px; font-family: inherit; }
.pill.primary { background: var(--m-primary); border-color: var(--m-primary); color: #fff; }
.pill:disabled { opacity: .5; }
</style>
