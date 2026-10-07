<script setup>
/**
 * 移动端 AI 问答（原生页，替代此前 iframe 嵌桌面 /ai/chat 的做法）
 *
 * 与桌面版共用同一套后端接口（api/ai.js），但布局按手机重做：
 *   - 顶部：返回 / 标题 / 历史会话 / 新建
 *   - 模式切换（知识库 / 通用）+ 模型选择（原生 select，不依赖未注册的 ElSelect）
 *   - 气泡消息流：用户右对齐、AI 左对齐并渲染 Markdown（html:false，AI 返回内容天然防 XSS）
 *   - 底部固定输入条（含 safe-area），生成中可「停止」
 *   - 历史会话用底部抽屉承载，避免挤占小屏正文空间
 */
import { ref, computed, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../store/auth'
import {
  aiAsk, listAiModels, listConversations, createConversation,
  deleteConversation, listMessages, updateConversation
} from '../../api/ai'
import MarkdownIt from 'markdown-it'

const router = useRouter()
const auth = useAuthStore()

const conversations = ref([])
const currentId = ref(null)
const convMessages = ref({})
const input = ref('')
const loading = ref(false)
const abortCtrl = ref(null)
const bodyRef = ref(null)
const historyOpen = ref(false)

const mode = ref('kb')
const modelList = ref([])
const selectedModelId = ref('')

const messages = computed(() => (currentId.value ? (convMessages.value[currentId.value] || []) : []))
const userName = computed(() => auth.user?.displayName || auth.user?.username || '我')
const userInitial = computed(() => (userName.value || '我').charAt(0).toUpperCase())

// 空状态文案跟随模式：切到「通用」后不能再挂着知识库口吻，否则用户以为模式没生效
const KB_SUGS = ['如何发布一篇文章？', '知识库支持哪些内容格式？', '怎么查看我的积分明细？', '帮我总结一下知识库的常见用法']
const GENERAL_SUGS = ['用三句话解释什么是向量数据库', '帮我把这段话润色得更正式', '推荐几本讲系统设计的好书', '写一个 Python 读取 Excel 的例子']
const sugs = computed(() => (mode.value === 'general' ? GENERAL_SUGS : KB_SUGS))

// ---------- Markdown：html:false 会转义源里的裸 HTML，AI 返回内容不会变成 XSS ----------
// 刻意不接 prism 高亮：桌面版那套 prism 语言组件有「全局 Prism 求值顺序」的坑，
// 移动端没必要为代码高亮付出这个复杂度和体积。
const md = new MarkdownIt({ html: false, linkify: true, breaks: true })
function renderMarkdown(src) {
  try {
    return md.render(String(src || ''))
  } catch {
    return String(src || '').replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]))
  }
}

function fmtMs(ms) {
  if (!ms) return ''
  return ms >= 1000 ? (ms / 1000).toFixed(2) + 's' : ms + 'ms'
}
function buildMeta(data) {
  if (!data) return null
  const name = data.modelName || data.model || ''
  if (!name && data.latencyMs == null) return null
  return { name, latencyMs: Number(data.latencyMs) || 0, usage: data.usage || null }
}

function scrollToBottom() {
  nextTick(() => { if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight })
}

async function loadModels() {
  try {
    const { data } = await listAiModels()
    modelList.value = data || []
    if (selectedModelId.value && !modelList.value.some((m) => String(m.id) === String(selectedModelId.value))) {
      selectedModelId.value = ''
    }
  } catch { modelList.value = [] }
}

async function loadConversations() {
  try {
    const { data } = await listConversations()
    conversations.value = data || []
  } catch { conversations.value = [] }
}

async function loadMessages(id) {
  if (!id || convMessages.value[id]) return
  try {
    const { data } = await listMessages(id)
    convMessages.value[id] = (data || []).map((m) => ({
      role: m.role, content: m.content, sources: m.sources || [], mode: m.mode || 'kb'
    }))
  } catch { convMessages.value[id] = [] }
}

