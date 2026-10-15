<script setup>
/**
 * 学习计划列表（2026-10-15）
 *
 * 目标：把「要读的文档 / 要学的课程 / 自己的事项」收成一个清单，看得到进度，
 * 到点提醒复习。计划与条目都只属于本人。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { listStudyPlans, createStudyPlan, deleteStudyPlan } from '../../api/studyPlan'
import {
  notifyEnabled, setNotifyEnabled, ensureNotifyPermission,
  resyncPlanNotifications, cancelAll, resyncPlanNotificationsSoon,
} from '../../utils/planNotify'
import { isNative } from '../../utils/runtimeBase'

const router = useRouter()
const loading = ref(true)
const plans = ref([])
const filter = ref('active')      // active | due | done | all
const showNew = ref(false)
const draft = ref({ title: '', note: '', remindAt: '' })

const FILTERS = [
  { key: 'active', label: '进行中' },
  { key: 'due', label: '待复习' },
  { key: 'done', label: '已完成' },
  { key: 'all', label: '全部' }
]

function itemsOf(p) { return Array.isArray(p.items) ? p.items : [] }
function doneCount(p) { return itemsOf(p).filter((i) => i.done).length }
function isDone(p) { const it = itemsOf(p); return it.length > 0 && it.every((i) => i.done) }
function percent(p) {
  const it = itemsOf(p)
  if (!it.length) return 0
  return Math.round((doneCount(p) / it.length) * 100)
}
function isDue(p) {
  if (isDone(p) || !p.remindAt) return false
  const t = new Date(p.remindAt).getTime()
  if (Number.isNaN(t)) return false
  const endOfToday = new Date(); endOfToday.setHours(23, 59, 59, 999)
  return t <= endOfToday.getTime()
}
function remindText(p) {
  if (!p.remindAt) return ''
  const d = new Date(p.remindAt)
  if (Number.isNaN(d.getTime())) return ''
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const day = new Date(d); day.setHours(0, 0, 0, 0)
  const diff = Math.round((day - today) / 86400000)
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  if (diff === 0) return `今天 ${hm}`
  if (diff === 1) return `明天 ${hm}`
  if (diff === -1) return `昨天（已逾期）`
  if (diff < 0) return `已逾期 ${-diff} 天`
  return `${d.getMonth() + 1}月${d.getDate()}日 ${hm}`
}

const shown = computed(() => {
  const list = plans.value
  if (filter.value === 'all') return list
  if (filter.value === 'done') return list.filter(isDone)
  if (filter.value === 'due') return list.filter(isDue)
  return list.filter((p) => !isDone(p))
})

const dueCount = computed(() => plans.value.filter(isDue).length)

const picker = ref('')
// 网页端收不到系统通知（没有原生桥），开关直接禁用并说明，避免用户点了没反应
const nativeApp = isNative()
const notifyOn = ref(notifyEnabled())

async function load() {
  loading.value = true
  try {
    const { data } = await listStudyPlans()
    plans.value = Array.isArray(data) ? data : []
    // 计划数据到手后顺手重排一次系统通知（内部会判断开关与权限）
    resyncPlanNotificationsSoon()
  } catch (e) {
    plans.value = []
    window.__kbMsg?.error('计划加载失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  } finally {
    loading.value = false
  }
}

/** 开关「系统通知」：打开时申请权限，关闭时清掉已排的通知 */
async function toggleNotify(v) {
  const on = !!v
  if (on) {
    const perm = await ensureNotifyPermission()
    if (perm === 'granted') {
      setNotifyEnabled(true)
      window.__kbMsg?.success('已开启，到点会在通知栏提醒你')
      resyncPlanNotifications(true)
      return
    }
    notifyOn.value = false
    window.__kbMsg?.warning(perm === 'unsupported'
      ? '网页端收不到系统通知，请在 App 内开启'
      : '系统通知权限未开启，请在系统设置里允许后重试')
    return
  }
  setNotifyEnabled(false)
  await cancelAll()
  window.__kbMsg?.success('已关闭系统通知')
}

function openNew() {
  draft.value = { title: '', note: '', remindAt: '' }
  showNew.value = true
}

