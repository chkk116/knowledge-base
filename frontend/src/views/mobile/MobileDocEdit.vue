<script setup>
/**
 * 移动端文档编辑页
 *
 * 取舍说明（很重要）：
 *   桌面端用的是 Tiptap 协同编辑器（含 Yjs、表格、图表块等重型依赖），
 *   它需要固定的宽屏工具栏，在手机上体验很差；而且依赖 @hocuspocus 连接，
 *   离线时完全不可用 —— 与「离线也能编辑」的目标冲突。
 *
 *   所以移动端改为「轻量编辑」：
 *     - 标题 input + 正文 textarea（纯文本 → 段落 HTML）
 *     - 支持 Markdown 式简易语法：行首 # / ## / ### 变标题，- 变列表，> 变引用
 *     - 保存时转成 HTML 存进共享文档（与桌面端的 content 字段完全兼容）
 *     - 离线时写进 IndexedDB 队列，联网自动同步
 *   代价：手机端编不了表格/导图（这类内容请在电脑上改）。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getShareDoc, updateShareDoc, createShareDoc } from '../../api/docShare'
import { enqueueDoc, getCachedDoc, cacheDoc } from '../../utils/offlineStore'

const route = useRoute()
const router = useRouter()

const spaceId = Number(route.params.spaceId)
const docId = Number(route.params.docId)

const title = ref('')
const body = ref('')
const loading = ref(true)
const saving = ref(false)
const dirty = ref(false)
const online = ref(navigator.onLine)

/** HTML → 可编辑纯文本（Markdown 风格），尽量保持可读 */
function htmlToMd(html) {
  if (!html) return ''
  const d = document.createElement('div')
  d.innerHTML = html
  const out = []
  const walk = (nodes) => {
    for (const el of nodes) {
      if (el.nodeType === 3) {
        const t = el.textContent
        if (t && t.trim()) out.push(t.trim())
        continue
      }
      if (el.nodeType !== 1) continue
      const tag = el.tagName.toLowerCase()
      if (tag === 'h1') { out.push('# ' + (el.textContent || '').trim()); continue }
      if (tag === 'h2') { out.push('## ' + (el.textContent || '').trim()); continue }
      if (tag === 'h3' || tag === 'h4') { out.push('### ' + (el.textContent || '').trim()); continue }
      if (tag === 'blockquote') { out.push('> ' + (el.textContent || '').trim()); continue }
      if (tag === 'li') { out.push('- ' + (el.textContent || '').trim()); continue }
      if (tag === 'pre') { out.push('```\n' + (el.textContent || '').trim() + '\n```'); continue }
      if (tag === 'br') { out.push(''); continue }
      if (tag === 'img') { out.push('[图片]'); continue }
      if (tag === 'ul' || tag === 'ol' || tag === 'table' || tag === 'tbody' || tag === 'thead' || tag === 'tr') {
        walk(el.childNodes); continue
      }
      if (tag === 'td' || tag === 'th') { out.push('| ' + (el.textContent || '').trim()); continue }
      if (tag === 'p' || tag === 'div' || tag === 'section') {
        const txt = (el.textContent || '').trim()
        out.push(txt)
        continue
      }
      walk(el.childNodes)
    }
  }
  walk(d.childNodes)
  return out.filter((l, i, a) => !(l === '' && a[i - 1] === '')).join('\n').trim()
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]))
}

