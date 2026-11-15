<script setup>
import { ref, reactive, nextTick, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound, Plus, Delete, EditPen, ChatLineRound, Document,
  Paperclip, Link, ArrowDown, CopyDocument, Refresh, Reading, Promotion, VideoCamera,
  Fold, Expand, VideoPause, Cpu, Search, Select
} from '@element-plus/icons-vue'
import ProviderLogo from '../components/ProviderLogo.vue'
import { useAuthStore } from '../store/auth'
import {
  aiAsk, aiAskStream, aiFeedback, listAiModels, listConversations, createConversation, updateConversation,
  deleteConversation, batchDeleteConversations, listMessages
, generateImage, createVideo, videoStatus } from '../api/ai'
import { useMarkdownView } from '../features/ai/useMarkdownView'
import { useRouter } from 'vue-router'
import { runLocalAgent } from '../utils/localAgent'
import { createMyDoc, updateMyDoc, listMyDocs } from '../api/kb'
import { listMyFiles } from '../api/file'
import api from '../api/request'
import { buildFlowchartGraph, flowchartHtml } from '../utils/flowchartGen'
import { createShareDoc, updateShareDoc, getShareDoc } from '../api/docShare'
import { shareDocPath, normalizeNavPath } from '../utils/docPath'
import { resolveDefaultSpaceId } from '../utils/spaceResolver'
import { agentCreateQuestions } from '../utils/questionGen'
import { mkey, sanitizeAnswer, sanitizeInput, cleanTitle, mdToHtml, toDocHtml, buildQuestionWithAttachments } from '../utils/aiPure'
import {
  estimateTokens, formatTokens, pickHistory, historyToText,
  COMPACT_INSTRUCTION, THINK_LEVELS, thinkPrefix,
} from '../utils/aiContext'
// ⚠️ 必须走 utils/prism 这一层包装，不能直接 `import Prism from 'prismjs'`：
// prismjs 是 CJS，构建后 `global` 不存在，语言组件又直接读写全局 Prism，
// 运行时会抛 "ReferenceError: Prism is not defined"。原因与顺序要求见 utils/prism.js。
import Prism from '../utils/prism'
import 'prismjs/components/prism-javascript'
import 'prismjs/components/prism-typescript'
import 'prismjs/components/prism-json'
import 'prismjs/components/prism-bash'
import 'prismjs/components/prism-python'
import 'prismjs/components/prism-java'
import 'prismjs/components/prism-sql'
import 'prismjs/components/prism-yaml'
import 'prismjs/components/prism-go'
import { escapeHtml } from '../utils/text'
import { useAiAttachments } from '../features/ai/useAiAttachments'
import { recognizeLocal } from '../utils/localOcr'

const auth = useAuthStore()

const conversations = ref([])
const currentId = ref(null)
const convMessages = ref({})
const input = ref('')
const loadingSet = ref(new Set())
const loading = computed(() => (currentId.value ? loadingSet.value.has(currentId.value) : false))
const abortCtrl = ref(null)   // 当前请求的中断控制器（停止生成按钮用）
// video generation polls status async; timers are tracked here and cleared on unmount.
// (previously referenced without declaration -> ReferenceError on opening AiChat)
const videoTimers = new Set()
function stopAi() { abortCtrl.value?.abort() }
const bodyRef = ref(null)
const messages = computed(() => (currentId.value ? (convMessages.value[currentId.value] || []) : []))
const started = computed(() => messages.value.length > 0)

const mode = ref('kb')
const route = useRoute()
const agentSteps = ref([])  // Agent 模式：步骤列表（累积展示，能看到它做了什么）
const agentStepsOpen = ref(true)  // 步骤折叠状态：执行中展开，完成后自动收起
// 一次 Agent 任务内的确认状态（WEB/AI 出题只问一遍，模型重试时不再弹）
const agentSession = ref({})
// 历史消息里的「执行过程」折叠状态（按消息索引，默认收起）
const msgStepsOpen = reactive({})

/**
 * 窗口用到 80% 时自动压缩一次。
 * 之所以放在「发送前」而不是定时器：只有真要发消息时上下文才会继续涨，
 * 空闲时压了也白压（还多花一次模型调用）。
 */
async function maybeAutoCompact() {
  if (ctxPct.value < AUTO_COMPACT_AT * 100) return
  if (compacting.value) return
  if (Date.now() - lastAutoCompact < 60000) return
  lastAutoCompact = Date.now()
  await compactContext(true)
}
// 高消耗发送确认的开关与阈值（管理员在「AI 模型管理 → 消息设置」配置）
const aiGuard = reactive({ enabled: true, imageThreshold: 2, textThreshold: 8000 })
async function loadAiGuard() {
  try {
    const { data } = await api.get('/public/ai-guard')
    if (data && typeof data.enabled === 'boolean') aiGuard.enabled = data.enabled
    if (data && Number(data.imageThreshold) > 0) aiGuard.imageThreshold = Number(data.imageThreshold)
    if (data && Number(data.textThreshold) > 0) aiGuard.textThreshold = Number(data.textThreshold)
  } catch { /* 读不到就用默认 */ }
}
const router = useRouter()
// Agent 询问用户（ask_user 工具）：拿不准时弹出来让用户拍板
const agentAsk = ref(null)      // { question, options, resolve }
const agentAskInput = ref('')
function askUser(question, options) {
  return new Promise((resolve) => {
    agentAskInput.value = ''
    agentAsk.value = { question, options: options || [], resolve }
  })
}
function answerAgentAsk(val) {
  const a = agentAsk.value
  if (!a) return
  const ans = String(val != null ? val : agentAskInput.value || '').trim()
  agentAsk.value = null
  agentAskInput.value = ''
  a.resolve(ans)
}
// ---------- 模型选择（模型由管理员在「AI 模型管理」页配置，这里只选不配）----------
// selectedModelId 为 '' 时不指定，走后端「默认模型」；选了具体 id 则本次请求强制用它
const modelList = ref([])
const selectedModelId = ref('')
// 模型 key：系统模型 s:<id>、自己的模型 u:<id>（两类 id 各自自增可能重复，必须带前缀区分）

function modelPayload() {
  const v = String(selectedModelId.value || '')
  if (!v) return { modelId: null }
  if (v.startsWith('u:')) return { modelId: null, userModelId: Number(v.slice(2)) }
  if (v.startsWith('s:')) return { modelId: Number(v.slice(2)) }
  return { modelId: Number(v) }
}
async function loadModels() {
  try {
    const { data } = await listAiModels()
    modelList.value = data || []
    // 下拉里选过、但管理员把该模型停用了 → 回到默认模型
    if (selectedModelId.value && !modelList.value.some((m) => mkey(m) === selectedModelId.value)) {
      selectedModelId.value = ''
    }
  } catch {
    modelList.value = []
  }
}

const { attachments, fileInput, attBusy, pickFiles, removeAttachment, toggleAttMode,
  onFilesPicked, clearAttachments } = useAiAttachments()
// ---------- 调用可视化：本次实际用的模型 / 耗时 / token 用量，回答下方一行展示 ----------
function buildMeta(data) {
  if (!data) return null
  const name = data.modelName || data.model || ''
  if (!name && data.latencyMs == null) return null
  // reasoning：思考型模型（DeepSeek / GLM 思考版）的思考过程，前端折叠展示；不落历史，仅本次回答可见
  return { name, latencyMs: Number(data.latencyMs) || 0, usage: data.usage || null, reasoning: data.reasoning || null }
}

/* ---------- 回答反馈（赞/踩）：数据进 kb_ai_qa_log，管理端「AI 反馈」看板汇总 ---------- */
const FB_REASONS = ['答非所问', '没有依据', '资料过时', '回答不完整']
const fbBusy = reactive({})

/** 赞/踩可随时切换或取消：再点一次同项 = 取消（rating 0），点另一项 = 改评；踩先展开原因面板 */
function rateMsg(m, rating) {
  if (!m.qaId || fbBusy[m.qaId]) return
  if (rating === -1) {
    if (m.fbRating === -1) { submitRate(m, 0, ''); return }   // 已踩 → 取消
    m.fbOpen = !m.fbOpen; return                              // 展开原因面板
  }
  submitRate(m, m.fbRating === 1 ? 0 : 1, '')                 // 已赞 → 取消
}

async function submitRate(m, rating, reason) {
  if (!m.qaId || fbBusy[m.qaId]) return
  fbBusy[m.qaId] = true
  try {
    await aiFeedback(m.qaId, rating, reason)
    m.fbRating = rating
    m.fbOpen = false
    ElMessage.success(rating === 1 ? '感谢反馈' : rating === 0 ? '已取消反馈' : '已记录，我们会据此补充文档')
  } catch (e) {
    ElMessage.error('反馈失败，请稍后重试')
  } finally {
    fbBusy[m.qaId] = false
  }
}
function onModelChange() {
  // 只影响后续提问：切换模型不会改写已有回答（历史回答保留当时的模型与耗时）
  ElMessage.info(selectedModelId.value ? '下一句将使用所选模型' : '下一句将使用默认模型')
}

// 顶部模型选择器（WorkBuddy 风格）：触发器显示当前模型 + 品牌图标，弹层列表带勾选
const currentModel = computed(() =>
  modelList.value.find((m) => mkey(m) === selectedModelId.value) || null)
