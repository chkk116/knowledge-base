<script setup>
import { ref, onMounted, watch, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../store/auth'
import { getUserCard, followUser, unfollowUser, getUserArticles, getUserFollowing, getUserFollowers, userBadges } from '../api/kb'
import { BADGE_ICONS, DEFAULT_BADGE_ICON, tierGradient } from '../utils/badgeIcons'
const g0 = (b) => tierGradient(b.tier)[0]
const g1 = (b) => tierGradient(b.tier)[1]
import { getLevel } from '../utils/level'
import WornBadgeStrip from '../components/WornBadgeStrip.vue'
import { fromNow, fmtDateTime } from '../utils/time'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const card = ref(null)
const notFound = ref(false)
const errMsg = ref('')
const activeTab = ref('articles')
const articles = ref([])
const following = ref([])
const followers = ref([])
const loading = ref(false)
const badges = ref([])

const levelInfo = computed(() => card.value ? getLevel(card.value.points || 0) : null)

// 当前佩戴的徽章 id 列表（供昵称旁展示）
const wornIds = computed(() => card.value?.wornBadges || [])

const loadCard = async (id) => {
  card.value = null
  notFound.value = false
  errMsg.value = ''
  try { card.value = (await getUserCard(id)).data }
  catch (e) {
    if (e.response?.status === 404) notFound.value = true
    else errMsg.value = e.response?.data?.error || e.response?.data?.message || '用户信息加载失败'
  }
}
const loadArticles = async (id) => {
  loading.value = true
  try { articles.value = (await getUserArticles(id, 0, 20)).data.content || [] } catch (e) { articles.value = [] } finally { loading.value = false }
}
const loadFollowing = async (id) => {
  loading.value = true
  try { following.value = (await getUserFollowing(id)).data || [] } catch (e) { following.value = [] } finally { loading.value = false }
}
const loadFollowers = async (id) => {
  loading.value = true
  try { followers.value = (await getUserFollowers(id)).data || [] } catch (e) { followers.value = [] } finally { loading.value = false }
}

const switchTab = (t) => {
  activeTab.value = t
  const id = Number(route.params.id)
  if (t === 'articles') loadArticles(id)
  else if (t === 'following') loadFollowing(id)
  else if (t === 'followers') loadFollowers(id)
}

const reload = () => {
  const id = Number(route.params.id)
  loadCard(id)
  switchTab(activeTab.value)
  loadBadges(id)
}

// 已获得徽章（访客主页展示）
async function loadBadges(id) {
  badges.value = []
  try {
    const { data } = await userBadges(id)
    badges.value = Array.isArray(data) ? data : []
  } catch (e) {
    badges.value = []
  }
}

const toggleFollow = async () => {
  if (!auth.loggedIn) { ElMessage.warning('请先登录'); router.push('/login'); return }
  const c = card.value
  if (!c) return
  if (auth.user && c.id === auth.user.id) { ElMessage.warning('不能关注自己'); return }
  try {
    if (c.isFollowing) { await unfollowUser(c.id); c.isFollowing = false; c.fans = Math.max(0, c.fans - 1) }
    else { await followUser(c.id); c.isFollowing = true; c.fans = c.fans + 1 }
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '操作失败')
  }
}

const goUser = (id) => router.push('/user/' + id)
const goPost = (id) => router.push('/post/' + id)

onMounted(reload)
// 同路由不同用户（点关注列表里的某人）切换时重载
watch(() => route.params.id, reload)
</script>

