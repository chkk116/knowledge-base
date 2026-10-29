<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { gradingPending, gradingDetail, submitGrading } from '../../api/admin'

/**
 * 主观题阅卷。
 * 背景：填空 / 简答交卷时无法自动判分，ExamService 会把作答标记为 pending 并暂不计入总分。
 * 这里列出待阅卷的考试，管理员逐题给分（0 ~ 该题满分）并可选写批注；全部阅完后后端重算总分与通过状态。
 */
const loading = ref(false)
const list = ref([])
const selectedId = ref(null)
const detail = ref(null)
const detailLoading = ref(false)
const submitting = ref(false)

// questionId -> { score, comment }
const form = reactive({})

const pendingItems = computed(() => (detail.value?.items || []).filter((it) => it.pending))
const objectiveCount = computed(() => (detail.value?.items || []).filter((it) => !it.pending).length)
const totalAssigned = computed(() => {
  const base = detail.value?.record?.score || 0
  return base + pendingItems.value.reduce((a, it) => a + (Number(form[it.questionId]?.score) || 0), 0)
})

const typeText = (t) => ({ SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }[t] || t)
const fmtTime = (t) => {
  if (!t) return '-'
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return '-'
  return `${d.getMonth() + 1}-${d.getDate()} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

async function loadList(keepSelection = false) {
  loading.value = true
  try {
    const { data } = await gradingPending()
    list.value = data || []
    if (!keepSelection) {
      selectedId.value = null
      detail.value = null
    } else if (selectedId.value && !list.value.some((r) => r.recordId === selectedId.value)) {
      selectedId.value = null
      detail.value = null
    }
  } catch (e) {
    ElMessage.error('加载待阅卷列表失败：' + (e.response?.data?.error || e.response?.status || '网络异常'))
  } finally {
    loading.value = false
  }
}

async function open(recordId) {
  selectedId.value = recordId
  detailLoading.value = true
  detail.value = null
  try {
    const { data } = await gradingDetail(recordId)
    detail.value = data
    for (const k of Object.keys(form)) delete form[k]
    for (const it of data.items || []) {
      if (it.pending) form[it.questionId] = { score: 0, comment: '' }
    }
  } catch (e) {
    ElMessage.error('加载阅卷详情失败：' + (e.response?.data?.error || e.response?.status || '网络异常'))
    selectedId.value = null
  } finally {
    detailLoading.value = false
  }
}

async function submit() {
  if (!detail.value) return
  const items = pendingItems.value.map((it) => ({
    questionId: it.questionId,
    score: Number(form[it.questionId]?.score) || 0,
    comment: form[it.questionId]?.comment || '',
  }))
  if (!items.length) return ElMessage.warning('没有需要阅卷的题目')
  submitting.value = true
  try {
    const { data } = await submitGrading(detail.value.record.id, items)
    ElMessage.success(
      data.pendingCount > 0
        ? `已提交 ${data.graded} 题，仍有 ${data.pendingCount} 题待阅`
        : `阅卷完成：总分 ${data.score}，${data.passed ? '已通过' : '未通过'}`
    )
    await loadList(true)
    if (data.pendingCount > 0) await open(detail.value.record.id)
    else { selectedId.value = null; detail.value = null }
  } catch (e) {
    ElMessage.error('提交失败：' + (e.response?.data?.error || e.response?.status || '网络异常'))
  } finally {
    submitting.value = false
  }
}

onMounted(() => loadList())
</script>

<template>
  <div class="grading">
    <div class="hd">
      <div>
        <h1>主观题阅卷</h1>
        <p class="sub">填空 / 简答交卷时不计分，在这里给分；全部阅完后自动重算总分与通过状态</p>
      </div>
      <el-button size="small" :loading="loading" @click="loadList()">刷新</el-button>
    </div>

    <div class="cols">
      <!-- 左：待阅卷列表 -->
      <aside class="side">
        <div class="side-hd">待阅卷 <span>{{ list.length }}</span></div>
        <div v-if="loading" class="tip">加载中…</div>
        <div v-else-if="!list.length" class="empty">暂无待阅卷的考试 🎉</div>
        <div v-else class="items">
          <button
            v-for="r in list"
            :key="r.recordId"
            type="button"
            class="item"
            :class="{ on: selectedId === r.recordId }"
            @click="open(r.recordId)"
          >
            <div class="i-top">
              <span class="i-user">{{ r.user }}</span>
              <span class="i-cnt">{{ r.pendingCount }} 题</span>
            </div>
            <div class="i-paper">{{ r.paperTitle }}</div>
            <div class="i-time">交卷 {{ fmtTime(r.submittedAt) }} · 客观题 {{ r.score }} 分</div>
          </button>
        </div>
      </aside>

      <!-- 右：阅卷详情 -->
      <section class="main">
        <div v-if="detailLoading" class="tip">加载中…</div>
        <div v-else-if="!detail" class="empty-main">从左侧选择一场考试开始阅卷</div>
        <template v-else>
          <div class="m-hd">
            <div>
              <div class="m-title">{{ detail.user }} · {{ detail.record.paperTitle || ('试卷 #' + detail.record.paperId) }}</div>
              <div class="m-sub">
                客观题 {{ detail.record.score }} 分 · 待阅 {{ pendingItems.length }} 题
                <span v-if="objectiveCount"> · 已自动判分 {{ objectiveCount }} 题</span>
              </div>
            </div>
            <div class="m-total">
              <span class="t-num">{{ totalAssigned }}</span>
              <span class="t-lab">阅后预估总分</span>
            </div>
          </div>

          <div v-if="!pendingItems.length" class="empty-main">这场考试没有待阅卷题目</div>

          <div v-for="(it, i) in pendingItems" :key="it.questionId" class="q-card">
            <div class="q-hd">
              <span class="q-no">第 {{ i + 1 }} 题</span>
              <el-tag size="small" effect="plain">{{ typeText(it.type) }}</el-tag>
              <span class="q-max">满分 {{ it.maxScore }}</span>
            </div>

            <div class="q-content" v-html="it.content"></div>

            <div class="q-block">
              <div class="b-lab">学员作答</div>
              <div class="b-ans">{{ it.userAnswer || '（未作答）' }}</div>
            </div>

            <div v-if="(it.correctAnswer || []).length" class="q-block">
              <div class="b-lab">参考答案</div>
              <div class="b-ref">
                <div v-for="o in it.correctAnswer" :key="o.label">
                  <b v-if="o.label">{{ o.label }}</b><span v-html="o.content"></span>
                </div>
              </div>
            </div>

            <div v-if="it.analysis" class="q-block">
              <div class="b-lab">解析</div>
              <div class="b-ref" v-html="it.analysis"></div>
            </div>

            <div class="q-grade">
              <label>得分</label>
              <el-input-number v-model="form[it.questionId].score" :min="0" :max="it.maxScore" :step="1" size="small" />
              <span class="of">/ {{ it.maxScore }}</span>
              <el-button size="small" text type="primary" @click="form[it.questionId].score = it.maxScore">给满分</el-button>
              <el-button size="small" text @click="form[it.questionId].score = 0">给 0 分</el-button>
            </div>
            <div class="q-cmt">
              <el-input
                v-model="form[it.questionId].comment"
                size="small"
                maxlength="200"
                show-word-limit
                placeholder="批注（可选，学员在成绩详情可见）"
              />
            </div>
          </div>

          <div v-if="pendingItems.length" class="m-actions">
            <el-button type="primary" :loading="submitting" @click="submit">提交阅卷</el-button>
            <span class="hint">提交后不可重复给分（已阅题目会被跳过）</span>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<style scoped>
.grading { max-width: 1240px; margin: 0 auto; padding: 4px 2px 40px; }
.hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.hd h1 { margin: 0; font-size: 22px; }
.hd .sub { margin: 6px 0 0; font-size: 13.5px; color: #64748b; }

.cols { display: grid; grid-template-columns: 300px 1fr; gap: 16px; align-items: start; }

.side { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; overflow: hidden; position: sticky; top: 12px; }
.side-hd { padding: 12px 14px; font-size: 14px; font-weight: 600; color: #1e293b; border-bottom: 1px solid #f1f5f9; }
.side-hd span { color: #94a3b8; font-weight: 400; margin-left: 4px; }
.items { max-height: 620px; overflow-y: auto; }
.item { display: block; width: 100%; text-align: left; border: 0; background: transparent; padding: 11px 14px; border-bottom: 1px solid #f6f8fb; cursor: pointer; }
.item:hover { background: #f8fafc; }
.item.on { background: #eef2ff; }
.i-top { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; }
.i-user { font-size: 13.5px; font-weight: 600; color: #1e293b; }
.i-cnt { font-size: 11.5px; color: #b45309; background: #fffbeb; border-radius: 6px; padding: 1px 7px; }
.i-paper { margin-top: 3px; font-size: 12.5px; color: #475569; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.i-time { margin-top: 3px; font-size: 11.5px; color: #94a3b8; }

.main { background: #fff; border: 1px solid #eef2f7; border-radius: 14px; padding: 16px 18px; min-height: 240px; }
.m-hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-bottom: 12px; border-bottom: 1px solid #f1f5f9; margin-bottom: 14px; }
.m-title { font-size: 16px; font-weight: 600; color: #1e293b; }
.m-sub { margin-top: 4px; font-size: 12.5px; color: #64748b; }
.m-total { text-align: right; }
.t-num { display: block; font-size: 26px; font-weight: 800; color: #2563eb; line-height: 1; }
.t-lab { font-size: 11.5px; color: #94a3b8; }

.q-card { border: 1px solid #eef2f7; border-radius: 12px; padding: 14px 16px; margin-bottom: 12px; background: #fcfdff; }
.q-hd { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.q-no { font-size: 13.5px; font-weight: 600; color: #334155; }
.q-max { font-size: 12px; color: #94a3b8; }
.q-content { font-size: 14.5px; line-height: 1.7; color: #1e293b; margin-bottom: 10px; }
.q-block { margin-bottom: 10px; }
.b-lab { font-size: 12px; font-weight: 600; color: #64748b; margin-bottom: 4px; }
.b-ans { font-size: 13.5px; line-height: 1.7; color: #334155; background: #f8fafc; border: 1px solid #eef2f7; border-radius: 8px; padding: 8px 10px; white-space: pre-wrap; overflow-wrap: anywhere; }
.b-ref { font-size: 13px; line-height: 1.7; color: #475569; }
.q-grade { display: flex; align-items: center; gap: 8px; margin-top: 10px; }
.q-grade label { font-size: 13px; color: #475569; }
.q-grade .of { font-size: 12.5px; color: #94a3b8; }
.q-cmt { margin-top: 8px; }
.m-actions { display: flex; align-items: center; gap: 12px; margin-top: 16px; }
.m-actions .hint { font-size: 12px; color: #94a3b8; }

.tip, .empty, .empty-main { padding: 28px 0; text-align: center; color: #94a3b8; font-size: 13px; }

@media (max-width: 900px) {
  .cols { grid-template-columns: 1fr; }
  .side { position: static; }
  .items { max-height: 260px; }
}

:deep(html.dark) .side,
:deep(html.dark) .main { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .side-hd,
:deep(html.dark) .m-title,
:deep(html.dark) .i-user,
:deep(html.dark) .q-content { color: #e5e7f0; }
:deep(html.dark) .item { border-bottom-color: #26263e; }
:deep(html.dark) .item:hover { background: #23233a; }
:deep(html.dark) .item.on { background: #2a2a46; }
:deep(html.dark) .q-card { background: #202034; border-color: #2c2c44; }
:deep(html.dark) .b-ans { background: #23233a; border-color: #2c2c44; color: #d5d7e5; }
</style>
