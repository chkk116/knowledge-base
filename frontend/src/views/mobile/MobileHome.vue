<script setup>
/**
 * 移动端首页
 *
 * 布局思路（参照主流 App 的「首屏 = 问候 + 快捷入口 + 最近内容」）：
 *   1. 顶部问候区（大标题 + 头像），带品牌渐变背景，视觉上先抓住注意力
 *   2. 四宫格快捷入口（拍照建文档 / 我的文档 / AI 问答 / 搜索）—— 大图标大触控区
 *   3. 「最近文档」横向卡片（继续上次的阅读）
 *   4. 「文档库」纵向列表（最近更新）
 *   5. 离线草稿区块（有本地未同步内容时置顶提示）
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../store/auth'
import { listMyShares, listShareDocs } from '../../api/docShare'
import { homeOverview, listAnnouncements, listDocs } from '../../api/kb'
import { getStats } from '../../utils/readStats'
import { listQueuedDocs, listCachedDocs } from '../../utils/offlineStore'
import { studyPlanSummary } from '../../api/studyPlan'
import { flashcardSummary } from '../../api/flashcard'
import { initPlanNotifications } from '../../utils/planNotify'
import { resolveDefaultSpaceId, mySpaces } from '../../utils/spaceResolver'
import { resolveUrl } from '../../utils/runtimeBase'
import { pickRecall, addSkip, docKey, daysAgo } from '../../utils/dailyRecall'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(true)
const recent = ref([])          // [{ spaceId, spaceName, doc }]
const spaces = ref([])
const offlineDocs = ref([])
const fromCache = ref(false)   // 最近文档是否来自本地缓存（离线提示用）
const spaceId = ref(null)
const overview = ref(null)        // 平台概览（/docs/overview）
const announcements = ref([])     // 公告
const picks = ref([])             // 精选文章（公开文章，Web 首页那批）
const stats = ref(getStats())     // 本机阅读统计
const planDue = ref(0)            // 到复习时间的学习计划数（首页提示）
const cardDue = ref(0)            // 今天到期的复习卡片数（首页提示 + 金刚区角标）

/** 每日回顾：候选池（最久没更新的几篇）+ 当前光标 */
const recall = ref(null)          // { candidates, cursor }
const recallItem = computed(() => {
  const r = recall.value
  return r && r.candidates && r.candidates.length ? r.candidates[r.cursor % r.candidates.length] : null
})
const recallAge = computed(() => daysAgo(recallItem.value?.doc))

/** 数据源是各空间拉回来的全部文档（不是「最近 6 篇」那批，否则挑来挑去都是新的） */
function loadRecall(all) {
  const r = pickRecall(all)
  if (!r) { recall.value = null; return }
  const idx = Math.max(0, r.candidates.findIndex((x) => x === r.item))
  recall.value = { candidates: r.candidates, cursor: idx }
}
function shuffleRecall() {
  if (recall.value && recall.value.candidates.length > 1) recall.value.cursor = (recall.value.cursor + 1) % recall.value.candidates.length
}
function dismissRecall() {
  const it = recallItem.value
  if (!it) return
  addSkip(docKey(it.doc))
  // 从候选池里直接剔掉，然后按原规则重新挑一篇
  const left = recall.value.candidates.filter((x) => x !== it)
  if (!left.length) { recall.value = null; return }
  recall.value = { candidates: left, cursor: recall.value.cursor % left.length }
}

const greet = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

const name = computed(() => auth.user?.displayName || auth.user?.username || '朋友')

