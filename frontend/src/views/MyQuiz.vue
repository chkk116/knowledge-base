<template>
  <div class="page">
    <div class="hd">
      <div>
        <h1>我的题库</h1>
        <p class="sub">用 AI 从你的资料里出题，只属于你自己，不进公共题库</p>
      </div>
      <div class="hd-acts">
        <el-button type="primary" :disabled="!list.length" @click="$router.push('/my-quiz/run')">开始刷题</el-button>
        <el-button plain @click="openGen">从文档生成</el-button>
        <el-button plain @click="openImport">粘贴导入</el-button>
        <el-button plain @click="$router.push('/ai/chat?mode=agent')">让 AI 出题</el-button>
      </div>
    </div>

    <ExamTabs active="myquiz" />

    <div class="stats">
      <div class="st"><b>{{ stats.questions ?? 0 }}</b><span>我的题目</span></div>
      <div class="st"><b>{{ stats.limit ?? 300 }}</b><span>上限</span></div>
      <el-button size="small" plain @click="load">刷新</el-button>
    </div>

    <el-alert
      type="info" :closable="false" show-icon
      title="怎么加题：打开「AI 问答」，直接说「用我的《xxx》这篇文档出 5 道单选题」，Agent 会出题并存入这里。"
      class="tipbar" />

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="!list.length" class="empty">
      <div class="empty-ico">📝</div>
      <div class="empty-t">还没有自己的题目</div>
      <div class="empty-s">去 AI 问答里让它按你的文档出几道题</div>
      <el-button type="primary" @click="$router.push('/ai/chat?mode=agent')">去出题</el-button>
    </div>

    <div v-else class="list">
      <div v-for="(q, i) in list" :key="q.id" class="card">
        <div class="c-hd">
          <el-tag size="small" effect="plain">{{ TYPE_CN[q.type] || q.type }}</el-tag>
          <span class="idx">#{{ i + 1 }}</span>
          <span class="spacer" />
          <el-button size="small" text type="danger" @click="remove(q)">删除</el-button>
        </div>
        <div class="c-q pre">{{ q.content }}</div>
        <el-collapse v-if="q.analysis" class="c-an">
          <el-collapse-item title="看解析" :name="q.id">
            <div class="pre">{{ q.analysis }}</div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </div>
    <el-dialog v-model="gen.open" title="从文档生成题目" width="620px" :close-on-click-modal="false">
      <div class="gen">
        <el-alert type="info" :closable="false" show-icon
          title="选一篇你自己的文档，AI 按它的内容出题，直接存进「我的题库」（不进公共题库）。生成需要联网，约 10~30 秒。" />
        <div class="gen-row">
          <el-select v-model="gen.docId" filterable placeholder="选择文档（只列你能看的）" class="gen-sel" :loading="gen.loadingDocs">
            <el-option v-for="d in gen.docs" :key="d.shareId + '-' + d.id"
                       :label="d.title" :value="d.shareId + ':' + d.id" />
          </el-select>
          <el-select v-model="gen.count" class="gen-cnt">
            <el-option :value="5" label="5 题" />
            <el-option :value="10" label="10 题" />
            <el-option :value="15" label="15 题" />
          </el-select>
        </div>
        <div class="gen-row">
          <el-checkbox v-model="gen.types" label="SINGLE">单选</el-checkbox>
          <el-checkbox v-model="gen.types" label="MULTI">多选</el-checkbox>
          <el-checkbox v-model="gen.types" label="JUDGE">判断</el-checkbox>
        </div>
        <div v-if="gen.tip" class="gen-tip">{{ gen.tip }}</div>
      </div>
      <template #footer>
        <el-button @click="gen.open = false">关闭</el-button>
        <el-button type="primary" :loading="gen.running" :disabled="!gen.docId || !gen.types.length" @click="doGen">
          {{ gen.running ? '生成中…' : '开始生成' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="imp.open" title="粘贴导入题目" width="760px" :close-on-click-modal="false">
      <div class="imp">
        <el-alert type="info" :closable="false" show-icon
          title="把题目文本粘进来，本地解析（断网也能用）。支持题目编号 1. / 1、，选项 A. / A、/（A），以及「答案：B」「解析：…」；判断题答「正确/错误」也行。" />
        <el-input
          v-model="imp.text" type="textarea" :rows="12" class="imp-ta"
          placeholder="1. 题干…&#10;A. 选项一&#10;B. 选项二&#10;答案：B&#10;解析：…" />
        <div class="imp-acts">
          <el-button @click="doParse" :disabled="!imp.text.trim()">解析</el-button>
          <span v-if="imp.parsed.length" class="imp-sum">
            共 {{ imp.parsed.length }} 题，可导入 <b>{{ imp.okCount }}</b> 题
            <span v-if="imp.badCount" class="imp-bad">（{{ imp.badCount }} 题有警告）</span>
          </span>
          <span class="spacer" />
          <el-button type="primary" :disabled="!imp.okCount || imp.saving" @click="doImport">
            {{ imp.saving ? '导入中…' : '导入到我的题库' }}
          </el-button>
        </div>

        <div v-if="imp.parsed.length" class="imp-list">
          <label v-for="(p, i) in imp.parsed" :key="i" class="imp-item" :class="{ bad: p.warn.length }">
            <el-checkbox v-model="p._pick" :disabled="p.warn.length && !p.options.length" />
            <div class="imp-body">
              <div class="imp-q">
                <el-tag size="small" effect="plain">{{ TYPE_CN[p.question.type] || p.question.type }}</el-tag>
                <span>{{ (p.question.content || '').slice(0, 120) }}</span>
              </div>
              <div class="imp-opts">
                <span v-for="o in p.options" :key="o.label" :class="{ right: o.isCorrect }">
                  {{ o.label }}.{{ o.content }}
                </span>
              </div>
              <div v-if="p.warn.length" class="imp-warn">⚠ {{ p.warn.join('；') }}</div>
            </div>
          </label>
        </div>
      </div>
      <template #footer>
        <el-button @click="imp.open = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { selfExamStats, selfExamQuestions, selfExamDeleteQuestion, selfExamBatchCreate } from '../api/training'
import { parseQuestions } from '../utils/quizParser'
import { listAllShareDocs } from '../api/docShare'
import { agentCreateQuestions } from '../utils/questionGen'
import ExamTabs from '../components/ExamTabs.vue'

const TYPE_CN = { SINGLE: '单选', MULTI: '多选', JUDGE: '判断', FILL: '填空', SUBJECTIVE: '主观' }

// 粘贴导入（纯本地解析，断网可用；只有最后入库需要联网）
const imp = ref({ open: false, text: '', parsed: [], saving: false, okCount: 0, badCount: 0 })
// 从文档生成：选文档 → agentCreateQuestions（DOC 源，强制进个人题库）
const gen = ref({ open: false, docs: [], docId: '', count: 5, types: ['SINGLE', 'JUDGE'], running: false, tip: '', loadingDocs: false })

async function openGen() {
  gen.value = { ...gen.value, open: true, tip: '', running: false }
  if (gen.value.docs.length) return
  gen.value.loadingDocs = true
  try {
    const { data } = await listAllShareDocs(false)
    gen.value.docs = (data || []).map((d) => ({ ...d, id: d.id || d.docId }))
  } catch (e) {
    ElMessage.error('文档列表加载失败')
  } finally {
    gen.value.loadingDocs = false
  }
}

async function doGen() {
  const [shareId, docId] = String(gen.value.docId).split(':')
  const doc = gen.value.docs.find((d) => String(d.shareId) === shareId && String(d.id) === docId)
  if (!doc) return
  gen.value.running = true
  gen.value.tip = '正在让 AI 按《' + doc.title + '》出题…'
  try {
    const askUser = async (msg, opts) => {
      try {
        await ElMessageBox.confirm(msg, '确认', { confirmButtonText: opts?.[0] || '继续', cancelButtonText: opts?.[1] || '取消' })
        return opts?.[0] || '继续'
      } catch (e) {
        return opts?.[1] || '取消'
      }
    }
    const result = await agentCreateQuestions(
      { topic: doc.title, count: gen.value.count, types: gen.value.types, sourceType: 'DOC', shareId: Number(shareId), docId: Number(docId) },
      { askUser, isDemo: false, session: {}, forcePersonal: true }
    )
    gen.value.tip = typeof result === 'string' ? result : '已生成'
    ElMessage.success('生成完成，已存入我的题库')
    gen.value.open = false
    await load()
  } catch (e) {
    gen.value.tip = e?.response?.data?.error || e.message || '生成失败'
  } finally {
    gen.value.running = false
  }
}

const esc = (s) => String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

function openImport() {
  imp.value = { open: true, text: '', parsed: [], saving: false, okCount: 0, badCount: 0 }
}

function doParse() {
  const list = parseQuestions(imp.value.text)
  list.forEach((p) => { p._pick = p.warn.length === 0 })
  imp.value.parsed = list
  imp.value.badCount = list.filter((p) => p.warn.length).length
  imp.value.okCount = list.filter((p) => p._pick).length
  if (!list.length) ElMessage.warning('没解析出题目，检查一下格式')
}

function syncPick() {
  imp.value.okCount = imp.value.parsed.filter((p) => p._pick).length
}

async function doImport() {
  const picked = imp.value.parsed.filter((p) => p._pick)
  if (!picked.length) return
  if (picked.length > 50) { ElMessage.warning('一次最多导入 50 题，请分两次'); return }
  imp.value.saving = true
  try {
    const items = picked.map((p) => ({ question: p.question, options: p.options }))
    const { data } = await selfExamBatchCreate(items)
    ElMessage.success(`已导入 ${(data || []).length} 题`)
    imp.value.open = false
    await load()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '导入失败')
  } finally {
    imp.value.saving = false
  }
}
const loading = ref(false)
const list = ref([])
const stats = ref({})

async function load() {
  loading.value = true
  try {
    const [a, b] = await Promise.all([selfExamStats(), selfExamQuestions()])
    stats.value = a.data || {}
    list.value = b.data || []
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

async function remove(q) {
  try {
    await ElMessageBox.confirm('删除后不可恢复，确定删除这道题吗？', '删除题目', { type: 'warning' })
    await selfExamDeleteQuestion(q.id)
    list.value = list.value.filter((x) => x.id !== q.id)
    stats.value = { ...stats.value, questions: Math.max(0, (stats.value.questions || 1) - 1) }
    ElMessage.success('已删除')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

watch(imp, () => { syncPick() }, { deep: true })

onMounted(load)
</script>

<style scoped>
.page { max-width: 900px; margin: 0 auto; padding: 20px 16px 60px; }
.hd { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.hd-acts { display: flex; gap: 8px; flex-shrink: 0; }
.hd h1 { font-size: 20px; margin: 0 0 4px; }
.sub { margin: 0; font-size: 13px; color: #6b7280; }
.stats { display: flex; align-items: center; gap: 18px; margin: 14px 0; }
.st { display: flex; align-items: baseline; gap: 6px; }
.st b { font-size: 20px; }
.st span { font-size: 12px; color: #6b7280; }
.tipbar { margin-bottom: 12px; }
.tip { padding: 24px; text-align: center; color: #6b7280; }
.empty { text-align: center; padding: 48px 16px; }
.empty-ico { font-size: 34px; }
.empty-t { font-size: 15px; font-weight: 600; margin-top: 8px; }
.empty-s { font-size: 13px; color: #6b7280; margin: 6px 0 14px; }
.list { display: flex; flex-direction: column; gap: 10px; }
.card { background: #fff; border: 1px solid #ebeef5; border-radius: 10px; padding: 12px 14px; }
.c-hd { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.idx { font-size: 12px; color: #9ca3af; }
.spacer { flex: 1; }
.c-q { font-size: 14.5px; line-height: 1.7; color: #1f2937; }
.c-an { margin-top: 6px; }

.imp-ta { margin: 10px 0; }
.imp-acts { display: flex; align-items: center; gap: 10px; }
.imp-acts .spacer { flex: 1; }
.imp-sum { font-size: 12.5px; color: #6b7280; }
.imp-bad { color: #d97706; }
.imp-list { margin-top: 12px; max-height: 320px; overflow: auto; border-top: 1px solid #f0f0f0; }
.imp-item { display: flex; gap: 10px; padding: 10px 4px; border-bottom: 1px dashed #f0f0f0; }
.imp-item.bad { background: #fffbeb; }
.imp-body { flex: 1; min-width: 0; }
.imp-q { font-size: 13.5px; line-height: 1.6; color: #1f2937; display: flex; gap: 6px; align-items: flex-start; }
.imp-opts { margin-top: 4px; display: flex; flex-wrap: wrap; gap: 4px 12px; font-size: 12.5px; color: #6b7280; }
.imp-opts .right { color: #16a34a; font-weight: 600; }
.imp-warn { margin-top: 4px; font-size: 12px; color: #d97706; }

.gen-row { display: flex; gap: 10px; align-items: center; margin-top: 10px; }
.gen-sel { flex: 1; }
.gen-cnt { width: 110px; }
.gen-tip { margin-top: 10px; font-size: 12.5px; color: #6b7280; }

.pre { white-space: pre-wrap; word-break: break-word; }
</style>