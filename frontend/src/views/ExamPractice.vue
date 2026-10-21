<template>
  <div class="page">
    <div class="hd">
      <div>
        <h1>练习</h1>
        <p class="sub">{{ subtitle }}</p>
      </div>
      <div class="hd-act">
        <el-button text @click="$router.push('/exams/wrong')">错题本</el-button>
        <el-button text @click="$router.push('/exams')">返回考试列表</el-button>
      </div>
    </div>

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="errMsg" class="tip">
      {{ errMsg }}
      <div style="margin-top:12px"><el-button @click="$router.push('/exams')">返回</el-button></div>
    </div>

    <!-- 本轮结果 -->
    <div v-else-if="finished" class="done">
      <el-progress type="circle" :percentage="percent" :width="132"
                   :status="percent >= 60 ? 'success' : 'warning'" />
      <div class="done-num">{{ correctCount }} / {{ judgedCount }} 题答对</div>
      <div class="done-sub">共 {{ total }} 题 · 已答 {{ answeredCount }} 题 · 答错 {{ wrongList.length }} 题</div>
      <div class="done-act">
        <el-button type="primary" @click="restart">再练一轮</el-button>
        <el-button @click="$router.push('/exams/wrong')">去错题本</el-button>
        <el-button @click="$router.push('/exams')">返回列表</el-button>
      </div>

      <div v-if="wrongList.length" class="review">
        <div class="review-hd">本轮错题（点题号回看解析）</div>
        <div class="review-list">
          <button v-for="q in wrongList" :key="q.id" type="button" class="review-item"
                  @click="review(q)">
            <span class="ri-no">第 {{ questions.findIndex(x => x.id === q.id) + 1 }} 题</span>
            <span class="ri-txt" v-html="q.content"></span>
          </button>
        </div>
      </div>
    </div>

    <!-- 答题 -->
    <div v-else-if="cur" class="wrap">
      <div class="main">
        <div class="bar">
          <span class="idx">第 {{ idx + 1 }} / {{ total }} 题</span>
          <el-tag size="small" effect="plain">{{ TYPE_CN[cur.type] || '单选' }}</el-tag>
          <span class="spacer" />
          <span class="stat">已答 {{ answeredCount }} · 正确 {{ correctCount }}</span>
          <el-button size="small" text type="primary" @click="finish">结束练习</el-button>
        </div>

        <div class="q-card">
          <div class="q-con" v-html="cur.content"></div>

          <div v-if="['SINGLE','JUDGE'].includes(cur.type)" class="opts">
            <button v-for="o in cur.options" :key="o.label" type="button" class="opt"
                    :class="optClass(o.label)"
                    :disabled="!!curResult" @click="pick(o.label)">
              <span class="k">{{ o.label }}</span><span class="ot" v-html="o.content"></span>
            </button>
          </div>
          <div v-else-if="cur.type === 'MULTI'" class="opts">
            <button v-for="o in cur.options" :key="o.label" type="button" class="opt"
                    :class="optClass(o.label)"
                    :disabled="!!curResult" @click="pick(o.label)">
              <span class="k">{{ o.label }}</span><span class="ot" v-html="o.content"></span>
            </button>
          </div>
          <template v-else>
            <textarea :value="curAnswer[0] || ''" class="fill" :disabled="!!curResult"
                      :placeholder="cur.type === 'FILL' ? '请输入答案' : '请输入你的作答'"
                      @input="setText($event.target.value)"></textarea>
          </template>

          <div v-if="curResult" class="res" :class="{ ok: curResult.correct, manual: curResult.manual }">
            <div class="res-title">
              <template v-if="curResult.manual">参考答案与解析</template>
              <template v-else>{{ curResult.correct ? '回答正确' : '回答错误' }}</template>
            </div>
            <div v-if="curResult.correctAnswer?.length" class="ans-block">
              <span class="lab">正确答案：</span>
              <div class="ans-list">
                <div v-for="c in curResult.correctAnswer" :key="c.label" class="ans-item">
                  <b>{{ c.label }}</b><span v-html="c.content"></span>
                </div>
              </div>
            </div>
            <div v-if="curResult.analysis" class="ana"><span class="lab">解析：</span><span v-html="curResult.analysis"></span></div>
            <div v-if="curResult.manual" class="tip-line">主观题不自动判分，请对照参考答案自评。</div>
          </div>
        </div>

        <div class="foot">
          <el-button :disabled="idx === 0" @click="prev">上一题</el-button>
          <span class="spacer" />
          <el-button v-if="!curResult" type="primary" :loading="checking" @click="confirm">确认答案</el-button>
          <el-button v-else type="primary" @click="next">{{ idx + 1 === total ? '完成练习' : '下一题' }}</el-button>
        </div>
      </div>

      <!-- 答题卡 -->
      <aside class="sheet">
        <div class="sheet-hd">答题卡</div>
        <div class="grid">
          <button v-for="(q, i) in questions" :key="q.id" type="button" class="cell"
                  :class="[stateOf(q), { cur: i === idx }]" @click="jump(i)">{{ i + 1 }}</button>
        </div>
        <div class="lg-legend">
          <span><i class="lg-dot lg-done" />答对</span>
          <span><i class="lg-dot lg-wrong" />答错</span>
          <span><i class="lg-dot lg-manual" />主观</span>
          <span><i class="lg-dot lg-todo" />未答</span>
        </div>
        <div class="sheet-tip">答完一题立即判分，可随时回看；答错的题会自动进错题本。</div>
      </aside>
    </div>
  </div>
</template>

