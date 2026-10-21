<template>
  <div class="page">
    <el-tabs v-model="tab">
      <!-- ============ 题库 ============ -->
      <el-tab-pane label="题库" name="bank">
        <div class="bar">
          <el-button type="primary" @click="openQuestion()">新建题目</el-button>
          <el-select v-model="qCat" placeholder="全部分类" clearable style="width: 180px"
                     @change="loadQ" class="cat-sel">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
          <span class="count-hint">共 {{ questions.length }} 道{{ qCat ? '（' + qCat + '）' : '' }}</span>
          <el-button v-if="qCat" type="primary" plain @click="assembleByCategory">用该分类组卷</el-button>
          <el-button v-if="qSel.length" type="danger" plain @click="delQuestionsBatch">批量删除（{{ qSel.length }}）</el-button>
        </div>
        <el-table v-stack-table :data="questions" border stripe v-loading="loadingQ"
                   @selection-change="(rows) => qSel = rows.map(r => r.id)">
          <el-table-column type="selection" width="46" />
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
          <span class="count-hint">共 {{ papers.length }} 份</span>
          <el-button v-if="pSel.length" type="danger" plain @click="delPapersBatch">批量删除（{{ pSel.length }}）</el-button>
          <span class="mock-hint">「模拟卷」开关：全站学员的「模拟练习」固定用这份卷，同时只能有一份</span>
        </div>
        <el-table v-stack-table :data="papers" border stripe v-loading="loadingP"
                   @selection-change="(rows) => pSel = rows.map(r => r.id)">
          <el-table-column type="selection" width="46" />
          <el-table-column prop="id" label="ID" width="70"  class-name="stack-hide" />
          <el-table-column prop="title" label="标题" min-width="160" />
          <el-table-column prop="questionCount" label="题量" width="80" />
          <el-table-column prop="totalScore" label="满分" width="80" />
          <el-table-column prop="passScore" label="及格" width="80" />
          <el-table-column label="模拟卷" width="92">
            <template #default="{ row }">
              <el-switch :model-value="row.mockEnabled === true" :loading="mockSaving === row.id"
                         :disabled="row.status !== 'PUBLISHED'"
                         @change="(v) => toggleMock(row, v)" />
            </template>
          </el-table-column>
          <el-table-column label="练习" width="80">
            <template #default="{ row }">
              <el-tag :type="row.practiceEnabled === false ? 'info' : 'success'" size="small" effect="plain">
                {{ row.practiceEnabled === false ? '关闭' : '开启' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="86">
            <template #default="{ row }">
              <el-tag :type="row.status === 'PUBLISHED' ? 'success' : 'info'" size="small" effect="plain">
                {{ row.status === 'PUBLISHED' ? '已发布' : '草稿' }}
              </el-tag>
            </template>
          </el-table-column>
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
        <el-form-item label="允许模拟">
          <el-switch v-model="pForm.practiceEnabled" />
          <span class="form-hint">开启后学员可选「练习模式」：答一题判一题看解析，不计成绩</span>
        </el-form-item>
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
        <div class="score-set">
          <span class="ss-lab">总分</span>
          <el-input-number v-model="aTotal" :min="1" :max="2000" size="small" style="width:110px" />
          <el-button size="small" plain @click="applyTotal">按总分分配</el-button>
          <span class="ss-lab">及格</span>
          <el-input-number v-model="aPass" :min="0" :max="2000" size="small" style="width:110px" />
        </div>
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

    <!-- 按分类组卷：先定总分/及格分，保存时平均分配到各题 -->
    <el-dialog v-model="catDlg" title="按分类组卷" width="420px">
      <div class="cat-form">
        <div class="cf-row"><span>分类</span><b>{{ qCat }}</b></div>
        <div class="cf-row"><span>题数</span><b>{{ catCount }} 题</b></div>
        <div class="cf-row"><span>总分</span><el-input-number v-model="catTotal" :min="1" :max="2000" /></div>
        <div class="cf-row"><span>及格分</span><el-input-number v-model="catPass" :min="0" :max="2000" /></div>
        <div class="cf-tip">总分按题数平均分配（余数补给前面的题），生成后可在试卷里继续调整。</div>
      </div>
      <template #footer>
        <el-button @click="catDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="confirmCatAssemble">生成试卷</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { errMsg } from '../../utils/errMsg'
import {
  adminQuestions, adminCreateQuestion, adminUpdateQuestion, adminDeleteQuestion, adminGetQuestion,
  adminPapers, adminCreatePaper, adminUpdatePaper, adminDeletePaper, adminAssemble,
  adminBatchDeleteQuestions, adminBatchDeletePapers, adminSetPaperMock
} from '../../api/training'

const tab = ref('bank')
const qCat = ref('')
const questions = ref([])
const loadingQ = ref(false)
const papers = ref([])
const loadingP = ref(false)
// 多选删除
const qSel = ref([])
const pSel = ref([])
// 切换页签 / 改筛选都清掉勾选：留着已删除的行会带着脏 id 去请求
watch(tab, () => { qSel.value = []; pSel.value = [] })
watch(qCat, () => { qSel.value = [] })

const typeText = (t) => ({ SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', ESSAY: '简答' }[t] || t)
// 分类列表从当前题目里提取：AI 批量出题后分类会杂，靠它快速归堆
const categories = computed(() => {
  const set = new Set()
  for (const q of questions.value) {
    const c = (q.category || '').trim()
    if (c) set.add(c)
  }
  return [...set].sort()
})
const hasOptions = computed(() => ['SINGLE', 'MULTI', 'JUDGE'].includes(qForm.value.type))

// 题目
const qDlg = ref(false)
const editingQ = ref(null)
let qLoadSeq = 0   // 题目详情请求代次（防回填竞态）
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
  // 请求代次守卫（审查 r10 High）：回填完成前用户可能关闭弹窗/切到另一题，晚到的响应不能覆盖
  const seq = ++qLoadSeq
  qForm.value = { ...row, options: [] }
  qDlg.value = true
  try {
    const { data } = await adminGetQuestion(row.id)
    if (seq !== qLoadSeq || !qDlg.value) return
    const opts = (data.options || []).map((o) => ({ label: o.label, content: o.content, isCorrect: !!o.isCorrect }))
    qForm.value = {
      ...data,
      options: opts.length ? opts : [{ label: 'A', content: '', isCorrect: false }],
    }
  } catch (e) {
    if (seq !== qLoadSeq) return
    ElMessage.error(errMsg(e, '读取题目详情失败'))
    qDlg.value = false
  }
}
const saveQuestion = () => {
  saving.value = true
  const f = qForm.value
  // 保存前校验（审查 r10 High）：空题干 / 空选项 / 无正确项不能入库——
  // 后端不校验，脏数据会直接影响考生（无答案题/0 分题）
  const content = String(f.content || '').trim()
  if (!content) { ElMessage.warning('题干不能为空'); saving.value = false; return }
  const q = { type: f.type, content, analysis: f.analysis, difficulty: f.difficulty, category: f.category }
  const opts = hasOptions.value ? f.options.map((o, i) => ({ label: String.fromCharCode(65 + i), content: String(o.content || '').trim(), isCorrect: !!o.isCorrect })) : []
  if (hasOptions.value) {
    if (opts.length < 2) { ElMessage.warning('客观题至少需要 2 个选项'); saving.value = false; return }
    if (opts.some((o) => !o.content)) { ElMessage.warning('存在空选项，请补全内容或删掉该行'); saving.value = false; return }
    const correct = opts.filter((o) => o.isCorrect).length
    if (f.type === 'SINGLE' || f.type === 'JUDGE') {
      if (correct !== 1) { ElMessage.warning(f.type === 'SINGLE' ? '单选题必须有且只有 1 个正确项' : '判断题必须有且只有 1 个正确项'); saving.value = false; return }
    } else if (f.type === 'MULTI' && correct < 2) {
      ElMessage.warning('多选题至少要有 2 个正确项'); saving.value = false; return
    }
  }
  const req = editingQ.value ? adminUpdateQuestion(editingQ.value.id, q, opts) : adminCreateQuestion(q, opts)
  req.then(() => { ElMessage.success('已保存'); qDlg.value = false; loadQ() })
    .catch(e => ElMessage.error(errMsg(e, '保存失败')))
    .finally(() => saving.value = false)
}
const delQuestion = (row) => ElMessageBox.confirm('确定删除该题目？关联试卷将丢失此题目。', '提示', { type: 'warning' })
  .then(() => adminDeleteQuestion(row.id)).then(() => { ElMessage.success('已删除'); loadQ() })
  // 删除失败不能静默（审查 r10 Medium）：网络/权限/外键失败要让用户知道，否则以为已删
  .catch((e) => { if (e !== 'cancel' && e !== 'close') ElMessage.error(errMsg(e, '删除失败')) })

/** 批量删除题目：把影响面说清楚（不是不可逆到说不清，但试卷关联会被清） */
const delQuestionsBatch = () => ElMessageBox.confirm(
  `确定删除选中的 ${qSel.value.length} 道题目？\n\n这些题目在相关试卷里的关联也会一并移除，试卷总分会重新计算。`,
  '批量删除', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' },
)
  .then(() => adminBatchDeleteQuestions(qSel.value))
  .then((r) => {
    ElMessage.success(`已删除 ${r.data?.deleted ?? qSel.value.length} 道题目`)
    qSel.value = []
    loadQ()
    loadP()   // 试卷总分/题量可能被重算，顺手刷新
  })
  .catch((e) => { if (e !== 'cancel' && e !== 'close') ElMessage.error(errMsg(e, '批量删除失败')) })

// 试卷
const pDlg = ref(false)
const editingP = ref(null)
const pForm = ref(blankP())
function blankP() { return { title: '', description: '', passScore: 60, durationMin: 0, status: 'PUBLISHED', practiceEnabled: true } }
const openPaper = (row) => { editingP.value = row || null; pForm.value = row ? { ...row, practiceEnabled: row.practiceEnabled !== false } : blankP(); pDlg.value = true }
const savePaper = () => {
  saving.value = true
  const f = pForm.value
  const p = { title: f.title, description: f.description, passScore: f.passScore, durationMin: f.durationMin, status: f.status, practiceEnabled: f.practiceEnabled !== false }
  const req = editingP.value ? adminUpdatePaper(editingP.value.id, p) : adminCreatePaper(p)
  req.then(() => { ElMessage.success('已保存'); pDlg.value = false; loadP() })
    .catch(e => ElMessage.error(errMsg(e, '保存失败')))
    .finally(() => saving.value = false)
}
const delPaper = (row) => ElMessageBox.confirm('删除试卷会同时删除相关考试记录，确定？', '提示', { type: 'warning' })
  .then(() => adminDeletePaper(row.id)).then(() => { ElMessage.success('已删除'); loadP() }).catch(() => {})

// 模拟卷：试卷自身的属性，直接在列表里拨开关（2026-10-21 改）
const mockSaving = ref(0)
async function toggleMock(row, enabled) {
  if (enabled) {
    const ok = await ElMessageBox.confirm(
      `把《${row.title}》设为全站模拟卷？\n\n学员端「模拟练习」会固定进入这份卷，原来设的那份自动取消。`,
      '设为模拟卷', { type: 'info', confirmButtonText: '确定' },
    ).then(() => true).catch(() => false)
    if (!ok) return
  }
  mockSaving.value = row.id
  try {
    await adminSetPaperMock(row.id, enabled)
    ElMessage.success(enabled ? '已设为模拟卷' : '已取消模拟卷')
    loadP()
  } catch (e) {
    ElMessage.error(errMsg(e, '保存失败'))
    loadP()
  } finally {
    mockSaving.value = 0
  }
}

/** 批量删除试卷：考试记录会一并删除，提示要比单条更醒目 */
const delPapersBatch = () => ElMessageBox.confirm(
  `确定删除选中的 ${pSel.value.length} 份试卷？\n\n这些试卷下的组卷关联和考生的考试记录都会一并删除，且无法恢复。`,
  '批量删除', { type: 'warning', confirmButtonText: '删除', confirmButtonClass: 'el-button--danger' },
)
  .then(() => adminBatchDeletePapers(pSel.value))
  .then((r) => {
    ElMessage.success(`已删除 ${r.data?.deleted ?? pSel.value.length} 份试卷`)
    pSel.value = []
    loadP()
  })
  .catch((e) => { if (e !== 'cancel' && e !== 'close') ElMessage.error(errMsg(e, '批量删除失败')) })

// 组卷
const aDlg = ref(false)
const selPaper = ref(null)
const assembleNote = ref('勾选题目并设置分值，保存后自动计算总分与题量。')
const qSearch = ref('')
const pick = reactive({})
const scores = reactive({})
const aTotal = ref(100)   // 组卷目标总分（按勾选题平均分配）
const aPass = ref(60)     // 及格分（保存时写回试卷）
/** 把总分平均分到已勾选的题目上（余数补给前面的题），管理员不用逐题算分值 */
function applyTotal() {
  const ids = Object.keys(pick).filter((id) => pick[id])
  if (!ids.length) { ElMessage.warning('先勾选题目'); return }
  const total = Math.max(1, Number(aTotal.value) || 0)
  const base = Math.floor(total / ids.length)
  let rem = total % ids.length
  ids.forEach((id) => { scores[id] = base + (rem > 0 ? 1 : 0); if (rem > 0) rem -= 1 })
}
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
  // 及格分不能高于总分（总分 = 各题分值之和）
  const sum = items.reduce((s, it) => s + it.score, 0)
  if (aPass.value > sum) { ElMessage.warning(`及格分（${aPass.value}）不能高于总分（${sum}）`); return }
  saving.value = true
  adminAssemble(selPaper.value.id, items)
    .then(() => {
      // 组卷后写回及格分（试卷表单里也能改，这里让组卷一步到位）
      if (selPaper.value && selPaper.value.passScore !== aPass.value) {
        const s = selPaper.value
        return adminUpdatePaper(s.id, {
          title: s.title, description: s.description, passScore: aPass.value,
          durationMin: s.durationMin, status: s.status,
        })
      }
      return null
    })
    .then(() => { ElMessage.success('组卷成功'); aDlg.value = false; loadP() })
    .catch(e => ElMessage.error(errMsg(e, '组卷失败')))
    .finally(() => saving.value = false)
}

/** 把当前分类下的题一次性组成试卷：AI 批量出题后逐题勾选太累，这也是「生成了用不上」的根源 */
const catDlg = ref(false)
const catCount = ref(0)
const catTotal = ref(100)
const catPass = ref(60)

function assembleByCategory() {
  const list = questions.value.filter((q) => (q.category || '') === qCat.value)
  if (!list.length) { ElMessage.warning('该分类下没有题目'); return }
  catCount.value = list.length
  catTotal.value = Math.max(100, list.length * 5)   // 默认每题 5 分，至少 100
  catPass.value = Math.round(catTotal.value * 0.6)
  catDlg.value = true
}

async function confirmCatAssemble() {
  const list = questions.value.filter((q) => (q.category || '') === qCat.value)
  if (!list.length) { ElMessage.warning('该分类下没有题目'); return }
  if (catPass.value > catTotal.value) { ElMessage.warning('及格分不能高于总分'); return }
  saving.value = true
  try {
    const { data: paper } = await adminCreatePaper({
      title: qCat.value + ' 试卷',
      description: '由题库分类自动组卷',
      passScore: catPass.value,
      durationMin: 60,
      status: 'PUBLISHED',
    })
    // 总分平均分到各题（余数补给前面的题）
    const base = Math.floor(catTotal.value / list.length)
    let rem = catTotal.value % list.length
    const items = list.map((q) => {
      const s = base + (rem > 0 ? 1 : 0)
      if (rem > 0) rem -= 1
      return { questionId: q.id, score: s }
    })
    try {
      await adminAssemble(paper.id, items)
    } catch (e2) {
      // 组卷失败要回滚空试卷（审查 r10 High）：否则留下 0 题 0 分的「已发布」卷，考生端可见
      await adminDeletePaper(paper.id).catch(() => {})
      throw e2
    }
    ElMessage.success(`已生成试卷《${paper.title}》：${items.length} 题 / ${catTotal.value} 分（及格 ${catPass.value}）`)
    catDlg.value = false
    loadP()
    tab.value = 'papers'
  } catch (e) {
    ElMessage.error(errMsg(e, '组卷失败'))
  } finally {
    saving.value = false
  }
}

const loadQ = () => {
  loadingQ.value = true
  adminQuestions(qCat.value || undefined)
    .then(r => { questions.value = r.data || r || [] })
    .catch(e => ElMessage.error(errMsg(e, '加载题库失败')))
    .finally(() => loadingQ.value = false)
}
const loadP = () => { loadingP.value = true; adminPapers().then(r => { papers.value = r.data || r || [] }).catch(() => {}).finally(() => loadingP.value = false) }

onMounted(() => { loadQ(); loadP() })
</script>

<style scoped>
.page { padding: 20px; max-width: 1000px; margin: 0 auto; }
.bar { margin-bottom: 12px; display: flex; gap: 10px; }
.score-set { margin-left: auto; display: flex; align-items: center; gap: 8px; }
.ss-lab { font-size: 12.5px; color: #94a3b8; }
.cat-form { display: flex; flex-direction: column; gap: 12px; }
.cf-row { display: flex; align-items: center; justify-content: space-between; font-size: 13.5px; color: #475569; }
.cf-row b { color: #0f172a; font-weight: 600; }
.cf-tip { font-size: 12px; color: #94a3b8; line-height: 1.5; }
.mock-hint { margin-left: auto; font-size: 12px; color: #94a3b8; }
.form-hint { margin-left: 10px; font-size: 12px; color: #94a3b8; }
.opts { display: flex; flex-direction: column; gap: 8px; }
.orow { display: flex; align-items: center; gap: 8px; }
.orow .lab { width: 22px; font-weight: 700; }
.oc { flex: 1; }
.sf { max-width: 240px; }
.count-hint { font-size: 12px; color: var(--el-text-color-secondary); margin-right: auto; }
.cat-sel { flex: none; }

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
