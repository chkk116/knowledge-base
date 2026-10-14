<script setup>
/**
 * 移动端「积分明细」：汇总卡（累计获得/支出）+ 类型筛选 + 分页明细
 * 复用 api/auth.js 的 pointsLogPaged
 */
import { onMounted, ref, computed } from 'vue'
import { pointsLogPaged } from '../../api/auth'
import { useAuthStore } from '../../store/auth'

const auth = useAuthStore()
const loading = ref(true)
const rows = ref([])
const earned = ref(0)
const spent = ref(0)
const total = ref(0)
const page = ref(0)
const pages = ref(1)
const type = ref('')
const SIZE = 20

const TYPES = [
  { v: '', label: '全部' },
  { v: 'DOC_CREATE', label: '发布' },
  { v: 'LIKE', label: '获赞' },
  { v: 'COMMENT', label: '评论' },
  { v: 'CHECKIN', label: '签到' },
  { v: 'EXAM', label: '考试' }
]

function fmtTime(s) {
  if (!s) return ''
  const d = new Date(s)
  if (isNaN(d)) return ''
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getMonth() + 1}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load(reset = true) {
  if (reset) { page.value = 0 }
  loading.value = true
  try {
    const r = await pointsLogPaged(page.value, SIZE, type.value).then((x) => x.data || x).catch(() => null)
    const content = (r && r.content) || []
    rows.value = reset ? content : [...rows.value, ...content]
    if (r) { earned.value = r.earned || 0; spent.value = r.spent || 0; total.value = r.total || 0; pages.value = r.pages || 1 }
  } finally { loading.value = false }
}
function pickType(v) { type.value = v; load(true) }
function more() { if (page.value + 1 < pages.value) { page.value++; load(false) } }

onMounted(() => load(true))
</script>

<template>
  <div class="pts">
    <div class="m-header"><div class="m-title">积分明细</div></div>

    <!-- 汇总卡 -->
    <div class="body">
      <div class="sum">
        <div class="s-bal">
          <div class="s-num">{{ auth.user?.points ?? 0 }}</div>
          <div class="s-lbl">当前积分</div>
        </div>
        <div class="s-side">
          <div class="s-item"><span class="up">+{{ earned }}</span><i>累计获得</i></div>
          <div class="s-item"><span class="down">-{{ spent }}</span><i>累计支出</i></div>
        </div>
      </div>

      <div class="cats">
        <button v-for="t in TYPES" :key="t.v" class="cat" :class="{ on: type === t.v }" @click="pickType(t.v)">{{ t.label }}</button>
      </div>

      <div class="m-card">
        <div v-if="loading && !rows.length" style="padding:14px"><div class="m-skel" style="height:40px"></div></div>
        <div v-else-if="rows.length">
          <div v-for="r in rows" :key="r.id" class="row">
            <span class="r-main">
              <span class="r-reason">{{ r.reason || r.type || '积分变动' }}</span>
              <span class="r-time">{{ fmtTime(r.createdAt) }}</span>
            </span>
            <span class="r-delta" :class="{ up: r.delta > 0 }">{{ r.delta > 0 ? '+' + r.delta : r.delta }}</span>
            <span class="r-bal">余 {{ r.balance }}</span>
          </div>
          <button v-if="page + 1 < pages" class="more" :disabled="loading" @click="more">{{ loading ? '加载中…' : '加载更多' }}</button>
        </div>
        <div v-else class="m-empty">
          <span class="ico"><el-icon :size="30"><Coin /></el-icon></span>
          <div>暂无积分记录</div>
        </div>
      </div>
    </div>
    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.pts { min-height: 100%; }
.body { padding: 0 16px; }
.sum { display: flex; align-items: center; gap: 16px; background: linear-gradient(135deg, #ff9a3c, #ff7d00); color: #fff;
  border-radius: var(--m-radius-lg); padding: 18px 20px; box-shadow: 0 6px 18px rgba(255,125,0,.28); }
.s-bal { text-align: center; }
.s-num { font-size: 32px; font-weight: 800; line-height: 1; font-variant-numeric: tabular-nums; }
.s-lbl { font-size: 11.5px; opacity: .9; margin-top: 4px; }
.s-side { flex: 1; display: flex; flex-direction: column; gap: 8px; }
.s-item { display: flex; justify-content: space-between; font-size: 13px; }
.s-item i { font-style: normal; opacity: .9; }
.s-item .up { font-weight: 700; }
.cats { display: flex; gap: 8px; overflow-x: auto; padding: 12px 0 4px; }
.cats::-webkit-scrollbar { display: none; }
.cat { flex: none; border: none; background: var(--m-card-2); color: var(--m-text-sub); font-size: 12.5px;
  padding: 5px 13px; border-radius: 999px; cursor: pointer; white-space: nowrap; }
.cat.on { background: var(--m-primary); color: #fff; }
.row { display: flex; align-items: center; gap: 10px; padding: 12px 4px; border-bottom: 1px solid var(--m-border); }
.row:last-child { border-bottom: none; }
.r-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.r-reason { font-size: 14px; color: var(--m-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.r-time { font-size: 11.5px; color: var(--m-text-dim); }
.r-delta { font-size: 16px; font-weight: 700; color: var(--m-text-dim); font-variant-numeric: tabular-nums; }
.r-delta.up { color: var(--m-success); }
.r-bal { flex: none; font-size: 11.5px; color: var(--m-text-dim); width: 54px; text-align: right; }
.more { width: 100%; border: none; background: transparent; color: var(--m-primary); font-size: 13px; padding: 12px; cursor: pointer; }
</style>