async function selectConversation(id) {
  historyOpen.value = false
  if (currentId.value === id) return
  currentId.value = id
  try { localStorage.setItem('kb_ai_current_conversation', String(id)) } catch {}
  await loadMessages(id)
  scrollToBottom()
}

async function newConversation() {
  historyOpen.value = false
  try {
    const { data } = await createConversation('新对话')
    convMessages.value[data.id] = []
    conversations.value.unshift(data)
    currentId.value = data.id
    try { localStorage.setItem('kb_ai_current_conversation', String(data.id)) } catch {}
    input.value = ''
  } catch {
    window.__kbMsg?.error('创建会话失败')
  }
}

async function removeConversation(id) {
  try {
    await deleteConversation(id)
    conversations.value = conversations.value.filter((c) => c.id !== id)
    delete convMessages.value[id]
    if (currentId.value === id) currentId.value = null
    window.__kbMsg?.success('已删除')
  } catch { window.__kbMsg?.error('删除失败') }
}

function stopAi() { abortCtrl.value?.abort() }

async function send(text) {
  const q = (text ?? input.value).trim()
  if (!q) { window.__kbMsg?.warning('请输入内容'); return }
  if (loading.value) return
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    window.__kbMsg?.warning('当前离线，无法使用 AI 问答')
    return
  }

  let convId = currentId.value
  if (!convId) {
    try {
      const { data } = await createConversation(q)
      convMessages.value[data.id] = []
      conversations.value.unshift(data)
      currentId.value = data.id
      convId = data.id
    } catch {
      window.__kbMsg?.error('创建会话失败')
      return
    }
  }

  const cur = convMessages.value[convId] || []
  convMessages.value[convId] = [...cur, { role: 'user', content: q }]
  const conv = conversations.value.find((c) => c.id === convId)
  if (conv && (conv.title === '新对话' || !conv.title)) {
    const t = q.length > 20 ? q.slice(0, 20) + '…' : q
    conv.title = t
    updateConversation(convId, { title: t }).catch(() => {})
  }

  input.value = ''
  loading.value = true
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
    const c2 = conversations.value.find((c) => c.id === convId)
    if (c2 && data.title && data.title !== c2.title) c2.title = data.title
  } catch (e) {
    const stopped = e && (e.name === 'CanceledError' || e.code === 'ERR_CANCELED')
    const curErr = convMessages.value[convId] || []
    convMessages.value[convId] = [...curErr, {
      role: 'ai',
      content: stopped ? '（已停止生成）' : '请求失败，请稍后重试。',
      sources: [], mode: mode.value
    }]
  } finally {
    abortCtrl.value = null
    loading.value = false
    scrollToBottom()
  }
}

onMounted(async () => {
  await loadModels()
  await loadConversations()
  let saved = null
  try { saved = localStorage.getItem('kb_ai_current_conversation') } catch {}
  if (saved && conversations.value.some((c) => String(c.id) === saved)) {
    await selectConversation(Number(saved))
  }
  scrollToBottom()
})
</script>

