<script setup>
import { ref, nextTick, onMounted, onBeforeUnmount, computed, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatDotRound, Plus, Delete, EditPen, ChatLineRound, Document,
  Paperclip, Link, ArrowDown, CopyDocument, Refresh, Reading, Promotion,
  Fold, Expand, VideoPause, Cpu
} from '@element-plus/icons-vue'
import { useAuthStore } from '../store/auth'
import {
  aiAsk, listAiModels, listConversations, createConversation, updateConversation,
  deleteConversation, listMessages
} from '../api/ai'
import MarkdownIt from 'markdown-it'
import Prism from 'prismjs'
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
// ---------- 调用可视化：本次实际用的模型 / 耗时 / token 用量，回答下方一行展示 ----------
function buildMeta(data) {
  if (!data) return null
  const name = data.modelName || data.model || ''
  if (!name && data.latencyMs == null) return null
  return { name, latencyMs: Number(data.latencyMs) || 0, usage: data.usage || null }
}
function fmtMs(ms) {
  if (!ms) return ''
  return ms >= 1000 ? (ms / 1000).toFixed(2) + 's' : ms + 'ms'
}
function onModelChange() {
  // 只影响后续提问：切换模型不会改写已有回答（历史回答保留当时的模型与耗时）
  ElMessage.info(selectedModelId.value ? '下一句将使用所选模型' : '下一句将使用默认模型')
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
const thumbStyle = computed(() => ({ transform: mode.value === 'general' ? 'translateX(100%)' : 'translateX(0)' }))

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
]

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

