<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- ============ 题库 ============ -->
      <el-tab-pane label="题库" name="bank">
        <div class="bar">
          <el-button type="primary" @click="openQuestion()">新建题目</el-button>
          <el-button @click="openAi">AI 出题</el-button>
        </div>
        <el-table v-stack-table :data="questions" border stripe v-loading="loadingQ">
          <el-table-column prop="id" label="ID" width="70"  class-name="stack-hide" />
          <el-table-column label="题型" width="90">
            <template #default="{ row }">{{ typeText(row.type) }}</template>
          </el-table-column>
          <el-table-column prop="content" label="题干" min-width="280" show-overflow-tooltip />
          <el-table-column prop="difficulty" label="难度" width="70" />
          <el-table-column prop="category" label="分类" width="110" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button size="small" @click="openQuestion(row)">编辑</el-button>
              <el-button size="small" type="danger" @click="delQuestion(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- ============ 试卷 ============ -->
      <el-tab-pane label="试卷" name="papers">
        <div class="bar">
          <el-button type="primary" @click="openPaper()">新建试卷</el-button>
        </div>
        <el-table v-stack-table :data="papers" border stripe v-loading="loadingP">
          <el-table-column prop="id" label="ID" width="70"  class-name="stack-hide" />
          <el-table-column prop="title" label="标题" min-width="160" />
          <el-table-column prop="questionCount" label="题量" width="80" />
          <el-table-column prop="totalScore" label="满分" width="80" />
          <el-table-column prop="passScore" label="及格" width="80" />
          <el-table-column prop="status" label="状态" width="90" />
          <el-table-column label="操作" min-width="220">
            <template #default="{ row }">
              <el-button size="small" type="primary" @click="openAssemble(row)">组卷</el-button>
              <el-button size="small" @click="openPaper(row)">编辑</el-button>
              <el-button size="small" type="danger" @click="delPaper(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 题目弹窗 -->
    <el-dialog v-model="qDlg" :title="editingQ ? '编辑题目' : '新建题目'" width="640px">
      <el-form label-width="80px">
        <el-form-item label="题型">
          <el-select v-model="qForm.type" @change="onTypeChange">
            <el-option v-for="t in ['SINGLE','MULTI','JUDGE','FILL','ESSAY']" :key="t" :value="t" :label="typeText(t)" />
          </el-select>
        </el-form-item>
        <el-form-item label="题干"><el-input v-model="qForm.content" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="解析"><el-input v-model="qForm.analysis" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="难度"><el-input-number v-model="qForm.difficulty" :min="1" :max="5" /></el-form-item>
        <el-form-item label="分类"><el-input v-model="qForm.category" placeholder="如：网络安全" /></el-form-item>

        <el-form-item v-if="hasOptions" label="选项">
          <div class="opts">
            <div v-for="(o, i) in qForm.options" :key="i" class="orow">
              <span class="lab">{{ String.fromCharCode(65 + i) }}</span>
              <el-input v-model="o.content" placeholder="选项内容" class="oc" />
              <el-checkbox v-model="o.isCorrect">正确</el-checkbox>
              <el-button text type="danger" size="small" @click="qForm.options.splice(i, 1)">删</el-button>
            </div>
            <el-button size="small" @click="qForm.options.push({ label: '', content: '', isCorrect: false })">+ 添加选项</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="qDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveQuestion">保存</el-button>
      </template>
    </el-dialog>

    <!-- 试卷弹窗 -->
    <el-dialog v-model="pDlg" :title="editingP ? '编辑试卷' : '新建试卷'" width="520px">
      <el-form label-width="80px">
        <el-form-item label="标题"><el-input v-model="pForm.title" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="pForm.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="及格分"><el-input-number v-model="pForm.passScore" :min="0" /></el-form-item>
        <el-form-item label="时长(分)"><el-input-number v-model="pForm.durationMin" :min="0" /></el-form-item>
        <el-form-item label="状态">
          <el-select v-model="pForm.status"><el-option value="PUBLISHED" label="已发布" /><el-option value="DRAFT" label="草稿" /></el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="savePaper">保存</el-button>
      </template>
    </el-dialog>

    <!-- AI 出题弹窗：先设条件 → 生成候选 → 勾选入库（不直接写题库） -->
    <el-dialog v-model="aiDlg" title="AI 出题" width="800px" :close-on-click-modal="false">
      <!-- 第一步：出题条件 -->
      <el-form v-if="!aiResult" label-width="92px">
        <el-form-item label="出题主题">
          <el-input v-model="aiForm.topic" maxlength="200" show-word-limit
                    placeholder="如：员工请假审批流程 / 信息安全等级保护基本要求" />
        </el-form-item>
        <el-form-item label="题量">
          <el-input-number v-model="aiForm.count" :min="1" :max="20" />
          <span class="hint">最多 20 道，生成后可逐题取舍</span>
        </el-form-item>
        <el-form-item label="题型">
          <el-checkbox-group v-model="aiForm.types">
            <el-checkbox v-for="t in TYPE_OPTS" :key="t.value" :value="t.value">{{ t.label }}</el-checkbox>
          </el-checkbox-group>
          <div class="hint block">不勾选则由 AI 自行搭配</div>
        </el-form-item>
        <el-form-item label="难度">
          <el-radio-group v-model="aiForm.difficulty">
            <el-radio :value="0">不限</el-radio>
            <el-radio v-for="d in [1, 2, 3, 4, 5]" :key="d" :value="d">{{ d }}</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="分类">
          <el-input v-model="aiForm.category" placeholder="写入题目的分类，可留空" />
        </el-form-item>
        <el-form-item label="出题依据">
          <el-radio-group v-model="aiForm.sourceType" @change="onSourceChange">
            <el-radio value="DOC">知识库文档</el-radio>
            <el-radio value="WEB">指定网页</el-radio>
            <el-radio value="AI">AI 通用知识</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="aiForm.sourceType === 'DOC'" label="空间">
          <el-select v-model="aiForm.shareId" placeholder="选择知识库空间" style="width:100%" clearable>
            <el-option v-for="s in shares" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
          <div class="hint block">不指定文档时，取该空间最近 8 篇文档作依据</div>
        </el-form-item>
        <el-form-item v-else-if="aiForm.sourceType === 'WEB'" label="参考链接">
          <el-input v-model="aiUrls" type="textarea" :rows="4" placeholder="每行一个链接，最多 5 个" />
          <div class="hint block">服务器会抓取这些网页的正文作为出题依据</div>
        </el-form-item>
        <el-alert v-else type="warning" :closable="false" show-icon class="ai-warn"
                  title="不依据知识库内容"
                  description="题目完全由 AI 凭通用知识生成，可能与你们的实际制度不符，保存前请逐题核对。" />
        <el-form-item label="额外要求">
          <el-input v-model="aiForm.extra" type="textarea" :rows="2"
                    placeholder="如：侧重实操判断、避免考条款编号、题干口语化" />
        </el-form-item>
      </el-form>

      <!-- 第二步：预览勾选 -->
      <div v-else class="ai-result">
        <el-alert v-if="aiResult.warnings && aiResult.warnings.length" type="warning" :closable="false" show-icon>
          <template #title>{{ aiResult.warnings.length }} 条提示</template>
          <div v-for="(w, i) in aiResult.warnings" :key="i" class="warn-line">· {{ w }}</div>
        </el-alert>

        <div class="mat-line">
          <span>依据：</span>
          <template v-if="aiResult.materials && aiResult.materials.length">
            <el-tag v-for="(m, i) in aiResult.materials" :key="i" size="small" type="info" class="mat-tag">
              {{ m.title }}<template v-if="m.chars"> · {{ m.chars }} 字</template>
            </el-tag>
          </template>
          <span v-else class="muted">无（AI 通用知识）</span>
          <span class="muted">· 模型 {{ aiResult.model }}</span>
        </div>

        <div class="pick-bar">
          <el-checkbox v-model="pickAll" :indeterminate="pickIndeterminate">全选</el-checkbox>
          <span class="muted">已选 {{ pickedCount }} / {{ (aiResult.questions || []).length }}</span>
        </div>

        <div v-for="(q, i) in aiResult.questions" :key="i" class="qcard" :class="{ off: !pickQ[i] }">
          <div class="qhead">
            <el-checkbox v-model="pickQ[i]" />
            <el-tag size="small" type="primary">{{ typeText(q.type) }}</el-tag>
            <el-tag size="small" type="info">难度 {{ q.difficulty }}</el-tag>
            <span class="qtext">{{ q.content }}</span>
          </div>
          <div v-if="q.options && q.options.length" class="qopts">
            <div v-for="o in q.options" :key="o.label" class="qopt" :class="{ ok: o.isCorrect }">
              <b>{{ o.label }}</b> {{ o.content }}
            </div>
          </div>
          <div v-if="q.analysis" class="qana">解析：{{ q.analysis }}</div>
        </div>
      </div>

      <template #footer>
        <el-button @click="aiDlg = false">关闭</el-button>
        <el-button v-if="aiResult" @click="aiResult = null">重新设置</el-button>
        <el-button v-if="!aiResult" type="primary" :loading="aiLoading"
                   :disabled="!aiForm.topic.trim()" @click="doGenerate">
          {{ aiLoading ? '生成中，通常 20~60 秒…' : '开始生成' }}
        </el-button>
        <el-button v-else type="primary" :loading="saving" :disabled="!pickedCount" @click="savePicked">
          保存选中 {{ pickedCount }} 道
        </el-button>
      </template>
    </el-dialog>

    <!-- 组卷弹窗 -->
    <el-dialog v-model="aDlg" :title="'组卷：' + (selPaper ? selPaper.title : '')" width="680px">
      <el-alert v-if="assembleNote" type="info" :closable="false" :title="assembleNote" style="margin-bottom:10px" />
      <div class="bar">
        <el-input v-model="qSearch" placeholder="筛选题干关键词" class="sf" />
      </div>
      <el-table v-stack-table :data="filteredQ" border max-height="420">
        <el-table-column width="50">
          <template #default="{ row }">
            <el-checkbox v-model="pick[row.id]" />
          </template>
        </el-table-column>
        <el-table-column prop="content" label="题干" min-width="240" show-overflow-tooltip />
        <el-table-column label="题型" width="80"><template #default="{ row }">{{ typeText(row.type) }}</template></el-table-column>
        <el-table-column label="分值" width="110">
          <template #default="{ row }">
            <el-input-number v-model="scores[row.id]" :min="0" :disabled="!pick[row.id]" size="small" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="aDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doAssemble">保存组卷</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errMsg } from '../../utils/errMsg'