function fmtTime(s) {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  const m = 60000, hh = 3600000, dd = 86400000
  if (diff < hh) return Math.max(1, Math.floor(diff / m)) + ' 分钟前'
  if (diff < dd) return Math.floor(diff / hh) + ' 小时前'
  if (diff < dd * 7) return Math.floor(diff / dd) + ' 天前'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

// 金刚区入口（新增功能一律在这里露出，避免"加了但找不到"）
// 扫一扫放首位：资产标签二维码扫完直接进资产详情，其余资产功能都从这里进
const QUICKS = [
  { label: '扫一扫', icon: 'Aim', color: 'purple', to: '/asset?scan=1' },
  { label: '拍照建文档', icon: 'Camera', color: 'grad', to: '/capture' },
  { label: '速记', icon: 'EditPen', color: 'cyan', to: '/quick' },
  { label: '网页剪藏', icon: 'Link', color: 'blue', to: '/clip' },
  { label: '复习卡片', icon: 'Postcard', color: 'orange', to: '/cards' },
  { label: '我的文档', icon: 'FolderOpened', color: 'blue', to: '/docs' },
  { label: 'AI 问答', icon: 'MagicStick', color: 'cyan', to: '/ai' },
  { label: '发现文章', icon: 'Compass', color: 'green', to: '/discover' },
  { label: '搜索', icon: 'Search', color: 'gray', to: '/search' },
  { label: '我的收藏', icon: 'Star', color: 'orange', to: '/favorites' },
  { label: '课程', icon: 'Notebook', color: 'blue', to: '/courses' },
  { label: '考试', icon: 'Trophy', color: 'green', to: '/exams' },
  { label: '离线内容', icon: 'Cloudy', color: 'purple', to: '/offline' },
  { label: '我的数据', icon: 'TrendCharts', color: 'red', to: '/stats' },
  { label: '学习计划', icon: 'Calendar', color: 'purple', to: '/plans' },
]

function plain(html) {
  return String(html || '').replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim()
}

/** 金刚区角标：目前只有「复习卡片」有动态数字，其余仍走 q.badge */
function badgeOf(q) {
  if (q.to === '/cards' && cardDue.value > 0) return cardDue.value > 99 ? '99+' : cardDue.value
  return q.badge?.value || ''
}

/** 公告：弹层看全文（移动端没有独立公告页） */
function openAnn(a) {
  if (!a) return
  window.__kbMsgBox?.alert
    ? window.__kbMsgBox.alert(plain(a.content) || '（无正文）', a.title || '公告', { confirmButtonText: '知道了' })
    : window.__kbMsg?.info(a.title || '公告')
}

let homeLoadPending = false
// ⚠️ 并发守卫必须用**独立的在途标志**，不能用 loading：loading 初值就是 true（首屏骨架屏），
// 拿它判断会把「首次 load」直接吞掉 → 首页永远停在骨架屏（外部审查 batch 引入的回归）。
let loadingNow = false
async function load() {
  // 并发守卫（外部审查 2026-10-11）：online 事件与首屏加载并发时，两轮 load 同时写 refs
  // 互相踩踏。加载中则记一次「待重跑」，本轮结束后补跑一次。
  if (loadingNow) { homeLoadPending = true; return }
  loadingNow = true
  loading.value = true
  // 概览 / 公告 / 精选文章：独立拉取，任一失败都不影响主页其它区块
  homeOverview().then((r) => { overview.value = r.data }).catch(() => {})
  listAnnouncements().then((r) => { announcements.value = (r.data || []).filter((a) => a.enabled !== false) }).catch(() => {})
  listDocs(0, 3, 'hot').then((r) => { picks.value = r.data?.content || [] }).catch(() => {})
  stats.value = getStats()
  studyPlanSummary().then((r) => { planDue.value = r.data?.due || 0 }).catch(() => {})
  flashcardSummary().then((r) => { cardDue.value = r.data?.due || 0 }).catch(() => {})
  try {
    const [list, q] = await Promise.all([
      mySpaces(true).catch(() => []),
      listQueuedDocs().catch(() => [])
    ])
    spaces.value = list
    offlineDocs.value = q
    spaceId.value = await resolveDefaultSpaceId().catch(() => null)

    // 聚合各空间最近 3 篇文档，取最新 6 篇做「最近文档」
    const buckets = await Promise.all(
      list.slice(0, 4).map(async (s) => {
        try {
          const { data } = await listShareDocs(s.id)
          return (data || []).map((d) => ({ spaceId: s.id, spaceName: s.name, doc: d }))
        } catch { return [] }
      })
    )
    const all = buckets.flat()
    recent.value = all
      .sort((a, b) => new Date(b.doc.updatedAt || b.doc.createdAt || 0) - new Date(a.doc.updatedAt || a.doc.createdAt || 0))
      .slice(0, 6)
    // 每日回顾要用**全部**文档做候选池，不能复用「最近 6 篇」，否则挑出来还是刚看过的
    loadRecall(all)

    // 离线（或接口拿不到数据）时回退到本地缓存，保证首页不是空的
    if (!recent.value.length) {
      const cached = await listCachedDocs().catch(() => [])
      recent.value = cached.slice(0, 6).map((c) => ({
        spaceId: c.spaceId, spaceName: c.spaceName || '离线缓存',
        doc: { id: c.docId, title: c.title, updatedAt: c.cachedAt }
      }))
      fromCache.value = recent.value.length > 0
    } else {
      fromCache.value = false
    }
  } finally {
    loadingNow = false
    loading.value = false
    if (homeLoadPending) { homeLoadPending = false; load() }
  }
}

function openDoc(item) {
  router.push(`/doc/${item.spaceId}/${item.doc.id}`)
}
function openSpace(id) { router.push(`/space/${id}`) }
function openDraft(localId) { router.push(`/draft/${localId}`) }

function onBackOnline() { load() }
onMounted(() => {
  load()
  // 点系统通知栏提醒 → 跳到对应学习计划（原生容器内才生效，网页端静默跳过）
  initPlanNotifications()
  // 联网后自动刷新一次（离线期间读的是缓存，恢复网络要及时拉最新）
  window.addEventListener('online', onBackOnline)
})
onUnmounted(() => window.removeEventListener('online', onBackOnline))
</script>

<template>
  <div class="home">
    <!-- 问候区：渐变头图 + 头像，移动端 App 常见的「氛围式」首屏 -->
    <header class="hero">
      <div class="hero-top">
        <div class="hero-txt">
          <div class="hero-greet">{{ greet }}</div>
          <div class="hero-name">{{ name }}</div>
        </div>
        <button class="hero-avatar" type="button" @click="router.push('/profile')">
          <img v-if="auth.user?.avatar" :src="resolveUrl(auth.user.avatar)" alt="" />
          <span v-else>{{ name.charAt(0) }}</span>
        </button>
      </div>

      <!-- 搜索条 + AI 入口：首屏不放真实输入框（避免键盘顶起布局），点进独立搜索页；
           右侧单独给 AI 问答一个按钮——它是高频功能，不该埋在二级页里 -->
      <div class="hero-search">
        <button class="searchbar" type="button" @click="router.push('/search')">
          <el-icon><Search /></el-icon>
          <span>搜索文档、标签、内容</span>
        </button>
        <button class="ai-btn" type="button" title="AI 问答" @click="router.push('/ai')">
          <el-icon :size="20"><MagicStick /></el-icon>
        </button>
      </div>
    </header>

    <!-- 离线草稿提示 -->
    <div v-if="offlineDocs.length" class="body">
      <button class="offline-card" type="button" @click="openDraft(offlineDocs[0].localId)">
        <span class="m-ico orange"><el-icon><Upload /></el-icon></span>
        <span class="oc-body">
          <span class="oc-title">{{ offlineDocs.length }} 篇文档待同步</span>
          <span class="oc-desc">联网后会自动上传到「我的文档」</span>
        </span>
        <el-icon class="oc-arrow"><Right /></el-icon>
      </button>
    </div>

    <!-- 今天有卡片要复习：直接给入口，不用进二级页 -->
    <div v-if="cardDue" class="body">
      <button class="offline-card plan-card" type="button" @click="router.push('/cards')">
        <span class="m-ico orange"><el-icon><Postcard /></el-icon></span>
        <span class="oc-body">
          <span class="oc-title">{{ cardDue }} 张卡片待复习</span>
          <span class="oc-desc">花两分钟过一遍，别让记过的忘掉</span>
        </span>
        <el-icon class="oc-arrow"><Right /></el-icon>
      </button>
    </div>

    <!-- 学习计划到复习时间：首页直接提示，不用去二级页找 -->
    <div v-if="planDue" class="body">
      <button class="offline-card plan-card" type="button" @click="router.push('/plans')">
        <span class="m-ico green"><el-icon><Calendar /></el-icon></span>
        <span class="oc-body">
          <span class="oc-title">{{ planDue }} 个学习计划待复习</span>
          <span class="oc-desc">点进去看看今天的复习内容</span>
        </span>
        <el-icon class="oc-arrow"><Right /></el-icon>
      </button>
    </div>

    <!-- 金刚区：8 个功能入口（新增的功能都放在这里，不用去二级页找） -->
    <div class="body">
      <div class="quicks">
        <button v-for="q in QUICKS" :key="q.label" class="quick" type="button" @click="router.push(q.to)">
          <span class="q-ico" :class="q.color"><el-icon :size="23"><component :is="q.icon" /></el-icon></span>
          <span class="q-txt">{{ q.label }}</span>
          <i v-if="badgeOf(q)" class="q-badge">{{ badgeOf(q) }}</i>
        </button>
      </div>
    </div>

    <!-- 数据概览：平台数据 + 本机阅读统计，点进「我的数据」 -->
    <div class="body">
      <button class="ovw" type="button" @click="router.push('/stats')">
        <span class="ovw-item"><span class="ovw-num">{{ overview?.totalDocs ?? '—' }}</span><span class="ovw-lbl">文档</span></span>
        <i></i>
        <span class="ovw-item"><span class="ovw-num">{{ overview?.weekNew ?? '—' }}</span><span class="ovw-lbl">本周新增</span></span>
        <i></i>
        <span class="ovw-item"><span class="ovw-num">{{ stats.streak }}</span><span class="ovw-lbl">连续天数</span></span>
        <i></i>
        <span class="ovw-item"><span class="ovw-num">{{ stats.weekRead }}</span><span class="ovw-lbl">本周阅读</span></span>
      </button>
    </div>

    <!-- 公告 -->
    <div v-if="announcements.length" class="body">
      <button class="ann" type="button" @click="openAnn(announcements[0])">
        <span class="ann-ico"><el-icon><Bell /></el-icon></span>
        <span class="ann-body">
          <span class="ann-t">{{ announcements[0].title || '公告' }}</span>
          <span class="ann-c">{{ plain(announcements[0].content) }}</span>
        </span>
        <el-icon class="ann-arrow"><Right /></el-icon>
      </button>
    </div>

    <!-- 每日回顾：从最久没更新的文档里挑一篇，让「写过就忘」的内容重新被看见。
         只在非骨架屏时出现，首屏加载失败也不影响其它区块。 -->
    <div v-if="!loading && recallItem" class="body">
      <div class="m-sec-title">每日回顾
        <button class="more" type="button" @click="shuffleRecall">换一篇</button>
      </div>
      <div class="m-card recall">
        <div class="rc-meta">
          <span class="rc-space">{{ recallItem.spaceName || '文档空间' }}</span>
          <span class="rc-dot">·</span>
          <span class="rc-age">{{ recallAge }}没更新</span>
        </div>
        <div class="rc-title">{{ recallItem.doc.title || '未命名文档' }}</div>
        <div class="rc-tip">翻一翻？它已经躺了 {{ recallAge }}了</div>
        <div class="rc-acts">
          <button class="rc-btn primary" type="button" @click="openDoc(recallItem)">打开这篇</button>
          <button class="rc-btn" type="button" @click="dismissRecall">不再推荐</button>
        </div>
      </div>
    </div>

    <!-- 最近文档：横向滑动卡片 -->
    <div v-if="loading" class="body">
      <div class="m-sec-title">最近文档</div>
      <div class="hscroll">
        <div v-for="i in 3" :key="i" class="hcard"><div class="m-skel" style="height:100%"></div></div>
      </div>
    </div>

    <div v-else-if="recent.length" class="body flush-l">
      <div class="m-sec-title pad">最近文档
        <span v-if="fromCache" class="cache-tag">离线缓存</span>
        <button class="more" @click="router.push('/docs')">全部</button></div>
      <div class="hscroll pad">
        <button v-for="(it, i) in recent" :key="i" class="hcard" type="button" @click="openDoc(it)">
          <span class="hc-tag">{{ it.spaceName }}</span>
          <span class="hc-title">{{ it.doc.title || '未命名文档' }}</span>
          <span class="hc-time">{{ fmtTime(it.doc.updatedAt || it.doc.createdAt) }}</span>
        </button>
      </div>
    </div>

    <!-- 我的文档库 -->
    <div class="body">
      <div class="m-sec-title">
        我的文档库
        <button class="more" @click="router.push('/docs')">全部</button>
      </div>
      <div v-if="loading" class="m-card">
        <div v-for="i in 3" :key="i" style="padding:14px">
          <div class="m-skel" style="height:18px;width:60%"></div>
          <div class="m-skel" style="height:1px;margin-top:12px"></div>
        </div>
      </div>
      <div v-else-if="spaces.length" class="m-card">
        <button v-for="s in spaces.slice(0, 5)" :key="s.id" class="m-row" type="button" @click="openSpace(s.id)">
          <span class="m-ico" :class="s.type === 'PERSONAL' ? '' : 'blue'">
            <el-icon><component :is="s.type === 'PERSONAL' ? 'User' : 'DocumentCopy'" /></el-icon>
          </span>
          <span class="m-row-body">
            <span class="m-row-title">{{ s.name }}</span>
            <span class="m-row-desc">
              {{ s.type === 'PERSONAL' ? '个人空间' : '团队空间' }}{{ s.memberCount ? ' · ' + s.memberCount + ' 人' : '' }}
            </span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>
      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><FolderOpened /></el-icon></span>
          <div>还没有文档空间</div>
          <button class="m-btn sm" style="margin-top:4px" @click="router.push('/capture')">拍照建第一篇</button>
        </div>
      </div>
    </div>

    <!-- 精选文章：Web 端首页那批公开文章，App 里从这里进 -->
    <div v-if="picks.length" class="body">
      <div class="m-sec-title">
        精选文章
        <button class="more" @click="router.push('/discover')">发现更多</button>
      </div>
      <div class="m-card">
        <button v-for="pd in picks" :key="pd.id" class="m-row" type="button" @click="router.push('/post/' + pd.id)">
          <span class="m-ico blue"><el-icon><Document /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">{{ pd.title || '未命名文档' }}</span>
            <span class="m-row-desc">{{ pd.ownerName || '匿名' }} · {{ pd.views || 0 }} 次浏览 · {{ pd.likes || 0 }} 赞</span>
          </span>
          <el-icon class="m-row-extra"><Right /></el-icon>
        </button>
      </div>
    </div>

    <div class="foot-space"></div>
  </div>
</template>

<style scoped>
.home { min-height: 100%; padding-bottom: 8px; }
.body { padding: 0 16px; }
.body.flush-l { padding-left: 0; padding-right: 0; }
.hscroll.pad, .m-sec-title.pad { padding-left: 16px; padding-right: 16px; }

/* ---------- 顶部问候区 ---------- */
.hero {
  padding: calc(env(safe-area-inset-top) + 14px) 16px 18px;
  background: var(--m-primary);
  border-radius: 0 0 24px 24px;
  color: #fff;
}
html.dark .hero { background: var(--m-primary-deep); }
.hero-top { display: flex; align-items: center; gap: 12px; }
.hero-txt { flex: 1 1 auto; min-width: 0; }
.hero-greet { font-size: 13px; opacity: .82; }
.hero-name { font-size: 22px; font-weight: 700; margin-top: 1px; letter-spacing: -.3px; }
.hero-avatar {
  flex: none; width: 44px; height: 44px; border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, .5); background: rgba(255, 255, 255, .2);
  color: #fff; font-size: 18px; font-weight: 600;
  display: inline-flex; align-items: center; justify-content: center;
  overflow: hidden; cursor: pointer; padding: 0;
}
.hero-avatar img { width: 100%; height: 100%; object-fit: cover; }