async function send(text) {
  const q = (text ?? input.value).trim()
  if (!q) { ElMessage.warning('请输入内容'); return }
  if (loading.value) return

  let convId = currentId.value
  if (!convId) {
    creating.value = true
    try {
      const { data } = await createConversation(q)
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
  convMessages.value[convId] = [...curUser, { role: 'user', content: q }]

  const conv = conversations.value.find((c) => c.id === convId)
  if (conv && (conv.title === '新对话' || !conv.title)) {
    const newTitle = q.length > 20 ? q.slice(0, 20) + '…' : q
    conv.title = newTitle
    updateConversation(convId, { title: newTitle }).catch(() => {})
  }

  input.value = ''
  loadingSet.value.add(convId)
  await nextTick()
  scrollToBottom()

  try {
    abortCtrl.value = new AbortController()
    const { data } = await aiAsk(q, convId, mode.value, {
      modelId: selectedModelId.value || null, signal: abortCtrl.value.signal
    })
    const curAi = convMessages.value[convId] || []
    convMessages.value[convId] = [...curAi, {
      role: 'ai', content: data.answer || '（暂无返回内容）',
      sources: data.sources || [], mode: mode.value, meta: buildMeta(data)
    }]
    if (currentId.value === convId) {
      const c2 = conversations.value.find((c) => c.id === convId)
      if (c2 && data.title && data.title !== c2.title) c2.title = data.title
      const idx = conversations.value.findIndex((c) => c.id === convId)
      if (idx > 0) { const [item] = conversations.value.splice(idx, 1); conversations.value.unshift(item) }
    }
  } catch (e) {
    const curErr = convMessages.value[convId] || []
    const stopped = e && (e.name === 'CanceledError' || e.code === 'ERR_CANCELED')
    convMessages.value[convId] = [...curErr, { role: 'ai', content: stopped ? '（已停止生成）' : '请求失败，请稍后重试。', sources: [], mode: mode.value }]
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
function onUpload() { ElMessage.info('暂仅支持文本提问，文件问答即将上线') }
async function copyAnswer(m) {
  try { await navigator.clipboard.writeText(m.content); ElMessage.success('已复制') }
  catch { ElMessage.warning('复制失败，请手动选择') }
}

// ---------- Markdown（markdown-it + Prism 语法高亮，均在 node_modules，无需额外安装） ----------
// html:false 防 XSS（AI 输出按纯文本解析）；linkify 自动识别裸链接；breaks 单换行即 <br> 贴合聊天排版
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
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
  const lang = info ? info.toLowerCase() : ''
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
  // 窄屏判定：与 CSS 断点保持一致（≤1024 侧栏变抽屉）
  mqNarrow = window.matchMedia('(max-width: 1024px)')
  isNarrow.value = mqNarrow.matches
  mqNarrow.addEventListener('change', onNarrowChange)

  bodyRef.value?.addEventListener('scroll', onBodyScroll)
  bodyRef.value?.addEventListener('click', onBodyClick)
  // 先拉可用模型，头部下拉与发送时的 modelId 都依赖它
  await loadModels()
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
      <div class="ai-conv-head">历史会话</div>
      <div v-loading="loadingConversations" class="ai-conv-list">
        <TransitionGroup name="conv" tag="div" class="ai-conv-inner">
          <div
            v-for="c in conversations"
            :key="c.id"
            class="ai-conv-item"
            :class="{ active: currentId === c.id }"
            @click="selectConversation(c.id)"
          >
            <el-icon class="ai-conv-icon"><ChatLineRound /></el-icon>
            <div class="ai-conv-main">
              <div v-if="renamingId === c.id" class="ai-conv-title" @click.stop>
                <el-input v-model="renameTitle" size="small" class="conv-rename-input"
                  @blur="submitRename(c)" @keydown.enter="submitRename(c)" />
              </div>
              <div v-else class="ai-conv-title" :title="c.title">{{ c.title }}</div>
              <div class="ai-conv-meta">{{ c.updatedAt?.replace('T',' ')?.slice(0,16) || '' }}</div>
            </div>
            <div class="ai-conv-actions" @click.stop>
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
            <div class="ai-sub">基于知识库作答 · 库里没有也能通用问答</div>
          </div>
        </div>
        <div class="ai-head-right">
          <el-select
            v-if="modelList.length"
            v-model="selectedModelId" class="ai-model-select" size="small"
            placeholder="选择模型" @change="onModelChange">
            <el-option value="" label="使用默认模型" />
            <el-option
              v-for="m in modelList" :key="m.id" :value="String(m.id)"
              :label="m.name + ' · ' + m.model" />
          </el-select>
          <el-tooltip v-else content="管理员尚未配置可用模型，AI 暂不可用" placement="bottom" :show-after="200">
            <span class="ai-model-tag is-empty"><span class="ai-dot"></span>未配置模型</span>
          </el-tooltip>
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
          <h2 class="ai-empty-title">你好，我是知识库 AI 助手</h2>
          <p class="ai-empty-tip">基于知识库检索作答，也能陪你日常聊聊</p>
          <div class="ai-features">
            <span class="ai-feature"><el-icon><Reading /></el-icon>知识检索</span>
            <span class="ai-feature"><el-icon><EditPen /></el-icon>总结写作</span>
            <span class="ai-feature"><el-icon><ChatDotRound /></el-icon>日常问答</span>
          </div>
          <div class="ai-sugs">
            <button v-for="s in suggestions" :key="s" class="ai-sug" type="button" @click="send(s)">{{ s }}</button>
          </div>
          <p class="ai-disclaimer">内容由 AI 生成，仅供参考</p>
        </div>

        <!-- 消息流（直接渲染，无外层 Transition，避免切换抖动） -->
        <div v-else class="ai-msgs">
          <template v-for="(m, i) in messages" :key="currentId + ':' + i">
            <!-- 用户：消息气泡在左，头像在右，整行右对齐 -->
            <div v-if="m.role === 'user'" class="ai-msg user">
              <div class="ai-bubble user-bubble" v-html="escapeHtml(m.content)"></div>
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
                  <span class="ai-name">知识库 AI</span>
                  <span class="ai-mode-chip">{{ m.mode === 'general' ? '通用问答' : '基于知识库' }}</span>
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
            </div>
            <div class="ai-input-right">
              <el-tooltip content="上传文件" placement="top" :show-after="300">
                <button type="button" class="ai-round-btn ghost" @click="onUpload"><el-icon><Paperclip /></el-icon></button>
              </el-tooltip>
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

.ai-conv-head { padding: 6px 18px 4px; font-size: 12px; color: #94a3b8; letter-spacing: .04em; }
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
.ai-model-select { width: 200px; }
.ai-model-select :deep(.el-select__wrapper) {
  height: 30px; font-size: 12.5px; border-radius: 999px;
  background: #eef2ff; border-color: #e0e7ff; color: #4f46e5;
}
.ai-model-select :deep(.el-select__placeholder) { color: #818cf8; }
html.dark .ai-model-select :deep(.el-select__wrapper) {
  background: #26264f; border-color: #3a3a63; color: #a5b4fc;
}
html.dark .ai-model-select :deep(.el-select__placeholder) { color: #8b8ca4; }
.ai-model-select :deep(.el-select__selected-item),
.ai-model-select :deep(.el-select__selected-item .el-select__placeholder) { color: inherit; }

/* 调用元信息（模型 / 耗时 / token） */
.ai-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 2px; }
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
.ai-input-bar { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }

/* 模式切换（滑动指示器） */
.ai-mode { position: relative; display: inline-flex; background: #f1f5f9; border-radius: 10px; padding: 3px; }
html.dark .ai-mode { background: #14142a; }
.ai-mode-thumb {
  position: absolute; top: 3px; left: 3px; width: calc(50% - 3px); height: calc(100% - 6px);
  background: #fff; border-radius: 8px; box-shadow: 0 1px 3px rgba(15,23,42,.12);
  transition: transform .22s cubic-bezier(.4,0,.2,1);
}
html.dark .ai-mode-thumb { background: #2b2c4d; }
.ai-mode-btn {
  position: relative; z-index: 1; flex: 1; display: inline-flex; align-items: center; justify-content: center; gap: 5px;
  border: none; background: transparent; color: #64748b; font-size: 13px; padding: 6px 16px; border-radius: 8px;
  cursor: pointer; transition: color .18s;
}
.ai-mode-btn .el-icon { font-size: 14px; }
.ai-mode-btn.active { color: #4f46e5; font-weight: 600; }
html.dark .ai-mode-btn.active { color: #a5b4fc; }

.ai-input-right { display: flex; align-items: center; gap: 8px; }
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
  .ai-model-select { width: 148px; }

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
  .ai-mode-btn { padding: 6px 12px; }
  .ai-foot-disclaimer { font-size: 11px; }
  .ai-tobottom { bottom: 8px; }
}
</style>