import {
  adminQuestions, adminCreateQuestion, adminUpdateQuestion, adminDeleteQuestion,
  adminPapers, adminCreatePaper, adminUpdatePaper, adminDeletePaper, adminAssemble,
  adminAiGenerateQuestions, adminBatchCreateQuestions
} from '../../api/training'
import { listMyShares } from '../../api/docShare'

const tab = ref('bank')
const questions = ref([])
const loadingQ = ref(false)
const papers = ref([])
const loadingP = ref(false)

const typeText = (t) => ({ SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }[t] || t)
const hasOptions = computed(() => ['SINGLE', 'MULTI', 'JUDGE'].includes(qForm.value.type))

// 题目
const qDlg = ref(false)
const editingQ = ref(null)
const saving = ref(false)
const qForm = ref(blankQ())
function blankQ() { return { type: 'SINGLE', content: '', analysis: '', difficulty: 1, category: '', options: [{ label: 'A', content: '', isCorrect: false }] } }
const onTypeChange = () => { if (hasOptions.value && (!qForm.value.options || !qForm.value.options.length)) qForm.value.options = [{ label: 'A', content: '', isCorrect: false }] }

const openQuestion = (row) => {
  editingQ.value = row || null
  qForm.value = row ? { ...row, options: row.type !== 'FILL' && row.type !== 'ESSAY' ? [{ label: 'A', content: '', isCorrect: false }] : [] } : blankQ()
  qDlg.value = true
}
const saveQuestion = () => {
  saving.value = true
  const f = qForm.value
  const q = { type: f.type, content: f.content, analysis: f.analysis, difficulty: f.difficulty, category: f.category }
  const opts = hasOptions.value ? f.options.map((o, i) => ({ label: String.fromCharCode(65 + i), content: o.content, isCorrect: !!o.isCorrect })) : []
  const req = editingQ.value ? adminUpdateQuestion(editingQ.value.id, q, opts) : adminCreateQuestion(q, opts)
  req.then(() => { ElMessage.success('已保存'); qDlg.value = false; loadQ() })
    .catch(e => ElMessage.error(errMsg(e, '保存失败')))
    .finally(() => saving.value = false)
}
const delQuestion = (row) => ElMessageBox.confirm('确定删除该题目？关联试卷将丢失此题目。', '提示', { type: 'warning' })
  .then(() => adminDeleteQuestion(row.id)).then(() => { ElMessage.success('已删除'); loadQ() }).catch(() => {})

