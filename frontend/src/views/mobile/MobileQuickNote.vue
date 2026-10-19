<script setup>
/**
 * 移动端「速记」：几秒钟记一句话，直接进「待整理箱」。
 *
 * 为什么单独做一个页面：手机上的核心诉求是「想到就记」——拍照建文档太重，
 * 打开文档库再新建更重。这里只有一个输入框 + 保存按钮。
 *
 * 设计取舍：
 *  - 落库走「待整理箱」（inbox=true），不直接进空间文档列表，避免速记污染正式文档库；
 *    用户在 /inbox 里再决定归到哪个空间、要不要改标题。
 *  - 支持 `#标签` 自动识别；整行是链接会自动转成可点链接。
 *  - 断网时先写本机队列（kb_quick_queue），下次进页面自动补同步，不丢内容。
 */
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createShareDoc, setShareDocTags } from '../../api/docShare'
import { mySpaces, resolveDefaultSpaceId, lastSpaceId, rememberSpace } from '../../utils/spaceResolver'

const router = useRouter()

const draft = ref('')
const saving = ref(false)
const spaces = ref([])
const targetSpace = ref(null)
const recent = ref([])      // 本机速记记录（最近 5 条）
const pending = ref(0)      // 本机待同步条数
const taRef = ref(null)

const RECENT_KEY = 'kb_quick_recent'
const QUEUE_KEY = 'kb_quick_queue'
const MAX_RECENT = 5

const esc = (s) => String(s || '').replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]))

function readJson(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key) || '') ?? fallback } catch { return fallback }
}
function writeJson(key, v) {
  try { localStorage.setItem(key, JSON.stringify(v)) } catch { /* 隐私模式忽略 */ }
}

/** 从正文里抽 #标签（支持中文，单个 ≤24 字，最多 12 个） */
function detectTags(text) {
  const out = []
  const re = /(?:^|[\s\u3000])#([^\s#，,。.、；;：:]{1,24})/g
  let m
  while ((m = re.exec(String(text || ''))) !== null) {
    const t = m[1].trim()
    if (t && !out.some((x) => x.toLowerCase() === t.toLowerCase())) out.push(t)
    if (out.length >= 12) break
  }
  return out
}

const tags = computed(() => detectTags(draft.value))

