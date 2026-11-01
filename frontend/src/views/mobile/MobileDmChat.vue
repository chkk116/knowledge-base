<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../../store/auth'
import { dmConversations, dmMessages, dmSend, dmRead, dmQuota, dmRecall } from '../../api/dm'
import { refreshDmUnread } from '../../store/dm'
import DmContent from '../../components/DmContent.vue'
import EmojiPanel from '../../components/EmojiPanel.vue'

/**
 * 移动端私信聊天页（全屏，隐藏底部 Tab）。
 * 交互按 App 习惯：点气泡展开「引用 / 撤回 / 复制」，表情用底部弹层，Enter 不发送（避免误触）。
 */
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const meId = computed(() => auth.user?.id)

const peerId = Number(route.params.peerId)
const peer = ref({ peerId, name: '用户 ' + peerId, avatar: '' })
const msgs = ref([])
const draft = ref('')
const loading = ref(true)
const sending = ref(false)
const quoteRef = ref(null)
const activeId = ref(null)
const emojiOpen = ref(false)
const listRef = ref(null)
const inputRef = ref(null)
const quota = ref({ mutual: false, replied: false, limited: false, sent: 0, peerAllowDm: true })

const canSend = computed(() => !!draft.value.trim() && !sending.value && quota.value.peerAllowDm)

