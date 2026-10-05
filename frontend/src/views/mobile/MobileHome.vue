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
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../store/auth'
import { listMyShares, listShareDocs } from '../../api/docShare'
import { listQueuedDocs } from '../../utils/offlineStore'
import { resolveDefaultSpaceId, mySpaces } from '../../utils/spaceResolver'
import { resolveUrl } from '../../utils/runtimeBase'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(true)
const recent = ref([])          // [{ spaceId, spaceName, doc }]
const spaces = ref([])
const offlineDocs = ref([])
const spaceId = ref(null)

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

async function load() {
  loading.value = true
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
    recent.value = buckets
      .flat()
      .sort((a, b) => new Date(b.doc.updatedAt || b.doc.createdAt || 0) - new Date(a.doc.updatedAt || a.doc.createdAt || 0))
      .slice(0, 6)
  } finally {
    loading.value = false
  }
}

function openDoc(item) {
  router.push(`/doc/${item.spaceId}/${item.doc.id}`)
}
function openSpace(id) { router.push(`/space/${id}`) }
function openDraft(localId) { router.push(`/draft/${localId}`) }

onMounted(load)
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
        <button class="ai-btn" type="button" title="AI 问答" @click="router.push('/embed/ai')">
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

    <!-- 四宫格快捷入口 -->
    <div class="body">
      <div class="quicks">
        <button class="quick" type="button" @click="router.push('/capture')">
          <span class="q-ico grad"><el-icon :size="23"><Camera /></el-icon></span>
          <span class="q-txt">拍照建文档</span>
        </button>
        <button class="quick" type="button" @click="router.push('/docs')">
          <span class="q-ico blue"><el-icon :size="23"><FolderOpened /></el-icon></span>
          <span class="q-txt">我的文档</span>
        </button>
        <button class="quick" type="button" @click="router.push('/embed/ai')">
          <span class="q-ico purple"><el-icon :size="23"><MagicStick /></el-icon></span>
          <span class="q-txt">AI 问答</span>
        </button>
        <button class="quick" type="button" @click="router.push('/search')">
          <span class="q-ico green"><el-icon :size="23"><Search /></el-icon></span>
          <span class="q-txt">搜索</span>
        </button>
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
      <div class="m-sec-title pad">最近文档 <button class="more" @click="router.push('/docs')">全部</button></div>
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
  background: linear-gradient(150deg, #6366f1 0%, #4f46e5 48%, #7c3aed 100%);
  border-radius: 0 0 24px 24px;
  color: #fff;
}
html.dark .hero { background: linear-gradient(150deg, #312e81 0%, #3730a3 50%, #4c1d95 100%); }
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
.q-ico.grad { background: linear-gradient(135deg, #6366f1, #4f46e5); color: #fff; box-shadow: 0 5px 14px rgba(79, 70, 229, .3); }
.q-ico.blue { background: #eff8ff; color: #1570ef; }
.q-ico.orange { background: #fff7ed; color: #ea580c; }
.q-ico.green { background: #ecfdf5; color: #059669; }
.q-ico.purple { background: #f5f3ff; color: #7c3aed; }
html.dark .q-ico.blue { background: #12253d; color: #60a5fa; }
html.dark .q-ico.orange { background: #3a2411; color: #fb923c; }
html.dark .q-ico.green { background: #0e2e26; color: #34d399; }
html.dark .q-ico.purple { background: #2a1f4d; color: #c4b5fd; }
.q-txt { font-size: 12px; color: var(--m-text-sub); line-height: 1.2; }

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