<template>
  <div class="aic">
    <header class="aic-head">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="aic-title">AI 问答</div>
      <button class="m-header-act" @click="historyOpen = true"><el-icon :size="20"><Clock /></el-icon></button>
      <button class="m-header-act" @click="newConversation"><el-icon :size="20"><Plus /></el-icon></button>
    </header>

    <div class="aic-bar">
      <div class="aic-seg">
        <button :class="{ on: mode === 'kb' }" @click="mode = 'kb'">知识库</button>
        <button :class="{ on: mode === 'general' }" @click="mode = 'general'">通用</button>
      </div>
      <select v-model="selectedModelId" class="aic-model" aria-label="模型">
        <option value="">默认模型</option>
        <option v-for="m in modelList" :key="m.id" :value="String(m.id)">{{ m.name }}</option>
      </select>
    </div>

    <div ref="bodyRef" class="aic-body">
      <div v-if="!messages.length" class="aic-empty">
        <div class="aic-empty-ico"><el-icon :size="26"><ChatDotRound /></el-icon></div>
        <div class="aic-empty-title">
          {{ mode === 'general' ? '你好，我是通用 AI 助手' : '你好，我是知识库 AI 助手' }}
        </div>
        <div class="aic-empty-tip">
          {{ mode === 'general' ? '不检索知识库，直接凭通用知识作答' : '基于知识库检索作答，也能陪你日常聊聊' }}
        </div>
        <div class="aic-sugs">
          <button v-for="s in sugs" :key="s" class="aic-sug" @click="send(s)">{{ s }}</button>
        </div>
      </div>

      <div v-for="(m, i) in messages" :key="i" class="aic-msg" :class="m.role">
        <div class="aic-av">
          <span v-if="m.role === 'user'" class="aic-av-u">{{ userInitial }}</span>
          <span v-else class="aic-av-a"><el-icon><MagicStick /></el-icon></span>
        </div>
        <div class="aic-bubble">
          <div v-if="m.role === 'ai'" class="aic-md" v-html="renderMarkdown(m.content)"></div>
          <div v-else class="aic-text">{{ m.content }}</div>

          <div v-if="m.sources && m.sources.length" class="aic-src">
            <div class="aic-src-h">引用来源（{{ m.sources.length }}）</div>
            <span v-for="(s, si) in m.sources" :key="si" class="aic-src-chip">{{ s.title || s.docTitle || '文档' }}</span>
          </div>
          <div v-if="m.meta && (m.meta.name || m.meta.latencyMs)" class="aic-meta">
            {{ m.meta.name }}<span v-if="m.meta.latencyMs"> · {{ fmtMs(m.meta.latencyMs) }}</span>
          </div>
        </div>
      </div>

      <div v-if="loading" class="aic-msg ai">
        <div class="aic-av"><span class="aic-av-a"><el-icon><MagicStick /></el-icon></span></div>
        <div class="aic-bubble aic-typing"><i /><i /><i /></div>
      </div>
      <div style="height:8px"></div>
    </div>

    <div class="aic-input">
      <textarea
        v-model="input" rows="1" class="aic-ta"
        placeholder="问点什么…" @keydown.enter.exact.prevent="send()"
      ></textarea>
      <button v-if="loading" class="aic-send stop" @click="stopAi">停止</button>
      <button v-else class="aic-send" :disabled="!input.trim()" @click="send()">
        <el-icon><Promotion /></el-icon>
      </button>
    </div>

    <div v-if="historyOpen" class="aic-mask" @click="historyOpen = false">
      <div class="aic-sheet" @click.stop>
        <div class="aic-sheet-h">
          <span>历史会话</span>
          <button class="aic-sheet-new" @click="newConversation"><el-icon><Plus /></el-icon> 新建</button>
        </div>
        <div class="aic-sheet-list">
          <div v-if="!conversations.length" class="aic-sheet-empty">还没有会话</div>
          <div
            v-for="c in conversations" :key="c.id" class="aic-sheet-row"
            :class="{ on: c.id === currentId }" @click="selectConversation(c.id)"
          >
            <span class="aic-sheet-title">{{ c.title || '新对话' }}</span>
            <button class="aic-sheet-del" @click.stop="removeConversation(c.id)"><el-icon><Delete /></el-icon></button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.aic {
  display: flex; flex-direction: column;
  /* ⚠️ 不用 height:100%：父级 .m-view 是 overflow-y:auto 的 flex item，其内百分比高度
     在部分 WebView 上会退化为「内容高度」——空态下输入条悬在半空、下方露出一段背景
     （2026-10-07 真机截图）。直接用视口高度减 TabBar（58px + safe-area，与 MobileApp.tabbar 对应），
     键盘弹起时 dvh 自动收缩，输入条跟随上移。 */
  height: calc(100dvh - 58px - env(safe-area-inset-bottom));
  background: var(--m-bg);
}
.aic-head {
  flex: none; display: flex; align-items: center; gap: 4px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
}
.aic-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }

