<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- ============ 题库 ============ -->
      <el-tab-pane label="题库" name="bank">
        <div class="bar">
          <el-button type="primary" @click="openQuestion()">新建题目</el-button>
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
  adminQuestions, adminCreateQuestion, adminUpdateQuestion, adminDeleteQuestion, adminGetQuestion,
  adminPapers, adminCreatePaper, adminUpdatePaper, adminDeletePaper, adminAssemble
} from '../../api/training'

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

const openQuestion = async (row) => {
  editingQ.value = row || null
  if (!row) {
    qForm.value = blankQ()
    qDlg.value = true
    return
  }
  // 列表接口为了省流量不返回 options；直接拿列表行会得到空选项，保存就把原选项清空了
  qForm.value = { ...row, options: [] }
  qDlg.value = true
  try {
    const { data } = await adminGetQuestion(row.id)
    const opts = (data.options || []).map((o) => ({ label: o.label, content: o.content, isCorrect: !!o.isCorrect }))
    qForm.value = {
      ...data,
      options: opts.length ? opts : [{ label: 'A', content: '', isCorrect: false }],
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '读取题目详情失败'))
    qDlg.value = false
  }
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
}
</style>
