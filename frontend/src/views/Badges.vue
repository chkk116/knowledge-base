<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listBadges, wearBadge } from '../api/kb'
import { useAuthStore } from '../store/auth'
import { BADGE_ICONS, DEFAULT_BADGE_ICON, LOCK_ICON, tierGradient, withAlpha } from '../utils/badgeIcons'
import { errMsg } from '../utils/errMsg'

const auth = useAuthStore()
const loading = ref(false)
const data = ref({ badges: [], earned: 0, total: 0 })
const onlyEarned = ref(false)
const wornIds = ref([...(auth.user?.wornBadges || [])])   // 当前佩戴的徽章 id 列表（最多 3 枚）
const wearing = ref(false)

const isWorn = (b) => wornIds.value.includes(b.id)
async function toggleWear(b) {
  if (wearing.value) return
  if (!isWorn(b) && wornIds.value.length >= 3) {
    ElMessage.warning('最多同时佩戴 3 枚勋章')
    return
  }
  const next = isWorn(b)
    ? wornIds.value.filter(id => id !== b.id)          // 再次点击 → 卸下
    : [...wornIds.value, b.id]                          // 佩戴（上限由后端兜底）
  wearing.value = true
  try {
    const { data } = await wearBadge(b.id)             // 后端按 id 在佩戴集合中切换
    const list = Array.isArray(data.wornBadges) ? data.wornBadges : next
    wornIds.value = [...list]
    if (auth.user) auth.user.wornBadges = [...list]     // 顶栏 / 资料页即时更新
    ElMessage.success(isWorn(b) ? `已卸下「${b.name}」` : `已佩戴「${b.name}」`)
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  } finally {
    wearing.value = false
  }
}

const HEX_OUTER = '50,3 91,27 91,73 50,97 9,73 9,27'
const HEX_INNER = '50,13 82,32 82,68 50,87 18,68 18,32'

const shown = computed(() => {
  let list = data.value.badges || []
  if (onlyEarned.value) list = list.filter(b => b.earned)
  return [...list].sort((a, b) => {
    if (a.earned !== b.earned) return a.earned ? -1 : 1
    const order = { '入门': 0, '进阶': 1, '高级': 2, '荣誉': 3 }
    return (order[a.tier] ?? 9) - (order[b.tier] ?? 9)
  })
})

const grad = (b) => tierGradient(b.tier)
const gradId = (b) => 'bg-' + b.id
const cardStyle = (b) => {
  const g = grad(b)
  return b.earned
    ? { '--c1': g[0], '--c2': g[1], '--glow': withAlpha(g[1], '66') }
    : { '--c1': g[0], '--c2': g[1], '--glow': 'transparent' }
}
const tierTagStyle = (b) => {
  const g = grad(b)
  return { color: g[1], borderColor: withAlpha(g[1], '66'), background: withAlpha(g[1], '14') }
}
const progressText = (b) => {
  if (b.earned) return '已解锁'
  const left = Math.max(0, b.threshold - b.current)
  const unit = { POINTS: '积分', DOCS: '篇', LIKES: '赞', FAVS: '次收藏', FANS: '粉丝', FOLLOWING: '关注', DAYS: '天' }[b.metric] || ''
  return `还差 ${left} ${unit}`
}