const currentModelLabel = computed(() => currentModel.value?.name || '默认模型')

// ---------- 上下文预算 / 用量环 / 思考强度 / 压缩 ----------
// 窗口大小由管理员在「AI 模型管理」里按模型配置（64K~1M 差得很远，写死只会两头不讨好）
const effectiveModel = computed(() =>
  currentModel.value || modelList.value.find((m) => m.isDefault) || modelList.value[0] || null)
// 模型本身能吃的最大窗口（管理员配置，作为上限）
const modelMaxWindow = computed(() => Number(effectiveModel.value?.contextWindow) || 128000)
/**
 * 用户可把窗口「调小」用（模型 1M 但只想用 200K：省 token、控成本、避免长上下文变慢）。
 * 只能往下调、不能超过模型上限，所以取 min。0 / 空 = 跟随模型最大值。
 * 按模型 id 分别记，换模型不会互相污染。
 */
const CTX_SET_KEY = 'kb_ai_ctx_window'
const ctxWindowSet = ref(0)
const ctxWindow = computed(() => {
  const max = modelMaxWindow.value
  const v = Number(ctxWindowSet.value) || 0
  return v > 0 ? Math.min(Math.max(v, 8000), max) : max
})
// 窗口档位说明（对齐截图里的「1M 上下文·超长」那种语义）
const ctxWindowLabel = computed(() => {
  const w = ctxWindow.value
  if (w >= modelMaxWindow.value) return '模型上限'
  if (w >= 500000) return '超长'
  if (w >= 128000) return '长'
  if (w >= 32000) return '标准'
  return '精简'
})
function loadCtxWindow() {
  try {
    const raw = localStorage.getItem(CTX_SET_KEY)
    const map = raw ? JSON.parse(raw) : {}
    const id = effectiveModel.value?.id || 'default'
    ctxWindowSet.value = Number(map[id]) || 0
  } catch { ctxWindowSet.value = 0 }
}
function saveCtxWindow() {
  try {
    const raw = localStorage.getItem(CTX_SET_KEY)
    const map = raw ? JSON.parse(raw) : {}
    const id = effectiveModel.value?.id || 'default'
    if (ctxWindowSet.value > 0) map[id] = ctxWindowSet.value
    else delete map[id]
    localStorage.setItem(CTX_SET_KEY, JSON.stringify(map))
  } catch { /* 存不了就算了，只影响下次进页面 */ }
}
watch(effectiveModel, () => loadCtxWindow())


const activeMessages = computed(() => (currentId.value ? (convMessages.value[currentId.value] || []) : []))
const historyTokens = computed(() => activeMessages.value.reduce((n, m) => n + estimateTokens(m.content), 0))
const inputTokens = computed(() => estimateTokens(input.value))
// 用量拆成几段，和「已用总量」对得上，也能看出到底是谁把窗口吃掉了
const ctxParts = computed(() => {
  const parts = [{ name: '系统提示词', value: 120, color: '#6366f1' }]
  // Agent 模式的工具手册（TOOL_MANUAL）本身就有上千 token，且每步都会带上
  if (mode.value === 'agent') parts.push({ name: '工具与技能', value: 1800, color: '#10b981' })
  parts.push({ name: '对话消息', value: historyTokens.value, color: '#f59e0b' })
  if (inputTokens.value > 0) parts.push({ name: '当前输入', value: inputTokens.value, color: '#a855f7' })
  return parts
})
const usedTokens = computed(() => ctxParts.value.reduce((n, p) => n + p.value, 0))
const ctxPct = computed(() => Math.min(100, Math.round((usedTokens.value / ctxWindow.value) * 100)))
const ctxColor = computed(() => (ctxPct.value >= 85 ? '#dc2626' : ctxPct.value >= 60 ? '#d97706' : '#16a34a'))
const ctxRing = computed(() => {
  const c = 2 * Math.PI * 15.5
  return `${(ctxPct.value / 100) * c} ${c}`
})

// 思考强度：作用于提示词而非厂商私有参数，所以对所有模型都成立（原生 reasoning 参数各家不统一）
const think = ref(localStorage.getItem('kb_ai_think') || 'normal')
watch(think, (v) => {
  localStorage.setItem('kb_ai_think', v)
  const t = THINK_LEVELS.find((x) => x.value === v)
  ElMessage.success('思考强度：' + (t ? t.label : v))
})

const compacting = ref(false)
/** 窗口用到这个比例就自动压一次（不必等用户发现「怎么开始变慢/报超长」） */
const AUTO_COMPACT_AT = 0.8
let lastAutoCompact = 0

async function compactContext(auto = false) {
  const msgs = activeMessages.value.filter((m) => m && m.content)
  if (msgs.length < 4) { if (!auto) ElMessage.info('对话还短，不需要压缩'); return }
  compacting.value = true
  try {
    const text = msgs.map((m) => (m.role === 'user' ? '用户：' : '助手：') + String(m.content)).join('\n\n')
    const { data } = await aiAsk(COMPACT_INSTRUCTION + text, null, 'general', {
      ...modelPayload(),
    })
    const summary = String((data && data.answer) || '').trim()
    if (!summary) { ElMessage.warning('压缩失败：模型没有返回内容'); return }
    // 留最后两条原文做衔接，其余全部收进摘要——否则摘要完立刻接不上当前话题
    const keep = msgs.slice(-2)
    convMessages.value[currentId.value] = [
      { role: 'assistant', content: '【历史摘要】' + summary, mode: 'general', compacted: true },
      ...keep,
    ]
    ElMessage.success(auto
      ? `上下文快满了，已自动压缩：${msgs.length} 条 → 摘要 + 最近 ${keep.length} 条`
      : `已压缩：${msgs.length} 条 → 摘要 + 最近 ${keep.length} 条`)
  } catch (e) {
    if (!auto) ElMessage.error('压缩失败：' + ((e && e.response && e.response.data && e.response.data.error) || (e && e.message) || '未知错误'))
  } finally {
    compacting.value = false
  }
}
const currentModelProvider = computed(() => currentModel.value?.provider || '')
function pickModel(id) {
  if (selectedModelId.value === id) return
  selectedModelId.value = id
  onModelChange()
}
const showScrollBtn = ref(false)
// 分段滑块位置：必须与按钮个数同步（kb=0 / general=1 / agent=2），
// 之前只算了 2 个位置，加了 Agent 按钮后遮罩就错位了。
const MODE_INDEX = { kb: 0, general: 1, agent: 2 }
const thumbStyle = computed(() => ({ transform: `translateX(${(MODE_INDEX[mode.value] || 0) * 100}%)` }))

// 侧栏：宽屏 = 折叠进文档流（记忆到 localStorage）；窄屏 = 覆盖式抽屉，默认收起
const SIDEBAR_KEY = 'kb_ai_sidebar_collapsed'
const sidebarCollapsed = ref(localStorage.getItem(SIDEBAR_KEY) === '1')
const isNarrow = ref(false)
const drawerOpen = ref(false)
let mqNarrow = null
function onNarrowChange(e) {
  isNarrow.value = e.matches
  if (!e.matches) drawerOpen.value = false
}
function closeDrawerOnMobile() {
  if (isNarrow.value) drawerOpen.value = false
}
const sideToggleTip = computed(() => {
  if (isNarrow.value) return drawerOpen.value ? '收起历史会话' : '历史会话'
  return sidebarCollapsed.value ? '展开历史会话' : '收起历史会话'
})
function toggleSidebar() {
  if (isNarrow.value) { drawerOpen.value = !drawerOpen.value; return }
  sidebarCollapsed.value = !sidebarCollapsed.value
  localStorage.setItem(SIDEBAR_KEY, sidebarCollapsed.value ? '1' : '0')
}

const creating = ref(false)
const renamingId = ref(null)
const renameTitle = ref('')
const showInstructionsDrawer = ref(false)
const instructionsForm = ref({ id: null, title: '', instructions: '' })
const savingInstructions = ref(false)
const loadingMessages = ref(false)
const loadingConversations = ref(false)

// 会话 id 按账号隔离：同一台设备换账号登录后不会请求上一个账号的会话（外部复审 2026-11-07）
const convStorageKey = () => 'kb_ai_current_conversation:' + (auth.user?.id || auth.user?.username || 'anon')

const suggestions = [
  '如何发布一篇文章？',
  '知识库支持哪些内容格式？',
  '怎么查看我的积分明细？',
  '帮我总结一下知识库的常见用法',
  '知识库里有哪些关于新员工入职的内容？',
  '最近更新的文档有哪些？',
  '帮我看看还差哪些勋章没拿到',
  '怎么把文档分享给同事？',
]
// 多一点轮换：每次进入随机抽 4 条，避免永远是同一套开场
const rotateSugs = (arr, n = 4) => [...arr].sort(() => Math.random() - 0.5).slice(0, n)

