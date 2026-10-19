<script setup>
/**
 * 移动端「复习卡片」：正面提问 → 显示答案 → 四档评分 → 按 SM-2 排下一次。
 *
 * 和「学习计划」的分工：
 *   学习计划管「要做什么」，卡片管「记住了没有」——一个排任务，一个管记忆。
 *   卡片仅本人可见，不参与任何统计上报。
 *
 * 复习过程放在同一个页面内（reviewing 模式），不跳路由：
 *   中途被打断回来时不用重新拉数据，也避免返回键打断复习节奏。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  listFlashcards, flashcardSummary, dueFlashcards,
  createFlashcard, updateFlashcard, removeFlashcard, reviewFlashcard,
} from '../../api/flashcard'

const router = useRouter()

const loading = ref(true)
const cards = ref([])
const stat = ref({ total: 0, due: 0, learned: 0, new: 0 })

// 复习模式
const reviewing = ref(false)
const queue = ref([])
const idx = ref(0)
const flipped = ref(false)
const grading = ref(false)
const doneCount = ref({ again: 0, hard: 0, good: 0, easy: 0 })

// 新建/编辑
const editorOpen = ref(false)
const editingId = ref(null)
const form = ref({ front: '', back: '' })
const saving = ref(false)

const current = computed(() => queue.value[idx.value] || null)
const progress = computed(() => queue.value.length ? `${Math.min(idx.value + 1, queue.value.length)} / ${queue.value.length}` : '0 / 0')
const finished = computed(() => reviewing.value && idx.value >= queue.value.length)

function errText(e) {
  return e?.response?.data?.error || e?.message || '操作失败'
}

async function load() {
  loading.value = true
  try {
    const [listRes, sumRes] = await Promise.all([listFlashcards(), flashcardSummary()])
    cards.value = Array.isArray(listRes.data) ? listRes.data : []
    stat.value = sumRes.data || { total: 0, due: 0, learned: 0, new: 0 }
  } catch (e) {
    window.__kbMsg?.error(errText(e))
  } finally {
    loading.value = false
  }
}

function fmtDue(s) {
  if (!s) return ''
  const d = new Date(s)
  const now = new Date()
  const diff = d.getTime() - now.getTime()
  if (diff <= 0) return '现在可复习'
  const days = Math.ceil(diff / 86400000)
  if (days <= 1) return '明天复习'
  return `${days} 天后复习`
}

// ---------- 复习 ----------

async function startReview() {
  try {
    const { data } = await dueFlashcards()
    const list = Array.isArray(data) ? data : []
    if (!list.length) { window.__kbMsg?.info('今天没有要复习的卡片'); return }
    queue.value = list
    idx.value = 0
    flipped.value = false
    doneCount.value = { again: 0, hard: 0, good: 0, easy: 0 }
    reviewing.value = true
  } catch (e) {
    window.__kbMsg?.error(errText(e))
  }
}

function flip() { flipped.value = true }

async function grade(g) {
  const c = current.value
  if (!c || grading.value) return
  grading.value = true
  try {
    await reviewFlashcard(c.id, g)
    doneCount.value[g] = (doneCount.value[g] || 0) + 1
    idx.value += 1
    flipped.value = false
  } catch (e) {
    window.__kbMsg?.error(errText(e))
  } finally {
    grading.value = false
  }
}

async function exitReview() {
  reviewing.value = false
  queue.value = []
  await load()
}

// ---------- 新建 / 编辑 ----------

function openCreate() {
  editingId.value = null
  form.value = { front: '', back: '' }
  editorOpen.value = true
}

function openEdit(c) {
  editingId.value = c.id
  form.value = { front: c.front, back: c.back }
  editorOpen.value = true
}

async function saveCard() {
  const front = form.value.front.trim()
  const back = form.value.back.trim()
  if (!front || !back) { window.__kbMsg?.warning('正面和背面都要填'); return }
  saving.value = true
  try {
    if (editingId.value) await updateFlashcard(editingId.value, { front, back })
    else await createFlashcard({ front, back })
    editorOpen.value = false
    window.__kbMsg?.success(editingId.value ? '已保存' : '卡片已创建')
    await load()
  } catch (e) {
    window.__kbMsg?.error(errText(e))
  } finally {
    saving.value = false
  }
}

async function del(c) {
  try {
    await window.__kbMsgBox.confirm(`删除这张卡片？\n\n${c.front.slice(0, 40)}`, '删除卡片', { type: 'warning' })
  } catch { return }
  try {
    await removeFlashcard(c.id)
    await load()
    window.__kbMsg?.success('已删除')
  } catch (e) {
    window.__kbMsg?.error(errText(e))
  }
}

onMounted(load)
</script>

<template>
  <div class="cards">
    <header class="m-header solid">
      <button class="m-header-act" type="button" aria-label="返回" @click="reviewing ? exitReview() : router.back()">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <div class="h-title">{{ reviewing ? '复习中' : '复习卡片' }}</div>
      <button v-if="!reviewing" class="m-header-act" type="button" aria-label="新建卡片" @click="openCreate">
        <el-icon :size="20"><Plus /></el-icon>
      </button>
      <span v-else class="h-progress">{{ progress }}</span>
    </header>

    <!-- ===== 复习模式 ===== -->
    <div v-if="reviewing" class="body">
      <div v-if="finished" class="m-card finish">
        <div class="finish-title">这一轮复习完了</div>
        <div class="finish-grid">
          <div><b>{{ doneCount.good + doneCount.easy }}</b><span>记住了</span></div>
          <div><b>{{ doneCount.hard }}</b><span>模糊</span></div>
          <div><b>{{ doneCount.again }}</b><span>重来</span></div>
        </div>
        <div class="finish-tip">「重来」的卡片 10 分钟后会再出现一次。</div>
        <button class="primary-btn" type="button" @click="exitReview">回到卡片列表</button>
      </div>

      <template v-else-if="current">
        <div class="m-card flash" @click="!flipped && flip()">
          <div class="flash-label">{{ flipped ? '答案' : '问题' }}</div>
          <div class="flash-text">{{ flipped ? current.back : current.front }}</div>
          <div v-if="!flipped" class="flash-hint">点一下显示答案</div>
        </div>

        <div v-if="!flipped" class="actions">
          <button class="primary-btn" type="button" @click="flip">显示答案</button>
        </div>
        <div v-else class="grade-row">
          <button class="grade g-again" type="button" :disabled="grading" @click="grade('again')">
            <b>重来</b><span>10 分钟</span>
          </button>
          <button class="grade g-hard" type="button" :disabled="grading" @click="grade('hard')">
            <b>模糊</b><span>稍后</span>
          </button>
          <button class="grade g-good" type="button" :disabled="grading" @click="grade('good')">
            <b>记得</b><span>按间隔</span>
          </button>
          <button class="grade g-easy" type="button" :disabled="grading" @click="grade('easy')">
            <b>太简单</b><span>拉长</span>
          </button>
        </div>
      </template>

      <div class="bottom-space"></div>
    </div>

    <!-- ===== 列表模式 ===== -->
    <div v-else class="body">
      <div class="m-card stat-card">
        <div class="stat-main">
          <div class="stat-num">{{ stat.due }}</div>
          <div class="stat-label">张待复习</div>
        </div>
        <div class="stat-side">
          <div><span>总数</span><b>{{ stat.total }}</b></div>
          <div><span>已学</span><b>{{ stat.learned }}</b></div>
          <div><span>新卡</span><b>{{ stat.new }}</b></div>
        </div>
      </div>
      <div class="actions">
        <button class="primary-btn" type="button" :disabled="!stat.due" @click="startReview">
          {{ stat.due ? `开始复习（${stat.due}）` : '今天没有要复习的' }}
        </button>
      </div>

      <div class="m-sec-title">全部卡片</div>
      <div v-if="loading" class="m-card">
        <div v-for="i in 3" :key="i" style="padding:12px 0">
          <div class="m-skel" style="height:14px;width:64%"></div>
          <div class="m-skel" style="height:11px;width:36%;margin-top:8px"></div>
        </div>
      </div>
      <div v-else-if="cards.length" class="m-card">
        <div v-for="c in cards" :key="c.id" class="card-row">
          <div class="card-main" @click="openEdit(c)">
            <div class="card-front">{{ c.front }}</div>
            <div class="card-meta">
              <span :class="{ due: new Date(c.dueAt) <= new Date() }">{{ fmtDue(c.dueAt) }}</span>
              <span v-if="c.reps">· 已复习 {{ c.reps }} 次</span>
            </div>
          </div>
          <button class="card-del" type="button" aria-label="删除" @click.stop="del(c)">
            <el-icon :size="16"><Delete /></el-icon>
          </button>
        </div>
      </div>
      <div v-else class="m-card empty">
        <div class="empty-title">还没有卡片</div>
        <div class="empty-tip">把容易忘的知识点做成卡片，到期会自动提醒你复习。</div>
        <button class="ghost-btn" type="button" @click="openCreate">新建第一张</button>
      </div>

      <div class="bottom-space"></div>
    </div>

    <!-- 新建 / 编辑 -->
    <transition name="sheet-up">
      <div v-if="editorOpen" class="sheet-mask" @click.self="editorOpen = false">
        <div class="sheet">
          <div class="sheet-title">{{ editingId ? '编辑卡片' : '新建卡片' }}</div>
          <div class="field">
            <label>正面（问题）</label>
            <textarea v-model="form.front" rows="3" maxlength="500" placeholder="例如：SM-2 里的 ease factor 下限是多少？"></textarea>
          </div>
          <div class="field">
            <label>背面（答案）</label>
            <textarea v-model="form.back" rows="4" maxlength="5000" placeholder="例如：1.3"></textarea>
          </div>
          <div class="sheet-actions">
            <button class="ghost-btn" type="button" @click="editorOpen = false">取消</button>
            <button class="primary-btn" type="button" :disabled="saving" @click="saveCard">{{ saving ? '保存中…' : '保存' }}</button>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.cards { min-height: 100vh; background: var(--m-bg); }
.body { padding: 12px 14px 0; }
.h-progress { font-size: 12.5px; color: var(--m-text-dim, #86909c); margin-right: 6px; }

.stat-card { display: flex; align-items: center; gap: 16px; padding: 16px; }
.stat-main { flex: none; text-align: center; min-width: 88px; }
.stat-num { font-size: 30px; font-weight: 500; color: var(--m-primary, #1677ff); line-height: 1.1; }
.stat-label { font-size: 11.5px; color: var(--m-text-dim, #86909c); margin-top: 2px; }
.stat-side { flex: 1; display: flex; justify-content: space-around; }
.stat-side div { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.stat-side span { font-size: 11px; color: var(--m-text-dim, #86909c); }
.stat-side b { font-size: 15px; font-weight: 500; color: var(--m-text); }

.actions { display: flex; gap: 10px; margin-top: 14px; }
.primary-btn { flex: 1; height: 44px; border: 0; border-radius: 12px; background: var(--m-primary, #1677ff); color: #fff; font-size: 15px; }
.primary-btn:disabled { opacity: .45; }
.ghost-btn { height: 40px; padding: 0 18px; border: 1px solid var(--m-border, #e5e6eb); border-radius: 12px; background: transparent; color: var(--m-text); font-size: 14px; }

.flash { min-height: 200px; padding: 18px 16px; display: flex; flex-direction: column; }
.flash-label { font-size: 11.5px; color: var(--m-text-dim, #86909c); }
.flash-text { flex: 1; margin-top: 12px; font-size: 17px; line-height: 1.7; color: var(--m-text); white-space: pre-wrap; word-break: break-word; }
.flash-hint { margin-top: 14px; font-size: 11.5px; color: var(--m-text-dim, #a8a8b3); }

.grade-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 14px; }
.grade { display: flex; flex-direction: column; align-items: center; gap: 3px; padding: 10px 0; border: 1px solid var(--m-border, #e5e6eb); border-radius: 12px; background: #fff; }
.grade b { font-size: 13.5px; font-weight: 500; }
.grade span { font-size: 10.5px; color: var(--m-text-dim, #86909c); }
.g-again { border-color: #ffd6d6; color: #c0392b; background: #fff5f5; }
.g-hard { border-color: #ffe2bf; color: #b26a00; background: #fffaf2; }
.g-good { border-color: #c6e2ff; color: #1677ff; background: #f5faff; }
.g-easy { border-color: #cdeccd; color: #1f7a1f; background: #f5fdf5; }

.finish { padding: 22px 18px; text-align: center; }
.finish-title { font-size: 16px; font-weight: 500; color: var(--m-text); }
.finish-grid { display: flex; justify-content: space-around; margin: 18px 0 12px; }
.finish-grid div { display: flex; flex-direction: column; gap: 3px; }
.finish-grid b { font-size: 22px; font-weight: 500; color: var(--m-primary, #1677ff); }
.finish-grid span { font-size: 11.5px; color: var(--m-text-dim, #86909c); }
.finish-tip { font-size: 11.5px; color: var(--m-text-dim, #86909c); margin-bottom: 16px; }

.card-row { display: flex; align-items: flex-start; gap: 8px; padding: 12px 0; border-bottom: 1px solid var(--m-border); }
.card-row:last-child { border-bottom: 0; }
.card-main { flex: 1; min-width: 0; }
.card-front { font-size: 14px; line-height: 1.55; color: var(--m-text); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; word-break: break-word; }
.card-meta { margin-top: 4px; font-size: 11.5px; color: var(--m-text-dim, #86909c); }
.card-meta .due { color: var(--m-primary, #1677ff); }
.card-del { flex: none; width: 32px; height: 32px; border: 0; background: transparent; color: var(--m-text-dim, #a8a8b3); }

.empty { padding: 26px 18px; text-align: center; }
.empty-title { font-size: 14px; color: var(--m-text); }
.empty-tip { margin: 6px 0 14px; font-size: 12px; line-height: 1.6; color: var(--m-text-dim, #86909c); }

.sheet-mask { position: fixed; inset: 0; z-index: 60; background: rgba(0, 0, 0, .35); display: flex; align-items: flex-end; }
.sheet { width: 100%; background: var(--m-bg, #f5f6f7); border-radius: 16px 16px 0 0; padding: 16px 14px calc(16px + env(safe-area-inset-bottom)); }
.sheet-title { font-size: 16px; font-weight: 500; color: var(--m-text); margin-bottom: 12px; }
.field { margin-bottom: 12px; }
.field label { display: block; font-size: 12px; color: var(--m-text-dim, #86909c); margin-bottom: 6px; }
.field textarea { width: 100%; padding: 10px 12px; border: 1px solid var(--m-border, #e5e6eb); border-radius: 10px; background: #fff; font: inherit; font-size: 14px; line-height: 1.6; color: var(--m-text); resize: none; }
.sheet-actions { display: flex; gap: 10px; margin-top: 4px; }
.sheet-actions .ghost-btn { flex: none; width: 88px; height: 44px; }

.bottom-space { height: 28px; }
</style>