onMounted(async () => {
  loading.value = true
  try {
    const { data: d } = await listBadges()
    data.value = d || { badges: [], earned: 0, total: 0 }
  } catch (e) {
    data.value = { badges: [], earned: 0, total: 0 }
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="badges-page">
    <div class="hd">
      <div>
        <h1>我的勋章</h1>
        <p class="sub">勋章由你的积分、文章、获赞、粉丝等实时数据自动解锁，记录每一步成长</p>
      </div>
      <div class="count" v-if="!loading">
        <span class="num">{{ data.earned }}</span><span class="sep">/</span><span class="tot">{{ data.total }}</span>
        <span class="lbl">已获得</span>
      </div>
    </div>

    <div class="toolbar">
      <div class="tiers">
        <button class="tier" :class="{ on: !onlyEarned }" @click="onlyEarned = false">全部</button>
        <button class="tier" :class="{ on: onlyEarned }" @click="onlyEarned = true">
          仅已获得 ({{ data.earned }})
        </button>
      </div>
      <div class="worn-count">已佩戴 <b>{{ wornIds.length }}</b> / 3</div>
    </div>

    <div v-if="loading" class="empty">加载中…</div>
    <div v-else-if="shown.length === 0" class="empty">
      还没有已获得的勋章，去 <a @click="$router.push('/write')">写第一篇</a> 或 <a @click="$router.push('/')">逛逛知识库</a> 解锁吧～
    </div>

    <div v-else class="grid">
      <div v-for="b in shown" :key="b.id" class="card" :class="{ locked: !b.earned, worn: isWorn(b) }" :style="cardStyle(b)">
        <div class="medal">
          <svg viewBox="0 0 100 100" class="hex" aria-hidden="true">
            <defs>
              <linearGradient :id="gradId(b)" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" :stop-color="grad(b)[0]" />
                <stop offset="100%" :stop-color="grad(b)[1]" />
              </linearGradient>
            </defs>
            <polygon class="hex-outer" :points="HEX_OUTER"
              :fill="b.earned ? `url(#${gradId(b)})` : 'none'"
              :stroke="b.earned ? `url(#${gradId(b)})` : 'rgba(120,130,150,.4)'"
              stroke-width="5" stroke-linejoin="round" />
            <polygon class="hex-inner" :points="HEX_INNER"
              :fill="b.earned ? 'rgba(255,255,255,.16)' : 'rgba(148,163,184,.08)'"
              :stroke="b.earned ? 'rgba(255,255,255,.5)' : 'rgba(120,130,150,.3)'"
              stroke-width="1.4" />
          </svg>
          <component :is="BADGE_ICONS[b.id] || DEFAULT_BADGE_ICON" class="mi" />
          <span v-if="!b.earned" class="lock"><component :is="LOCK_ICON" class="lock-ic" /></span>
        </div>
        <div class="info">
          <div class="name">{{ b.name }}</div>
          <div class="desc">{{ b.desc }}</div>
          <span class="tier-tag" :style="tierTagStyle(b)">{{ b.tier }}</span>
        </div>
        <div class="foot">
          <div class="bar" v-if="!b.earned">
            <div class="fill" :style="{ width: b.progress + '%', background: `linear-gradient(90deg, ${grad(b)[0]}, ${grad(b)[1]})` }"></div>
          </div>
          <div class="status" :class="{ ok: b.earned }">
            <span v-if="b.earned">✓ 已解锁</span>
            <span v-else>{{ progressText(b) }}</span>
          </div>
          <button v-if="b.earned" class="wear-btn" :class="{ on: isWorn(b) }"
                  :disabled="wearing || (!isWorn(b) && wornIds.length >= 3)" @click="toggleWear(b)">
            {{ isWorn(b) ? '卸下' : (wornIds.length >= 3 ? '已满 3 枚' : '佩戴') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.badges-page { max-width: 1000px; margin: 0 auto; padding: 8px 4px 48px; }
.hd { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 18px; }
.hd h1 { font-size: 24px; font-weight: 800; letter-spacing: -.3px; color: #0f172a; margin: 0; }
.sub { font-size: 13px; color: #94a3b8; margin: 6px 0 0; max-width: 560px; line-height: 1.5; }
.count { display: flex; align-items: baseline; gap: 2px; }
.count .num { font-size: 30px; font-weight: 800; color: #6366f1; font-variant-numeric: tabular-nums; }
.count .sep { font-size: 22px; color: #cbd5e1; }
.count .tot { font-size: 18px; color: #94a3b8; font-variant-numeric: tabular-nums; }
.count .lbl { font-size: 13px; color: #64748b; margin-left: 6px; }

.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.worn-count { font-size: 13px; color: #64748b; font-weight: 600; }
.worn-count b { color: #4f46e5; font-size: 15px; }
html.dark .worn-count { color: #9aa0b4; }
html.dark .worn-count b { color: #a5b4fc; }
.tiers { display: inline-flex; background: #f1f5f9; border-radius: 10px; padding: 3px; }
.tier { border: none; background: transparent; padding: 6px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; color: #64748b; font-weight: 600; }
.tier.on { background: #fff; color: #4f46e5; box-shadow: 0 1px 3px rgba(0,0,0,.08); }

.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 14px; }
.card {
  position: relative; overflow: hidden;
  background: #fff; border: 1px solid #eef2f7; border-radius: 18px; padding: 20px 18px 16px;
  display: flex; flex-direction: column; align-items: center; text-align: center;
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
}
.card::before {
  content: ''; position: absolute; inset: 0 0 auto 0; height: 3px;
  background: linear-gradient(90deg, var(--c1, #6366f1), var(--c2, #8b5cf6));
  opacity: 0; transition: opacity .18s ease;
}
.card:not(.locked):hover { transform: translateY(-3px); box-shadow: 0 14px 34px rgba(79,70,229,.14); border-color: #dfe1ff; }
.card:not(.locked):hover::before { opacity: 1; }
.card.locked { background: #fafbfc; }
.card.worn { border-color: var(--c2, #8b5cf6); box-shadow: 0 0 0 2px color-mix(in srgb, var(--c2, #8b5cf6) 38%, transparent), 0 10px 26px rgba(79,70,229,.14); }

.medal { position: relative; width: 80px; height: 80px; margin: 2px auto 14px; filter: drop-shadow(0 10px 20px var(--glow, transparent)); }
/* 已解锁：轻微上下浮动 + 发光呼吸 */
.card:not(.locked) .medal { animation: badge-bob 3.4s ease-in-out infinite; }
.card.locked .medal { filter: grayscale(1) drop-shadow(0 6px 12px rgba(15,23,42,.12)); opacity: .85; }
/* 已解锁：六边形内流动高光扫过 */
.medal::after {
  content: ''; position: absolute; inset: 0; pointer-events: none; z-index: 2;
  clip-path: polygon(50% 3%, 91% 27%, 91% 73%, 50% 97%, 9% 73%, 9% 27%);
  background: linear-gradient(115deg, transparent 38%, rgba(255,255,255,.6) 50%, transparent 62%);
  background-size: 250% 100%;
  background-position: 150% 0;
  animation: badge-shine 3.8s ease-in-out infinite;
}
.card.locked .medal::after { display: none; }
.hex { width: 100%; height: 100%; display: block; }
.mi { position: absolute; left: 50%; top: 50%; transform: translate(-50%,-50%); width: 34px; height: 34px; color: #fff; z-index: 1; }
.card.locked .mi { color: #94a3b8; }
.lock { position: absolute; right: 2px; bottom: 4px; width: 20px; height: 20px; display: flex; align-items: center; justify-content: center; background: rgba(15,23,42,.82); border: 1.5px solid rgba(255,255,255,.85); border-radius: 50%; z-index: 3; }
.lock-ic { width: 11px; height: 11px; color: #fff; }

.info { flex: 1; }
.name { font-size: 16px; font-weight: 750; color: #0f172a; }
.desc { font-size: 12px; color: #64748b; margin-top: 5px; line-height: 1.5; }
.tier-tag { display: inline-block; font-size: 11px; font-weight: 700; padding: 2px 9px; border-radius: 999px; border: 1px solid; margin-top: 9px; }

.foot { width: 100%; margin-top: 14px; }
.bar { height: 6px; border-radius: 999px; background: #eef2f7; overflow: hidden; margin-bottom: 7px; }
.bar .fill { height: 100%; border-radius: 999px; transition: width .5s ease; }
.status { font-size: 12px; color: #94a3b8; font-weight: 600; }
.status.ok { color: #16a34a; }

.wear-btn {
  width: 100%; padding: 7px 0; border-radius: 10px; cursor: pointer;
  font-size: 13px; font-weight: 700; border: 1.5px solid #e2e8f0; background: #fff; color: #4f46e5;
  transition: all .15s ease;
}
.wear-btn:hover:not(:disabled) { border-color: #c7d2fe; background: #f5f3ff; }
.wear-btn.on { background: linear-gradient(135deg, var(--c1, #6366f1), var(--c2, #8b5cf6)); border-color: transparent; color: #fff; box-shadow: 0 6px 16px rgba(79,70,229,.28); }
.wear-btn:disabled { opacity: .6; cursor: default; }

.empty { color: #94a3b8; font-size: 14px; padding: 40px 0; text-align: center; }
.empty a { color: #6366f1; cursor: pointer; }

@keyframes badge-bob {
  0%, 100% { transform: translateY(0); filter: drop-shadow(0 8px 16px var(--glow)); }
  50% { transform: translateY(-5px); filter: drop-shadow(0 16px 26px var(--glow)); }
}
@keyframes badge-shine {
  0% { background-position: 150% 0; }
  55%, 100% { background-position: -60% 0; }
}

html.dark .hd h1 { color: #e8e8f0; }
html.dark .tiers { background: #23232e; }
html.dark .tier { color: #9aa0b4; }
html.dark .tier.on { background: #2c2c3a; color: #a5b4fc; }
html.dark .card { background: #1b1b26; border-color: #2c2c3a; }
html.dark .card.locked { background: #16161f; }
html.dark .card:not(.locked):hover { box-shadow: 0 14px 34px rgba(99,102,241,.26); border-color: #3a3a4e; }
html.dark .card::before { opacity: .9; }
html.dark .name { color: #e8e8f0; }
html.dark .desc { color: #9aa0b4; }
html.dark .status { color: #8b8fa0; }
html.dark .bar { background: #2c2c3a; }

@media (max-width: 640px) {
  .hd h1 { font-size: 20px; }
  .grid { grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; }
  .card { padding: 16px 12px 12px; }
  .medal { width: 68px; height: 68px; }
  .mi { width: 30px; height: 30px; }
}

/* 尊重系统的「减少动态效果」偏好 */
@media (prefers-reduced-motion: reduce) {
  .card:not(.locked) .medal, .medal::after { animation: none !important; }
  .card:not(.locked) .medal { transform: none; }
}
</style>