// 空状态文案必须跟着模式走：切到「通用」后如果还挂着「我是知识库 AI 助手 / 基于知识库检索作答」
// 和一堆知识库相关的推荐问题，用户会以为模式没生效 —— 这正是「选了通用还感觉基于知识库」的来源之一。
const generalSuggestions = [
  '用三句话解释什么是向量数据库',
  '帮我把这段话润色得更正式',
  '推荐几本讲系统设计的好书',
  '写一个 Python 读取 Excel 的例子',
  '把这段会议纪要提炼成三个要点',
  '帮我写一封项目延期的通知邮件',
  '解释一下什么是幂等性，举个例子',
  '给我的周报起几个标题',
]
const agentSuggestions = [
  '帮我写一篇关于「消防演练注意事项」的文档，存进知识库',
  '帮我出 5 道关于请假流程的单选题，加进题库',
  '审查我最近的一篇文档，指出问题并优化',
  '把知识库里关于报销的内容找出来，对比有没有矛盾',
  '整理一篇「远程办公设备清单」并存为文档',
  '出 3 道关于信息安全的判断题放进我的题库',
  '帮我检查最近的文档里有没有过期日期',
]
const emptyCopy = computed(() => {
  if (mode.value === 'agent') {
    return {
      title: '你好，我是 Agent',
      tip: '我会自己检索资料、读写文档——你说目标，我来干活（写好的文档会存进知识库）',
      features: [
        { icon: 'Search', text: '自主检索' },
        { icon: 'EditPen', text: '写 / 改文档' },
        { icon: 'Cpu', text: '多步执行' },
      ],
      sugs: rotateSugs(agentSuggestions),
    }
  }
  if (mode.value === 'general') {
    return {
      title: '你好，我是通用 AI 助手',
      tip: '不检索知识库，直接凭通用知识作答',
      features: [
        { icon: 'ChatDotRound', text: '自由问答' },
        { icon: 'EditPen', text: '写作润色' },
        { icon: 'Cpu', text: '编程协助' },
      ],
      sugs: rotateSugs(generalSuggestions),
    }
  }
  return {
    // kb 模式保持原有身份文案（2026-10-06 用户确认：知识库模式照旧，新口径只作用于通用模式）
    title: '你好，我是知识库 AI 助手',
    tip: '基于知识库检索作答，也能陪你日常聊聊',
    features: [
      { icon: 'Reading', text: '知识检索' },
      { icon: 'EditPen', text: '总结写作' },
      { icon: 'ChatDotRound', text: '日常问答' },
    ],
    sugs: suggestions,
  }
})

const currentConversation = computed(() =>
  conversations.value.find((c) => c.id === currentId.value)
)

// 用户头像：auth 有 avatar 用图片，否则按用户名 hash 取渐变色 + 首字母
const userName = computed(() => auth.user?.displayName || auth.user?.username || '我')
const userInitial = computed(() => {
  const n = (userName.value || '我').charAt(0)
  return n ? n.toUpperCase() : '我'
})
const userAvatar = computed(() => auth.user?.avatar || '')
function hashCode(s) {
  let h = 0
  for (let i = 0; i < s.length; i++) h = ((h << 5) - h) + s.charCodeAt(i)
  return Math.abs(h)
}
const userGradient = computed(() => {
  const palette = [
    'linear-gradient(135deg,#6366f1,#4338ca)',
    'linear-gradient(135deg,#0ea5e9,#0369a1)',
    'linear-gradient(135deg,#10b981,#047857)',
    'linear-gradient(135deg,#f59e0b,#b45309)',
    'linear-gradient(135deg,#ec4899,#9d174d)',
    'linear-gradient(135deg,#8b5cf6,#6d28d9)',
  ]
  const name = userName.value || 'me'
  return palette[hashCode(name) % palette.length]
})

function scrollToBottom() {
  nextTick(() => {
    if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight
  })
}
function onBodyScroll() {
  const el = bodyRef.value
  if (!el) return
  showScrollBtn.value = (el.scrollHeight - el.scrollTop - el.clientHeight) > 140
}

async function loadConversations() {
  loadingConversations.value = true
  try {
    const { data } = await listConversations()
    conversations.value = data || []
  } catch {
    ElMessage.error('加载会话列表失败')
  } finally {
    loadingConversations.value = false
  }
}

async function loadMessages(id, { silent = false } = {}) {
  if (!id) return
  if (convMessages.value[id]) { if (!silent) scrollToBottom(); return }
  loadingMessages.value = true
  try {
    const { data } = await listMessages(id)
    convMessages.value[id] = (data || []).map((m) => ({ role: m.role, content: m.content, sources: m.sources || [], mode: m.mode || 'kb' }))
  } catch {
    ElMessage.error('加载历史消息失败')
    convMessages.value[id] = []
  } finally {
    loadingMessages.value = false
  }
  if (!silent) scrollToBottom()
}

async function selectConversation(id) {
  closeDrawerOnMobile()
  if (currentId.value === id) return
  currentId.value = id
  localStorage.setItem(convStorageKey(), String(id))
  await loadMessages(id)
  scrollToBottom()
}

async function newConversation() {
  if (creating.value) return
  creating.value = true
  try {
    const { data } = await createConversation('新对话')
    convMessages.value[data.id] = []
    conversations.value.unshift(data)
    currentId.value = data.id
    localStorage.setItem(convStorageKey(), String(data.id))
    input.value = ''
    closeDrawerOnMobile()
  } catch {
    ElMessage.error('创建会话失败')
  } finally {
    creating.value = false
  }
}

/**
 * 轻量 Markdown → HTML：Agent 写/改文档时用（文档正文存的是富文本 HTML）。
 * 只支持标题 / 列表 / 加粗 / 代码块 / 段落 —— 够 Agent 产出用，且零依赖。
 */
/**
 * Agent 模式：ReAct 编排 + 本地工具（检索 / 读文档 / 写文档 / 改文档 / 跳转）。
 * 与移动端同一套引擎（utils/localAgent），走「通用」模式的无状态调用，不进后端会话。
 */