const clock = (t) => {
  if (!t) return ''
  const d = new Date(t)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
const sameDay = (a, b) => new Date(a).toDateString() === new Date(b).toDateString()
function showDay(i) {
  if (i === 0) return true
  const p = msgs.value[i - 1]?.createdAt
  const c = msgs.value[i]?.createdAt
  return !p || !c || !sameDay(p, c)
}
function dayLabel(t) {
  if (!t) return ''
  const d = new Date(t)
  const today = new Date()
  const y = new Date(); y.setDate(today.getDate() - 1)
  if (d.toDateString() === today.toDateString()) return '今天'
  if (d.toDateString() === y.toDateString()) return '昨天'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}
function canRecall(m) {
  return m.kind !== 'RECALL' && Date.now() - new Date(m.createdAt).getTime() < 2 * 60 * 1000
}

function scrollBottom() {
  return nextTick(() => { const el = listRef.value; if (el) el.scrollTop = el.scrollHeight })
}

async function load() {
  loading.value = true
  try {
    const [conv, m, q] = await Promise.all([dmConversations(), dmMessages(peerId), dmQuota(peerId)])
    const c = (conv.data || []).find((x) => Number(x.peerId) === peerId)
    if (c) peer.value = { peerId, name: c.name, avatar: c.avatar }
    msgs.value = m.data || []
    quota.value = q.data || quota.value
    try { await dmRead(peerId); refreshDmUnread() } catch (_) { /* ignore */ }
    await scrollBottom()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

async function send() {
  if (!canSend.value) return
  const text = draft.value.trim()
  sending.value = true
  try {
    const { data } = await dmSend(peerId, text, 'TEXT', quoteRef.value?.id ?? null)
    draft.value = ''
    quoteRef.value = null
    msgs.value.push({ id: data.id, fromId: meId.value, kind: 'TEXT', content: text, createdAt: new Date().toISOString(), read: false })
    if (data.quota) quota.value = { ...quota.value, ...data.quota }
    await scrollBottom()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '发送失败')
  } finally {
    sending.value = false
  }
}

function quote(m) {
  quoteRef.value = { id: m.id, text: m.content || '', fromMe: m.fromId === meId.value }
  activeId.value = null
}

async function recall(m) {
  try {
    await ElMessageBox.confirm('撤回后对方也会消失，确定？', '撤回消息', { type: 'warning', confirmButtonText: '撤回', cancelButtonText: '取消' })
  } catch { return }
  try {
    await dmRecall(m.id)
    const t = msgs.value.find((x) => x.id === m.id)
    if (t) { t.kind = 'RECALL'; t.content = '' }
    activeId.value = null
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '撤回失败')
  }
}

function copy(m) {
  const text = String(m.content || '').replace(/\[\[(e|t):[A-Za-z0-9_-]+\]\]/g, '[表情]')
  navigator.clipboard?.writeText(text).then(() => ElMessage.success('已复制')).catch(() => {})
  activeId.value = null
}

function insertEmoji(p) {
  const token = p.kind === 'img'
    ? (p.name && p.name.startsWith('tw:') ? `[[t:${p.name.slice(3)}]]` : `[[e:${p.name}]]`)
    : (p.text || '')
  const el = inputRef.value?.textarea
  if (el) {
    const s = el.selectionStart ?? draft.value.length
    const e = el.selectionEnd ?? s
    draft.value = draft.value.slice(0, s) + token + draft.value.slice(e)
    nextTick(() => { el.focus(); const pos = s + token.length; try { el.setSelectionRange(pos, pos) } catch (_) {} })
  } else {
    draft.value += token
  }
}

function onDmEvent(ev) {
  const d = ev.detail || {}
  if (Number(d.fromId) !== peerId) return
  if (msgs.value.some((m) => m.id === d.id)) return
  msgs.value.push({
    id: d.id, fromId: d.fromId, kind: 'TEXT', content: d.content,
    createdAt: d.createdAt || new Date().toISOString(), read: true,
    replyId: d.replyId, replyFrom: d.replyFrom, replyText: d.replyText,
  })
  dmRead(peerId).catch(() => {})
  refreshDmUnread()
  scrollBottom()
}
function onRecallEvent(ev) {
  const t = msgs.value.find((x) => x.id === ev.detail?.id)
  if (t) { t.kind = 'RECALL'; t.content = '' }
}

onMounted(() => {
  window.addEventListener('kb:dm', onDmEvent)
  window.addEventListener('kb:dm-recall', onRecallEvent)
  load()
})
onUnmounted(() => {
  window.removeEventListener('kb:dm', onDmEvent)
  window.removeEventListener('kb:dm-recall', onRecallEvent)
})
</script>

<template>
  <div class="chat">
    <header class="hd">
      <button class="ic-btn" type="button" aria-label="返回" @click="router.back()">
        <el-icon :size="19"><ArrowLeft /></el-icon>
      </button>
      <div class="who">
        <div class="nm">{{ peer.name }}</div>
        <div class="sub">
          <span v-if="quota.mutual" class="tag ok">互相关注</span>
          <span v-else-if="quota.replied" class="tag">对方已回复</span>
        </div>
      </div>
      <span class="spacer" />
    </header>

    <div ref="listRef" class="list" @click="activeId = null">
      <div v-if="loading" class="tip">加载中…</div>
      <template v-for="(m, i) in msgs" :key="m.id">
        <div v-if="showDay(i)" class="day"><span>{{ dayLabel(m.createdAt) }}</span></div>
        <div v-if="m.kind === 'RECALL'" class="recalled">对方撤回了一条消息</div>
        <div v-else class="row" :class="{ mine: m.fromId === meId }">
          <el-avatar v-if="m.fromId !== meId" :size="34" :src="peer.avatar || undefined" class="av">
            {{ (peer.name || '?').slice(0, 1) }}
          </el-avatar>
          <div class="col">
            <div class="bubble" :class="{ img: m.kind === 'IMAGE' }" @click.stop="activeId = activeId === m.id ? null : m.id">
              <div v-if="m.replyId" class="quote">
                <span class="q-from">{{ m.replyFrom === meId ? '我' : peer.name }}</span>
                <span class="q-txt">{{ m.replyText }}</span>
              </div>
              <img v-if="m.kind === 'IMAGE'" :src="m.content" class="img" />
              <DmContent v-else :text="m.content" />
            </div>
            <div class="meta"><span>{{ clock(m.createdAt) }}</span><span v-if="m.fromId === meId && m.read">已读</span></div>
            <div v-if="activeId === m.id" class="acts" @click.stop>
              <button type="button" @click="quote(m)">引用</button>
              <button v-if="m.fromId === meId && canRecall(m)" type="button" @click="recall(m)">撤回</button>
              <button type="button" @click="copy(m)">复制</button>
            </div>
          </div>
          <el-avatar v-if="m.fromId === meId" :size="34" :src="auth.user?.avatar || undefined" class="av">
            {{ (auth.user?.username || '我').slice(0, 1) }}
          </el-avatar>
        </div>
      </template>
    </div>

    <div v-if="quota.limited" class="quota">还没互相关注，最多先发 {{ 3 - quota.sent }} 条（对方回复后可继续）</div>
    <div v-if="quoteRef" class="quote-bar">
      <span class="qb">{{ quoteRef.fromMe ? '我' : peer.name }}：{{ quoteRef.text.slice(0, 40) }}</span>
      <button type="button" class="qb-x" @click="quoteRef = null">取消</button>
    </div>

    <footer class="foot">
      <button type="button" class="ic-btn" :class="{ on: emojiOpen }" aria-label="表情" @click="emojiOpen = !emojiOpen">😊</button>
      <el-input
        ref="inputRef" v-model="draft" type="textarea" :rows="1" resize="none"
        class="editor" :placeholder="quota.peerAllowDm ? '发消息…' : '对方未开放私信'"
        :disabled="!quota.peerAllowDm" />
      <button type="button" class="send" :disabled="!canSend" @click="send">{{ sending ? '发送中' : '发送' }}</button>
    </footer>

    <div v-if="emojiOpen" class="emoji-mask" @click="emojiOpen = false">
      <div class="emoji-sheet" @click.stop>
        <EmojiPanel sheet @insert="insertEmoji" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.chat {
  position: fixed; inset: 0; z-index: 60; display: flex; flex-direction: column;
  background: var(--m-bg); color: var(--m-text);
}
.hd {
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 6px) 10px 8px;
  background: var(--m-card); border-bottom: 1px solid var(--m-border);
}
.ic-btn {
  width: 34px; height: 34px; border: 0; background: transparent; color: var(--m-text);
  border-radius: 10px; display: inline-flex; align-items: center; justify-content: center;
  font-size: 18px; cursor: pointer; flex: none;
}
.ic-btn:active, .ic-btn.on { background: var(--m-border); }
.who { display: flex; flex-direction: column; min-width: 0; }
.who .nm { font-size: 16px; font-weight: 600; line-height: 1.2; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.who .sub { margin-top: 2px; }
.tag { font-size: 10.5px; color: var(--m-text-sub); background: var(--m-border); padding: 1px 6px; border-radius: 6px; }
.tag.ok { color: #0a7d33; background: rgba(52, 199, 89, .14); }
.spacer { flex: 1; }

.list { flex: 1; overflow-y: auto; padding: 10px 12px 6px; -webkit-overflow-scrolling: touch; }
.tip { text-align: center; color: var(--m-text-dim); font-size: 12.5px; padding: 16px; }
.day { text-align: center; margin: 8px 0 12px; }
.day span { background: rgba(0, 0, 0, .06); color: var(--m-text-sub); font-size: 11px; padding: 2px 9px; border-radius: 999px; }

.row { display: flex; gap: 8px; align-items: flex-start; margin-bottom: 12px; }
.row.mine { flex-direction: row-reverse; }
.av { flex: none; margin-top: 2px; }
.col { display: flex; flex-direction: column; align-items: flex-start; max-width: 74%; min-width: 0; }
.row.mine .col { align-items: flex-end; }
.bubble {
  padding: 8px 11px; border-radius: 12px; background: var(--m-card); color: var(--m-text);
  font-size: 14px; line-height: 1.55; white-space: pre-wrap; word-break: break-word;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .06);
}
.row.mine .bubble { background: var(--m-primary); color: #fff; }
.bubble.img { padding: 4px; }
.bubble .img { max-width: 190px; border-radius: 8px; display: block; }
.meta { display: flex; gap: 6px; margin-top: 3px; font-size: 10.5px; color: var(--m-text-dim); }
.acts { display: flex; gap: 6px; margin-top: 6px; }
.acts button {
  border: 0; background: var(--m-card); color: var(--m-text-sub); font-size: 12px;
  padding: 4px 10px; border-radius: 8px; box-shadow: 0 1px 4px rgba(15, 23, 42, .12); cursor: pointer;
}
.acts button:active { background: var(--m-border); }
.recalled { text-align: center; font-size: 11.5px; color: var(--m-text-dim); margin: 4px 0 10px; }

.quota { padding: 6px 12px; background: #fff7e6; color: #b45309; font-size: 11.5px; }
.quote-bar {
  display: flex; align-items: center; gap: 8px; padding: 6px 12px;
  background: var(--m-card); border-top: 1px solid var(--m-border); font-size: 12px; color: var(--m-text-sub);
}
.qb { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.qb-x { border: 0; background: transparent; color: var(--m-primary); font-size: 12px; cursor: pointer; }

.foot {
  display: flex; align-items: flex-end; gap: 8px; padding: 8px 10px calc(8px + env(safe-area-inset-bottom));
  background: var(--m-card); border-top: 1px solid var(--m-border);
}
.editor { flex: 1; }
.editor :deep(.el-textarea__inner) { border-radius: 18px; padding: 8px 12px; font-size: 14px; max-height: 96px; }
.send {
  flex: none; height: 34px; padding: 0 16px; border: 0; border-radius: 17px;
  background: var(--m-primary); color: #fff; font-size: 13.5px; font-weight: 600; cursor: pointer;
}
.send:disabled { opacity: .45; }

.emoji-mask { position: fixed; inset: 0; background: rgba(0, 0, 0, .35); z-index: 70; display: flex; align-items: flex-end; }
.emoji-sheet { width: 100%; background: var(--m-card); }
</style>