// 试卷
const pDlg = ref(false)
const editingP = ref(null)
const pForm = ref(blankP())
function blankP() { return { title: '', description: '', passScore: 60, durationMin: 0, status: 'PUBLISHED' } }
const openPaper = (row) => { editingP.value = row || null; pForm.value = row ? { ...row } : blankP(); pDlg.value = true }
const savePaper = () => {
  saving.value = true
  const f = pForm.value
  const p = { title: f.title, description: f.description, passScore: f.passScore, durationMin: f.durationMin, status: f.status }
  const req = editingP.value ? adminUpdatePaper(editingP.value.id, p) : adminCreatePaper(p)
  req.then(() => { ElMessage.success('已保存'); pDlg.value = false; loadP() })
    .catch(e => ElMessage.error(errMsg(e, '保存失败')))
    .finally(() => saving.value = false)
}
const delPaper = (row) => ElMessageBox.confirm('删除试卷会同时删除相关考试记录，确定？', '提示', { type: 'warning' })
  .then(() => adminDeletePaper(row.id)).then(() => { ElMessage.success('已删除'); loadP() }).catch(() => {})

// 组卷
const aDlg = ref(false)
const selPaper = ref(null)
const assembleNote = ref('勾选题目并设置分值，保存后自动计算总分与题量。')
const qSearch = ref('')
const pick = reactive({})
const scores = reactive({})
const filteredQ = computed(() => questions.value.filter(q => !qSearch.value || (q.content || '').includes(qSearch.value)))
const openAssemble = (row) => {
  selPaper.value = row
  for (const k in pick) delete pick[k]
  for (const k in scores) delete scores[k]
  aDlg.value = true
}
const doAssemble = () => {
  const items = Object.keys(pick).filter(id => pick[id]).map(id => ({ questionId: Number(id), score: scores[id] || 0 }))
  if (!items.length) { ElMessage.warning('请至少勾选一题'); return }
  saving.value = true
  adminAssemble(selPaper.value.id, items)
    .then(() => { ElMessage.success('组卷成功'); aDlg.value = false; loadP() })
    .catch(e => ElMessage.error(errMsg(e, '组卷失败')))
    .finally(() => saving.value = false)
}

