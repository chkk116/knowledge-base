<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { aiFeedbackStats, aiFeedbackActions, saveAiFeedbackAction } from '../../api/admin'
import { createMyDoc } from '../../api/kb'

/**
 * AI 反馈与知识缺口 —— 工作台（不是只读看板）。
 * 数据来源：kb_ai_qa_log（每次 AI 回答一行；rating=1/-1 为赞/踩；hit=false 为检索未命中）。
 *
 * 闭环用法：
 *   高频未命中 → 「检索测试」验证搜不搜得到 → 「补文档」一键建知识缺口草稿 → 完善发布后检索即命中；
 *   差评 → 展开完整问答与原因，按「检索没命中 / 文档不对 / 模型答错」定性处理，处理完标记，避免反复排查同一条。
 *
 * 处理状态存本机 localStorage（kb_ai_feedback_actions_v1）：跨设备同步与服务端回测（补文档后
 * 该问题是否复现）属于二期，需要后端持久化。
 */
const router = useRouter()
const loading = ref(false)
const days = ref(7)
const s = ref({
  days: 7, total: 0, kbTotal: 0, hit: 0, miss: 0, hitRate: 0,
  positive: 0, negative: 0, topMissed: [], recentMissed: [], recentNegative: [],
})

// ---------- 处理状态（本机持久化） ----------
const ACTIONS_KEY = 'kb_ai_feedback_actions_v1'
const actions = ref({})   // { [actKey]: { status: 'done'|'ignored', note, at } }
const filter = ref('all') // all | open | done | ignored

// 二期：服务端持久化（跨设备），localStorage 作为离线缓存/兜底
function loadActions() {
  try { return JSON.parse(localStorage.getItem(ACTIONS_KEY) || '{}') } catch { return {} }
}
async function pullActions() {
  try {
    const { data } = await aiFeedbackActions()
    actions.value = data || {}
    saveLocal()
  } catch {
    actions.value = loadActions()
    ElMessage.warning('服务端状态读取失败，暂用本机记录')
  }
}
function saveLocal() {
  try { localStorage.setItem(ACTIONS_KEY, JSON.stringify(actions.value)) } catch { /* 隐私模式忽略 */ }
}
/** 乐观更新：先改本机，再同步服务端；失败保留本机记录并提示 */
async function setStatus(key, status, note = '') {
  if (!status) delete actions.value[key]
  else actions.value[key] = { status, note: String(note || '').slice(0, 200), at: Date.now() }
  saveLocal()
  try {
    await saveAiFeedbackAction({ key, status, note: String(note || '').slice(0, 200) })
  } catch {
    ElMessage.warning('服务端保存失败，状态仅保存在本机')
  }
}
/** key：聚合项按问题文本（截 120 字），原始项按记录 id —— 聚合与原始共用同一状态 */
const kMiss = (q) => 'm:' + String(q || '').trim().slice(0, 120)
const kId = (kind, id) => kind + ':' + id
function statusOf(key) { return actions.value[key]?.status || '' }
function isOpen(key) { return statusOf(key) === '' }
function passFilter(key) {
  const st = statusOf(key)
  if (filter.value === 'all') return true
  if (filter.value === 'open') return st === ''
  return st === filter.value
}
const pendingCount = computed(() => {
  let c = 0
  for (const m of s.value.topMissed || []) if (isOpen(kMiss(m.question))) c++
  for (const it of s.value.recentMissed || []) if (isOpen(kId('r', it.id))) c++
  for (const n of s.value.recentNegative || []) if (isOpen(kId('n', n.id))) c++
  return c
})

async function markDone(key) {
  let note = ''
  try {
    const { value } = await ElMessageBox.prompt('处理备注（可选，如：已补充《xxx》文档）', '标记为已处理', {
      confirmButtonText: '确定', cancelButtonText: '取消', inputPlaceholder: '选填',
    })
    note = value || ''
  } catch { return }
  await setStatus(key, 'done', note)
}
async function markIgnored(key) {
  await setStatus(key, 'ignored', '')
}
async function reopen(key) {
  await setStatus(key, '')
}