.aic-bar {
  flex: none; display: flex; align-items: center; gap: 10px;
  padding: 6px 16px 10px;
}
.aic-seg {
  display: flex; gap: 2px; padding: 3px;
  background: var(--m-card); border-radius: 11px; box-shadow: var(--m-shadow);
}
.aic-seg button {
  border: none; background: transparent; cursor: pointer;
  padding: 6px 14px; border-radius: 9px;
  font-size: 13.5px; font-weight: 600; color: var(--m-text-sub);
}
.aic-seg button.on { background: var(--m-primary); color: #fff; }
.aic-model {
  flex: 1 1 auto; min-width: 0; height: 34px;
  border: 1px solid var(--m-border); border-radius: 11px;
  background: var(--m-card); color: var(--m-text);
  font-size: 13px; padding: 0 8px;
}

.aic-body {
  flex: 1 1 auto; min-height: 0; overflow-y: auto; -webkit-overflow-scrolling: touch;
  padding: 6px 14px 0;
}

.aic-empty { text-align: center; padding: 34px 12px 20px; }
.aic-empty-ico {
  width: 54px; height: 54px; margin: 0 auto 14px; border-radius: 50%;
  background: var(--m-primary-soft); color: var(--m-primary);
  display: inline-flex; align-items: center; justify-content: center;
}
.aic-empty-title { font-size: 17px; font-weight: 700; color: var(--m-text); }
.aic-empty-tip { font-size: 13px; color: var(--m-text-dim); margin-top: 5px; }
.aic-sugs { display: flex; flex-wrap: wrap; gap: 8px; justify-content: center; margin-top: 18px; }
.aic-sug {
  border: 1px solid var(--m-border); background: var(--m-card);
  color: var(--m-text); border-radius: 14px;
  padding: 8px 13px; font-size: 13px; cursor: pointer;
}
.aic-sug:active { background: var(--m-card-2); }

.aic-msg { display: flex; gap: 8px; margin-bottom: 14px; align-items: flex-start; }
.aic-msg.user { flex-direction: row-reverse; }
.aic-av { flex: none; width: 30px; height: 30px; }
.aic-av-u, .aic-av-a {
  width: 30px; height: 30px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  font-size: 13px; font-weight: 700; color: #fff;
}
.aic-av-u { background: var(--m-primary); }
.aic-av-a { background: var(--m-success); }

.aic-bubble {
  max-width: calc(100% - 46px);
  background: var(--m-card); border-radius: 14px;
  padding: 10px 13px; box-shadow: var(--m-shadow);
  font-size: 14.5px; line-height: 1.65; color: var(--m-text);
  word-break: break-word; overflow-wrap: anywhere;
}
.aic-msg.user .aic-bubble { background: var(--m-primary); color: #fff; }
.aic-text { white-space: pre-wrap; }

/* Markdown 正文：移动端字号收紧、代码块横向滚动、表格不撑破屏幕 */
.aic-md :deep(p) { margin: 0 0 8px; }
.aic-md :deep(p:last-child) { margin-bottom: 0; }
.aic-md :deep(pre) {
  background: rgba(0, 0, 0, .06); border-radius: 9px;
  padding: 10px; overflow-x: auto; margin: 8px 0;
}
html.dark .aic-md :deep(pre) { background: rgba(255, 255, 255, .08); }
.aic-md :deep(code) { font-size: 13px; }
.aic-md :deep(ul), .aic-md :deep(ol) { padding-left: 20px; margin: 6px 0; }
.aic-md :deep(a) { color: var(--m-primary); }
.aic-md :deep(table) { display: block; overflow-x: auto; max-width: 100%; }
.aic-md :deep(blockquote) {
  margin: 8px 0; padding-left: 10px;
  border-left: 3px solid var(--m-border); color: var(--m-text-sub);
}

.aic-src { margin-top: 9px; border-top: 1px solid var(--m-border); padding-top: 8px; }
.aic-src-h { font-size: 12px; font-weight: 600; color: var(--m-text-dim); margin-bottom: 6px; }
.aic-src-chip {
  display: inline-block; margin: 0 6px 6px 0; padding: 4px 9px;
  background: var(--m-card-2); border-radius: 9px;
  font-size: 12px; color: var(--m-text-sub);
}
.aic-meta { margin-top: 7px; font-size: 11.5px; color: var(--m-text-dim); }

.aic-typing { display: inline-flex; gap: 5px; align-items: center; padding: 13px; }
.aic-typing i {
  width: 7px; height: 7px; border-radius: 50%; background: var(--m-text-dim);
  animation: aicBlink 1.2s infinite ease-in-out;
}
.aic-typing i:nth-child(2) { animation-delay: .2s; }
.aic-typing i:nth-child(3) { animation-delay: .4s; }
@keyframes aicBlink { 0%, 80%, 100% { opacity: .25; } 40% { opacity: 1; } }

/* 底部输入条：安全区由 TabBar 统一兜底（TabBar 自带 padding-bottom: safe-area），
   这里不再叠加 env(safe-area-inset-bottom)，否则输入条被垫高、输入框显得偏上。 */
.aic-input {
  flex: none; display: flex; align-items: flex-end; gap: 8px;
  padding: 8px 14px;
  background: var(--m-bg); border-top: 1px solid var(--m-border);
}
.aic-ta {
  flex: 1 1 auto; min-width: 0; max-height: 96px; min-height: 40px;
  border: 1px solid var(--m-border); border-radius: 12px;
  background: var(--m-card); color: var(--m-text);
  padding: 10px 12px; font-size: 15px; line-height: 1.4;
  resize: none; outline: none;
}
.aic-send {
  flex: none; width: 44px; height: 40px; border: none; border-radius: 12px;
  background: var(--m-primary); color: #fff; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
}
.aic-send:disabled { opacity: .45; }
.aic-send.stop { background: var(--m-danger); width: 56px; font-size: 13.5px; }

.aic-mask {
  position: fixed; inset: 0; z-index: 60;
  background: rgba(0, 0, 0, .45);
  display: flex; align-items: flex-end;
}
.aic-sheet {
  width: 100%; max-height: 62%;
  background: var(--m-bg); border-radius: 18px 18px 0 0;
  display: flex; flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom);
}
.aic-sheet-h {
  flex: none; display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px 10px; font-size: 15.5px; font-weight: 700;
  border-bottom: 1px solid var(--m-border);
}
.aic-sheet-new {
  border: none; background: transparent; color: var(--m-primary);
  font-size: 13.5px; font-weight: 600; cursor: pointer;
  display: inline-flex; align-items: center; gap: 3px;
}
.aic-sheet-list { flex: 1 1 auto; overflow-y: auto; padding: 6px 12px 12px; }
.aic-sheet-empty { text-align: center; color: var(--m-text-dim); font-size: 13px; padding: 22px 0; }
.aic-sheet-row {
  display: flex; align-items: center; gap: 8px;
  padding: 12px 10px; border-radius: 11px; cursor: pointer;
}
.aic-sheet-row.on { background: var(--m-card-2); }
.aic-sheet-title {
  flex: 1 1 auto; min-width: 0; font-size: 14.5px; color: var(--m-text);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.aic-sheet-del {
  flex: none; border: none; background: transparent;
  color: var(--m-text-dim); cursor: pointer;
  display: inline-flex; align-items: center;
}
</style>
