<script setup>
/**
 * 移动端章节学习页：渲染章节内容（HTML）+ 标记完成 + 上/下一章
 * 章节内容随课程返回，这里取 getCourse 后按 chapterId 定位（training.js 无单独章节接口）
 */
import { onMounted, ref, computed, nextTick, onUnmounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCourse, finishChapter, courseProgress, resetChapter } from '../../api/training'

const route = useRoute()
const router = useRouter()
const courseId = route.params.id
const chapterId = route.params.chId
const loading = ref(true)
const course = ref(null)
const chapter = ref(null)
const idx = ref(-1)
const finishing = ref(false)
const finished = ref(false)

const chapters = computed(() => (course.value && course.value.chapters) || [])

// ---------- 完成门槛（学习通式）：长文必须滑到底，短文强制停留 ----------
const rootEl = ref(null)
const dwell = ref(0)              // 已停留秒数
const reachedBottom = ref(false)  // 是否已滑到章节末尾
const needsScroll = ref(false)    // 内容是否长到需要滚动
let tickTimer = null
let scrollEl = null

/** 正文纯文本字数（用来估「该看多久」） */
const textLen = computed(() => String(chapter.value?.content || '').replace(/<[^>]*>/g, '').length)
/** 最短停留秒数：按字数放宽，8~90 秒封顶，避免长章节一进来就能点完成 */
const minSeconds = computed(() => Math.min(90, Math.max(8, Math.ceil(textLen.value / 120))))
const dwellLeft = computed(() => Math.max(0, dwell.value >= minSeconds.value ? 0 : minSeconds.value - dwell.value))
/** 能不能点「标记完成」：时间够了，且长内容已滑到底 */
const canFinish = computed(() => !finished.value && dwellLeft.value <= 0 && (!needsScroll.value || reachedBottom.value))
const gateText = computed(() => {
  if (finished.value) return '本章已完成'
  if (dwellLeft.value > 0) return `继续阅读 ${dwellLeft.value} 秒后自动完成`
  if (needsScroll.value && !reachedBottom.value) return '滑到章节末尾后自动完成'
  return '条件已满足，正在自动完成…'
})

/** 找到真正滚动的祖先容器（App 里是 .m-view，不是 window） */
function findScroller() {
  let el = rootEl.value?.parentElement
  while (el) {
    const oy = getComputedStyle(el).overflowY
    if ((oy === 'auto' || oy === 'scroll') && el.scrollHeight > el.clientHeight) return el
    el = el.parentElement
  }
  return document.scrollingElement || document.documentElement
}

function measure() {
  if (!scrollEl) scrollEl = findScroller()
  if (!scrollEl) return
  const scrollable = scrollEl.scrollHeight - scrollEl.clientHeight
  needsScroll.value = scrollable > 40
  if (!needsScroll.value) reachedBottom.value = true
  else if (scrollEl.scrollTop + scrollEl.clientHeight >= scrollEl.scrollHeight - 24) reachedBottom.value = true
}

function onScroll() {
  if (!scrollEl) return
  if (scrollEl.scrollTop + scrollEl.clientHeight >= scrollEl.scrollHeight - 24) reachedBottom.value = true
}

async function load() {
  loading.value = true
  try {
    const [c, p] = await Promise.all([
      getCourse(courseId).then((r) => r.data || r).catch(() => null),
      // 已完成的章节要直接显示「已完成」，否则每次进来都让人重复点一遍
      courseProgress(courseId).then((r) => (Array.isArray(r.data) ? r.data : [])).catch(() => []),
    ])
    course.value = c
    const list = (c && c.chapters) || []
    idx.value = list.findIndex((x) => String(x.id) === String(chapterId))
    chapter.value = list[idx.value] || null
    // ⚠️ 接口返回的是**每章一条、带 finished 标记**的完整列表（不是「已完成列表」），
    // 必须按 finished 过滤，否则每章都会被判成已完成
    const doneIds = p
      .filter((x) => (typeof x === 'object' ? x.finished !== false : true))
      .map((x) => (typeof x === 'object' ? x.chapterId : x))
      .filter(Boolean)
    finished.value = doneIds.some((id) => String(id) === String(chapterId))
  } finally {
    loading.value = false
  }
}

async function markDone(auto = false) {
  if (finishing.value || finished.value) return
  finishing.value = true
  try {
    await finishChapter(courseId, chapterId)
    finished.value = true
    window.__kbMsg?.success(auto ? '已读完，本章自动完成' : '已标记完成')
  } catch (e) {
    autoTried = false   // 失败后允许下一次条件满足时重试
    window.__kbMsg?.error(e?.response?.data?.error || '标记失败，请检查网络后重试')
  } finally { finishing.value = false }
}

/**
 * 自动完成：条件（停留够 + 长文滑到底）一满足就自己标记，不用用户去点按钮。
 * 只尝试一次，失败才允许重试，避免网络抖动时疯狂重发。
 */
let autoTried = false
watch(canFinish, (ok) => {
  if (!ok || autoTried || finished.value) return
  autoTried = true
  markDone(true)
})

// ---------- 自动翻页（学习通式）：完成后倒计时进入下一章 ----------
const autoNextLeft = ref(0)
let autoNextTimer = null

function hasNext() {
  return idx.value >= 0 && idx.value < chapters.value.length - 1
}

function startAutoNext() {
  if (!hasNext()) return
  clearInterval(autoNextTimer)
  autoNextLeft.value = 3
  autoNextTimer = setInterval(() => {
    autoNextLeft.value -= 1
    if (autoNextLeft.value <= 0) {
      clearInterval(autoNextTimer)
      autoNextTimer = null
      go(idx.value + 1)
    }
  }, 1000)
}