<script setup>
/**
 * Web 端「练习」（2026-10-21 重做，对齐 51CTO 题库）：
 * 整卷加载 → 逐题作答即时判分 → 答题卡自由切题 → 结果页回顾错题。
 * 入口：/exams 的「练习」按钮（paperId）或「随机练习」（category/count）。
 */
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { usePracticeSession } from '../utils/practiceSession'

const route = useRoute()
const router = useRouter()
const s = usePracticeSession()
const {
  TYPE_CN, loading, errMsg, questions, idx, checking, cur, total, finished,
  curAnswer, curResult, answeredCount, correctCount, judgedCount, wrongList, percent,
  stateOf, load, pick, setText, submit, jump, next, prev, finish, restart,
} = s

const subtitle = computed(() => (route.query.paperId
  ? (s.title.value || '按试卷练习')
  : '从题库抽题，答一题判一题，不计入成绩'))

function optClass(label) {
  const on = curAnswer.value.includes(label)
  const r = curResult.value
  if (!r) return { on }
  const right = r.correctAnswer?.some((c) => c.label === label)
  return { on, ok: right, bad: on && !right }
}

async function confirm() {
  const r = await submit()
  if (r?.error) ElMessage.warning(r.error)
}

/** 结果页点错题：回到那道题看解析（已判分，只读回看） */
function review(q) {
  const i = questions.value.findIndex((x) => x.id === q.id)
  if (i >= 0) jump(i)
}

onMounted(() => load({
  paperId: route.query.paperId,
  category: route.query.category,
  count: route.query.count,
}))
</script>

<style scoped>
.page { max-width: 1080px; margin: 0 auto; padding: 24px 16px 60px; }
.hd { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; margin-bottom: 14px; }
.hd h1 { margin: 0; font-size: 22px; }
.hd .sub { color: #64748b; font-size: 13px; margin: 6px 0 0; }
.hd-act { display: flex; gap: 4px; }
.tip { color: #94a3b8; padding: 40px 0; text-align: center; }
.wrap { display: grid; grid-template-columns: minmax(0, 1fr) 236px; gap: 18px; align-items: start; }
.main { min-width: 0; }
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
.opt.ok .k { background: #16a34a; color: #fff; }
.opt.bad .k { background: #dc2626; color: #fff; }
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
.foot { display: flex; align-items: center; margin-top: 14px; }

.sheet { position: sticky; top: 16px; background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 14px; }
.sheet-hd { font-size: 13.5px; font-weight: 600; color: #0f172a; margin-bottom: 10px; }
.grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 6px; }
.cell { height: 32px; border-radius: 8px; border: 1px solid #e9eef5; background: #fff; color: #64748b;
  font-size: 12.5px; font-family: inherit; cursor: pointer; font-variant-numeric: tabular-nums; }
.cell.done { background: #dcfce7; border-color: #86efac; color: #15803d; }
.cell.wrong { background: #fee2e2; border-color: #fca5a5; color: #b91c1c; }
.cell.manual { background: #eef2ff; border-color: #c7d2fe; color: #4338ca; }
.cell.cur { box-shadow: 0 0 0 2px #6366f1; font-weight: 700; }
.lg-legend { display: flex; flex-flow: row wrap; gap: 6px 10px; margin-top: 12px; font-size: 11.5px; color: #94a3b8; }
.lg-legend > span { display: flex; flex-direction: row; align-items: center; gap: 4px; height: 14px; line-height: 14px; }
.lg-dot { flex: none; width: 9px; height: 9px; border-radius: 3px; border: 1px solid #e9eef5; display: block; box-sizing: border-box; }
.lg-dot.lg-done { background: #dcfce7; border-color: #86efac; }
.lg-dot.lg-wrong { background: #fee2e2; border-color: #fca5a5; }
.lg-dot.lg-manual { background: #eef2ff; border-color: #c7d2fe; }
.lg-dot.lg-todo { background: #fff; }
.sheet-tip { margin-top: 10px; font-size: 11.5px; color: #94a3b8; line-height: 1.5; }

.done { text-align: center; padding: 30px 20px; display: flex; flex-direction: column; align-items: center; gap: 12px; }
.done-num { font-size: 20px; font-weight: 600; color: #0f172a; }
.done-sub { font-size: 13px; color: #94a3b8; }
.done-act { display: flex; gap: 8px; flex-wrap: wrap; justify-content: center; }
.review { width: 100%; max-width: 720px; margin-top: 18px; text-align: left; }
.review-hd { font-size: 13.5px; font-weight: 600; color: #0f172a; margin-bottom: 8px; }
.review-list { display: flex; flex-direction: column; gap: 8px; }
.review-item { display: flex; gap: 10px; padding: 10px 12px; border: 1px solid #eef2f7; border-radius: 10px;
  background: #fff; cursor: pointer; text-align: left; font-family: inherit; }
.review-item:hover { border-color: #c7d2fe; }
.ri-no { flex: none; font-size: 12.5px; color: #b91c1c; }
.ri-txt { flex: 1; font-size: 13px; color: #334155; line-height: 1.55; overflow: hidden;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }

@media (max-width: 860px) {
  .wrap { grid-template-columns: 1fr; }
  .sheet { position: static; order: -1; }
  .grid { grid-template-columns: repeat(8, 1fr); }
}
:deep(html.dark) .q-card, :deep(html.dark) .sheet { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .q-con, :deep(html.dark) .ot { color: #e5e7eb; }
:deep(html.dark) .opt, :deep(html.dark) .cell, :deep(html.dark) .review-item { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .res { background: #20203a; }
:deep(html.dark) .sheet-hd, :deep(html.dark) .done-num, :deep(html.dark) .review-hd { color: #e5e7eb; }
</style>