// ===================== AI 出题 =====================
const TYPE_OPTS = [
  { value: 'SINGLE', label: '单选' },
  { value: 'MULTI', label: '多选' },
  { value: 'JUDGE', label: '判断' },
  { value: 'FILL', label: '填空' },
  { value: 'ESSAY', label: '简答' },
]

const aiDlg = ref(false)
const aiLoading = ref(false)
const aiResult = ref(null)
const aiUrls = ref('')
const shares = ref([])
const pickQ = ref({})
const aiForm = ref(blankAi())

function blankAi() {
  return {
    topic: '', count: 5, types: ['SINGLE', 'MULTI', 'JUDGE'],
    difficulty: 0, category: '', sourceType: 'DOC', shareId: null, extra: '',
  }
}

const pickedCount = computed(() => Object.values(pickQ.value).filter(Boolean).length)
const pickAll = computed({
  get: () => {
    const n = (aiResult.value?.questions || []).length
    return n > 0 && pickedCount.value === n
  },
  set: (v) => {
    const next = {}
    ;(aiResult.value?.questions || []).forEach((_, i) => { next[i] = v })
    pickQ.value = next
  },
})
const pickIndeterminate = computed(() => {
  const n = (aiResult.value?.questions || []).length
  return pickedCount.value > 0 && pickedCount.value < n
})

