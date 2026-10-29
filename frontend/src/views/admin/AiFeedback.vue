<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { aiFeedbackStats } from '../../api/admin'

/**
 * AI 反馈与知识缺口看板。
 * 数据来源：kb_ai_qa_log（每次 AI 回答一行；rating=1/-1 为赞/踩；hit=false 为检索未命中）。
 * 用途：先看「命中率」判断检索质量，再看「高频未命中」决定补哪些文档，最后看「最近差评」判断是内容问题还是模型问题。
 */
const loading = ref(false)
const days = ref(7)
const s = ref({
  days: 7, total: 0, kbTotal: 0, hit: 0, miss: 0, hitRate: 0,
  positive: 0, negative: 0, topMissed: [], recentMissed: [], recentNegative: [],
})

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

onMounted(load)
</script>

<template>
  <div class="fb-page">
    <div class="hd">
      <div>
        <h1>AI 反馈与知识缺口</h1>
        <p class="sub">赞 / 踩数据 + 检索未命中的问题，用来决定该补哪些文档</p>
      </div>
      <div class="hd-act">
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
      <div class="card ok">
        <div class="v">{{ s.positive }}</div>
        <div class="k">👍 赞</div>
        <div class="hint">用户认为有帮助</div>
      </div>
      <div class="card bad">
        <div class="v">{{ s.negative }}</div>
        <div class="k">👎 踩</div>
        <div class="hint">优先排查的差评</div>
      </div>
    </div>

    <div class="cols">
      <section class="panel">
        <div class="p-hd">高频未命中问题 <span>近 {{ s.days }} 天</span></div>
        <div v-if="!s.topMissed?.length" class="empty">暂无未命中记录</div>
        <ul v-else class="missed">
          <li v-for="(m, i) in s.topMissed" :key="i">
            <span class="idx">{{ i + 1 }}</span>
            <span class="q">{{ m.question }}</span>
            <span class="cnt">{{ m.count }} 次</span>
          </li>
        </ul>
      </section>

      <section class="panel">
        <div class="p-hd">最近差评</div>
        <div v-if="!s.recentNegative?.length" class="empty">暂无差评</div>
        <ul v-else class="neg">
          <li v-for="n in s.recentNegative" :key="n.id">
            <div class="n-top">
              <span class="n-q">{{ n.question || '（无问题文本）' }}</span>
              <span class="n-t">{{ fmtTime(n.ratedAt || n.createdAt) }}</span>
            </div>
            <div class="n-meta">
              <el-tag v-if="n.reason" size="small" type="danger" effect="plain">{{ n.reason }}</el-tag>
              <span class="n-user">{{ n.user }}</span>
            </div>
            <div v-if="n.answer" class="n-a">答：{{ n.answer }}</div>
          </li>
        </ul>
      </section>
    </div>

    <section class="panel">
      <div class="p-hd">最近未命中的提问（原始清单）</div>
      <div v-if="!s.recentMissed?.length" class="empty">暂无未命中记录</div>
      <div v-else class="table">
        <div v-for="m in s.recentMissed" :key="m.id" class="row">
          <span class="r-q">{{ m.question }}</span>
          <el-tag size="small" effect="plain" class="r-mode">{{ m.mode === 'kb' ? '知识库' : m.mode }}</el-tag>
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
.hd-act { display: flex; align-items: center; gap: 10px; }

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
.p-hd { font-size: 15px; font-weight: 600; color: #1e293b; margin-bottom: 12px; display: flex; align-items: baseline; gap: 8px; }
.p-hd span { font-size: 12px; font-weight: 400; color: #94a3b8; }
.empty { padding: 26px 0; text-align: center; color: #94a3b8; font-size: 13px; }

.missed { list-style: none; margin: 0; padding: 0; }
.missed li { display: flex; align-items: flex-start; gap: 10px; padding: 9px 0; border-bottom: 1px solid #f1f5f9; }
.missed li:last-child { border-bottom: 0; }
.missed .idx { flex: 0 0 20px; height: 20px; line-height: 20px; text-align: center; border-radius: 6px; background: #f1f5f9; color: #64748b; font-size: 12px; }
.missed .q { flex: 1; min-width: 0; font-size: 13.5px; color: #334155; overflow-wrap: anywhere; }
.missed .cnt { flex: 0 0 auto; font-size: 12px; color: #b45309; background: #fffbeb; border-radius: 6px; padding: 1px 7px; }

.neg { list-style: none; margin: 0; padding: 0; max-height: 320px; overflow-y: auto; }
.neg li { padding: 10px 0; border-bottom: 1px solid #f1f5f9; }
.neg li:last-child { border-bottom: 0; }
.n-top { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.n-q { font-size: 13.5px; font-weight: 500; color: #334155; overflow-wrap: anywhere; }
.n-t { flex: 0 0 auto; font-size: 12px; color: #94a3b8; }
.n-meta { display: flex; align-items: center; gap: 8px; margin-top: 5px; }
.n-user { font-size: 12px; color: #94a3b8; }
.n-a { margin-top: 5px; font-size: 12.5px; color: #64748b; line-height: 1.6; overflow-wrap: anywhere; }

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
</style>
