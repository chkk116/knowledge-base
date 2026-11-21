<script setup>
/**
 * 移动端「练习」（2026-10-21 重做，对齐 51CTO 题库）：
 *   - 整卷加载，答一题判一题（答案判分时才下发）
 *   - 答题卡：题号网格看对错、点号自由跳题
 *   - 结果页：正确率 + 本轮错题回顾
 *
 * 入口：
 *   /exam/practice?paperId=12              按试卷练
 *   /exam/practice?category=xxx&count=10   随机抽题
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { usePracticeSession } from '../../utils/practiceSession'

const route = useRoute()
const router = useRouter()
const s = usePracticeSession()
const {
  TYPE_CN, loading, errMsg, title, questions, idx, checking, cur, total, finished,
  curAnswer, curResult, answeredCount, correctCount, judgedCount, wrongList, percent,
  stateOf, load, pick, setText, submit, jump, next, prev, finish, restart,
} = s

const sheetOpen = ref(false)

function choose(label) {
  if (curResult.value) return
  pick(label)
}
function optClass(o) {
  const on = curAnswer.value.includes(o.label)
  const r = curResult.value
  if (!r) return { on }
  const right = r.correctAnswer?.some((c) => c.label === o.label)
  return { on, ok: right, bad: on && !right }
}
async function confirm() {
  const r = await submit()
  if (r?.error) window.__kbMsg?.warning(r.error)
}
function onText(e) { setText(e.target.value) }
function goCell(i) { jump(i); sheetOpen.value = false }
function review(q) {
  const i = questions.value.findIndex((x) => x.id === q.id)
  if (i >= 0) jump(i)
}
function onNext() {
  if (idx.value + 1 >= total.value) finish()
  else next()
}

onMounted(() => load({
  paperId: route.query.paperId,
  category: route.query.category,
  count: route.query.count,
}))
</script>

<template>
  <div class="pr">
    <header class="pad">
      <button class="bk" type="button" aria-label="返回" @click="router.back()"><el-icon><ArrowLeft /></el-icon></button>
      <div class="tt">{{ title }}</div>
      <div class="cnt" v-if="total && !finished">{{ idx + 1 }} / {{ total }}</div>
      <button v-if="total && !finished" class="card-btn" type="button" @click="sheetOpen = true">答题卡</button>
    </header>

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="errMsg" class="tip">
      <el-icon :size="34" class="warn"><WarningFilled /></el-icon>
      <div>{{ errMsg }}</div>
      <button class="pill" type="button" @click="router.back()">返回</button>
    </div>

    <!-- 本轮结果 -->
    <div v-else-if="finished" class="done-box">
      <div class="ring" :class="{ pass: percent >= 60 }">
        <span class="ring-num">{{ percent }}<i>%</i></span>
        <span class="ring-lab">正确率</span>
      </div>
      <div class="done-num">{{ correctCount }}<span> / {{ judgedCount }} 题答对</span></div>
      <div class="done-sub">共 {{ total }} 题 · 已答 {{ answeredCount }} · 答错 {{ wrongList.length }}</div>
      <div class="done-act">
        <button class="pill primary" type="button" @click="restart">再练一轮</button>
        <button class="pill" type="button" @click="router.push('/exam/wrong')">错题本</button>
        <button class="pill" type="button" @click="router.replace('/exams')">返回考试中心</button>
      </div>

      <div v-if="wrongList.length" class="review">
        <div class="review-hd">本轮错题（点一下回看解析）</div>
        <button v-for="q in wrongList" :key="q.id" type="button" class="review-item" @click="review(q)">
          <span class="ri-no">第 {{ questions.findIndex(x => x.id === q.id) + 1 }} 题</span>
          <span class="ri-txt" v-html="q.content"></span>
        </button>
      </div>
    </div>

    <!-- 答题 -->
    <div v-else-if="cur" class="body">
      <div class="q-card">
        <div class="q-no">
          第 {{ idx + 1 }} 题
          <i class="qt">{{ TYPE_CN[cur.type] || '单选' }}</i>
          <span class="q-stat">已答 {{ answeredCount }} · 正确 {{ correctCount }}</span>
        </div>
        <div class="q-con" v-html="cur.content"></div>

        <div v-if="['SINGLE','JUDGE','MULTI'].includes(cur.type)" class="opts">
          <button v-for="o in cur.options" :key="o.label" type="button" class="opt" :class="optClass(o)"
                  @click="choose(o.label)">
            <span class="k">{{ o.label }}</span><span class="ot" v-html="o.content"></span>
          </button>
        </div>
        <textarea v-else :value="curAnswer[0] || ''" class="fill" :disabled="!!curResult"
                  :placeholder="cur.type === 'FILL' ? '请输入答案' : '请输入你的作答'"
                  @input="onText"></textarea>

        <div v-if="curResult" class="res" :class="{ ok: curResult.correct, manual: curResult.manual }">
          <div class="res-title">
            <template v-if="curResult.manual">参考答案与解析</template>
            <template v-else>{{ curResult.correct ? '回答正确' : '回答错误' }}</template>
          </div>
          <div v-if="curResult.correctAnswer?.length" class="res-line">
            <span class="lab">正确答案：</span>
            <div class="ans-list">
              <div v-for="c in curResult.correctAnswer" :key="c.label" class="ans-item">
                <b>{{ c.label }}</b><span v-html="c.content" />
              </div>
            </div>
          </div>
          <div v-if="curResult.analysis" class="res-line">
            <span class="lab">解析：</span><span class="ana" v-html="curResult.analysis" />
          </div>
          <div v-if="curResult.manual" class="res-tip">主观题不自动判分，请对照参考答案自评。</div>
        </div>
      </div>
    </div>

    <div class="foot" v-if="!loading && !errMsg && !finished && cur">
      <button class="fb" type="button" :disabled="idx === 0" @click="prev">上一题</button>
      <button v-if="!curResult" class="fb primary" type="button" :disabled="checking" @click="confirm">
        {{ checking ? '判分中…' : '确认答案' }}
      </button>
      <button v-else class="fb primary" type="button" @click="onNext">
        {{ idx + 1 === total ? '完成练习' : '下一题' }}
      </button>
    </div>

    <!-- 答题卡 -->
    <transition name="sheet-up">
      <div v-if="sheetOpen" class="sheet-mask" @click.self="sheetOpen = false">
        <div class="sheet">
          <div class="sheet-title">答题卡<span class="sheet-sub">{{ answeredCount }} / {{ total }} 已答</span></div>
          <div class="grid">
            <button v-for="(q, i) in questions" :key="q.id" type="button" class="cell"
                    :class="[stateOf(q), { cur: i === idx }]" @click="goCell(i)">{{ i + 1 }}</button>
          </div>
          <div class="lg-legend">
            <span><i class="lg-dot lg-done" />答对</span>
            <span><i class="lg-dot lg-wrong" />答错</span>
            <span><i class="lg-dot lg-manual" />主观</span>
            <span><i class="lg-dot lg-todo" />未答</span>
          </div>
          <div class="sheet-actions">
            <button class="ghost-btn" type="button" @click="sheetOpen = false">继续答题</button>
            <button class="primary-btn" type="button" @click="sheetOpen = false; finish()">结束练习</button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.pr { min-height: 100%; background: var(--m-bg); display: flex; flex-direction: column; }
.pad { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; gap: 8px; padding: 10px 12px;
  background: var(--m-card); border-bottom: 1px solid var(--m-border); }
.bk { width: 32px; height: 32px; border: none; background: transparent; color: var(--m-text); display: grid; place-items: center; }
.tt { flex: 1; font-size: 15px; font-weight: 600; color: var(--m-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cnt { font-size: 12.5px; color: var(--m-text-dim); font-variant-numeric: tabular-nums; }
.card-btn { flex: none; height: 28px; padding: 0 10px; border: 1px solid var(--m-border); border-radius: 8px;
  background: var(--m-card); color: var(--m-primary); font-size: 12px; font-family: inherit; }
.tip { padding: 70px 32px; text-align: center; color: var(--m-text-dim); display: flex; flex-direction: column; align-items: center; gap: 10px; }
.warn { color: var(--m-warn); }
.body { flex: 1; padding: 12px 16px 20px; overflow-y: auto; }
.q-card { background: var(--m-card); border-radius: 14px; padding: 14px; }
.q-no { font-size: 12.5px; color: var(--m-text-dim); margin-bottom: 8px; }
.qt { font-style: normal; margin-left: 8px; padding: 1px 8px; border-radius: 999px; background: var(--m-card-2); color: var(--m-text-sub); font-size: 11px; }
.q-stat { float: right; color: var(--m-text-dim); }
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
.opt.ok .k { background: var(--m-success); color: #fff; }
.opt.bad .k { background: var(--m-danger); color: #fff; }
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
.foot { display: flex; gap: 10px; padding: 10px 16px calc(10px + env(safe-area-inset-bottom));
  background: var(--m-card); border-top: 1px solid var(--m-border); }
.fb { flex: 1; height: 44px; border: 1px solid var(--m-border); border-radius: 12px; background: var(--m-card);
  color: var(--m-text); font-size: 15px; font-family: inherit; }
.fb.primary { flex: 2; border: none; background: var(--m-primary); color: #fff; font-weight: 600; }
.fb:disabled { opacity: .45; }

.done-box { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 26px 16px 40px; overflow-y: auto; }
.ring { width: 132px; height: 132px; border-radius: 50%; display: flex; flex-direction: column; align-items: center;
  justify-content: center; gap: 2px; border: 8px solid var(--m-warn); color: var(--m-text); }
.ring.pass { border-color: var(--m-success); }
.ring-num { font-size: 32px; font-weight: 800; line-height: 1; }
.ring-num i { font-size: 14px; font-style: normal; font-weight: 600; }
.ring-lab { font-size: 11.5px; color: var(--m-text-dim); }
.done-num { font-size: 20px; font-weight: 700; color: var(--m-text); margin-top: 6px; }
.done-num span { font-size: 13px; font-weight: 400; color: var(--m-text-dim); }
.done-sub { font-size: 12px; color: var(--m-text-dim); }
.done-act { display: flex; flex-direction: column; gap: 8px; width: 100%; max-width: 320px; margin-top: 8px; }
.pill { width: 100%; height: 44px; border: 1px solid var(--m-border); border-radius: 12px; background: var(--m-card);
  color: var(--m-text); font-size: 14px; font-family: inherit; }
.pill.primary { background: var(--m-primary); border-color: var(--m-primary); color: #fff; font-weight: 600; }
.review { width: 100%; margin-top: 14px; }
.review-hd { font-size: 12.5px; color: var(--m-text-dim); margin-bottom: 8px; }
.review-item { display: flex; gap: 8px; width: 100%; padding: 10px 12px; margin-bottom: 8px; border: 1px solid var(--m-border);
  border-radius: 12px; background: var(--m-card); text-align: left; font-family: inherit; }
.ri-no { flex: none; font-size: 12px; color: var(--m-danger); }
.ri-txt { flex: 1; font-size: 13px; color: var(--m-text-sub); line-height: 1.5; overflow: hidden;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }

.sheet-mask { position: fixed; inset: 0; z-index: 60; background: rgba(0, 0, 0, .35); display: flex; align-items: flex-end; }
.sheet { width: 100%; background: var(--m-bg, #f5f6f7); border-radius: 16px 16px 0 0; padding: 16px 14px calc(16px + env(safe-area-inset-bottom)); }
.sheet-title { font-size: 16px; font-weight: 500; color: var(--m-text); margin-bottom: 12px; }
.sheet-sub { margin-left: 8px; font-size: 12px; color: var(--m-text-dim); }
.grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; max-height: 44vh; overflow-y: auto; }
.cell { height: 40px; border-radius: 10px; border: 1px solid var(--m-border); background: var(--m-card);
  color: var(--m-text-sub); font-size: 13px; font-family: inherit; font-variant-numeric: tabular-nums; }
.cell.done { background: rgba(22, 163, 74, .12); border-color: var(--m-success); color: var(--m-success); }
.cell.wrong { background: rgba(220, 38, 38, .1); border-color: var(--m-danger); color: var(--m-danger); }
.cell.manual { background: var(--m-primary-soft); border-color: var(--m-primary); color: var(--m-primary); }
.cell.cur { box-shadow: inset 0 0 0 2px var(--m-primary); font-weight: 700; }
.lg-legend { display: flex; flex-flow: row wrap; gap: 8px 14px; margin-top: 12px; font-size: 11.5px; color: var(--m-text-dim); }
.lg-legend > span { display: flex; flex-direction: row; align-items: center; gap: 4px; height: 14px; line-height: 14px; }
.lg-dot { flex: none; width: 9px; height: 9px; border-radius: 3px; border: 1px solid var(--m-border); display: block; box-sizing: border-box; }
.lg-dot.lg-done { background: rgba(22, 163, 74, .12); border-color: var(--m-success); }
.lg-dot.lg-wrong { background: rgba(220, 38, 38, .1); border-color: var(--m-danger); }
.lg-dot.lg-manual { background: var(--m-primary-soft); border-color: var(--m-primary); }
.sheet-actions { display: flex; gap: 10px; margin-top: 14px; }
.ghost-btn { flex: none; width: 110px; height: 44px; border: 1px solid var(--m-border); border-radius: 12px;
  background: transparent; color: var(--m-text); font-size: 14px; font-family: inherit; }
.primary-btn { flex: 1; height: 44px; border: none; border-radius: 12px; background: var(--m-primary);
  color: #fff; font-size: 15px; font-family: inherit; }
</style>