.hero-search { margin-top: 14px; display: flex; align-items: center; gap: 8px; }
.searchbar {
  flex: 1 1 auto; min-width: 0; height: 42px;
  border: none; border-radius: 12px;
  background: rgba(255, 255, 255, .2);
  color: rgba(255, 255, 255, .88);
  display: flex; align-items: center; gap: 8px;
  padding: 0 14px; font-size: 14px; cursor: pointer;
  backdrop-filter: blur(6px);
}
.searchbar:active { background: rgba(255, 255, 255, .28); }
/* AI 问答：与搜索条同高的方形按钮，半透明白底衬托在渐变头上 */
.ai-btn {
  flex: none; width: 42px; height: 42px; padding: 0;
  border: none; border-radius: 12px;
  background: rgba(255, 255, 255, .2);
  color: #fff;
  display: inline-flex; align-items: center; justify-content: center;
  cursor: pointer; backdrop-filter: blur(6px);
}
.ai-btn:active { background: rgba(255, 255, 255, .34); }

/* ---------- 离线卡片 ---------- */
.offline-card {
  width: 100%; margin-top: 12px;
  border: none; border-radius: var(--m-radius);
  background: #fff7ed; color: #9a3412;
  display: flex; align-items: center; gap: 12px;
  padding: 12px 14px; cursor: pointer; text-align: left;
  box-shadow: var(--m-shadow);
}
/* 学习计划待复习卡：与离线卡区分配色（绿色=成长/学习） */
.plan-card { background: #ecfdf5; color: #065f46; }
html.dark .plan-card { background: #0e2e26; color: #6ee7b7; }
html.dark .offline-card { background: #3b2411; color: #fdba74; }
.oc-body { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; }
.oc-title { font-size: 14.5px; font-weight: 600; }
.oc-desc { font-size: 12px; opacity: .82; margin-top: 1px; }
.oc-arrow { flex: none; opacity: .6; }

/* ---------- 四宫格 ---------- */
.quicks {
  display: grid; grid-template-columns: repeat(4, 1fr);
  gap: 10px; margin-top: 16px;
}
.quick {
  border: none; background: transparent; padding: 0;
  display: flex; flex-direction: column; align-items: center; gap: 7px;
  cursor: pointer;
}
.q-ico {
  width: 52px; height: 52px; border-radius: 16px;
  display: inline-flex; align-items: center; justify-content: center;
  transition: transform .14s;
}
.quick:active .q-ico { transform: scale(.92); }
/* 大厂做法：快捷入口用「浅色底 + 品牌色图标」，不用渐变实心块 */
.q-ico.grad { background: var(--m-primary-soft); color: var(--m-primary); box-shadow: none; }
.q-ico.blue { background: #eff8ff; color: #1570ef; }
.q-ico.orange { background: #fff7ed; color: #ea580c; }
.q-ico.green { background: #ecfdf5; color: #059669; }
.q-ico.cyan { background: #ecfeff; color: #0891b2; }
html.dark .q-ico.blue { background: #12253d; color: #60a5fa; }
html.dark .q-ico.orange { background: #3a2411; color: #fb923c; }
html.dark .q-ico.green { background: #0e2e26; color: #34d399; }
html.dark .q-ico.cyan { background: #0e2e33; color: #67e8f9; }
.q-ico.gray { background: #f2f4f7; color: #667085; }
.q-ico.purple { background: #f4f1ff; color: #7c5cff; }
.q-ico.red { background: #fef2f2; color: #dc2626; }
html.dark .q-ico.gray { background: #24262b; color: #98a2b3; }
html.dark .q-ico.purple { background: #241f3d; color: #a78bfa; }
html.dark .q-ico.red { background: #3a1c1c; color: #f87171; }
.q-txt { font-size: 12px; color: var(--m-text-sub); line-height: 1.2; }
.quick { position: relative; }
.q-badge {
  position: absolute; top: -4px; right: 50%; margin-right: -30px;
  min-width: 16px; height: 16px; padding: 0 4px; border-radius: 8px;
  background: #ef4444; color: #fff; font-size: 10px; font-style: normal;
  display: inline-flex; align-items: center; justify-content: center; font-weight: 600;
}

/* ---------- 数据概览 ---------- */
.ovw {
  width: 100%; margin-top: 14px; padding: 13px 6px;
  border: none; border-radius: var(--m-radius); background: var(--m-card);
  box-shadow: var(--m-shadow); cursor: pointer;
  display: flex; align-items: center; justify-content: space-around;
}
.ovw:active { background: var(--m-card-2); }
.ovw-item { flex: 1 1 0; display: flex; flex-direction: column; align-items: center; gap: 2px; }
.ovw-num { font-size: 17px; font-weight: 700; color: var(--m-text); font-variant-numeric: tabular-nums; }
.ovw-lbl { font-size: 11px; color: var(--m-text-dim); }
.ovw i { width: 1px; height: 20px; background: var(--m-border); }

/* ---------- 公告 ---------- */
.ann {
  width: 100%; margin-top: 10px; padding: 11px 13px;
  border: none; border-radius: var(--m-radius);
  background: var(--m-primary-soft); color: var(--m-text);
  display: flex; align-items: center; gap: 10px; text-align: left; cursor: pointer;
}
.ann-ico { flex: none; color: var(--m-primary); display: inline-flex; }
.ann-body { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.ann-t { font-size: 13.5px; font-weight: 600; color: var(--m-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ann-c {
  font-size: 12px; color: var(--m-text-sub);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.ann-arrow { flex: none; color: var(--m-text-dim); }

/* ---------- 横向卡片 ---------- */
.hscroll {
  display: flex; gap: 10px; overflow-x: auto; padding-bottom: 4px;
  scroll-snap-type: x proximity;
}
.hcard {
  flex: none; width: 168px; height: 104px;
  border: none; border-radius: var(--m-radius);
  background: var(--m-card); box-shadow: var(--m-shadow);
  padding: 13px; text-align: left; cursor: pointer;
  display: flex; flex-direction: column; gap: 4px;
  scroll-snap-align: start;
  overflow: hidden;
}
.hcard:active { transform: scale(.98); }
.hc-tag {
  font-size: 10.5px; font-weight: 600; color: var(--m-primary);
  background: var(--m-primary-soft); border-radius: 6px;
  padding: 1px 6px; align-self: flex-start;
  max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.hc-title {
  font-size: 14px; font-weight: 600; color: var(--m-text); line-height: 1.35;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
  flex: 1 1 auto;
}
.hc-time { font-size: 11.5px; color: var(--m-text-dim); }

.foot-space { height: 12px; }
</style>