/** Markdown 风格纯文本 → 段落 HTML（与桌面端 content 字段兼容） */
function mdToHtml(md) {
  const lines = String(md || '').replace(/\r/g, '').split('\n')
  const out = []
  let inCode = false
  let listBuf = []

  const flushList = () => {
    if (listBuf.length) { out.push('<ul>' + listBuf.map((t) => '<li>' + t + '</li>').join('') + '</ul>'); listBuf = [] }
  }

  for (const raw of lines) {
    const line = raw
    if (/^```/.test(line.trim())) {
      flushList()
      if (!inCode) { inCode = true; out.push('<pre><code>') }
      else { inCode = false; out.push('</code></pre>') }
      continue
    }
    if (inCode) { out.push(escapeHtml(line) + '\n'); continue }

    const t = line.trim()
    if (!t) { flushList(); continue }
    let m
    if ((m = t.match(/^###\s+(.*)$/))) { flushList(); out.push('<h3>' + escapeHtml(m[1]) + '</h3>'); continue }
    if ((m = t.match(/^##\s+(.*)$/))) { flushList(); out.push('<h2>' + escapeHtml(m[1]) + '</h2>'); continue }
    if ((m = t.match(/^#\s+(.*)$/))) { flushList(); out.push('<h1>' + escapeHtml(m[1]) + '</h1>'); continue }
    if ((m = t.match(/^>\s?(.*)$/))) { flushList(); out.push('<blockquote>' + escapeHtml(m[1]) + '</blockquote>'); continue }
    if ((m = t.match(/^[-*+]\s+(.*)$/))) { listBuf.push(escapeHtml(m[1])); continue }
    if ((m = t.match(/^(\d+)[.)]\s+(.*)$/))) { listBuf.push(escapeHtml(m[2])); continue }
    flushList()
    // 行内加粗 / 斜体 / 代码
    let html = escapeHtml(t)
    html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    html = html.replace(/(^|[^*])\*([^*]+)\*/g, '$1<em>$2</em>')
    html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
    out.push('<p>' + html + '</p>')
  }
  flushList()
  if (inCode) out.push('</code></pre>')
  return out.join('')
}

async function load() {
  loading.value = true
  try {
    if (docId > 0) {
      try {
        const { data } = await getShareDoc(spaceId, docId)
        title.value = data.title || ''
        body.value = htmlToMd(data.content)
        cacheDoc(spaceId, data).catch(() => {})
      } catch (e) {
        const c = await getCachedDoc(spaceId, docId).catch(() => null)
        if (c) { title.value = c.title || ''; body.value = htmlToMd(c.html); window.__kbMsg?.warning('离线，已载入本地缓存') }
        else throw e
      }
    }
  } catch (e) {
    window.__kbMsg?.error('载入失败：' + (e.response?.data?.error || e.message))
  } finally {
    loading.value = false
  }
}

async function save() {
  if (saving.value) return
  saving.value = true
  const html = mdToHtml(body.value)
  const t = title.value.trim() || '未命名文档'
  try {
    if (online.value && docId > 0) {
      await updateShareDoc(spaceId, docId, { title: t, content: html })
      dirty.value = false
      window.__kbMsg?.success('已保存')
      router.replace(`/doc/${spaceId}/${docId}`)
    } else if (online.value && docId <= 0) {
      const { data } = await createShareDoc(spaceId, { title: t, content: html })
      dirty.value = false
      window.__kbMsg?.success('已保存')
      router.replace(`/doc/${spaceId}/${data.id}`)
    } else {
      // 离线：队列（docId>0 时记下，便于未来做「离线编辑已有文档」的合并）
      await enqueueDoc({ spaceId, title: t, content: html })
      dirty.value = false
      window.__kbMsg?.success(docId > 0
        ? '离线暂不支持修改已有文档：已另存为新草稿，联网后自动创建'
        : '已存到本机，联网后自动同步')
      router.replace('/docs')
    }
  } catch (e) {
    try {
      await enqueueDoc({ spaceId, title: t, content: html })
      window.__kbMsg?.warning(docId > 0 ? '网络异常：已另存为新草稿（离线暂不支持更新原文档）' : '网络异常，已存到本机')
      router.replace('/docs')
    } catch {
      window.__kbMsg?.error('保存失败：' + (e.response?.data?.error || e.message))
    }
  } finally {
    saving.value = false
  }
}

async function back() {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm('有未保存的修改，要先保存吗？', '离开编辑', {
        confirmButtonText: '保存', cancelButtonText: '不保存', distinguishCancelAndClose: true,
        type: 'warning'
      })
      await save()
      return
    } catch (action) {
      if (action === 'cancel') { /* 不保存，直接走 */ }
      else return  // 关闭弹窗：留在页面
    }
  }
  router.back()
}

function onOnline() { online.value = true }
function onOffline() { online.value = false }

onMounted(() => {
  load()
  window.addEventListener('online', onOnline)
  window.addEventListener('offline', onOffline)
})
onUnmounted(() => {
  window.removeEventListener('online', onOnline)
  window.removeEventListener('offline', onOffline)
})

const charCount = computed(() => body.value.length)
</script>

<template>
  <div class="de">
    <header class="de-head">
      <button class="m-header-act" @click="back"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="de-title">{{ docId > 0 ? '编辑文档' : '新建文档' }}</div>
      <button class="m-header-act" :disabled="saving" @click="save">
        <el-icon v-if="saving" class="rot"><Loading /></el-icon>
        <el-icon v-else><Check /></el-icon>
      </button>
    </header>

    <div v-if="!online" class="off-note">
      <el-icon><WarningFilled /></el-icon> 离线编辑中，保存后会存到本机，联网自动同步
    </div>

    <div class="de-body">
      <input v-model="title" class="t-input" placeholder="标题" @input="dirty = true" />
      <textarea v-model="body" class="t-body" @input="dirty = true"
                placeholder="开始写点什么…
支持简易 Markdown：
# 标题    ## 二级标题
- 列表项   > 引用
**加粗**   `代码`"></textarea>
    </div>

    <div class="m-actionbar">
      <div class="de-count">{{ charCount }} 字</div>
      <button class="m-btn" style="flex:1 1 auto;width:auto" :disabled="saving" @click="save">
        <el-icon v-if="saving" class="rot"><Loading /></el-icon>
        {{ online ? '保存' : '存到本机' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.de { height: 100%; display: flex; flex-direction: column; }
.de-head {
  flex: none; display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
}
.de-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.rot { animation: kb-rot 1s linear infinite; }
@keyframes kb-rot { to { transform: rotate(360deg); } }

.off-note {
  flex: none;
  display: flex; align-items: center; gap: 6px;
  margin: 0 16px 8px; padding: 9px 12px;
  background: #fff7ed; color: #9a3412; border-radius: 12px; font-size: 12.5px;
}
html.dark .off-note { background: #3b2411; color: #fdba74; }

.de-body { flex: 1 1 auto; min-height: 0; overflow-y: auto; padding: 0 16px 100px; }
.t-input {
  width: 100%; box-sizing: border-box;
  border: none; outline: none; background: transparent;
  font-size: 22px; font-weight: 700; color: var(--m-text);
  font-family: inherit; padding: 6px 0 12px; letter-spacing: -.3px;
}
.t-input::placeholder { color: var(--m-text-dim); font-weight: 600; }
.t-body {
  width: 100%; box-sizing: border-box; min-height: 52vh;
  border: none; outline: none; background: transparent; resize: none;
  font-size: 16px; line-height: 1.78; color: var(--m-text);
  font-family: inherit; padding: 0;
}
.t-body::placeholder { color: var(--m-text-dim); font-size: 14.5px; line-height: 1.8; }

.de-count { flex: none; font-size: 12px; color: var(--m-text-dim); display: flex; align-items: center; padding-right: 4px; }
</style>