// ---------- 动作 ----------
/** 送进「检索测试」：先验证检索到底能不能找到，再决定补什么 */
function testSearch(q) {
  const kw = String(q || '').trim().slice(0, 60)
  if (!kw) return
  router.push({ path: '/admin/search-test', query: { q: kw } })
}

/** 一键建「知识缺口」草稿：标题=问题，正文=骨架；创建后自动标记已处理 */
const creating = ref('')
async function createGapDoc(question) {
  const q = String(question || '').trim()
  if (!q) return
  const title = ('【知识缺口】' + q).slice(0, 80)
  try {
    await ElMessageBox.confirm(
      `将以「${title}」为标题创建一篇草稿（仅自己可见），内容预置问题与补充骨架。`,
      '补文档', { confirmButtonText: '创建草稿', cancelButtonText: '取消' }
    )
  } catch { return }
  creating.value = q
  try {
    const esc = q.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    const content = `<h2>问题</h2><p>${esc}</p><h2>背景</h2><p>该问题在 AI 问答中检索未命中（知识库现有内容未能回答）。</p><h2>待补充</h2><ul><li>与该问题相关的制度 / 说明 / 操作步骤</li><li>常见误区与注意事项</li></ul>`
    const { data } = await createMyDoc({ title, content, status: 'DRAFT' })
    await setStatus(kMiss(q), 'done', '已创建草稿 #' + (data?.id ?? ''))
    ElMessage.success('已创建草稿《' + title + '》，到「我的帖子」完善后提交')
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '创建草稿失败')
  } finally {
    creating.value = ''
  }
}

/** 复制差评的完整上下文，便于贴给文档作者 / 模型维护者 */
async function copyQa(n) {
  const text = `【问题】${n.question || ''}\n【回答】${n.answer || ''}\n【原因】${n.reason || '未填'}\n【用户】${n.user || ''}\n【时间】${fmtTime(n.ratedAt || n.createdAt)}`
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制问答上下文')
  } catch {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
}

/** 差评定性：0=检索没命中（该补文档） 1=文档不对（该修订） 2=模型答错（该调提示词/模型） */
async function classify(n, key) {
  const { value } = await ElMessageBox.prompt(
    '输入 0 / 1 / 2（0=检索没命中，该补文档；1=文档不对，该修订；2=模型答错，该调提示词或换模型），可附加说明',
    '差评定性', { confirmButtonText: '确定', cancelButtonText: '取消', inputPlaceholder: '如：0 已在《运维手册》补充' }
  ).catch(() => ({ value: '' }))
  const v = String(value || '').trim()
  if (!v) return
  const map = { 0: '检索没命中', 1: '文档不对', 2: '模型答错' }
  const note = (map[v[0]] || '其他') + (v.slice(1).trim() ? '：' + v.slice(1).trim() : '')
  // 0=检索没命中走「已忽略」（等补完文档它自然消失）；1/2 走「已处理」
  await setStatus(key, v[0] === '0' ? 'ignored' : 'done', note)
  if (n?.question) await setStatus(kMiss(n.question), 'done', note)
  ElMessage.success('已记录：' + note)
}

const filteredTopMissed = computed(() => (s.value.topMissed || []).filter((m) => passFilter(kMiss(m.question))))
const filteredRecentMissed = computed(() => (s.value.recentMissed || []).filter((m) => passFilter(kId('r', m.id))))
const filteredNegative = computed(() => (s.value.recentNegative || []).filter((n) => passFilter(kId('n', n.id))))

async function load() {
  loading.value = true
  try {
    const { data } = await aiFeedbackStats(days.value)
    s.value = { ...s.value, ...(data || {}) }
  } catch (e) {
    ElMessage.error('加载失败：' + (e.response?.data?.error || e.response?.status || '网络异常'))
  } finally {
    loading.value = false
  }
}

