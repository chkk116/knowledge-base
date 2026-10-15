<script setup>
/**
 * 学习计划详情（2026-10-15）
 *
 * 条目可以来自：文档库（点击跳原文）/ 课程（点击跳课程）/ 自定义事项。
 * 勾选即记完成时间，进度实时更新；计划可设复习提醒时间。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import {
  getStudyPlan, updateStudyPlan, deleteStudyPlan,
  addStudyPlanItem, updateStudyPlanItem, deleteStudyPlanItem,
} from '../../api/studyPlan'
import { resyncPlanNotificationsSoon } from '../../utils/planNotify'
import { listAllShareDocs } from '../../api/docShare'
import { listCourses } from '../../api/training'

const route = useRoute()
const router = useRouter()
let planId = Number(route.params.id)

const loading = ref(true)
const plan = ref(null)
const busy = ref(false)
const picker = ref('')          // '' | 'doc' | 'course' | 'text'
const pickerKeyword = ref('')
const docOptions = ref([])
const courseOptions = ref([])
const textDraft = ref('')
const showEdit = ref(false)
const editDraft = ref({ title: '', note: '', remindAt: '' })

const items = computed(() => (Array.isArray(plan.value?.items) ? plan.value.items : []))
const doneCount = computed(() => items.value.filter((i) => i.done).length)
const percent = computed(() => (items.value.length ? Math.round((doneCount.value / items.value.length) * 100) : 0))
const finished = computed(() => items.value.length > 0 && doneCount.value === items.value.length)

const shownDocs = computed(() => {
  const q = pickerKeyword.value.trim().toLowerCase()
  const list = docOptions.value
  if (!q) return list.slice(0, 40)
  return list.filter((d) => String(d.title || '').toLowerCase().includes(q)).slice(0, 40)
})
const shownCourses = computed(() => {
  const q = pickerKeyword.value.trim().toLowerCase()
  const list = courseOptions.value
  if (!q) return list.slice(0, 40)
  return list.filter((c) => String(c.title || '').toLowerCase().includes(q)).slice(0, 40)
})

function fmtRemind(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
/** datetime-local 输入框需要 yyyy-MM-ddTHH:mm */
function toLocalInput(value) {
  if (!value) return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return ''
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`
}

async function load(id = planId) {
  loading.value = true
  try {
    const { data } = await getStudyPlan(id)
    plan.value = data
  } catch (e) {
    plan.value = null
    window.__kbMsg?.error('计划加载失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  } finally {
    loading.value = false
  }
}

async function toggle(item) {
  if (busy.value) return
  busy.value = true
  try {
    const { data } = await updateStudyPlanItem(planId, item.id, { done: !item.done })
    plan.value = data
  } catch (e) {
    window.__kbMsg?.error('操作失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  } finally {
    busy.value = false
  }
}

function openItem(item) {
  if (item.type === 'DOC' && item.refShareId && item.refId) router.push(`/doc/${item.refShareId}/${item.refId}`)
  else if (item.type === 'COURSE' && item.refId) router.push(`/course/${item.refId}`)
}

async function removeItem(item) {
  try {
    await ElMessageBox.confirm(`移除条目「${item.title}」？`, '移除条目', {
      type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消',
    })
  } catch { return }
  try {
    const { data } = await deleteStudyPlanItem(planId, item.id)
    plan.value = data
  } catch (e) {
    window.__kbMsg?.error('移除失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  }
}

async function openPicker(kind) {
  picker.value = kind
  pickerKeyword.value = ''
  textDraft.value = ''
  if (kind === 'doc' && !docOptions.value.length) {
    try {
      const { data } = await listAllShareDocs()
      docOptions.value = Array.isArray(data) ? data : []
    } catch { window.__kbMsg?.warning('文档列表加载失败，可稍后重试') }
  }
  if (kind === 'course' && !courseOptions.value.length) {
    try {
      const { data } = await listCourses()
      courseOptions.value = Array.isArray(data) ? data : (data?.content || [])
    } catch { window.__kbMsg?.warning('课程列表加载失败，可稍后重试') }
  }
}

async function addItem(payload) {
  try {
    const { data } = await addStudyPlanItem(planId, payload)
    plan.value = data
    picker.value = ''
    window.__kbMsg?.success('已加入计划')
  } catch (e) {
    window.__kbMsg?.error('添加失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  }
}
function addDoc(d) { addItem({ type: 'DOC', refId: d.id, refShareId: d.shareId, title: d.title || '未命名文档' }) }
function addCourse(c) { addItem({ type: 'COURSE', refId: c.id, title: c.title || '课程' }) }
function addText() {
  const t = textDraft.value.trim()
  if (!t) { window.__kbMsg?.warning('请填写事项内容'); return }
  addItem({ type: 'TEXT', title: t })
}

function openEdit() {
  editDraft.value = {
    title: plan.value?.title || '',
    note: plan.value?.note || '',
    remindAt: toLocalInput(plan.value?.remindAt),
  }
  showEdit.value = true
}
async function confirmEdit() {
  const title = editDraft.value.title.trim()
  if (!title) { window.__kbMsg?.warning('请填写计划名称'); return }
  try {
    const { data } = await updateStudyPlan(planId, {
      title,
      note: editDraft.value.note.trim() || null,
      remindAt: editDraft.value.remindAt || null,
    })
    plan.value = data
    showEdit.value = false
    window.__kbMsg?.success('已保存')
  } catch (e) {
    window.__kbMsg?.error('保存失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  }
}

async function removePlan() {
  try {
    await ElMessageBox.confirm(`删除计划「${plan.value?.title}」？`, '删除计划', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
    })
  } catch { return }
  try {
    await deleteStudyPlan(planId)
    resyncPlanNotificationsSoon()
    window.__kbMsg?.success('已删除')
    router.replace('/plans')
  } catch (e) {
    window.__kbMsg?.error('删除失败：' + (e.response?.data?.error || e.message || '请稍后重试'))
  }
}

onMounted(() => load())
watch(() => route.params.id, (v) => {
  const id = Number(v)
  if (id && id !== planId) { planId = id; load(id) }
})
// 计划数据一变就重排系统通知（内部防抖 + 按开关/权限兜底）：勾选完成 → 取消，
// 改提醒时间 → 重排；不需要每个操作各写一次
watch(plan, () => resyncPlanNotificationsSoon())
</script>

<template>
  <div class="pd">
    <header class="m-header solid">
      <button class="m-header-act" type="button" aria-label="返回" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">{{ plan?.title || '学习计划' }}</div>
      <button class="m-header-act" type="button" aria-label="编辑计划" @click="openEdit"><el-icon><EditPen /></el-icon></button>
    </header>

    <div v-if="loading" class="body">
      <div class="m-skel" style="height:64px;margin-top:12px"></div>
      <div class="m-skel" style="height:120px;margin-top:12px"></div>
    </div>

    <template v-else-if="plan">
      <div class="body">
        <div class="ov">
          <div class="ov-line">
            <span class="ov-pct">{{ percent }}%</span>
            <span v-if="finished" class="ov-tag">已完成</span>
          </div>
          <div class="bar"><i :style="{ width: percent + '%' }"></i></div>
          <div class="ov-meta">
            <span>{{ doneCount }}/{{ items.length }} 项已完成</span>
            <span v-if="plan.remindAt">提醒：{{ fmtRemind(plan.remindAt) }}</span>
            <span v-else>未设提醒</span>
          </div>
        </div>
        <p v-if="plan.note" class="note">{{ plan.note }}</p>
      </div>

      <div class="body">
        <div class="m-sec-title">
          条目
          <span class="m-chip gray" style="margin-left:6px">{{ items.length }}</span>
          <button class="more" type="button" @click="openPicker('doc')">+ 添加</button>
        </div>

        <div v-if="items.length" class="m-card item-list">
          <div v-for="it in items" :key="it.id" class="item-row">
            <button class="check" type="button" :class="{ on: it.done }" :aria-label="it.done ? '标记未完成' : '标记完成'" @click="toggle(it)">
              <el-icon v-if="it.done"><Check /></el-icon>
            </button>
            <button class="item-main" type="button" @click="openItem(it)">
              <span class="item-title" :class="{ done: it.done }">{{ it.title }}</span>
              <span class="item-meta">
                <span v-if="it.type === 'DOC'">文档</span>
                <span v-else-if="it.type === 'COURSE'">课程</span>
                <span v-else>自定义</span>
                <template v-if="it.done && it.doneAt"> · 已完成</template>
              </span>
            </button>
            <button class="item-del" type="button" aria-label="移除条目" @click="removeItem(it)"><el-icon><Close /></el-icon></button>
          </div>
        </div>

        <div v-else class="m-card empty">
          <div class="empty-icon"><el-icon :size="28"><Aim /></el-icon></div>
          <div>还没有条目，添加要读的文档或课程吧</div>
          <button class="m-btn sm" type="button" style="margin-top:10px" @click="openPicker('doc')">添加条目</button>
        </div>

        <div class="actions">
          <button class="act" type="button" @click="openPicker('doc')"><el-icon><Document /></el-icon>文档</button>
          <button class="act" type="button" @click="openPicker('course')"><el-icon><Reading /></el-icon>课程</button>
          <button class="act" type="button" @click="openPicker('text')"><el-icon><EditPen /></el-icon>自定义</button>
        </div>
        <button class="del-plan" type="button" @click="removePlan">删除这个计划</button>
        <div style="height:26px"></div>
      </div>
    </template>

    <div v-else class="m-empty">
      <span class="ico"><el-icon :size="30"><Calendar /></el-icon></span>
      <div>计划不存在或已删除</div>
      <button class="m-btn sm" style="margin-top:8px" @click="router.replace('/plans')">返回列表</button>
    </div>

    <!-- 选择条目来源 -->
    <transition name="sheet-up">
      <div v-if="picker" class="m-sheet-mask" @click.self="picker = ''">
        <div class="m-sheet" @click.stop>
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">
            {{ picker === 'doc' ? '选择文档' : picker === 'course' ? '选择课程' : '自定义事项' }}
          </div>

          <template v-if="picker === 'text'">
            <div class="form">
              <input v-model="textDraft" class="f-input" maxlength="200" placeholder="例如：复习第三章笔记" />
            </div>
            <button class="m-sheet-item primary" type="button" @click="addText">加入计划</button>
          </template>

          <template v-else>
            <label class="searchbox">
              <el-icon><Search /></el-icon>
              <input v-model="pickerKeyword" type="search" :placeholder="picker === 'doc' ? '搜索文档标题' : '搜索课程'" />
            </label>
            <div class="pick-list">
              <template v-if="picker === 'doc'">
                <button v-for="d in shownDocs" :key="d.shareId + ':' + d.id" class="pick-row" type="button" @click="addDoc(d)">
                  <span class="pick-title">{{ d.title || '未命名文档' }}</span>
                  <span class="pick-sub">{{ d.spaceName || '文档空间' }}</span>
                </button>
                <div v-if="!shownDocs.length" class="pick-empty">没有可添加的文档</div>
              </template>
              <template v-else>
                <button v-for="c in shownCourses" :key="c.id" class="pick-row" type="button" @click="addCourse(c)">
                  <span class="pick-title">{{ c.title || '课程' }}</span>
                  <span class="pick-sub">{{ c.category || '课程' }}</span>
                </button>
                <div v-if="!shownCourses.length" class="pick-empty">没有可添加的课程</div>
              </template>
            </div>
          </template>

          <button class="m-sheet-item cancel" type="button" @click="picker = ''">取消</button>
        </div>
      </div>
    </transition>

    <!-- 编辑计划 -->
    <transition name="sheet-up">
      <div v-if="showEdit" class="m-sheet-mask" @click.self="showEdit = false">
        <div class="m-sheet" @click.stop>
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">编辑计划</div>
          <div class="form">
            <input v-model="editDraft.title" class="f-input" maxlength="120" placeholder="计划名称" />
            <textarea v-model="editDraft.note" class="f-area" rows="2" maxlength="500" placeholder="备注（可选）"></textarea>
            <label class="f-label">复习提醒</label>
            <input v-model="editDraft.remindAt" class="f-input" type="datetime-local" />
            <p class="f-hint">清空即可取消提醒。</p>
          </div>
          <button class="m-sheet-item primary" type="button" @click="confirmEdit">保存</button>
          <button class="m-sheet-item cancel" type="button" @click="showEdit = false">取消</button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.pd { min-height: 100%; }
.body { padding: 0 16px; }

.ov { margin-top: 12px; padding: 14px 16px; border-radius: 14px; background: var(--m-card); box-shadow: 0 1px 2px rgba(0, 0, 0, .04), 0 6px 18px rgba(15, 23, 42, .04); }
.ov-line { display: flex; align-items: baseline; gap: 8px; }
.ov-pct { font-size: 26px; font-weight: 800; letter-spacing: -.5px; }
.ov-tag { padding: 2px 8px; border-radius: 999px; background: var(--m-primary-soft); color: var(--m-primary); font-size: 11px; font-weight: 600; }
.ov .bar { height: 7px; border-radius: 4px; background: var(--m-border); overflow: hidden; margin: 10px 0 8px; }
.ov .bar i { display: block; height: 100%; border-radius: 4px; background: var(--m-primary); transition: width .3s; }
.ov-meta { display: flex; flex-wrap: wrap; gap: 12px; font-size: 12px; color: var(--m-text-dim); }
.note { margin: 10px 2px 0; font-size: 13px; line-height: 1.7; color: var(--m-text-sub); white-space: pre-wrap; }

.item-list { overflow: hidden; }
.item-row { display: flex; align-items: center; }
.item-row + .item-row { border-top: 1px solid var(--m-border); }
.check {
  flex: none; width: 22px; height: 22px; margin: 0 10px 0 14px; border-radius: 50%;
  border: 1.5px solid var(--m-border); background: transparent; color: #fff;
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
}
.check.on { background: var(--m-primary); border-color: var(--m-primary); }
.item-main { flex: 1 1 auto; min-width: 0; padding: 13px 4px; text-align: left; border: 0; background: transparent; color: var(--m-text); font-family: inherit; cursor: pointer; }
.item-title { display: block; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-title.done { color: var(--m-text-dim); text-decoration: line-through; }
.item-meta { display: block; margin-top: 3px; font-size: 11px; color: var(--m-text-dim); }
.item-del { flex: none; width: 40px; border: 0; background: transparent; color: var(--m-text-dim); cursor: pointer; }

.empty { padding: 26px 16px; text-align: center; font-size: 13px; color: var(--m-text-dim); }
.empty-icon { margin-bottom: 6px; color: var(--m-primary); }

.actions { display: flex; gap: 10px; margin-top: 14px; }
.act {
  flex: 1 1 0; height: 42px; border-radius: 12px; border: 1px solid var(--m-border);
  background: var(--m-card); color: var(--m-text); font: inherit; font-size: 13px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
}
.act:active { background: var(--m-card-2); }
.del-plan {
  display: block; width: 100%; margin-top: 18px; height: 44px; border-radius: 12px;
  border: 1px solid var(--m-border); background: var(--m-card); color: #e5484d;
  font: inherit; font-size: 14px; cursor: pointer;
}

.searchbox {
  display: flex; align-items: center; gap: 8px; margin: 0 16px 10px; padding: 0 12px;
  min-height: 42px; border-radius: 12px; background: var(--m-bg); color: var(--m-text-dim);
}
.searchbox input { flex: 1; min-width: 0; height: 40px; border: 0; outline: 0; background: transparent; color: var(--m-text); font: inherit; font-size: 14px; }
.pick-list { max-height: 42vh; overflow-y: auto; padding: 0 8px 6px; }
.pick-row { display: block; width: 100%; padding: 11px 10px; text-align: left; border: 0; background: transparent; color: var(--m-text); font-family: inherit; cursor: pointer; border-radius: 10px; }
.pick-row:active { background: var(--m-card-2); }
.pick-title { display: block; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pick-sub { display: block; margin-top: 2px; font-size: 11.5px; color: var(--m-text-dim); }
.pick-empty { padding: 18px; text-align: center; font-size: 12.5px; color: var(--m-text-dim); }

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
