<template>
  <div class="page" v-if="data">
    <div class="score-card" :class="{ pass: data.record.passed }">
      <div class="num">{{ data.record.score }}</div>
      <div class="txt">{{ data.record.passed ? '及格' : '未及格' }}</div>
      <div class="time">用时 {{ Math.round(data.record.durationSec / 60) }} 分 {{ data.record.durationSec % 60 }} 秒</div>
    </div>

    <h2>逐题回顾</h2>
    <div v-for="(it, i) in data.items" :key="it.questionId" class="q" :class="it.correct ? 'ok' : 'no'">
      <div class="qt">
        <b>{{ i + 1 }}.</b>
        <span class="qt-html" v-html="it.content"></span>
        <em>({{ typeText(it.type) }} · 得 {{ it.score }} 分)</em>
      </div>
      <div class="ans">
        <span class="lab">你的答案：</span>
        <span :class="it.correct ? 'good' : 'bad'">{{ showAnswer(it) }}</span>
      </div>
      <div v-if="it.type !== 'FILL' && it.type !== 'ESSAY'" class="correct">
        <span class="lab">正确答案：</span>
        <div class="opt-list">
          <div v-for="o in correctOptions(it)" :key="o.label" class="opt-row">
            <b>{{ o.label }}</b><span v-html="o.content"></span>
          </div>
          <div v-if="!correctOptions(it).length" class="opt-none">—</div>
        </div>
      </div>
      <div v-if="it.analysis" class="ana">
        <span class="lab">解析：</span><span v-html="it.analysis"></span>
      </div>
    </div>

    <el-button @click="$router.push('/exams')">返回考试列表</el-button>
  </div>
  <div v-else class="tip">加载中…</div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { examRecord } from '../api/training'

const route = useRoute()
const router = useRouter()
const data = ref(null)

const typeText = (t) => ({ SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }[t] || t)
// 你的答案：多选题存的是 "A,B"，展示成「A、B」；主观题原样显示
const showAnswer = (it) => {
  const raw = String(it.userAnswer || '').trim()
  if (!raw) return '（未作答）'
  if (['SINGLE', 'MULTI', 'JUDGE'].includes(it.type)) return raw.split(',').map((s) => s.trim()).filter(Boolean).join('、')
  return raw
}
// 正确答案：逐条列出（审查 r10：原来用「；」拼成一行，长选项挤成一团没法看）
const correctOptions = (it) => (it.options || []).filter((o) => o.isCorrect)

const load = () => examRecord(route.params.id).then(r => { data.value = r.data || r }).catch(() => {})
onMounted(load)
</script>

<style scoped>
.page { max-width: 860px; margin: 0 auto; padding: 24px 16px 60px; }
.score-card { text-align: center; padding: 22px; border-radius: 16px; background: #fef2f2; border: 1px solid #fecaca; margin-bottom: 22px; }
.score-card.pass { background: #f0fdf4; border-color: #bbf7d0; }
.num { font-size: 42px; font-weight: 800; color: #dc2626; }
.score-card.pass .num { color: #16a34a; }
.txt { color: #64748b; font-size: 14px; }
.time { color: #94a3b8; font-size: 12px; margin-top: 4px; }
h2 { font-size: 17px; margin: 8px 0 14px; }
.q { background: #fff; border: 1px solid #eef2f7; border-radius: 12px; padding: 14px 16px; margin-bottom: 12px; border-left: 4px solid #cbd5e1; }
.q.ok { border-left-color: #16a34a; }
.q.no { border-left-color: #dc2626; }
.qt { font-size: 15px; line-height: 1.6; margin-bottom: 10px; }
.qt-html { display: inline; }
.qt-html :deep(p) { margin: 2px 0; }
.qt-html :deep(img) { max-width: 100%; height: auto; border-radius: 6px; }
.qt em { color: #94a3b8; font-style: normal; font-size: 12px; font-weight: 400; margin-left: 6px; }
.ans, .correct { font-size: 13px; margin: 4px 0; display: flex; gap: 4px; }
.opt-list { display: flex; flex-direction: column; gap: 3px; }
.opt-row { display: flex; gap: 6px; line-height: 1.55; color: #16a34a; }
.opt-row b { flex: none; }
.opt-none { color: #94a3b8; }
.lab { color: #94a3b8; flex: none; }
.good { color: #16a34a; }
.bad { color: #dc2626; }
.ana { color: #64748b; font-size: 13px; margin-top: 6px; display: flex; gap: 4px; line-height: 1.6; }
.tip { text-align: center; color: #94a3b8; padding: 60px 0; }
:deep(html.dark) .q { background: #1b1b2e; border-color: #2c2c44; }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 1024px) {
  .page { padding: 20px 14px 48px; }
  .q { padding: 12px 14px; }
}

@media (max-width: 640px) {
  .page { padding: 14px 12px 40px; }
  .score-card { padding: 16px; margin-bottom: 18px; }
  .num { font-size: 34px; }
  .txt { font-size: 13px; }
  h2 { font-size: 16px; margin: 6px 0 12px; }
  .q { padding: 12px; margin-bottom: 10px; }
  .qt { font-size: 14px; overflow-wrap: anywhere; }
  .ans, .correct { font-size: 12.5px; overflow-wrap: anywhere; }
  .ana { font-size: 12.5px; }
}
</style>