async function confirmNew() {
  const title = draft.value.title.trim()
  if (!title) { window.__kbMsg?.warning('请填写计划名称'); return }
  try {
    const { data } = await createStudyPlan({
      title,
      note: draft.value.note.trim() || null,
      remindAt: draft.value.remindAt || null,
    })
    plans.value = [data, ...plans.value]
    showNew.value = false
    window.__kbMsg?.success('计划已创建')
    router.push(`/plans/${data.id}`)
  } catch (e) {
    window.__kbMsg?.error('创建失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  }
}

async function removePlan(p) {
  try {
    await ElMessageBox.confirm(`删除计划「${p.title}」？条目会一并删除。`, '删除计划', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
    })
  } catch { return }
  try {
    await deleteStudyPlan(p.id)
    plans.value = plans.value.filter((x) => x.id !== p.id)
    window.__kbMsg?.success('已删除')
  } catch (e) {
    window.__kbMsg?.error('删除失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  }
}

onMounted(load)
</script>

<template>
  <div class="plans">
    <header class="m-header solid">
      <button class="m-header-act" type="button" aria-label="返回" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">学习计划</div>
      <button class="m-header-act" type="button" aria-label="新建计划" @click="openNew"><el-icon><Plus /></el-icon></button>
    </header>

    <div class="body">
      <div v-if="dueCount" class="due-tip">
        <el-icon><Bell /></el-icon>
        <span>有 {{ dueCount }} 个计划到复习时间了</span>
        <button type="button" @click="filter = 'due'">查看</button>
      </div>

      <!-- 系统通知开关：原生 App 内到点发通知栏；网页端无此能力，开关禁用 -->
      <div class="notify-row">
        <span class="nr-main">
          <span class="nr-title">系统通知提醒</span>
          <span class="nr-sub">{{ nativeApp ? '到复习时间后在手机通知栏提醒（需通知权限）' : '仅在 App 内可用，网页端收不到系统通知' }}</span>
        </span>
        <el-switch :model-value="notifyOn" :disabled="!nativeApp" @change="toggleNotify" />
      </div>

      <div class="seg">
        <button v-for="f in FILTERS" :key="f.key" type="button" class="seg-item"
                :class="{ on: filter === f.key }" @click="filter = f.key">{{ f.label }}</button>
      </div>

      <div v-if="loading" class="m-card loading-card">
        <div v-for="i in 3" :key="i" class="skeleton-row">
          <div class="m-skel" style="height:16px;width:56%"></div>
          <div class="m-skel" style="height:12px;width:34%;margin-top:10px"></div>
        </div>
      </div>

      <div v-else-if="shown.length" class="m-card plan-list">
        <div v-for="p in shown" :key="p.id" class="plan-row">
          <button class="plan-main" type="button" @click="router.push(`/plans/${p.id}`)">
            <div class="plan-top">
              <span class="plan-title">{{ p.title }}</span>
              <span v-if="isDone(p)" class="plan-tag done">已完成</span>
              <span v-else-if="isDue(p)" class="plan-tag due">{{ remindText(p) }}</span>
            </div>
            <div v-if="p.note" class="plan-note">{{ p.note }}</div>
            <div class="plan-progress">
              <div class="bar"><i :style="{ width: percent(p) + '%' }"></i></div>
              <span class="pct">{{ doneCount(p) }}/{{ itemsOf(p).length }} · {{ percent(p) }}%</span>
            </div>
            <div v-if="p.remindAt && !isDone(p) && !isDue(p)" class="plan-remind">提醒：{{ remindText(p) }}</div>
          </button>
          <button class="plan-del" type="button" aria-label="删除计划" @click="removePlan(p)"><el-icon><Delete /></el-icon></button>
        </div>
      </div>

      <div v-else class="m-card empty">
        <div class="empty-icon"><el-icon :size="30"><Calendar /></el-icon></div>
        <div>{{ filter === 'all' ? '还没有学习计划' : '这里还没有计划' }}</div>
        <button class="m-btn sm" type="button" style="margin-top:10px" @click="openNew">新建学习计划</button>
      </div>
      <div class="bottom-space"></div>
    </div>

    <!-- 新建计划 -->
    <transition name="sheet-up">
      <div v-if="showNew" class="m-sheet-mask" @click.self="showNew = false">
        <div class="m-sheet" @click.stop>
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">新建学习计划</div>
          <div class="form">
            <input v-model="draft.title" class="f-input" maxlength="120" placeholder="计划名称，如「本周读完产品手册」" />
            <textarea v-model="draft.note" class="f-area" rows="2" maxlength="500" placeholder="备注（可选）"></textarea>
            <label class="f-label">复习提醒</label>
            <input v-model="draft.remindAt" class="f-input" type="datetime-local" />
            <p class="f-hint">留空表示不提醒；到点后会在「学习计划」和首页提示你复习。</p>
          </div>
          <button class="m-sheet-item primary" type="button" @click="confirmNew">创建并添加条目</button>
          <button class="m-sheet-item cancel" type="button" @click="showNew = false">取消</button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.plans { min-height: 100%; }
.body { padding: 0 16px; }
.due-tip {
  display: flex; align-items: center; gap: 8px; margin-top: 10px; padding: 11px 13px;
  border-radius: 12px; background: #fff7ed; color: #9a3412; font-size: 12.5px;
}
.due-tip span { flex: 1 1 auto; }
.due-tip button { border: 0; background: transparent; color: #c2410c; font: inherit; font-size: 12.5px; font-weight: 600; }
html.dark .due-tip { background: #3b2411; color: #fdba74; }
html.dark .due-tip button { color: #fdba74; }

/* 系统通知开关行 */
.notify-row {
  display: flex; align-items: center; gap: 12px; margin-top: 10px; padding: 11px 14px;
  border-radius: 12px; background: var(--m-card); border: 1px solid var(--m-border);
}
.nr-main { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.nr-title { font-size: 14px; font-weight: 600; color: var(--m-text); }
.nr-sub { font-size: 11.5px; color: var(--m-text-dim); }

.seg { display: flex; gap: 4px; margin: 12px 0 10px; padding: 3px; border-radius: 10px; background: var(--m-card-2); }.seg-item {
  flex: 1 1 0; padding: 7px 0; border: 0; border-radius: 8px; background: transparent;
  color: var(--m-text-sub); font: inherit; font-size: 12.5px; cursor: pointer;
}
.seg-item.on { background: var(--m-card); color: var(--m-text); font-weight: 600; box-shadow: 0 1px 2px rgba(15, 23, 42, .08); }

.plan-list { overflow: hidden; }
.plan-row { display: flex; align-items: stretch; }
.plan-row + .plan-row { border-top: 1px solid var(--m-border); }
.plan-main { flex: 1 1 auto; min-width: 0; padding: 13px 6px 13px 14px; text-align: left; border: 0; background: transparent; color: var(--m-text); font-family: inherit; cursor: pointer; }
.plan-main:active, .plan-del:active { background: var(--m-card-2); }
.plan-top { display: flex; align-items: center; gap: 8px; }
.plan-title { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14.5px; font-weight: 600; }
.plan-tag { flex: none; padding: 2px 7px; border-radius: 999px; font-size: 10.5px; font-weight: 600; }
.plan-tag.done { background: var(--m-primary-soft); color: var(--m-primary); }
.plan-tag.due { background: #fff1f0; color: #e5484d; }
html.dark .plan-tag.due { background: #3d1a1a; color: #f87171; }
.plan-note { margin-top: 4px; font-size: 12.5px; color: var(--m-text-dim); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.plan-progress { display: flex; align-items: center; gap: 9px; margin-top: 9px; }
.plan-progress .bar { flex: 1 1 auto; height: 6px; border-radius: 3px; background: var(--m-border); overflow: hidden; }
.plan-progress .bar i { display: block; height: 100%; border-radius: 3px; background: var(--m-primary); transition: width .3s; }
.pct { flex: none; font-size: 11px; color: var(--m-text-dim); }
.plan-remind { margin-top: 6px; font-size: 11px; color: var(--m-text-dim); }
.plan-del { flex: none; width: 44px; border: 0; background: transparent; color: var(--m-text-dim); cursor: pointer; }

.empty { padding: 30px 16px; text-align: center; font-size: 13px; color: var(--m-text-dim); }
.empty-icon { margin-bottom: 6px; color: var(--m-primary); }
.loading-card { padding: 2px 14px; }
.skeleton-row { padding: 14px 0; }
.skeleton-row + .skeleton-row { border-top: 1px solid var(--m-border); }
.bottom-space { height: 22px; }

.form { padding: 0 16px 10px; }
.f-input, .f-area {
  display: block; width: 100%; box-sizing: border-box; margin-bottom: 10px;
  padding: 11px 13px; border: 1px solid var(--m-border); border-radius: 12px;
  background: var(--m-bg); color: var(--m-text); font: inherit; font-size: 14px; outline: none;
}
.f-input:focus, .f-area:focus { border-color: var(--m-primary); }
.f-area { resize: none; line-height: 1.6; }
.f-label { display: block; margin: 2px 2px 6px; font-size: 12.5px; color: var(--m-text-dim); }
.f-hint { margin: 0 2px; font-size: 11.5px; line-height: 1.6; color: var(--m-text-dim); }
.m-sheet-item.primary { color: var(--m-primary); font-weight: 600; }
</style>