const fmtTime = (t) => {
  if (!t) return '-'
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return '-'
  return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(() => { pullActions(); load() })
</script>

<template>
  <div class="fb-page">
    <div class="hd">
      <div>
        <h1>AI 反馈与知识缺口</h1>
        <p class="sub">不是看板，是工作台：未命中 → 检索测试 → 补文档；差评 → 定性 → 处理。处理完标记，避免重复排查同一条</p>
      </div>
      <div class="hd-act">
        <el-radio-group v-model="filter" size="small">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="open">待处理</el-radio-button>
          <el-radio-button value="done">已处理</el-radio-button>
          <el-radio-button value="ignored">已忽略</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="days" size="small" @change="load">
          <el-radio-button :value="7">近 7 天</el-radio-button>
          <el-radio-button :value="30">近 30 天</el-radio-button>
          <el-radio-button :value="90">近 90 天</el-radio-button>
        </el-radio-group>
        <el-button size="small" :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="cards">
      <div class="card">
        <div class="v">{{ s.hitRate }}<span class="u">%</span></div>
        <div class="k">检索命中率</div>
        <div class="hint">{{ s.hit }} / {{ s.kbTotal }} 次知识库提问命中</div>
      </div>
      <div class="card">
        <div class="v">{{ s.total }}</div>
        <div class="k">总提问</div>
        <div class="hint">含通用模式</div>
      </div>
      <div class="card" :class="{ bad: pendingCount > 0 }">
        <div class="v">{{ pendingCount }}</div>
        <div class="k">待处理</div>
        <div class="hint">未命中 + 差评里还没标记的</div>
      </div>
      <div class="card" :class="s.negative > 0 ? 'bad' : 'ok'">
        <div class="v">{{ s.negative }}</div>
        <div class="k">👎 踩（{{ s.positive }} 赞）</div>
        <div class="hint">定性后标记，避免重复排查</div>
      </div>
    </div>

    <div class="cols">
      <section class="panel">
        <div class="p-hd">高频未命中问题 <span>近 {{ s.days }} 天 · 补完文档后检索即命中</span></div>
        <div v-if="!filteredTopMissed.length" class="empty">没有符合条件的记录</div>
        <ul v-else class="missed">
          <li v-for="(m, i) in filteredTopMissed" :key="i">
            <span class="idx">{{ i + 1 }}</span>
            <span class="q" :class="{ handled: statusOf(kMiss(m.question)) }">{{ m.question }}</span>
            <span class="cnt">{{ m.count }} 次</span>
            <span v-if="statusOf(kMiss(m.question)) === 'done' && m.recurred" class="st warn" :title="'补文档后又出现 ' + m.recurCount + ' 次未命中'">
              ⚠ 又出现 {{ m.recurCount }} 次
            </span>
            <span v-else-if="statusOf(kMiss(m.question)) === 'done' && m.verified" class="st verified" title="标记为已处理后，该问题再未出现未命中">
              ✓ 补文档后未复现
            </span>
            <span v-else-if="statusOf(kMiss(m.question))" class="st" :class="statusOf(kMiss(m.question))">
              {{ statusOf(kMiss(m.question)) === 'done' ? '已处理' : '已忽略' }}
            </span>
            <span v-if="statusOf(kMiss(m.question))" class="ops">
              <el-button v-if="m.recurred" size="small" link type="warning" @click="reopen(kMiss(m.question))">重新排查</el-button>
              <el-button v-if="actions[kMiss(m.question)]?.note" size="small" link disabled :title="actions[kMiss(m.question)].note">已备注</el-button>
            </span>
            <span v-else class="ops">
              <el-button size="small" link type="primary" @click="testSearch(m.question)">检索测试</el-button>
              <el-button size="small" link type="primary" :loading="creating === m.question" @click="createGapDoc(m.question)">补文档</el-button>
              <el-button size="small" link @click="markDone(kMiss(m.question))">已处理</el-button>
              <el-button size="small" link @click="markIgnored(kMiss(m.question))">忽略</el-button>
            </span>
          </li>
        </ul>
      </section>

      <section class="panel">
        <div class="p-hd">最近差评 <span>先定性：检索没命中 / 文档不对 / 模型答错</span></div>
        <div v-if="!filteredNegative.length" class="empty">没有符合条件的差评</div>
        <ul v-else class="neg">
          <li v-for="n in filteredNegative" :key="n.id">
            <div class="n-top">
              <span class="n-q" :class="{ handled: statusOf(kId('n', n.id)) }">{{ n.question || '（无问题文本）' }}</span>
              <span class="n-t">{{ fmtTime(n.ratedAt || n.createdAt) }}</span>
            </div>
            <div class="n-meta">
              <el-tag v-if="n.reason" size="small" type="danger" effect="plain">{{ n.reason }}</el-tag>
              <span class="n-user">{{ n.user }}</span>
              <span v-if="statusOf(kId('n', n.id))" class="st" :class="statusOf(kId('n', n.id))">
                {{ statusOf(kId('n', n.id)) === 'done' ? '已处理' : '已忽略' }}{{ actions[kId('n', n.id)]?.note ? ' · ' + actions[kId('n', n.id)].note : '' }}
              </span>
            </div>
            <div v-if="n.answer" class="n-a">答：{{ n.answer }}</div>
            <div class="n-ops">
              <el-button size="small" link type="primary" @click="copyQa(n)">复制上下文</el-button>
              <el-button size="small" link type="primary" @click="classify(n, kId('n', n.id))">定性</el-button>
              <el-button v-if="statusOf(kId('n', n.id))" size="small" link @click="reopen(kId('n', n.id))">重新打开</el-button>
              <el-button v-else size="small" link @click="markDone(kId('n', n.id))">已处理</el-button>
              <el-button v-if="!statusOf(kId('n', n.id))" size="small" link @click="markIgnored(kId('n', n.id))">忽略</el-button>
            </div>
          </li>
        </ul>
      </section>
    </div>

    <section class="panel">
      <div class="p-hd">最近未命中的提问（原始清单）</div>
      <div v-if="!filteredRecentMissed.length" class="empty">没有符合条件的记录</div>
      <div v-else class="table">
        <div v-for="m in filteredRecentMissed" :key="m.id" class="row">
          <span class="r-q" :class="{ handled: statusOf(kId('r', m.id)) || statusOf(kMiss(m.question)) }">{{ m.question }}</span>
          <el-tag size="small" effect="plain" class="r-mode">{{ m.mode === 'kb' ? '知识库' : m.mode }}</el-tag>
          <span v-if="statusOf(kId('r', m.id)) || statusOf(kMiss(m.question))" class="st done">已处理</span>
          <el-button v-else size="small" link type="primary" @click="createGapDoc(m.question)">补文档</el-button>
          <span class="r-t">{{ fmtTime(m.createdAt) }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.fb-page { max-width: 1180px; margin: 0 auto; padding: 4px 2px 40px; }
.hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 18px; }
.hd h1 { margin: 0; font-size: 22px; }
.hd .sub { margin: 6px 0 0; font-size: 13.5px; color: #64748b; }
.hd-act { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }

.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.card { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 16px 18px; }
.card .v { font-size: 30px; font-weight: 700; color: #1e293b; line-height: 1.1; }
.card .v .u { font-size: 16px; font-weight: 600; margin-left: 1px; }
.card .k { margin-top: 6px; font-size: 13.5px; font-weight: 600; color: #475569; }
.card .hint { margin-top: 4px; font-size: 12px; color: #94a3b8; }
.card.ok .v { color: #15803d; }
.card.bad .v { color: #b91c1c; }

.cols { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 14px; }
.panel { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 16px 18px; }
.p-hd { font-size: 15px; font-weight: 600; color: #1e293b; margin-bottom: 12px; display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; }
.p-hd span { font-size: 12px; font-weight: 400; color: #94a3b8; }
.empty { padding: 26px 0; text-align: center; color: #94a3b8; font-size: 13px; }

.handled { opacity: .5; text-decoration: line-through; }
.st { flex: none; font-size: 11px; border-radius: 6px; padding: 1px 7px; }
.st.done, .st.verified { color: #15803d; background: #f0fdf4; }
.st.ignored { color: #64748b; background: #f1f5f9; }
.st.warn { color: #b45309; background: #fffbeb; }
.ops { flex: none; display: flex; align-items: center; }
.ops .el-button { margin-left: 0; padding: 0 4px; }

.missed { list-style: none; margin: 0; padding: 0; }
.missed li { display: flex; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid #f1f5f9; }
.missed li:last-child { border-bottom: 0; }
.missed .idx { flex: 0 0 20px; height: 20px; line-height: 20px; text-align: center; border-radius: 6px; background: #f1f5f9; color: #64748b; font-size: 12px; }
.missed .q { flex: 1; min-width: 0; font-size: 13.5px; color: #334155; overflow-wrap: anywhere; }
.missed .cnt { flex: 0 0 auto; font-size: 12px; color: #b45309; background: #fffbeb; border-radius: 6px; padding: 1px 7px; }

.neg { list-style: none; margin: 0; padding: 0; max-height: 420px; overflow-y: auto; }
.neg li { padding: 10px 0; border-bottom: 1px solid #f1f5f9; }
.neg li:last-child { border-bottom: 0; }
.n-top { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.n-q { font-size: 13.5px; font-weight: 500; color: #334155; overflow-wrap: anywhere; }
.n-t { flex: 0 0 auto; font-size: 12px; color: #94a3b8; }
.n-meta { display: flex; align-items: center; gap: 8px; margin-top: 5px; flex-wrap: wrap; }
.n-user { font-size: 12px; color: #94a3b8; }
.n-a { margin-top: 5px; font-size: 12.5px; color: #64748b; line-height: 1.6; overflow-wrap: anywhere; }
.n-ops { display: flex; align-items: center; gap: 2px; margin-top: 6px; }
.n-ops .el-button { margin-left: 0; padding: 0 5px; }

.table { display: flex; flex-direction: column; }
.row { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid #f1f5f9; }
.row:last-child { border-bottom: 0; }
.r-q { flex: 1; min-width: 0; font-size: 13.5px; color: #334155; overflow-wrap: anywhere; }
.r-mode { flex: 0 0 auto; }
.r-t { flex: 0 0 auto; font-size: 12px; color: #94a3b8; }

@media (max-width: 960px) {
  .cards { grid-template-columns: repeat(2, 1fr); }
  .cols { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .cards { grid-template-columns: 1fr 1fr; gap: 10px; }
  .card .v { font-size: 24px; }
  .ops { flex-direction: column; align-items: flex-end; gap: 0; }
}

:deep(html.dark) .card,
:deep(html.dark) .panel { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .card .v,
:deep(html.dark) .p-hd { color: #e5e7f0; }
:deep(html.dark) .card .k { color: #b7b8cd; }
:deep(html.dark) .missed li,
:deep(html.dark) .neg li,
:deep(html.dark) .row { border-bottom-color: #2a2a40; }
:deep(html.dark) .missed .q,
:deep(html.dark) .n-q,
:deep(html.dark) .r-q { color: #d5d7e5; }
:deep(html.dark) .missed .idx { background: #26263e; color: #a5a7bd; }
:deep(html.dark) .st.done,
:deep(html.dark) .st.verified { color: #4ade80; background: rgba(74, 222, 128, .12); }
:deep(html.dark) .st.ignored { color: #a5a7bd; background: #26263e; }
:deep(html.dark) .st.warn { color: #fbbf24; background: rgba(251, 191, 36, .12); }
</style>