function cancelAutoNext() {
  if (autoNextTimer) { clearInterval(autoNextTimer); autoNextTimer = null }
  autoNextLeft.value = 0
}

// 完成 → 自动翻页；离开页面/切换章节都要清掉，否则会跳错章
watch(finished, (v) => { if (v) { startAutoNext() } else { cancelAutoNext() } })
watch(idx, () => cancelAutoNext())

/** 重学：进度归零、停留与「滑到底」状态全部重置，回到顶部重新走门槛 */
const relearning = ref(false)
async function relearn() {
  if (relearning.value) return
  cancelAutoNext()
  relearning.value = true
  try {
    await resetChapter(courseId, chapterId)
    finished.value = false
    autoTried = false
    dwell.value = 0
    reachedBottom.value = false
    needsScroll.value = false
    await nextTick()
    measure()
    scrollEl?.scrollTo({ top: 0, behavior: 'smooth' })
    window.__kbMsg?.success('已重置，重新学习本章')
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '重置失败，请重试')
  } finally {
    relearning.value = false
  }
}

function go(i) {
  const ch = chapters.value[i]
  if (ch) router.replace(`/course/${courseId}/chapter/${ch.id}`)
}

onMounted(async () => {
  await load()
  await nextTick()
  measure()
  scrollEl?.addEventListener('scroll', onScroll, { passive: true })
  // 每秒累加停留时间；隔秒重新量一次，防止图片加载后高度变化导致判定失效
  tickTimer = setInterval(() => {
    dwell.value++
    if (dwell.value % 2 === 0) measure()
  }, 1000)
})
onUnmounted(() => {
  if (tickTimer) clearInterval(tickTimer)
  if (autoNextTimer) clearInterval(autoNextTimer)
  scrollEl?.removeEventListener('scroll', onScroll)
})
</script>

<template>
  <div class="ch" ref="rootEl" v-if="chapter">
    <div class="topbar">
      <button class="bk" @click="router.back()"><el-icon><ArrowLeft /></el-icon></button>
      <div class="t">{{ chapter.title }}</div>
      <button class="nav" :disabled="idx <= 0" @click="go(idx - 1)"><el-icon><ArrowLeft /></el-icon></button>
      <button class="nav" :disabled="idx >= chapters.length - 1" @click="go(idx + 1)"><el-icon><Right /></el-icon></button>
    </div>

    <div class="body">
      <!-- 章节正文（HTML） -->
      <div class="m-card prose" v-html="chapter.content || '<p style=\'color:#86909c\'>本章暂无内容</p>'"></div>

      <div v-if="gateText" class="gate-tip">{{ gateText }}</div>

      <!-- 未完成：进度提示（按钮不可点，条件满足会自动完成） -->
      <button v-if="!finished" class="m-btn" disabled>
        {{ dwellLeft
          ? `阅读中… 还剩 ${dwellLeft}s`
          : (needsScroll && !reachedBottom ? '滑到章节末尾即自动完成' : '正在标记完成…') }}
      </button>

      <!-- 已完成：自动翻页倒计时 + 重学 -->
      <template v-else>
        <div v-if="autoNextLeft > 0" class="auto-next">
          <span class="an-text">{{ autoNextLeft }} 秒后自动进入下一章</span>
          <button class="an-btn" @click="cancelAutoNext">暂停</button>
          <button class="an-btn primary" @click="go(idx + 1)">立即进入</button>
        </div>
        <div v-else class="done-row">
          <button class="m-btn ghost" :disabled="relearning" @click="relearn">
            {{ relearning ? '重置中…' : '重学本章' }}
          </button>
          <button v-if="hasNext()" class="m-btn" @click="go(idx + 1)">下一章</button>
          <button v-else class="m-btn" disabled>已学完全部章节</button>
        </div>
      </template>
    </div>
  </div>
  <div v-else class="loading">{{ loading ? '加载中…' : '章节不存在' }}</div>
</template>

<style scoped>
.ch { min-height: 100%; background: var(--m-bg); }
.topbar { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; gap: 6px;
  padding: 10px 12px; background: var(--m-card); border-bottom: 1px solid var(--m-border); }
.bk { width: 32px; height: 32px; border: none; background: transparent; color: var(--m-text); cursor: pointer; display: grid; place-items: center; }
.t { flex: 1; font-size: 15.5px; font-weight: 600; color: var(--m-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nav { width: 32px; height: 32px; border: none; background: var(--m-card-2); color: var(--m-text-sub);
  border-radius: 8px; cursor: pointer; display: grid; place-items: center; }
.nav:disabled { opacity: .4; }
.body { padding: 12px 16px; }
/* 完成门槛提示：在按钮上方说明「还差什么」 */
.gate-tip { margin: 14px 2px 8px; font-size: 12px; line-height: 1.5; color: var(--m-text-dim); text-align: center; }
.prose { font-size: 14.5px; line-height: 1.8; color: var(--m-text); }
.loading { padding: 40px; text-align: center; color: var(--m-text-dim); }
/* 自动翻页倒计时条 */
.auto-next { display: flex; align-items: center; gap: 8px; margin: 10px 0 4px;
  padding: 10px 12px; background: var(--m-card-2); border-radius: 10px; }
.an-text { flex: 1; font-size: 13px; color: var(--m-text-sub); }
.an-btn { border: none; background: transparent; color: var(--m-text-sub); font-size: 13px;
  padding: 6px 10px; border-radius: 8px; cursor: pointer; }
.an-btn.primary { background: var(--m-primary); color: #fff; font-weight: 600; }
.done-row { display: flex; gap: 10px; margin-top: 10px; }
.done-row .m-btn { flex: 1; margin-top: 0; }
.m-btn.ghost { background: var(--m-card-2); color: var(--m-text-sub); }
</style>