async function sendAgent(raw) {
  if (!raw) { ElMessage.warning('请输入任务描述'); return }
  if (loading.value) return

  let convId = currentId.value
  // 当前会话丢了（刷新/清理/多标签）时，优先复用最近 30 分钟内创建的会话，
  // 避免「发一次任务就多一个会话」——会话列表被同名任务刷屏
  if (!convId && conversations.value.length) {
    const top = conversations.value[0]
    const t = new Date(top.createdAt || top.updatedAt || 0).getTime()
    if (!t || Date.now() - t < 30 * 60 * 1000) {
      convId = currentId.value = top.id
      localStorage.setItem(convStorageKey(), String(top.id))
      if (!convMessages.value[top.id]) { try { await loadMessages(top.id) } catch (e) {} }
    }
  }
  if (!convId) {
    creating.value = true
    try {
      const { data } = await createConversation(cleanTitle(raw))
      convMessages.value[data.id] = []
      conversations.value.unshift(data)
      currentId.value = data.id
      localStorage.setItem(convStorageKey(), String(data.id))
      convId = data.id
    } catch {
      ElMessage.error('创建会话失败')
      return
    } finally {
      creating.value = false
    }
  }

  // 历史按模型窗口的 35% 取（其余留给工具手册、资料与回答），不再写死「6 条 × 400 字」
  const budget = Math.max(2000, Math.floor(ctxWindow.value * 0.35))
  const history = historyToText(pickHistory(convMessages.value[convId] || [], budget))
  const cleanTask = sanitizeInput(raw)
  const task = thinkPrefix(think.value) + (history ? `【最近的对话】\n${history}\n\n【本次任务】${cleanTask}` : cleanTask)

  convMessages.value[convId] = [...(convMessages.value[convId] || []), { role: 'user', content: cleanTask }]
  input.value = ''
  clearAttachments()
  loadingSet.value.add(convId)
  agentSteps.value = []
  agentStepsOpen.value = true
  agentSession.value = {}
  await nextTick()
  scrollToBottom()

  try {
    abortCtrl.value = new AbortController()
    const r = await runLocalAgent({
      question: task,
      ask: async (t, sig) => {
        const { data } = await aiAsk(t, null, 'general', {
          ...modelPayload(), signal: sig || abortCtrl.value.signal
        })
        return (data && data.answer) || ''
      },
      onStep: (s) => { agentSteps.value = [...agentSteps.value, s] },
      signal: abortCtrl.value.signal,
      // 权限二次校验：registry 里声明 perm 的工具（如 create_questions）据此拦一道，
      // 不传的话那句「即便模型猜到工具名也绕不过权限码」就是空话
      hasPerm: (p) => auth.hasPerm(p),
      hooks: {
        askUser,
        createImage: async (prompt) => {
          const { data } = await generateImage(prompt)
          return '图片已生成，把下面这行 Markdown 原样放进最终回答里：\n![生成的图片](' + (data.url || '') + ')'
        },
        createVideo: async (prompt) => {
          // 不在单个请求里等视频（nginx /api/ 超时 120s，会 504）：先提交拿 taskId，再前端轮询
          const { data } = await api.post('/ai/video', { prompt })
          const taskId = data && data.taskId
          if (!taskId) return '视频提交失败：未返回任务 id'
          const deadline = Date.now() + 6 * 60 * 1000
          while (Date.now() < deadline) {
            await new Promise((r) => setTimeout(r, 5000))
            try {
              const { data: st } = await api.get('/ai/video/' + encodeURIComponent(taskId))
              if (st && st.url) {
                return '视频已生成，把下面这行原样放进文章正文（独立一行）：\n@[video](' + st.url + ')'
              }
              if (st && String(st.status).toUpperCase() === 'FAIL') {
                return '视频生成失败：' + (st.message || '上游异常')
              }
            } catch (e) { /* 单次轮询失败忽略，继续等 */ }
          }
          return '视频仍在生成（taskId=' + taskId + '），稍后再让我插入文章即可'
        },
        listMyFiles: async () => {
          try {
            const { data } = await listMyFiles()
            const rows = (data || []).slice(0, 40).map((f) => {
              const kind = /^image/i.test(f.contentType || '') ? '图片'
                : /^(audio|video)/i.test(f.contentType || '') ? '音视频' : '文件'
              return `- [${kind}] ${f.name} → ${f.url}`
            })
            return rows.length ? `我的文件共 ${rows.length} 个：\n` + rows.join('\n') : '我还没有上传文件'
          } catch (e) {
            return '列出文件失败：' + ((e && e.message) || e)
          }
        },
        navigate: async (path) => {
          if (!path || !String(path).startsWith('/')) return '跳转失败：路径不合法'
          try {
            const fixed = await normalizeNavPath(path, { mobile: false })
            router.push(fixed)
            return fixed === path
              ? '已跳转到 ' + path
              : `已跳转到 ${fixed}（原路径 ${path} 不存在，已按文档 id 纠正）`
          } catch (e) { return '跳转失败：' + ((e && e.message) || e) }
        },
        listMyDocs: async () => {
          try {
            const { data } = await listMyDocs()
            const rows = (data || []).slice(0, 30).map((d) => `- [${d.id}] ${d.title || '无标题'}`)
            return rows.length ? `我的文档共 ${rows.length} 篇：\n` + rows.join('\n') : '我还没有自己的文档'
          } catch (e) { return '列出文档失败：' + ((e && e.message) || e) }
        },
        insertFlowchart: async ({ title, docId, nodes, edges }) => {
          if (auth.isDemo) return '演示账号是只读的，无法生成文档'
          try {
            const graph = buildFlowchartGraph(nodes, edges)
            if (!graph.nodes.length) return '没有有效节点，未生成流程图'
            const html = flowchartHtml(title, graph)
            const sid = await resolveDefaultSpaceId()
            if (!sid) return '没有可写入的共享空间'
            const tip = `（${graph.nodes.length} 个节点 / ${graph.edges.length} 条连线）`
            // docId 来自模型输出：必须是整数，防路径拼接出非预期端点（外部复审 2026-10-08）
            const docIdNum = Number(docId)
            if (docId && !Number.isInteger(docIdNum)) return '文档 id 非法'
            const t = String(title || '流程图').slice(0, 80)
            if (docId) {
              const { data } = await getShareDoc(sid, docId)
              await updateShareDoc(sid, docId, { content: String(data.content || '') + html })
              return `已在共享文档《${data.title || docId}》中生成流程图${tip}。`
                + `打开地址：${shareDocPath(sid, docId, false)}`
            }
            const { data } = await createShareDoc(sid, { title: t, content: html })
            return `已新建共享文档《${t}》并生成流程图${tip}（id=${data && data.id}）。`
              + `打开地址：${shareDocPath(sid, data && data.id, false)}`
          } catch (e) {
            return '生成流程图失败：' + ((e && e.message) || e)
          }
        },
        createQuestions: (args) => agentCreateQuestions(args, { askUser, isDemo: auth.isDemo, session: agentSession.value }),
        createDoc: async ({ title, content, status }) => {
          // 演示账号后端会 403，提前给出明确原因，免得 Agent 白跑几步再失败
          if (auth.isDemo) return '演示账号是只读的，无法创建文档（请用正式账号登录后再试）'
          try {
            const isDraft = status === 'draft'
            const { data } = await createMyDoc({
              title, content: toDocHtml(content), status: isDraft ? 'DRAFT' : 'PENDING_REVIEW'
            })
            const st = (data && data.status) || (isDraft ? 'DRAFT' : 'PENDING_REVIEW')
            if (st === 'PUBLISHED') return '已发布《' + title + '》（id=' + (data && data.id) + '）'
            if (st === 'DRAFT') return '已存为草稿《' + title + '》（id=' + (data && data.id) + '，仅自己可见）'
            return '已提交审核《' + title + '》（id=' + (data && data.id) + '，等待管理员通过后才公开）'
          } catch (e) { return '新建失败：' + ((e && e.message) || e) }
        },
        updateDoc: async ({ id, title, content }) => {
          if (auth.isDemo) return '演示账号是只读的，无法修改文档（请用正式账号登录后再试）'
          try {
            const body = { content: toDocHtml(content) }
            if (title) body.title = title
            const { data } = await updateMyDoc(id, body)
            const st = data && data.status
            return '已更新文档《' + ((data && data.title) || title || id) + '》（id=' + id + '）' + (st === 'PENDING_REVIEW' ? '（改动已提交审核）' : '')
          } catch (e) { return '更新失败：' + ((e && e.message) || e) }
        }
      }
    })
    // steps 跟着消息存下来：否则执行过程只在 loading 里，回答一出来就再也看不到
    convMessages.value[convId] = [...convMessages.value[convId], {
      role: 'assistant', content: sanitizeAnswer(r.answer), mode: 'agent', steps: r.steps || [],
    }]
  } catch (e) {
    const msg = String((e && e.message) || e || '')
    // 点「停止」或切走页面时 axios 抛的是取消，不是失败——写成「执行失败」只会让人以为坏了
    const canceled = /cancel(ed|elled)|abort/i.test(msg) || (e && (e.name === 'CanceledError' || e.code === 'ERR_CANCELED'))
    convMessages.value[convId] = [...convMessages.value[convId], {
      role: 'assistant',
      content: canceled ? '（已停止）' : 'Agent 执行失败：' + (msg || '未知错误'),
      mode: 'agent',
    }]
  } finally {
    agentStepsOpen.value = false   // 完成后自动收起步骤（数据保留，可点「展开」回看）
    loadingSet.value.delete(convId)
    abortCtrl.value = null
    await nextTick()
    scrollToBottom()
  }
}