<template>
  <div v-if="notFound" class="loading">用户不存在或已被删除</div>
  <div v-else-if="errMsg" class="loading">{{ errMsg }}</div>
  <div class="up-page" v-else-if="card">
    <div class="cover">
      <div class="cover-avatar">
        <img v-if="card.avatar" :src="card.avatar" alt="" />
        <span v-else>{{ (card.displayName || card.username || '?').slice(0, 1) }}</span>
      </div>
    </div>

    <div class="body">
      <aside class="side">
        <div class="name">
          {{ card.displayName || card.username }}
          <WornBadgeStrip :badge-ids="wornIds" :size="22" />
        </div>
        <div class="username">@{{ card.username }}</div>
        <div class="role-row">
          <el-tag v-if="levelInfo" size="small" effect="plain">Lv{{ levelInfo.level }} · {{ levelInfo.title }}</el-tag>
        </div>
        <p class="bio" v-if="card.bio">{{ card.bio }}</p>
        <p class="bio muted" v-else>这个人很懒，还没有填写简介～</p>

        <ul class="meta">
          <li><span>原创</span><b>{{ card.original || 0 }}</b></li>
          <li><span>获赞</span><b>{{ card.likes || 0 }}</b></li>
          <li><span>收藏</span><b>{{ card.favorites || 0 }}</b></li>
          <li><span>积分</span><b>{{ card.points || 0 }}</b></li>
        </ul>

        <!-- 关注 / 粉丝 入口（点击切到对应 Tab） -->
        <div class="rel">
          <div class="rel-item" @click="switchTab('following')">
            <b>{{ card.following || 0 }}</b><span>关注</span>
          </div>
          <div class="rel-item" @click="switchTab('followers')">
            <b>{{ card.fans || 0 }}</b><span>粉丝</span>
          </div>
        </div>

        <el-button v-if="auth.loggedIn && auth.user && card.id !== auth.user.id"
                   class="follow-btn" :class="{ on: card.isFollowing }" @click="toggleFollow">
          {{ card.isFollowing ? '已关注' : '+ 关注' }}
        </el-button>
        <el-button v-else-if="auth.user && card.id === auth.user.id" class="follow-btn ghost" @click="router.push('/profile')">
          编辑我的资料
        </el-button>

        <!-- 已获得徽章 -->
        <div class="badge-box" v-if="badges.length">
          <div class="badge-hd">勋章 <span class="cnt">{{ badges.length }}</span></div>
          <div class="badge-strip">
            <span v-for="b in badges" :key="b.id" class="b-chip" :title="b.desc" :style="{ '--c1': g0(b), '--c2': g1(b) }">
              <span class="b-ic"><component :is="BADGE_ICONS[b.id] || DEFAULT_BADGE_ICON" /></span><span class="b-name">{{ b.name }}</span>
            </span>
          </div>
        </div>
      </aside>

      <main class="main">
        <div class="tabs">
          <button :class="{ on: activeTab === 'articles' }" @click="switchTab('articles')">文章</button>
          <button :class="{ on: activeTab === 'following' }" @click="switchTab('following')">关注的人</button>
          <button :class="{ on: activeTab === 'followers' }" @click="switchTab('followers')">粉丝</button>
        </div>

        <!-- 文章列表 -->
        <ul class="doc-list" v-if="activeTab === 'articles'">
          <li v-for="d in articles" :key="d.id" @click="goPost(d.id)">
            <div class="doc-title">{{ d.title }}</div>
            <div class="doc-meta">
              <span class="cat" v-if="d.category">{{ d.category }}</span>
              <span class="time">👁 {{ d.views || 0 }} · {{ fromNow(d.updatedAt) }}</span>
            </div>
          </li>
          <li v-if="!loading && !articles.length" class="empty">还没有发布文章</li>
        </ul>

        <!-- 关注的人 -->
        <ul class="user-list" v-else-if="activeTab === 'following'">
          <li v-for="u in following" :key="u.id" @click="goUser(u.id)">
            <img v-if="u.avatar" class="ava" :src="u.avatar" alt="" />
            <span v-else class="ava none">{{ (u.displayName || u.username || '?').slice(0, 1) }}</span>
            <div class="uinfo">
              <span class="nm">{{ u.displayName || u.username }}</span>
              <span class="ub" v-if="u.bio">{{ u.bio }}</span>
            </div>
          </li>
          <li v-if="!loading && !following.length" class="empty">还没有关注任何人</li>
        </ul>

        <!-- 粉丝 -->
        <ul class="user-list" v-else-if="activeTab === 'followers'">
          <li v-for="u in followers" :key="u.id" @click="goUser(u.id)">
            <img v-if="u.avatar" class="ava" :src="u.avatar" alt="" />
            <span v-else class="ava none">{{ (u.displayName || u.username || '?').slice(0, 1) }}</span>
            <div class="uinfo">
              <span class="nm">{{ u.displayName || u.username }}</span>
              <span class="ub" v-if="u.bio">{{ u.bio }}</span>
            </div>
          </li>
          <li v-if="!loading && !followers.length" class="empty">还没有粉丝</li>
        </ul>
      </main>
    </div>
  </div>
  <div v-else class="loading">加载中…</div>
</template>

