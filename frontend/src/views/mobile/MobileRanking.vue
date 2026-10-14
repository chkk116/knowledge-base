<script setup>
/**
 * 移动端「积分排行」：我的名次卡 + 排行榜（前 20）
 * 复用 api/auth.js 的 ranking
 * 返回：{ top:[{rank,username,displayName,avatar,points}], me:{rank,points,username}, total }
 */
import { onMounted, ref } from 'vue'
import { ranking } from '../../api/auth'
import { resolveUrl } from '../../utils/runtimeBase'

const loading = ref(true)
const top = ref([])
const me = ref(null)
const total = ref(0)

async function load() {
  loading.value = true
  try {
    const r = await ranking().then((x) => x.data || x).catch(() => null)
    top.value = (r && r.top) || []
    me.value = r?.me || null
    total.value = r?.total || 0
  } finally { loading.value = false }
}
function avatar(u) { return u.avatar ? resolveUrl(u.avatar) : '' }
onMounted(load)
</script>

<template>
  <div class="rk">
    <div class="m-header"><div class="m-title">积分排行</div></div>
    <div class="body">
      <!-- 我的名次 -->
      <div v-if="me" class="me">
        <span class="me-rank">第 {{ me.rank }} 名</span>
        <span class="me-txt">{{ me.points }} 积分 · 共 {{ total }} 人</span>
      </div>

      <div v-if="loading" class="m-card"><div style="padding:14px"><div class="m-skel" style="height:44px"></div></div></div>
      <div v-else-if="top.length" class="m-card">
        <div v-for="u in top" :key="u.rank" class="row" :class="{ self: me && u.username === me.username }">
          <span class="rank" :class="'r' + (u.rank <= 3 ? u.rank : 0)">{{ u.rank }}</span>
          <span class="av" :style="avatar(u) ? { backgroundImage: `url(${avatar(u)})` } : {}">
            <template v-if="!avatar(u)">{{ (u.displayName || u.username || '?').slice(0, 1) }}</template>
          </span>
          <span class="nm">{{ u.displayName || u.username }}</span>
          <span class="pt">{{ u.points }}<i>分</i></span>
        </div>
      </div>
      <div v-else class="m-card"><div class="m-empty"><div>暂无排行数据</div></div></div>
    </div>
    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.rk { min-height: 100%; }
.body { padding: 0 16px; }
.me { display: flex; align-items: center; justify-content: space-between; background: linear-gradient(135deg, #4f86ff, #6366f1);
  color: #fff; border-radius: var(--m-radius-lg); padding: 16px 20px; box-shadow: 0 6px 18px rgba(99,102,241,.28); }
.me-rank { font-size: 20px; font-weight: 800; }
.me-txt { font-size: 12.5px; opacity: .9; }
.row { display: flex; align-items: center; gap: 12px; padding: 11px 4px; border-bottom: 1px solid var(--m-border); }
.row:last-child { border-bottom: none; }
.row.self { background: var(--m-primary-soft); border-radius: 10px; }
.rank { flex: none; width: 26px; text-align: center; font-size: 14px; font-weight: 700; color: var(--m-text-dim); font-variant-numeric: tabular-nums; }
.rank.r1 { color: #f5a623; } .rank.r2 { color: #9aa4b2; } .rank.r3 { color: #cd7f32; }
.av { flex: none; width: 38px; height: 38px; border-radius: 50%; background: var(--m-primary-soft) center/cover; color: var(--m-primary);
  display: grid; place-items: center; font-weight: 700; font-size: 15px; }
.nm { flex: 1; min-width: 0; font-size: 14.5px; color: var(--m-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pt { flex: none; font-size: 16px; font-weight: 700; color: var(--m-primary); font-variant-numeric: tabular-nums; }
.pt i { font-size: 10.5px; font-style: normal; margin-left: 2px; color: var(--m-text-dim); font-weight: 500; }
</style>