async function send(text) {
  const raw = (text ?? input.value).trim()
  const hasAtt = attachments.value.length > 0
  if (!raw && !hasAtt) { ElMessage.warning('请输入内容'); return }
  if (loading.value) return
  await maybeAutoCompact()
  // Agent 模式走独立链路（ReAct + 本地工具），不进后端会话
  if (mode.value === 'agent') { await sendAgent(raw); return }

  // 「识文字」模式的图片先本地 OCR（文字拼进提问，离线也能用）；「看图」模式的收集为多模态 images
  for (const a of attachments.value) {
    if (a.kind !== 'image' || a.mode !== 'ocr' || a.text) continue
    try {
      ElMessage.info('正在本地识别「' + a.name + '」…')
      const r = await recognizeLocal(a.file || a.dataUrl, () => {}, { table: true })
      a.text = (r && r.text ? String(r.text) : '').trim()
      if (!a.text) ElMessage.warning('「' + a.name + '」没有识别出文字，已跳过')
    } catch (err) {
      ElMessage.warning('「' + a.name + '」本地识别失败：' + ((err && err.message) || err))
    }
  }
  const fileNames = attachments.value.map((a) => a.name)
  const images = attachments.value.filter((a) => a.kind === 'image' && a.mode === 'vision' && a.dataUrl).map((a) => a.dataUrl)
  const q = buildQuestionWithAttachments(raw || (images.length ? '请识别并说明这些图片的内容' : ''), attachments.value)

  // 高消耗确认：图片走多模态约 850 tokens/张，长文本约 1.5 tokens/汉字。
  // 开关与阈值由管理员在「AI 模型管理 → 消息设置」里配置（/api/public/ai-guard），读不到用默认值。
  const textLen = attachments.value.reduce((n, a) => n + (a.text ? a.text.length : 0), 0) + raw.length
  const estTokens = images.length * 850 + Math.round(textLen * 1.5)
  if (aiGuard.enabled && (images.length >= aiGuard.imageThreshold || textLen >= aiGuard.textThreshold)) {
    try {
      await ElMessageBox.confirm(
        `本次将发送 ${images.length} 张图片、约 ${textLen} 字文本，预计消耗约 ${estTokens.toLocaleString()} tokens。`,
        '确认消耗',
        { confirmButtonText: '继续发送', cancelButtonText: '取消', type: 'warning' }
      )
    } catch { return }
  }
  const titleBase = raw || ('附件：' + fileNames.join('、'))

  let convId = currentId.value
  if (!convId && conversations.value.length) {
    const top = conversations.value[0]
    const t = new Date(top.createdAt || top.updatedAt || 0).getTime()
    if (!t || Date.now() - t < 30 * 60 * 1000) {
      convId = currentId.value = top.id
      localStorage.setItem(convStorageKey(), String(top.id))
      if (!convMessages.value[top.id]) { try { await loadMessages(top.id) } catch (e) {} }
    }
  }
  if (!convId) {
    creating.value = true
    try {
      const { data } = await createConversation(titleBase)
      convMessages.value[data.id] = []
      conversations.value.unshift(data)
      currentId.value = data.id
      localStorage.setItem(convStorageKey(), String(data.id))
      convId = data.id
    } catch {
      ElMessage.error('创建会话失败')
      return
    } finally {
      creating.value = false
    }
  }

  const curUser = convMessages.value[convId] || []
  const cleanRaw = sanitizeInput(raw)
  convMessages.value[convId] = [...curUser, { role: 'user', content: cleanRaw, files: fileNames.length ? fileNames : null }]

  const conv = conversations.value.find((c) => c.id === convId)
  if (conv && (conv.title === '新对话' || !conv.title)) {
    const newTitle = cleanTitle(sanitizeInput(titleBase))
    conv.title = newTitle
    updateConversation(convId, { title: newTitle }).catch(() => {})
  }

  input.value = ''
  clearAttachments()
  loadingSet.value.add(convId)
  await nextTick()
  scrollToBottom()

  try {
    abortCtrl.value = new AbortController()
    // 思考强度作用于提示词：普通问答也带上，保持和 Agent 模式一致的风格控制
    const question = thinkPrefix(think.value) + q

    // 先插一条空的 AI 消息，收到增量就往里追加 —— 首字到达即渲染，不用等整段生成完
    const startIdx = (convMessages.value[convId] || []).length
    convMessages.value[convId] = [...(convMessages.value[convId] || []), {
      role: 'ai', content: '', sources: [], mode: mode.value, streaming: true, reasoning: '', qaId: null
    }]
    let lastScroll = 0
    const patch = (fn) => {
      const arr = convMessages.value[convId]
      if (!arr || !arr[startIdx]) return
      fn(arr[startIdx])
      convMessages.value[convId] = [...arr]
      const now = Date.now()
      if (currentId.value === convId && now - lastScroll > 80) { lastScroll = now; scrollToBottom() }
    }

    let data
    try {
      data = await aiAskStream(question, convId, mode.value, {
        ...modelPayload(),
        images: images.length ? images : null,
        signal: abortCtrl.value.signal,
        onDelta: (d) => patch((m) => { m.content += d }),
        onReasoning: (r) => patch((m) => { m.reasoning = (m.reasoning || '') + r })
      })
    } catch (err) {
      // 旧后端没有 /ai/ask/stream：404 时回退到一次性接口，保证兼容
      if (err && err.status === 404) {
        const { data: fallback } = await aiAsk(question, convId, mode.value, {
          ...modelPayload(), signal: abortCtrl.value.signal,
          images: images.length ? images : null
        })
        data = fallback
      } else {
        throw err
      }
    }
    patch((m) => {
      m.content = data.answer || m.content || '（暂无返回内容）'
      m.sources = data.sources || []
      m.meta = buildMeta(data)
      m.qaId = data.qaId || null
      m.streaming = false
    })
    if (currentId.value === convId) {
      const c2 = conversations.value.find((c) => c.id === convId)
      if (c2 && data.title && data.title !== c2.title) c2.title = data.title
      const idx = conversations.value.findIndex((c) => c.id === convId)
      if (idx > 0) { const [item] = conversations.value.splice(idx, 1); conversations.value.unshift(item) }
    }
  } catch (e) {
    const curErr = convMessages.value[convId] || []
    const stopped = e && (e.name === 'CanceledError' || e.code === 'ERR_CANCELED' || e.name === 'AbortError')
    const text = stopped ? '（已停止生成）' : '请求失败，请稍后重试。'
    const last = curErr[curErr.length - 1]
    if (last && last.streaming) {
      // 已经吐了部分内容就保留，只把 streaming 标记摘掉；一个字都没有才替换成提示
      if (last.content) last.streaming = false
      else curErr[curErr.length - 1] = { role: 'ai', content: text, sources: [], mode: mode.value }
      convMessages.value[convId] = [...curErr]
    } else {
      convMessages.value[convId] = [...curErr, { role: 'ai', content: text, sources: [], mode: mode.value }]
    }
  } finally {
    abortCtrl.value = null
    loadingSet.value.delete(convId)
    if (currentId.value === convId) scrollToBottom()
  }
}

async function regenerate() {
  if (loading.value) return
  const arr = convMessages.value[currentId.value] || []
  let ui = -1
  for (let i = arr.length - 1; i >= 0; i--) { if (arr[i].role === 'user') { ui = i; break } }
  if (ui < 0) return
  const lastQ = arr[ui].content
  const convId = currentId.value

  loadingSet.value.add(convId)
  await nextTick()
  try {
    abortCtrl.value = new AbortController()
    const { data } = await aiAsk(lastQ, convId, mode.value, {
      ...modelPayload(), signal: abortCtrl.value.signal
    })
    const newArr = [...arr]
    const aiMsg = {
      role: 'ai', content: data.answer || '（暂无返回内容）',
      sources: data.sources || [], mode: mode.value, meta: buildMeta(data), qaId: data.qaId || null
    }
    if (newArr[ui + 1] && newArr[ui + 1].role === 'ai') newArr[ui + 1] = aiMsg
    else newArr.splice(ui + 1, 0, aiMsg)
    convMessages.value[convId] = newArr
  } catch (e) {
    const stopped = e && (e.name === 'CanceledError' || e.code === 'ERR_CANCELED')
    if (!stopped) ElMessage.error('重新生成失败，请稍后重试')
  } finally {
    abortCtrl.value = null
    loadingSet.value.delete(convId)
    if (currentId.value === convId) scrollToBottom()
  }
}

async function removeConversation(id, e) {
  e?.stopPropagation()
  try {
    await ElMessageBox.confirm('确定删除这条会话及其历史消息吗？', '删除会话', { type: 'warning' })
    await deleteConversation(id)
    conversations.value = conversations.value.filter((c) => c.id !== id)
    delete convMessages.value[id]
    if (currentId.value === id) { currentId.value = null; localStorage.removeItem(convStorageKey()) }
    ElMessage.success('已删除')
  } catch (e) {
    if (e !== 'cancel') ElMessage.error('删除失败')
  }
}

// ---------- 批量删除会话 ----------
const batchMode = ref(false)
const selectedIds = ref([])

function toggleBatchMode() {
  batchMode.value = !batchMode.value
  selectedIds.value = []
}

function isSelected(id) {
  return selectedIds.value.includes(id)
}

function toggleSelect(id) {
  const i = selectedIds.value.indexOf(id)
  if (i >= 0) selectedIds.value.splice(i, 1)
  else selectedIds.value.push(id)
}

function selectAllConvs() {
  selectedIds.value = selectedIds.value.length === conversations.value.length
    ? []
    : conversations.value.map((c) => c.id)
}