/** 第一行当标题：去掉 #标签 与纯链接，超长截断；空则用时间 */
function titleOf(text) {
  const first = String(text || '').split(/\r?\n/).map((s) => s.trim()).find(Boolean) || ''
  let t = first.replace(/(?:^|[\s\u3000])#[^\s#，,。.、；;：:]{1,24}/g, '').trim()
  if (/^https?:\/\/\S+$/i.test(t)) t = ''
  if (!t) {
    const d = new Date()
    t = `速记 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
  }
  return t.length > 40 ? t.slice(0, 40) : t
}

/** 纯文本 → 简单 HTML（按行分段；整行是链接就变成 a 标签） */
function toHtml(text) {
  return String(text || '')
    .split(/\r?\n/)
    .map((line) => {
      const t = line.trim()
      if (!t) return ''
      if (/^https?:\/\/\S+$/i.test(t)) {
        return `<p><a href="${esc(t)}" target="_blank" rel="noopener">${esc(t)}</a></p>`
      }
      return `<p>${esc(t)}</p>`
    })
    .filter(Boolean)
    .join('')
}

function autoGrow() {
  const el = taRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 260) + 'px'
}

function pushRecent(item) {
  const list = [item, ...readJson(RECENT_KEY, [])].slice(0, MAX_RECENT)
  recent.value = list
  writeJson(RECENT_KEY, list)
}

function queueOf() { return readJson(QUEUE_KEY, []) }

/** 断网暂存：只留必要字段，联网后补建文档 */
function pushQueue(item) {
  const q = queueOf()
  q.push(item)
  writeJson(QUEUE_KEY, q)
  pending.value = q.length
}

async function createOne(text, spaceId, tagList) {
  const { data } = await createShareDoc(spaceId, { title: titleOf(text), content: toHtml(text) }, true)
  // 标签是附加动作：失败不影响「已经存进去」这件事
  if (tagList?.length && data?.id) {
    try { await setShareDocTags(spaceId, data.id, tagList) } catch { /* 忽略 */ }
  }
  return data
}

async function save() {
  const text = draft.value.trim()
  if (!text || saving.value) return
  saving.value = true
  try {
    const spaceId = targetSpace.value || await resolveDefaultSpaceId()
    await createOne(text, spaceId, tags.value)
    pushRecent({ text, at: Date.now(), spaceId, synced: true })
    draft.value = ''
    await nextTick()
    autoGrow()
    window.__kbMsg?.success('已存入待整理箱')
  } catch (e) {
    pushQueue({ text, spaceId: targetSpace.value || null, tags: tags.value, at: Date.now() })
    pushRecent({ text, at: Date.now(), spaceId: targetSpace.value || null, synced: false })
    draft.value = ''
    await nextTick()
    autoGrow()
    window.__kbMsg?.warning('网络不可用，已暂存本机，联网后自动同步')
  } finally {
    saving.value = false
  }
}

/** 补同步本机队列（进页面时跑一次；失败的留在队列里等下次） */
async function flushQueue() {
  const q = queueOf()
  pending.value = q.length
  if (!q.length) return
  const left = []
  let ok = 0
  for (const it of q) {
    try {
      const spaceId = it.spaceId || await resolveDefaultSpaceId()
      await createOne(it.text, spaceId, it.tags || [])
      ok++
    } catch { left.push(it) }
  }
  writeJson(QUEUE_KEY, left)
  pending.value = left.length
  if (ok) window.__kbMsg?.success(`已同步 ${ok} 条本机速记`)
}

function onSpaceChange(v) {
  targetSpace.value = v
  if (v) rememberSpace(v)
}

function openInbox() { router.push('/inbox') }

onMounted(async () => {
  recent.value = readJson(RECENT_KEY, [])
  pending.value = queueOf().length
  try {
    spaces.value = await mySpaces(true)
    const def = lastSpaceId() || (await resolveDefaultSpaceId())
    targetSpace.value = spaces.value.some((s) => String(s.id) === String(def)) ? def : (spaces.value[0]?.id ?? null)
  } catch { /* 离线时下拉为空，保存会自动落默认空间 */ }
  flushQueue()
  nextTick(() => taRef.value?.focus())
})
</script>

<template>
  <div class="quick">
    <header class="m-header solid">
      <button class="m-header-act" type="button" aria-label="返回" @click="router.back()">
        <el-icon :size="20"><ArrowLeft /></el-icon>
      </button>
      <div class="h-title">速记</div>
      <button class="m-header-act inbox-act" type="button" aria-label="待整理箱" @click="openInbox">
        <el-icon :size="18"><Files /></el-icon>
      </button>
    </header>

    <div class="body">
      <div class="m-card editor-card">
        <textarea
          ref="taRef"
          v-model="draft"
          class="qn-input"
          rows="5"
          placeholder="想到什么就记什么…&#10;支持 #标签，整行是链接会自动变成可点链接"
          @input="autoGrow"
        ></textarea>
        <div class="qn-foot">
          <span class="qn-count">{{ draft.length }}</span>
          <button class="qn-save" type="button" :disabled="!draft.trim() || saving" @click="save">
            {{ saving ? '保存中…' : '存到待整理箱' }}
          </button>
        </div>
      </div>

      <div v-if="tags.length" class="tag-row">
        <span class="tag-label">识别到标签</span>
        <span v-for="t in tags" :key="t" class="tag-pill"># {{ t }}</span>
      </div>

      <div class="m-card row-card">
        <div class="row-left">归入空间</div>
        <select class="row-select" :value="targetSpace || ''" aria-label="选择空间" @change="onSpaceChange(Number($event.target.value) || null)">
          <option v-if="!spaces.length" :value="targetSpace || ''">默认空间</option>
          <option v-for="s in spaces" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
      </div>
      <div class="hint-line">速记会先收进「待整理箱」，之后在文档库里再决定归到哪个空间。</div>

      <div v-if="pending" class="pending-line">
        本机还有 {{ pending }} 条未同步，联网后会自动补传。
      </div>

      <template v-if="recent.length">
        <div class="m-sec-title">本机最近速记</div>
        <div class="m-card">
          <div v-for="(r, i) in recent" :key="r.at + '-' + i" class="recent-row">
            <div class="recent-text">{{ r.text }}</div>
            <div class="recent-meta">
              <span>{{ new Date(r.at).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' }) }}</span>
              <span v-if="!r.synced" class="badge-warn">待同步</span>
            </div>
          </div>
        </div>
      </template>

      <div class="bottom-space"></div>
    </div>
  </div>
</template>

<style scoped>
.quick { min-height: 100vh; background: var(--m-bg); }
.body { padding: 12px 14px 0; }

.editor-card { padding: 12px 12px 10px; }
.qn-input {
  width: 100%; border: 0; outline: 0; resize: none; background: transparent;
  font: inherit; font-size: 15px; line-height: 1.65; color: var(--m-text);
  min-height: 110px;
}
.qn-input::placeholder { color: var(--m-text-dim, #a8a8b3); }
.qn-foot { display: flex; align-items: center; gap: 10px; margin-top: 6px; }
.qn-count { flex: 1; font-size: 11.5px; color: var(--m-text-dim, #a8a8b3); }
.qn-save {
  height: 34px; padding: 0 16px; border: 0; border-radius: 999px;
  background: var(--m-primary, #1677ff); color: #fff; font-size: 13.5px;
}
.qn-save:disabled { opacity: .45; }

.tag-row { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin: 10px 2px 0; }
.tag-label { font-size: 11.5px; color: var(--m-text-dim, #86909c); }
.tag-pill { padding: 2px 8px; border-radius: 999px; background: var(--m-primary-soft, #e8f3ff); color: var(--m-primary, #1677ff); font-size: 11.5px; }

.row-card { display: flex; align-items: center; padding: 0 14px; height: 48px; margin-top: 10px; }
.row-left { flex: none; font-size: 13.5px; color: var(--m-text); }
.row-select { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; text-align: right; font: inherit; font-size: 13.5px; color: var(--m-text-dim, #4e5969); }
.hint-line { margin: 8px 4px 0; font-size: 11.5px; line-height: 1.6; color: var(--m-text-dim, #86909c); }
.pending-line { margin: 10px 4px 0; font-size: 12px; color: #b26a00; }

.recent-row { padding: 11px 0; border-bottom: 1px solid var(--m-border); }
.recent-row:last-child { border-bottom: 0; }
.recent-text { font-size: 13.5px; line-height: 1.55; color: var(--m-text); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; word-break: break-word; }
.recent-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 11.5px; color: var(--m-text-dim, #86909c); }
.badge-warn { padding: 1px 6px; border-radius: 999px; background: #fff4e5; color: #b26a00; }

.bottom-space { height: 28px; }
</style>
