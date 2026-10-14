<script setup>
/**
 * 移动端「他人主页」：作者卡片 + 关注/取关 + 徽章 + 已发布文章
 * 复用 api/kb.js 的 getUserCard / followUser / unfollowUser / getUserArticles / userBadges
 */
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getUserCard, followUser, unfollowUser, getUserArticles, userBadges } from '../../api/kb'
import { resolveUrl } from '../../utils/runtimeBase'
import { useAuthStore } from '../../store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = route.params.id

const loading = ref(true)
const card = ref(null)
const articles = ref([])
const badges = ref([])
const following = ref(false)
const busy = ref(false)

const isSelf = computed(() => auth.user && String(auth.user.id) === String(id))
const av = computed(() => (card.value?.avatar ? resolveUrl(card.value.avatar) : ''))

function fmtDate(s) {
  if (!s) return ''
  const d = new Date(s); if (isNaN(d)) return ''
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function load() {
  loading.value = true
  try {
    const [c, a, b] = await Promise.all([
      getUserCard(id).then((x) => x.data || x).catch(() => null),
      getUserArticles(id, 0, 20).then((x) => x.data || x).catch(() => null),
      userBadges(id).then((x) => (Array.isArray(x.data) ? x.data : [])).catch(() => [])
    ])
    card.value = c
    following.value = !!c?.isFollowing
    articles.value = (a && a.content) || (Array.isArray(a) ? a : [])
    badges.value = b
  } finally { loading.value = false }
}

async function toggleFollow() {
  if (busy.value || isSelf.value) return
  busy.value = true
  try {
    if (following.value) { await unfollowUser(id); following.value = false; window.__kbMsg?.success('已取消关注') }
    else { await followUser(id); following.value = true; window.__kbMsg?.success('已关注') }
    if (card.value) { card.value.fans = Math.max(0, (card.value.fans || 0) + (following.value ? 1 : -1)) }
  } catch { window.__kbMsg?.error('操作失败') }
  finally { busy.value = false }
}

onMounted(load)
</script>

<template>
  <div class="up" v-if="card">
    <div class="hero">
      <button class="back" @click="router.back()"><el-icon><ArrowLeft /></el-icon></button>
      <span class="av" :style="av ? { backgroundImage: `url(${av})` } : {}">
        <template v-if="!av">{{ (card.displayName || '?').slice(0, 1) }}</template>
      </span>
      <div class="nm">{{ card.displayName || '用户' }}</div>
      <div class="bio" v-if="card.bio">{{ card.bio }}</div>
      <button v-if="!isSelf" class="follow" :class="{ on: following }" :disabled="busy" @click="toggleFollow">
        {{ following ? '已关注' : '+ 关注' }}
      </button>
    </div>

    <div class="stats">
      <div class="st"><b>{{ card.original || 0 }}</b><i>文章</i></div>
      <div class="st"><b>{{ card.likes || 0 }}</b><i>获赞</i></div>
      <div class="st"><b>{{ card.favorites || 0 }}</b><i>收藏</i></div>
      <div class="st"><b>{{ card.fans || 0 }}</b><i>粉丝</i></div>
      <div class="st"><b>{{ card.following || 0 }}</b><i>关注</i></div>
      <div class="st"><b>{{ card.points || 0 }}</b><i>积分</i></div>
    </div>

    <div class="body">
      <template v-if="badges.length">
        <div class="m-sec-title">徽章（{{ badges.length }}）</div>
        <div class="m-card bdg">
          <span v-for="b in badges" :key="b.id" class="bchip" :title="b.name">{{ b.icon }} {{ b.name }}</span>
        </div>
      </template>

      <div class="m-sec-title">TA 的文章（{{ articles.length }}）</div>
      <div class="m-card">
        <button v-for="d in articles" :key="d.id" class="m-row" type="button" @click="router.push('/post/' + d.id)">
          <span class="m-ico blue"><el-icon><Document /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">{{ d.title || '（无标题）' }}</span>
            <span class="m-row-desc">{{ fmtDate(d.publishedAt || d.createdAt) }}</span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
        <div v-if="!articles.length" class="m-empty"><div>还没有发布文章</div></div>
      </div>
    </div>
    <div style="height:12px"></div>
  </div>
  <div v-else class="loading">{{ loading ? '加载中…' : '用户不存在' }}</div>
</template>

<style scoped>
.up { min-height: 100%; }
.hero { position: relative; display: flex; flex-direction: column; align-items: center; padding: 28px 20px 18px;
  background: linear-gradient(160deg, #eef2ff, #f8fafc); }
html.dark .hero { background: linear-gradient(160deg, #1c2333, #17181c); }
.back { position: absolute; top: 12px; left: 12px; width: 34px; height: 34px; border-radius: 50%; border: none;
  background: rgba(0,0,0,.06); color: var(--m-text); display: grid; place-items: center; cursor: pointer; }
.av { width: 72px; height: 72px; border-radius: 50%; background: var(--m-primary-soft) center/cover; color: var(--m-primary);
  display: grid; place-items: center; font-size: 28px; font-weight: 700; box-shadow: 0 4px 14px rgba(0,0,0,.1); }
.nm { margin-top: 10px; font-size: 18px; font-weight: 700; color: var(--m-text); }
.bio { margin-top: 4px; font-size: 12.5px; color: var(--m-text-sub); text-align: center; }
.follow { margin-top: 12px; height: 34px; padding: 0 26px; border-radius: 999px; border: none; font-size: 14px; font-weight: 600;
  background: var(--m-primary); color: #fff; cursor: pointer; }
.follow.on { background: var(--m-card-2); color: var(--m-text-sub); }
.stats { display: flex; background: var(--m-card); padding: 12px 0; border-bottom: 1px solid var(--m-border); }
.st { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 2px; }
.st b { font-size: 16px; color: var(--m-text); font-variant-numeric: tabular-nums; }
.st i { font-size: 11px; font-style: normal; color: var(--m-text-dim); }
.body { padding: 0 16px; }
.bdg { display: flex; flex-wrap: wrap; gap: 8px; padding: 12px; }
.bchip { font-size: 12px; background: var(--m-card-2); border-radius: 999px; padding: 4px 10px; color: var(--m-text-sub); }
.loading { padding: 40px; text-align: center; color: var(--m-text-dim); }
</style>