async function removeSelectedConvs() {
  const ids = [...selectedIds.value]
  if (!ids.length) return
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${ids.length} 个会话？删除后聊天记录无法恢复。`,
      '批量删除', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch { return }
  try {
    await batchDeleteConversations(ids)
    const gone = new Set(ids.map(String))
    conversations.value = conversations.value.filter((c) => !gone.has(String(c.id)))
    ids.forEach((id) => { delete convMessages.value[id] })
    // 当前正在看的会话被删掉了 → 切到剩下的第一条，别停在空白页
    if (ids.some((id) => String(id) === String(currentId.value))) {
      currentId.value = conversations.value[0]?.id ?? null
      if (currentId.value == null) localStorage.removeItem(convStorageKey())
    }
    ElMessage.success(`已删除 ${ids.length} 个会话`)
    batchMode.value = false
    selectedIds.value = []
  } catch (e) {
    ElMessage.error('批量删除失败：' + ((e && e.message) || e))
  }
}

function startRename(c, e) {
  e?.stopPropagation()
  renamingId.value = c.id
  renameTitle.value = c.title
  nextTick(() => { const el = document.querySelector('.conv-rename-input input'); el?.focus(); el?.select() })
}
async function submitRename(c) {
  const title = renameTitle.value.trim()
  if (!title || title === c.title) { renamingId.value = null; return }
  try { await updateConversation(c.id, { title }); c.title = title; ElMessage.success('已重命名') }
  catch { ElMessage.error('重命名失败') } finally { renamingId.value = null }
}

function openInstructions(c) {
  instructionsForm.value = { id: c.id, title: c.title, instructions: c.instructions || '' }
  showInstructionsDrawer.value = true
}
async function submitInstructions() {
  const { id, title, instructions } = instructionsForm.value
  savingInstructions.value = true
  try {
    const { data } = await updateConversation(id, { title, instructions })
    const conv = conversations.value.find((c) => c.id === id)
    if (conv) { conv.title = data.title; conv.instructions = data.instructions }
    showInstructionsDrawer.value = false
    ElMessage.success('已保存')
  } catch { ElMessage.error('保存失败') } finally { savingInstructions.value = false }
}

/** 把文本类内容（文本文件 + 「识文字」的图片）拼进提问；「看图」的图片由 images 走多模态 */
async function copyAnswer(m) {
  try { await navigator.clipboard.writeText(m.content); ElMessage.success('已复制') }
  catch { ElMessage.warning('复制失败，请手动选择') }
}

// ---------- Markdown 渲染：共用模块 + 本端注入 Prism 高亮器 ----------
function highlightCode(code, lang) {
  const l = (lang || '').toLowerCase()
  if (l && Prism.languages[l]) {
    try { return Prism.highlight(code, Prism.languages[l], l) } catch (e) { /* 语言不可用则回退纯文本 */ }
  }
  return escapeHtml(code)
}
const { renderMarkdown, fmtMs, fmtTokens, openSource } = useMarkdownView({ highlight: highlightCode })

function onBodyClick(e) {
  const btn = e.target.closest?.('.md-copy-btn')
  if (!btn) return
  const codeEl = btn.parentElement?.querySelector('code')
  const text = codeEl?.innerText || ''
  navigator.clipboard.writeText(text).then(() => {
    const old = btn.textContent; btn.textContent = '已复制'
    setTimeout(() => { btn.textContent = old }, 1200)
  }).catch(() => ElMessage.warning('复制失败'))
}

function onKey(e) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() }
}

onMounted(async () => {
  // 支持 /ai/chat?mode=agent 直达对应模式（「我的测验 → 让 AI 出题」就用这个）
  const qm = String(route.query.mode || '').toLowerCase()
  if (['kb', 'general', 'agent'].includes(qm)) mode.value = qm
  // 搜索无结果「去问 AI」：把原关键词带过来预填输入框，用户可直接回车发送
  const qpre = String(route.query.q || '').trim()
  if (qpre && !input.value) input.value = qpre
  loadAiGuard()
  // 窄屏判定：与 CSS 断点保持一致（≤1024 侧栏变抽屉）
  mqNarrow = window.matchMedia('(max-width: 1024px)')
  isNarrow.value = mqNarrow.matches
  mqNarrow.addEventListener('change', onNarrowChange)

  bodyRef.value?.addEventListener('scroll', onBodyScroll)
  bodyRef.value?.addEventListener('click', onBodyClick)
  // 先拉可用模型，头部下拉与发送时的 modelId 都依赖它
  await loadModels()
  loadCtxWindow()   // 模型拿到后才能按 id 恢复用户调过的上下文窗口
  await loadConversations()
  const saved = localStorage.getItem(convStorageKey())
  if (saved && conversations.value.some((c) => String(c.id) === saved)) {
    currentId.value = Number(saved)
    await loadMessages(currentId.value)
  } else if (conversations.value.length > 0) {
    currentId.value = conversations.value[0].id
    localStorage.setItem(convStorageKey(), String(currentId.value))
    await loadMessages(currentId.value)
  } else {
    currentId.value = null
    localStorage.removeItem(convStorageKey())
  }
})

onBeforeUnmount(() => {
  videoTimers.forEach((t) => clearInterval(t))
  if (mqNarrow) mqNarrow.removeEventListener('change', onNarrowChange)
  bodyRef.value?.removeEventListener('scroll', onBodyScroll)
  bodyRef.value?.removeEventListener('click', onBodyClick)
})

watch(currentId, (id) => { if (id) localStorage.setItem(convStorageKey(), String(id)) })
</script>

<template>
  <div class="ai-layout">
    <!-- ===== 侧栏 ===== -->
    <aside
      class="ai-sidebar"
      :class="{
        collapsed: !isNarrow && sidebarCollapsed,
        'is-drawer': isNarrow,
        'drawer-open': isNarrow && drawerOpen,
      }"
    >
      <div class="ai-sidebar-inner">
      <div class="ai-sidebar-head">
        <button type="button" class="ai-newchat" :disabled="creating" @click="newConversation">
          <el-icon><Plus /></el-icon><span>新建对话</span>
        </button>
      </div>
      <div class="ai-conv-head">
        <span>历史会话</span>
        <div class="conv-head-acts">
          <template v-if="batchMode">
            <button type="button" class="conv-mini" @click="selectAllConvs">
              {{ selectedIds.length === conversations.length && conversations.length ? '取消全选' : '全选' }}
            </button>
            <button type="button" class="conv-mini danger" :disabled="!selectedIds.length" @click="removeSelectedConvs">
              删除{{ selectedIds.length ? `(${selectedIds.length})` : '' }}
            </button>
            <button type="button" class="conv-mini" @click="toggleBatchMode">取消</button>
          </template>
          <button v-else type="button" class="conv-mini" :disabled="!conversations.length"
            title="批量删除会话" @click="toggleBatchMode">批量</button>
        </div>
      </div>
      <div v-loading="loadingConversations" class="ai-conv-list">
        <TransitionGroup name="conv" tag="div" class="ai-conv-inner">
          <div
            v-for="c in conversations"
            :key="c.id"
            class="ai-conv-item"
            :class="{ active: currentId === c.id && !batchMode, selected: batchMode && isSelected(c.id) }"
            @click="batchMode ? toggleSelect(c.id) : selectConversation(c.id)"
          >
            <el-icon v-if="batchMode" class="ai-conv-check" :class="{ on: isSelected(c.id) }">
              <Select />
            </el-icon>
            <el-icon v-else class="ai-conv-icon"><ChatLineRound /></el-icon>
            <div class="ai-conv-main">
              <div v-if="renamingId === c.id" class="ai-conv-title" @click.stop>
                <el-input v-model="renameTitle" size="small" class="conv-rename-input"
                  @blur="submitRename(c)" @keydown.enter="submitRename(c)" />
              </div>
              <div v-else class="ai-conv-title" :title="c.title">{{ c.title }}</div>
              <div class="ai-conv-meta">{{ c.updatedAt?.replace('T',' ')?.slice(0,16) || '' }}</div>
            </div>
            <div v-if="!batchMode" class="ai-conv-actions" @click.stop>
              <el-button text :icon="Document" title="对话条例" @click="openInstructions(c)" />
              <el-button text :icon="EditPen" title="重命名" @click="startRename(c, $event)" />
              <el-button text :icon="Delete" title="删除" @click="removeConversation(c.id, $event)" />
            </div>
          </div>
        </TransitionGroup>
        <div v-if="conversations.length === 0 && !loadingConversations" class="ai-conv-empty">
          暂无历史会话<br>点击上方“新建对话”开始提问
        </div>
      </div>
      </div>
    </aside>

    <!-- 窄屏抽屉遮罩：点击任意处收起侧栏 -->
    <div v-if="isNarrow && drawerOpen" class="ai-side-mask" @click="drawerOpen = false"></div>

    <!-- ===== 主区 ===== -->
    <div class="ai-page">
      <div class="ai-head">
        <div class="ai-head-left">
          <el-tooltip :content="sideToggleTip" placement="bottom" :show-after="200">
            <button type="button" class="ai-side-toggle" @click="toggleSidebar">
              <el-icon v-if="isNarrow ? !drawerOpen : sidebarCollapsed"><Expand /></el-icon>
              <el-icon v-else><Fold /></el-icon>
            </button>
          </el-tooltip>
          <div class="ai-head-info">
            <div class="ai-title">{{ currentConversation?.title || 'AI 智能问答' }}</div>
            <div class="ai-sub">{{ mode === 'agent' ? 'Agent 模式 · 自主检索 / 写文档 / 改文档' : (mode === 'general' ? '通用模式 · 直接作答' : '基于知识库作答 · 库里没有也能通用问答') }}</div>
          </div>
        </div>

      </div>

      <div class="ai-body" ref="bodyRef" v-loading="loadingMessages">
        <!-- 空状态 -->
        <div v-if="!started && !loadingMessages" key="empty" class="ai-empty">
          <div class="ai-orb">
            <svg class="ai-orb-mark" viewBox="0 0 24 24" aria-hidden="true">
              <path d="M12 2 C12 8 8 12 2 12 C8 12 12 16 12 22 C12 16 16 12 22 12 C16 12 12 8 12 2 Z" fill="#fff"/>
            </svg>
          </div>
          <h2 class="ai-empty-title">{{ emptyCopy.title }}</h2>
          <p class="ai-empty-tip">{{ emptyCopy.tip }}</p>
          <div class="ai-features">
            <span v-for="f in emptyCopy.features" :key="f.text" class="ai-feature">
              <el-icon><Reading v-if="f.icon === 'Reading'" /><EditPen v-else-if="f.icon === 'EditPen'" /><Cpu v-else-if="f.icon === 'Cpu'" /><ChatDotRound v-else /></el-icon>{{ f.text }}
            </span>
          </div>
          <div class="ai-sugs">
            <button v-for="s in emptyCopy.sugs" :key="s" class="ai-sug" type="button" @click="send(s)">{{ s }}</button>
          </div>
          <p class="ai-disclaimer">内容由 AI 生成，仅供参考</p>
        </div>

        <!-- 消息流（直接渲染，无外层 Transition，避免切换抖动） -->
        <div v-else class="ai-msgs">
          <template v-for="(m, i) in messages" :key="currentId + ':' + i">
            <!-- 用户：消息气泡在左，头像在右，整行右对齐 -->
            <div v-if="m.role === 'user'" class="ai-msg user">
              <div class="ai-bubble user-bubble">
                <div v-if="m.files && m.files.length" class="ai-files">
                  <span v-for="f in m.files" :key="f" class="ai-file-chip">📎 {{ f }}</span>
                </div>
                <div v-html="escapeHtml(m.content)"></div>
              </div>
              <div class="ai-avatar user-av" :style="{ background: userGradient }">
                <img v-if="userAvatar" :src="userAvatar" alt="" />
                <span v-else>{{ userInitial }}</span>
              </div>
            </div>

            <!-- AI：左对齐，自定义 sparkle 头像 -->
            <div v-else class="ai-msg ai">
              <div class="ai-avatar ai-av">
                <svg class="ai-av-mark" viewBox="0 0 24 24" aria-hidden="true">
                  <path d="M12 2 C12 8 8 12 2 12 C8 12 12 16 12 22 C12 16 16 12 22 12 C16 12 12 8 12 2 Z" fill="#fff"/>
                </svg>
              </div>
              <div class="ai-col">
                <div class="ai-name-row">
                  <span class="ai-name">{{ m.mode === 'agent' ? 'Agent' : '知识库 AI' }}</span>
                  <span class="ai-mode-chip">{{ m.mode === 'agent' ? 'Agent 执行' : (m.mode === 'general' ? '通用问答' : '基于知识库') }}</span>
                </div>
                <!-- 思考过程：思考型模型才有，默认折叠（历史回答不保留） -->
                <details v-if="m.meta && m.meta.reasoning" class="ai-reasoning">
                  <summary>💭 思考过程（{{ m.meta.reasoning.length }} 字）</summary>
                  <div class="ai-reasoning-body">{{ m.meta.reasoning }}</div>
                </details>
                <div v-if="m.steps && m.steps.length" class="ai-agent-steps msg-steps">
                  <div class="ai-agent-head" @click="msgStepsOpen[i] = !msgStepsOpen[i]">
                    <span>执行过程（{{ m.steps.length }} 步）</span>
                    <span class="ai-agent-toggle">{{ msgStepsOpen[i] ? '收起' : '展开' }}</span>
                  </div>
                  <div v-show="msgStepsOpen[i]" class="ai-agent-list">
                    <div v-for="(st, si) in m.steps" :key="si" class="ai-agent-step">
                      <span class="ai-agent-dot"></span>{{ st }}
                    </div>
                  </div>
                </div>
                <div class="ai-flat" v-html="renderMarkdown(m.content)"></div>

                <div v-if="m.sources && m.sources.length" class="ai-sources">
                  <div class="ai-sources-head">
                    <el-icon><Link /></el-icon>
                    <span>引用来源（{{ m.sources.length }}）</span>
                  </div>
                  <div class="ai-source-list">
                    <button v-for="(s, si) in m.sources" :key="si" type="button" class="ai-source"
                      :title="s.id != null ? '点击在新窗口阅读' : ''" @click="openSource(s)">
                      <span class="ai-source-title">{{ s.title }}</span>
                      <span class="ai-source-snippet">{{ s.snippet }}</span>
                    </button>
                  </div>
                </div>
                <!-- 检索未命中：明确告知「查过知识库但没找到」，把这种回答和「引用了资料」区分开。
                     否则答案看上去和引用资料时一模一样，用户无法判断它到底有没有依据知识库。 -->
                <div v-else-if="m.mode !== 'general'" class="ai-no-source">
                  <el-icon><Search /></el-icon>
                  <span>已检索知识库，未命中相关内容</span>
                </div>

                <!-- 反馈：赞 / 踩（自绘拇指图标，可随时切换或取消）。踩时展开原因面板；数据回流到管理端「AI 反馈」看板 -->
                <div v-if="m.qaId && !m.streaming" class="ai-fb">
                  <button type="button" class="ai-fb-btn" :class="{ on: m.fbRating === 1 }" title="有帮助" @click="rateMsg(m, 1)">
                    <svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="currentColor" d="M2 21h2V9H2v12zm20-10.09c0-.58-.47-1.05-1.05-1.05H13.8l.94-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.3 1.3 6.72 7.9A1.98 1.98 0 0 0 5.3 9.3v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-1.91z"/></svg>
                  </button>
                  <button type="button" class="ai-fb-btn" :class="{ on: m.fbRating === -1 }" title="没帮助" @click="rateMsg(m, -1)">
                    <svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true" style="transform: rotate(180deg)"><path fill="currentColor" d="M2 21h2V9H2v12zm20-10.09c0-.58-.47-1.05-1.05-1.05H13.8l.94-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.3 1.3 6.72 7.9A1.98 1.98 0 0 0 5.3 9.3v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-1.91z"/></svg>
                  </button>
                  <span v-if="m.fbRating === 1 && !m.fbOpen" class="ai-fb-done">已赞，再点一次可取消</span>
                  <span v-else-if="m.fbRating === -1" class="ai-fb-done">已反馈，再点 👎 可取消</span>
                  <div v-if="m.fbOpen && m.fbRating !== -1" class="ai-fb-reasons">
                    <span class="ai-fb-tip">哪里不好？</span>
                    <button v-for="r in FB_REASONS" :key="r" type="button" @click="submitRate(m, -1, r)">{{ r }}</button>
                    <button type="button" class="ghost" @click="submitRate(m, -1, '')">就这些</button>
                  </div>
                </div>

                <!-- 调用可视化：本次实际用的模型、耗时、token 用量 -->
                <div v-if="m.meta" class="ai-meta">
                  <span v-if="m.meta.name" class="ai-meta-chip">
                    <el-icon><Cpu /></el-icon>{{ m.meta.name }}
                  </span>
                  <span v-if="m.meta.latencyMs" class="ai-meta-chip">{{ fmtMs(m.meta.latencyMs) }}</span>
                  <span v-if="fmtTokens(m.meta.usage)" class="ai-meta-chip">
                    {{ fmtTokens(m.meta.usage) }}
                  </span>
                </div>

                <div class="ai-actions">
                  <el-tooltip content="复制" placement="top" :show-after="200">
                    <button type="button" class="ai-icon-btn" @click="copyAnswer(m)"><el-icon><CopyDocument /></el-icon></button>
                  </el-tooltip>
                  <el-tooltip content="重新生成" placement="top" :show-after="200">
                    <button type="button" class="ai-icon-btn" :disabled="loading" @click="regenerate"><el-icon><Refresh /></el-icon></button>
                  </el-tooltip>
                </div>
              </div>
            </div>
          </template>

          <div v-if="loading" class="ai-msg ai">
            <div class="ai-avatar ai-av">
              <svg class="ai-av-mark" viewBox="0 0 24 24" aria-hidden="true">
                <path d="M12 2 C12 8 8 12 2 12 C8 12 12 16 12 22 C12 16 16 12 22 12 C16 12 12 8 12 2 Z" fill="#fff"/>
              </svg>
            </div>
            <div class="ai-col">
              <div class="ai-typing"><span></span><span></span><span></span></div>
              <!-- Agent 模式：步骤可折叠（执行中展开看进度，完成后自动收起，避免刷屏） -->
              <div v-if="agentSteps.length" class="ai-agent-steps">
                <div class="ai-agent-head" @click="agentStepsOpen = !agentStepsOpen">
                  <span>执行过程（{{ agentSteps.length }} 步）</span>
                  <span class="ai-agent-toggle">{{ agentStepsOpen ? '收起' : '展开' }}</span>
                </div>
                <div v-show="agentStepsOpen" class="ai-agent-list">
                  <div v-for="(s, i) in agentSteps" :key="i" class="ai-agent-step">
                    <span class="ai-agent-dot"></span>{{ s }}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>

        <transition name="fade">
          <button v-if="showScrollBtn && started" type="button" class="ai-tobottom" @click="scrollToBottom" title="回到底部">
            <el-icon><ArrowDown /></el-icon>
          </button>
        </transition>
      </div>

      <!-- 输入栏 -->
      <div class="ai-input-wrap">
        <div class="ai-input-card">
          <input ref="fileInput" type="file" multiple hidden
            accept="image/*,.pdf,.docx,.pptx,.xlsx,.txt,.md,.markdown,.json,.csv,.log,.xml,.yml,.yaml,.sql,.ts,.js,.py,.java,.html,.css,.vue,.ini,.conf,.properties"
            @change="onFilesPicked" />
          <div v-if="attachments.length" class="ai-atts">
            <span v-for="a in attachments" :key="a.id" class="ai-att" :title="a.name">
              {{ a.kind === 'image' ? (a.mode === 'vision' ? '🖼' : '📝') : '📄' }} {{ a.name.length > 14 ? a.name.slice(0, 13) + '…' : a.name }}
              <button v-if="a.kind === 'image'" type="button" class="ai-att-mode"
                :title="a.mode === 'vision' ? '当前：直接给模型看图。点击改为本地 OCR 识别文字（离线可用）' : '当前：本地 OCR 识别文字。点击改回直接看图'"
                @click="toggleAttMode(a.id)">{{ a.mode === 'vision' ? '看图' : '识文' }}</button>
              <button type="button" class="ai-att-x" @click="removeAttachment(a.id)">×</button>
            </span>
          </div>
          <textarea v-model="input" class="ai-text" rows="2"
            placeholder="输入你的问题，Enter 发送，Shift + Enter 换行" @keydown="onKey"></textarea>
          <div class="ai-input-bar">
            <div class="ai-mode" role="tablist" aria-label="回答模式">
              <span class="ai-mode-thumb" :style="thumbStyle"></span>
              <button type="button" class="ai-mode-btn" :class="{ active: mode === 'kb' }"
                title="仅依据知识库内容作答" @click="mode = 'kb'">
                <el-icon><Reading /></el-icon><span>知识库</span>
              </button>
              <button type="button" class="ai-mode-btn" :class="{ active: mode === 'general' }"
                title="不检索知识库，进行通用问答" @click="mode = 'general'">
                <el-icon><ChatDotRound /></el-icon><span>通用</span>
              </button>
              <button type="button" class="ai-mode-btn" :class="{ active: mode === 'agent' }"
                title="Agent：自主检索资料、写文档、改文档（会真正操作你的知识库）" @click="mode = 'agent'">
                <el-icon><Cpu /></el-icon><span>Agent</span>
              </button>
            </div>
            <div class="ai-input-right">
              <el-tooltip content="上传文件（图片 OCR / 文本类）" placement="top" :show-after="300">
                <button type="button" class="ai-round-btn ghost" @click="pickFiles" :disabled="attBusy"><el-icon><Paperclip /></el-icon></button>
              </el-tooltip>
              <el-tooltip content="生成视频（需管理员配置文生视频模型）" placement="top" :show-after="300">
                <button type="button" class="ai-round-btn ghost" @click="genVideo" :disabled="genBusy"><el-icon><VideoCamera /></el-icon></button>
              </el-tooltip>
              <div class="ai-input-tools">
              <!-- 上下文用量环：点开可以看明细、切思考强度、压缩历史 -->
              <el-popover placement="top-end" :width="304" trigger="click"
                          popper-class="ai-ctx-pop" :show-arrow="false" :offset="8">
                <template #reference>
                  <button type="button" class="ai-ctx"
                          :title="`上下文 ${formatTokens(usedTokens)} / ${formatTokens(ctxWindow)}（${ctxPct}%）`">
                    <svg viewBox="0 0 36 36" class="ai-ctx-ring" aria-hidden="true">
                      <circle cx="18" cy="18" r="15.5" class="ai-ctx-bg" />
                      <circle cx="18" cy="18" r="15.5" class="ai-ctx-fg"
                              :stroke="ctxColor" :stroke-dasharray="ctxRing" />
                    </svg>
                    <span class="ai-ctx-num">{{ ctxPct }}</span>
                  </button>
                </template>
                <div class="ctxp">
                  <div class="ctxp-head">
                    <span>上下文用量</span>
                    <b :style="{ color: ctxColor }">{{ ctxPct }}%</b>
                  </div>
                  <div class="ctxp-bar seg">
                    <i v-for="p in ctxParts" :key="p.name"
                       :style="{ width: (p.value / ctxWindow) * 100 + '%', background: p.color }"></i>
                  </div>
                  <div v-for="p in ctxParts" :key="p.name" class="ctxp-row">
                    <span class="ctxp-k"><i class="ctxp-dot" :style="{ background: p.color }"></i>{{ p.name }}</span>
                    <b>{{ formatTokens(p.value) }}</b>
                  </div>
                  <div class="ctxp-foot">
                    <span>窗口 {{ formatTokens(ctxWindow) }} · {{ effectiveModel?.name || '默认模型' }} · {{ activeMessages.length }} 条消息</span>
                  </div>

                  <div class="ctxp-sec">上下文窗口</div>
                  <el-slider
                    :model-value="ctxWindow" :min="8000" :max="modelMaxWindow" :step="1000"
                    :show-tooltip="false" class="ctxp-slider"
                    @update:model-value="(v) => { ctxWindowSet = v; saveCtxWindow() }" />
                  <div class="ctxp-slider-label">
                    <span><b>{{ formatTokens(ctxWindow) }}</b> 上下文 · {{ ctxWindowLabel }}</span>
                    <button v-if="ctxWindowSet > 0" type="button" class="ctxp-reset"
                            @click="ctxWindowSet = 0; saveCtxWindow()">恢复上限</button>
                  </div>

                  <div class="ctxp-sec">思考强度</div>
                  <div class="ctxp-think">
                    <button v-for="t in THINK_LEVELS" :key="t.value" type="button"
                            class="ctxp-tbtn" :class="{ on: think === t.value }" :title="t.tip"
                            @click="think = t.value">{{ t.label }}</button>
                  </div>

                  <div class="ctxp-sec">压缩上下文</div>
                  <button type="button" class="ctxp-act" :disabled="compacting" @click="compactContext">
                    {{ compacting ? '压缩中…' : '把历史压成摘要' }}
                  </button>
                  <p class="ctxp-tip">保留目标、结果和未完成事项，细节会丢。对话很长、开始变慢或报超长时用。</p>
                </div>
              </el-popover>
              <el-popover
                v-if="modelList.length"
                placement="top-end" :width="288" trigger="click"
                popper-class="aimp-popover" :show-arrow="false" :offset="8">
                <template #reference>
                  <button type="button" class="aimp-trigger" :title="currentModel ? currentModel.name + ' · ' + currentModel.model : '使用默认模型'">
                    <ProviderLogo v-if="currentModelProvider" :provider="currentModelProvider" :size="16" />
                    <span v-else class="aimp-dot"></span>
                    <span class="aimp-label">{{ currentModelLabel }}</span>
                    <el-icon class="aimp-caret"><ArrowDown /></el-icon>
                  </button>
                </template>

                <div class="aimp-list">
                  <div class="aimp-head">选择模型</div>
                  <button type="button" class="aimp-row" :class="{ on: !selectedModelId }" @click="pickModel('')">
                    <span class="aimp-ico"><span class="aimp-dot"></span></span>
                    <span class="aimp-main">
                      <span class="aimp-name">默认模型</span>
                      <span class="aimp-sub">由管理员指定</span>
                    </span>
                    <el-icon v-if="!selectedModelId" class="aimp-check"><Select /></el-icon>
                  </button>
                  <button
                    v-for="m in modelList" :key="m.scope + ':' + m.id" type="button"
                    class="aimp-row" :class="{ on: selectedModelId === mkey(m) }"
                    @click="pickModel(mkey(m))">
                    <span class="aimp-ico"><ProviderLogo :provider="m.provider" :size="18" /></span>
                    <span class="aimp-main">
                      <span class="aimp-name">{{ m.name }}<span v-if="m.scope === 'user'" style="margin-left:6px;font-size:10px;color:#1677ff;border:1px solid #bcd3ff;border-radius:4px;padding:0 4px">我的</span></span>
                      <span class="aimp-sub">{{ m.model }}<template v-if="m.isDefault"> · 默认</template></span>
                    </span>
                    <el-icon v-if="selectedModelId === mkey(m)" class="aimp-check"><Select /></el-icon>
                  </button>
                  <button type="button" class="aimp-row aimp-add" @click="router.push('/my-ai-models')">
                    <span class="aimp-ico aimp-add-ico">＋</span>
                    <span class="aimp-main">
                      <span class="aimp-name aimp-add-name">自定义模型</span>
                      <span class="aimp-sub">添加自己的模型，与管理员模型互不影响</span>
                    </span>
                  </button>
                </div>
              </el-popover>
              <el-tooltip v-else content="管理员尚未配置可用模型，可先添加自己的模型" placement="top" :show-after="200">
                <span class="ai-model-tag is-empty" style="cursor:pointer" @click="router.push('/my-ai-models')">
                  <span class="ai-dot"></span>添加自定义模型
                </span>
              </el-tooltip>
              </div>
              <button v-if="!loading" type="button" class="ai-send" @click="send()">
                <el-icon><Promotion /></el-icon>
                <span>发送</span>
              </button>
              <button v-else type="button" class="ai-send stop" title="停止生成" @click="stopAi">
                <el-icon><VideoPause /></el-icon>
                <span>停止</span>
              </button>
            </div>
          </div>
        </div>
        <div class="ai-foot-disclaimer">内容由 AI 生成，请甄别参考</div>
      </div>
    </div>

    <!-- 对话条例 -->
    <el-drawer v-model="showInstructionsDrawer" title="对话条例" size="360px">
      <div class="ai-form">
        <el-form label-position="top">
          <el-form-item label="会话标题"><el-input v-model="instructionsForm.title" /></el-form-item>
          <el-form-item label="本次对话条例 / 系统提示">
            <el-input v-model="instructionsForm.instructions" type="textarea" :rows="8"
              placeholder="例如：请用简短 bullet 回答；优先引用官方文档；只讨论 Java 相关话题……" />
          </el-form-item>
        </el-form>
        <div class="ai-form-actions">
          <el-button @click="showInstructionsDrawer = false">取消</el-button>
          <el-button type="primary" :loading="savingInstructions" @click="submitInstructions">保存</el-button>
        </div>
      </div>
    </el-drawer>

    <!-- Agent 询问用户：给选项点选，也可以自己输入 -->
    <div v-if="agentAsk" class="ai-ask-mask" @click.self="answerAgentAsk('')">
      <div class="ai-ask-box">
        <div class="ai-ask-title">Agent 想跟你确认</div>
        <p class="ai-ask-q">{{ agentAsk.question }}</p>
        <div v-if="agentAsk.options.length" class="ai-ask-opts">
          <button
            v-for="(o, i) in agentAsk.options" :key="i"
            class="ai-ask-opt" @click="answerAgentAsk(o)"
          >{{ o }}</button>
        </div>
        <div class="ai-ask-custom">
          <input
            v-model="agentAskInput" class="ai-ask-i"
            placeholder="也可以自己输入…" @keyup.enter="answerAgentAsk()"
          />
          <button class="ai-ask-send" @click="answerAgentAsk()">确定</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped src="./AiChat.css"></style>

<!-- 模型选择弹层：el-popover 挂到 body，scoped 样式作用不到，需非 scoped（2026-10-18） -->
<style src="./AiChatGlobal.css"></style>