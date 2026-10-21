/**
 * 练习会话（2026-10-21）
 *
 * 一次把整卷/抽题结果拿在本地，逐题判分（答案只在判分时由服务端下发），
 * 支持自由切题、答题卡状态、回看已判分的题——对齐 51CTO 题库的练习体验。
 *
 * 用法：
 *   const s = usePracticeSession()
 *   await s.load({ paperId }) 或 await s.load({ category, count })
 *   s.pick(label) / s.submit() / s.next() / s.prev() / s.jump(i) / s.restart()
 */
import { computed, reactive, ref } from 'vue'
import { startPractice, practiceCheck } from '../api/training'

const TYPE_CN = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }

export function usePracticeSession() {
  const loading = ref(true)
  const errMsg = ref('')
  const title = ref('练习模式')
  const questions = ref([])
  const idx = ref(0)
  const checking = ref(false)
  /** 本次练习的服务端会话 id（判分校验用） */
  const sessionId = ref('')
  /** qid -> 选项 label 数组；填空/简答为 [文本] */
  const answers = reactive({})
  /** qid -> { correct, manual, correctAnswer, analysis } */
  const results = reactive({})
  /** 来源参数，供「再练一轮」重抽 */
  let source = {}

  const cur = computed(() => questions.value[idx.value] || null)
  const total = computed(() => questions.value.length)
  const finished = computed(() => !loading.value && !errMsg.value && total.value > 0 && idx.value >= total.value)
  const isMulti = computed(() => cur.value?.type === 'MULTI')
  const isText = computed(() => ['FILL', 'ESSAY'].includes(cur.value?.type))
  const curAnswer = computed(() => (cur.value ? (answers[cur.value.id] || []) : []))
  const curResult = computed(() => (cur.value ? (results[cur.value.id] || null) : null))

  const answeredCount = computed(() => Object.keys(results).length)
  const correctCount = computed(() => Object.values(results).filter((r) => r && r.correct).length)
  const wrongList = computed(() => questions.value.filter((q) => {
    const r = results[q.id]
    return r && !r.manual && !r.correct
  }))
  /** 只统计能自动判分的题（主观题不进分母，否则正确率永远被拉低） */
  const judgedCount = computed(() => Object.values(results).filter((r) => r && !r.manual).length)
  const percent = computed(() => (judgedCount.value ? Math.round((correctCount.value / judgedCount.value) * 100) : 0))

  /** 答题卡状态：done=已答对 / wrong=答错 / manual=主观 / todo=未答（当前题由样式叠加 cur） */
  function stateOf(q) {
    const r = results[q.id]
    if (!r) return 'todo'
    if (r.manual) return 'manual'
    return r.correct ? 'done' : 'wrong'
  }

  async function load(params = {}) {
    source = { ...params }
    loading.value = true
    errMsg.value = ''
    Object.keys(answers).forEach((k) => delete answers[k])
    Object.keys(results).forEach((k) => delete results[k])
    idx.value = 0
    try {
      const pid = Number(params.paperId)
      // 统一走「开启会话」接口：服务端把本次题目登记进 session，判分只认这份会话，
      // 防止练习接口被拿 questionId 当答案查询器（审查 r11 High）
      const payload = pid
        ? { paperId: pid }
        : { category: String(params.category || ''), count: Number(params.count) || 10 }
      const { data } = await startPractice(payload)
      sessionId.value = data?.sessionId || ''
      questions.value = data?.questions || []
      title.value = pid
        ? ((data?.paper?.title || '试卷') + ' · 练习')
        : (params.category ? `${params.category} · 练习` : '随机练习')
      if (!questions.value.length) errMsg.value = '没有可练习的题目'
    } catch (e) {
      errMsg.value = e?.response?.data?.error || e?.message || '加载失败'
      questions.value = []
    } finally {
      loading.value = false
    }
  }

  function pick(label) {
    const q = cur.value
    if (!q || results[q.id]) return
    const arr = answers[q.id] ? [...answers[q.id]] : []
    if (q.type === 'MULTI') {
      const i = arr.indexOf(label)
      if (i >= 0) arr.splice(i, 1); else arr.push(label)
    } else {
      answers[q.id] = [label]
      return
    }
    answers[q.id] = arr
  }

  function setText(text) {
    const q = cur.value
    if (!q || results[q.id]) return
    answers[q.id] = [text]
  }

  /** 是否已作答（含多选至少一项） */
  function hasAnswer() {
    const a = curAnswer.value
    return Array.isArray(a) && a.length > 0 && String(a[0] ?? '').trim() !== ''
  }

  /** 判分：服务端返回对错 + 正确答案 + 解析 */
  async function submit() {
    const q = cur.value
    if (!q || checking.value || results[q.id]) return null
    if (!hasAnswer()) return { error: '先作答再确认' }
    checking.value = true
    try {
      const ans = [...curAnswer.value]
      if (q.type === 'MULTI') ans.sort()
      const { data } = await practiceCheck(sessionId.value, q.id, ans)
      results[q.id] = data || {}
      return results[q.id]
    } catch (e) {
      return { error: e?.response?.data?.error || e?.message || '判分失败' }
    } finally {
      checking.value = false
    }
  }

  function jump(i) {
    if (i < 0 || i >= total.value) return
    idx.value = i
  }
  function next() {
    if (idx.value < total.value) idx.value += 1
  }
  function prev() { if (idx.value > 0) idx.value -= 1 }
  /** 结束本轮：进入结果页 */
  function finish() { idx.value = total.value }

  async function restart() {
    await load(source)
  }

  return {
    TYPE_CN,
    loading, errMsg, title, questions, idx, checking, answers, results,
    cur, total, finished, isMulti, isText, curAnswer, curResult,
    answeredCount, correctCount, judgedCount, wrongList, percent, stateOf,
    load, pick, setText, hasAnswer, submit, jump, next, prev, finish, restart,
  }
}