const openAi = () => {
  aiForm.value = blankAi()
  aiResult.value = null
  aiUrls.value = ''
  pickQ.value = {}
  aiDlg.value = true
  const useFirst = () => {
    if (!aiForm.value.shareId && shares.value.length) aiForm.value.shareId = shares.value[0].id
  }
  if (shares.value.length) { useFirst(); return }
  listMyShares()
    .then((r) => {
      shares.value = (r.data || r || []).map((s) => ({ id: s.id, name: s.name || s.title || ('空间 ' + s.id) }))
      useFirst()
    })
    .catch(() => {})
}

/**
 * 切到「指定网页」「AI 通用知识」时先问一句——这两条路都会离开知识库的边界：
 * 前者让服务器去访问外部网站，后者完全不受内部资料约束。
 */
const onSourceChange = async (v) => {
  if (v === 'WEB') {
    try {
      await ElMessageBox.confirm(
        '将让服务器访问你填写的网页并抓取正文作为出题依据，请确认这些地址是你信任的。',
        '确认联网获取资料',
        { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' },
      )
    } catch { aiForm.value.sourceType = 'DOC' }
  } else if (v === 'AI') {
    try {
      await ElMessageBox.confirm(
        '题目将完全不依据知识库内容，由 AI 凭通用知识生成，可能与你们的实际制度不符。是否继续？',
        '确认不依据知识库',
        { type: 'warning', confirmButtonText: '仍要用通用知识', cancelButtonText: '取消' },
      )
    } catch { aiForm.value.sourceType = 'DOC' }
  }
}

const doGenerate = () => {
  const f = aiForm.value
  if (!f.topic.trim()) { ElMessage.warning('请填写出题主题'); return }
  if (f.sourceType === 'DOC' && !f.shareId) { ElMessage.warning('请选择知识库空间'); return }
  const urls = aiUrls.value.split(/[\n\r,，\s]+/).map((s) => s.trim()).filter(Boolean)
  if (f.sourceType === 'WEB' && !urls.length) { ElMessage.warning('请至少填写一个参考链接'); return }

  const payload = {
    topic: f.topic.trim(),
    count: f.count,
    types: f.types,
    difficulty: f.difficulty,
    category: f.category,
    sourceType: f.sourceType,
    extra: f.extra,
    shareId: f.sourceType === 'DOC' ? f.shareId : null,
    urls: f.sourceType === 'WEB' ? urls : null,
  }
  aiLoading.value = true
  adminAiGenerateQuestions(payload)
    .then((r) => {
      const d = r.data || r
      const pick = {}
      ;(d.questions || []).forEach((_, i) => { pick[i] = true })
      pickQ.value = pick
      aiResult.value = d
    })
    .catch((e) => ElMessage.error(errMsg(e, '生成失败')))
    .finally(() => { aiLoading.value = false })
}

const savePicked = async () => {
  const qs = (aiResult.value?.questions || []).filter((_, i) => pickQ.value[i])
  if (!qs.length) { ElMessage.warning('请至少勾选一道题'); return }
  // 通用知识生成的内容没有内部依据，入库前再确认一次
  if (aiResult.value.sourceType === 'AI') {
    try {
      await ElMessageBox.confirm(
        `这 ${qs.length} 道题由 AI 凭通用知识生成，未经知识库校验。确认保存到题库？`,
        '保存前确认', { type: 'warning' },
      )
    } catch { return }
  }
  saving.value = true
  const items = qs.map((q) => ({
    question: {
      type: q.type, content: q.content, analysis: q.analysis,
      difficulty: q.difficulty, category: aiForm.value.category || '',
    },
    options: (q.options || []).map((o) => ({ label: o.label, content: o.content, isCorrect: !!o.isCorrect })),
  }))
  adminBatchCreateQuestions(items)
    .then((r) => {
      const saved = r.data || r || []
      ElMessage.success(`已保存 ${saved.length} 道题`)
      aiDlg.value = false
      loadQ()
    })
    .catch((e) => ElMessage.error(errMsg(e, '保存失败')))
    .finally(() => { saving.value = false })
}

const loadQ = () => { loadingQ.value = true; adminQuestions().then(r => { questions.value = r.data || r || [] }).catch(() => {}).finally(() => loadingQ.value = false) }
const loadP = () => { loadingP.value = true; adminPapers().then(r => { papers.value = r.data || r || [] }).catch(() => {}).finally(() => loadingP.value = false) }

onMounted(() => { loadQ(); loadP() })
</script>

<style scoped>
.page { padding: 20px; max-width: 1000px; margin: 0 auto; }
.bar { margin-bottom: 12px; display: flex; gap: 10px; }
.opts { display: flex; flex-direction: column; gap: 8px; }
.orow { display: flex; align-items: center; gap: 8px; }
.orow .lab { width: 22px; font-weight: 700; }
.oc { flex: 1; }
.sf { max-width: 240px; }

/* ===== AI 出题 ===== */
.hint { color: var(--el-text-color-secondary); font-size: 12px; margin-left: 8px; }
.hint.block { display: block; margin: 4px 0 0; }
.ai-warn { margin: 0 0 18px 92px; }
.ai-result { max-height: 58vh; overflow: auto; padding-right: 4px; }
.mat-line { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 10px 0; font-size: 12px; }
.mat-tag { max-width: 240px; overflow: hidden; text-overflow: ellipsis; }
.pick-bar { display: flex; align-items: center; gap: 12px; padding: 6px 0; margin-bottom: 8px; border-bottom: 1px solid var(--el-border-color-lighter); }
.qcard { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 10px 12px; margin-bottom: 8px; }
.qcard.off { opacity: .45; }
.qhead { display: flex; align-items: flex-start; gap: 8px; }
.qtext { flex: 1; line-height: 1.55; }
.qopts { display: flex; flex-direction: column; gap: 3px; margin: 6px 0 0 26px; }
.qopt { font-size: 13px; color: var(--el-text-color-regular); }
.qopt.ok { color: var(--el-color-success); font-weight: 600; }
.qana { margin: 6px 0 0 26px; font-size: 12px; color: var(--el-text-color-secondary); line-height: 1.6; }
.warn-line { font-size: 12px; line-height: 1.7; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }

/* ===== 响应式（统一断点 1024 / 640，只写布局与尺寸） ===== */
@media (max-width: 1024px) {
  .page { padding: 14px; }
  .bar { flex-wrap: wrap; }
  .orow { flex-wrap: wrap; }
  .oc { flex: 1 1 160px; min-width: 140px; }
  .el-tabs :deep(.el-table) { max-width: 100%; }
}
@media (max-width: 640px) {
  .page { padding: 12px; }
  .bar { gap: 8px; }
  .bar :deep(.el-button) { margin-left: 0; }
  .sf { max-width: 100%; }
  .opts { gap: 10px; }
  .orow { gap: 6px; }
  .orow .oc { flex: 1 1 100%; min-width: 0; }
  .ai-warn { margin-left: 0; }
  .ai-result { max-height: 62vh; }
}
</style>