<style scoped>
.up-page { max-width: 920px; margin: 0 auto; }
.cover {
  height: 170px; border-radius: 16px 16px 0 0;
  background: url('../assets/profile-cover.jpg') no-repeat top center / cover;
  position: relative;
}
.cover-avatar { position: absolute; left: 28px; bottom: -42px; width: 96px; height: 96px; border-radius: 50%; background: #fff; padding: 4px; box-shadow: 0 6px 20px rgba(0,0,0,.18); }
.cover-avatar img, .cover-avatar span { width: 100%; height: 100%; border-radius: 50%; object-fit: cover; display: flex; align-items: center; justify-content: center; font-size: 36px; font-weight: 700; color: #6366f1; background: #eef2ff; }
.body { display: grid; grid-template-columns: 280px 1fr; gap: 18px; margin-top: 58px; }
.side { background: #fff; border: 1px solid #eef2f7; border-radius: 16px; padding: 18px; align-self: stretch; }
.name { font-size: 19px; font-weight: 700; color: #0f172a; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.name-badge { margin-left: 2px; }
.username { color: #94a3b8; font-size: 13px; margin-top: 2px; }
.role-row { display: flex; gap: 8px; margin: 12px 0; flex-wrap: wrap; }
.bio { font-size: 13px; color: #475569; line-height: 1.6; margin: 10px 0; }
.bio.muted { color: #cbd5e1; }
.meta { list-style: none; padding: 0; margin: 12px 0; border-top: 1px dashed #eef2f7; padding-top: 12px; display: grid; grid-template-columns: 1fr 1fr; gap: 6px 12px; }
.meta li { display: flex; justify-content: space-between; font-size: 13px; color: #64748b; }
.meta li b { color: #334155; font-weight: 600; }
.rel { display: flex; gap: 10px; margin: 12px 0; }
.rel-item { flex: 1; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 12px; padding: 10px; text-align: center; cursor: pointer; }
.rel-item:hover { border-color: #c7d2fe; }
.rel-item b { display: block; font-size: 16px; color: #4f46e5; }
.rel-item span { font-size: 12px; color: #64748b; }
.follow-btn { width: 100%; margin-top: 6px; border: 1px solid #6366f1; background: #6366f1; color: #fff; border-radius: 16px; padding: 8px; cursor: pointer; font-size: 14px; }
.follow-btn.on { background: #fff; color: #64748b; border-color: #e2e8f0; }
.follow-btn.ghost { background: #f1f5f9; color: #475569; border-color: #e2e8f0; }

/* 访客主页：已获勋章 */
.badge-box { margin-top: 14px; background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 12px; }
.badge-hd { font-size: 13px; font-weight: 700; color: #334155; margin-bottom: 8px; }
.badge-hd .cnt { color: #94a3b8; font-weight: 600; margin-left: 4px; }
.badge-strip { display: flex; flex-wrap: wrap; gap: 8px; }
.b-chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 10px 4px 5px; border-radius: 999px;
  background: color-mix(in srgb, var(--c1, #6366f1) 12%, #fff);
  border: 1px solid color-mix(in srgb, var(--c1, #6366f1) 36%, #fff);
}
.b-ic {
  width: 20px; height: 20px; border-radius: 50%; flex: none;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, var(--c1, #6366f1), var(--c2, #8b5cf6));
  color: #fff;
}
.b-ic :deep(svg) { width: 12px; height: 12px; }
.b-chip .b-name { font-size: 12px; font-weight: 700; color: #334155; }

.main { background: #fff; border: 1px solid #eef2f7; border-radius: 16px; padding: 18px 20px; min-height: 360px; }
.tabs { display: flex; gap: 6px; border-bottom: 1px solid #eef2f7; margin-bottom: 14px; }
.tabs button { border: none; background: none; padding: 10px 14px; cursor: pointer; font-size: 15px; color: #64748b; border-bottom: 2px solid transparent; }
.tabs button.on { color: #4f46e5; font-weight: 700; border-bottom-color: #4f46e5; }

.doc-list { list-style: none; padding: 0; margin: 0; }
.doc-list li { padding: 12px 4px; border-bottom: 1px solid #f1f5f9; cursor: pointer; }
.doc-list li:last-child { border-bottom: none; }
.doc-list li:hover { background: #fafbff; }
.doc-title { font-size: 15px; font-weight: 600; color: #1e293b; }
.doc-meta { display: flex; gap: 10px; margin-top: 6px; align-items: center; }
.cat { font-size: 12px; color: #6366f1; background: #eef2ff; padding: 1px 8px; border-radius: 6px; }
.time { color: #94a3b8; font-size: 12px; }

.user-list { list-style: none; padding: 0; margin: 0; }
.user-list li { display: flex; align-items: center; gap: 12px; padding: 12px 4px; border-bottom: 1px solid #f1f5f9; cursor: pointer; }
.user-list li:last-child { border-bottom: none; }
.user-list li:hover { background: #fafbff; }
.ava { width: 44px; height: 44px; border-radius: 50%; object-fit: cover; background: #e2e8f0; flex: none; }
.ava.none { display: flex; align-items: center; justify-content: center; color: #64748b; font-size: 18px; font-weight: 700; }
.uinfo { display: flex; flex-direction: column; min-width: 0; }
.uinfo .nm { font-weight: 600; color: #1e293b; font-size: 14px; }
.uinfo .ub { color: #94a3b8; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.empty { color: #94a3b8; font-size: 14px; padding: 20px 0; text-align: center; }
.loading { max-width: 920px; margin: 40px auto; text-align: center; color: #94a3b8; }

/* 暗色模式适配（侧栏 / 主栏为自定义白底卡片，需整体换成暗底 + Element 文字/边框变量；el-tag、el-button 由 Element 自动处理） */
html.dark .cover-avatar { background: var(--el-bg-color-overlay); box-shadow: 0 6px 20px rgba(0,0,0,.5); }
html.dark .cover-avatar img, html.dark .cover-avatar span { background: rgba(99,102,241,.18); color: #a5b4fc; }
html.dark .side { background: var(--el-bg-color-overlay); border-color: var(--el-border-color-lighter); }
html.dark .name { color: var(--el-text-color-primary); }
html.dark .username { color: var(--el-text-color-secondary); }
html.dark .bio { color: var(--el-text-color-regular); }
html.dark .bio.muted { color: var(--el-text-color-placeholder); }
html.dark .meta { border-top-color: var(--el-border-color-lighter); }
html.dark .meta li { color: var(--el-text-color-secondary); }
html.dark .meta li b { color: var(--el-text-color-primary); }
html.dark .rel-item { background: var(--el-fill-color); border-color: var(--el-border-color-lighter); }
html.dark .rel-item:hover { border-color: rgba(129,140,248,.5); }
html.dark .rel-item b { color: #a5b4fc; }
html.dark .rel-item span { color: var(--el-text-color-secondary); }
html.dark .follow-btn.on { background: var(--el-fill-color); color: var(--el-text-color-secondary); border-color: var(--el-border-color); }
html.dark .follow-btn.ghost { background: var(--el-fill-color); color: var(--el-text-color-regular); border-color: var(--el-border-color); }
html.dark .badge-box { background: #1b1b26; border-color: #2c2c3a; }
html.dark .badge-hd { color: #e8e8f0; }
html.dark .b-chip { background: color-mix(in srgb, var(--c1, #6366f1) 18%, #1b1b26); border-color: color-mix(in srgb, var(--c1, #6366f1) 45%, #2c2c3a); }
html.dark .b-chip .b-name { color: #cbd5e1; }
html.dark .main { background: var(--el-bg-color-overlay); border-color: var(--el-border-color-lighter); }
html.dark .tabs { border-bottom-color: var(--el-border-color-lighter); }
html.dark .tabs button { color: var(--el-text-color-secondary); }
html.dark .tabs button.on { color: #a5b4fc; border-bottom-color: #818cf8; }
html.dark .doc-list li { border-bottom-color: var(--el-border-color-lighter); }
html.dark .doc-list li:hover { background: var(--el-fill-color-light); }
html.dark .doc-title { color: var(--el-text-color-primary); }
html.dark .cat { background: rgba(99,102,241,.18); color: #a5b4fc; }
html.dark .time { color: var(--el-text-color-secondary); }
html.dark .user-list li { border-bottom-color: var(--el-border-color-lighter); }
html.dark .user-list li:hover { background: var(--el-fill-color-light); }
html.dark .ava { background: var(--el-fill-color); }
html.dark .ava.none { color: var(--el-text-color-secondary); }
html.dark .uinfo .nm { color: var(--el-text-color-primary); }
html.dark .uinfo .ub { color: var(--el-text-color-secondary); }
html.dark .empty { color: var(--el-text-color-secondary); }
html.dark .loading { color: var(--el-text-color-secondary); }

/* ============================================================
   响应式：≤1024 单列（文章 / 关注 / 粉丝在上，资料侧栏移到下方）
          ≤ 640 手机：头像居中、内边距与字号收紧
   ============================================================ */
@media (max-width: 1024px) {
  .body { grid-template-columns: 1fr; margin-top: 54px; }
  .main { order: 1; }
  .side { order: 2; }
  /* Tab 三个词在窄屏一行放不下时允许换行 */
  .tabs { flex-wrap: wrap; }
}

@media (max-width: 640px) {
  .cover { height: 130px; }
  .cover-avatar { left: 50%; transform: translateX(-50%); width: 76px; height: 76px; }
  .body { margin-top: 46px; gap: 14px; }
  .side { padding: 14px; }
  .main { padding: 14px; }
  .name { font-size: 17px; }
  .tabs button { padding: 8px 10px; font-size: 14px; }
  .doc-title { font-size: 14px; }
  .ava { width: 38px; height: 38px; }
  .user-list li { gap: 10px; }
}
</style>
