<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../../store/auth'
import { dmConversations, dmMessages, dmSend, dmRead, dmQuota, dmRecall, uploadDmImage, dmAttachment } from '../../api/dm'
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

// 私信图片：附件落在私有目录，必须带令牌取 blob，不能拿 content 直接当 <img src>
const BLANK_IMG = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7'
const imgUrls = ref({})
const imgInflight = new Set()
function imgUrl(m) { return imgUrls.value[m.id] || '' }
function releaseImages() {
  Object.values(imgUrls.value).forEach((u) => u && URL.revokeObjectURL(u))
  imgUrls.value = {}
}
function ensureImages() {
  // 撤回 / 切会话后释放 objectURL，避免内存与旧图残留
  const alive = new Set(msgs.value.filter((m) => m.kind === 'IMAGE' && m.content).map((m) => m.id))
  const next = { ...imgUrls.value }
  let dropped = false
  for (const id of Object.keys(next)) {
    if (!alive.has(Number(id))) { URL.revokeObjectURL(next[id]); delete next[id]; dropped = true }
  }
  if (dropped) imgUrls.value = next
  for (const m of msgs.value) {
    if (m.kind !== 'IMAGE' || !m.content || imgUrls.value[m.id] || imgInflight.has(m.id)) continue
    imgInflight.add(m.id)
    dmAttachment(m.content).then(({ data }) => {
      const u = URL.createObjectURL(data)
      // 请求回来时会话可能已切走：立即释放，别挂到新会话上
      if (!msgs.value.some((x) => x.id === m.id && x.kind === 'IMAGE')) { URL.revokeObjectURL(u); return }
      imgUrls.value = { ...imgUrls.value, [m.id]: u }
    }).catch(() => { /* 无权/已撤回/文件已删：留占位图，不弹错误 */ })
      .finally(() => imgInflight.delete(m.id))
  }
}
watch(msgs, ensureImages, { deep: true })
const draft = ref('')
const loading = ref(true)
const sending = ref(false)
const uploading = ref(false)
const quoteRef = ref(null)
const activeId = ref(null)
const emojiOpen = ref(false)
const listRef = ref(null)
const inputRef = ref(null)
const fileInput = ref(null)
// 待发图片（可多张）：选图只进预览区，点「发送」才逐张上传发出
const pendingImages = ref([])   // [{ id, file, url, name }]
const MAX_DM_IMAGES = 9
const quota = ref({ mutual: false, replied: false, limited: false, sent: 0, remaining: -1, peerAllowDm: true })

const canSend = computed(() => (!!draft.value.trim() || pendingImages.value.length > 0)
  && !sending.value && !uploading.value && quota.value.peerAllowDm
  && !(quota.value.limited && quota.value.sent >= 3))

/** 陌生人额度剩余条数：后端 remaining=-1 表示不限；兼容旧包用 3-sent 兜底 */
function quotaLeft() {
  const q = quota.value
  if (!q.limited) return Infinity
  return Number(q.remaining ?? (3 - q.sent))
}

function clearPendingImages() {
  pendingImages.value.forEach((p) => URL.revokeObjectURL(p.url))
  pendingImages.value = []
}
function removePendingImage(id) {
  const hit = pendingImages.value.find((p) => p.id === id)
  if (hit) URL.revokeObjectURL(hit.url)
  pendingImages.value = pendingImages.value.filter((p) => p.id !== id)
}
function pickImage() { fileInput.value?.click() }

/** 还能选几张：一次最多 9 张，且未互关时受「陌生人 3 条」额度约束 */
function freeImageSlots() {
  const byMax = MAX_DM_IMAGES - pendingImages.value.length
  const left = quotaLeft()
  if (left === Infinity) return byMax
  return Math.min(byMax, Math.max(0, left - (draft.value.trim() ? 1 : 0) - pendingImages.value.length))
}

function onPickImage(e) {
  const files = Array.from(e.target.files || [])
  e.target.value = ''
  if (!files.length) return
  const slots = freeImageSlots()
  if (slots <= 0) {
    ElMessage.warning(quota.value.limited ? '对方回复前最多只能发 3 条，剩余图片请等对方回复后再发' : `一次最多 ${MAX_DM_IMAGES} 张`)
    return
  }
  let added = 0
  let over = false
  for (const f of files) {
    if (added >= slots) { over = true; break }
    if (!/^image\//i.test(f.type)) { ElMessage.warning(`「${f.name}」不是图片，已跳过`); continue }
    if (f.size > 8 * 1024 * 1024) { ElMessage.warning(`「${f.name}」超过 8MB，已跳过`); continue }
    pendingImages.value.push({
      id: Date.now() + '-' + Math.random().toString(36).slice(2, 7),
      file: f, url: URL.createObjectURL(f), name: f.name,
    })
    added++
  }
  if (over) ElMessage.warning(quota.value.limited ? '已达到发送额度上限，多余图片未加入' : `一次最多 ${MAX_DM_IMAGES} 张，多余图片未加入`)
  nextTick(() => inputRef.value?.focus?.())
}

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

/**
 * 统一发送：文字 + 多张待发图片，点「发送」才真正提交。
 * 后端一条私信只存一个 URL，未互关时还有「陌生人 3 条」额度，故逐张发；
 * 额度耗尽或任一张失败即停，未发的保留在预览区可重试。
 */
async function send() {
  if (!canSend.value) return
  const text = draft.value.trim()
  const images = [...pendingImages.value]
  const replyId = quoteRef.value?.id ?? null
  sending.value = true
  try {
    let replyUsed = false
    if (text) {
      const { data } = await dmSend(peerId, text, 'TEXT', replyId)
      draft.value = ''
      quoteRef.value = null
      replyUsed = true
      msgs.value.push({ id: data.id, fromId: meId.value, kind: 'TEXT', content: text, createdAt: new Date().toISOString(), read: false })
      if (data.quota) quota.value = { ...quota.value, ...data.quota }
    }
    if (images.length) {
      uploading.value = true
      try {
        for (const item of images) {
          if (quotaLeft() <= 0) {
            ElMessage.warning('已达到 3 条上限，剩余图片未发送，等对方回复后可继续')
            break
          }
          const { data: up } = await uploadDmImage(item.file)
          const imgRef = up?.ref
          if (!imgRef) throw new Error('上传返回异常')
          const { data: r } = await dmSend(peerId, imgRef, 'IMAGE', replyUsed ? null : replyId)
          replyUsed = true
          quoteRef.value = null
          removePendingImage(item.id)
          msgs.value.push({ id: r.id, fromId: meId.value, kind: 'IMAGE', content: imgRef, createdAt: new Date().toISOString(), read: false })
          if (r.quota) quota.value = { ...quota.value, ...r.quota }
        }
      } finally {
        uploading.value = false
      }
    }
    await scrollBottom()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || (images.length ? '图片发送失败，未发的图片仍在输入区，可重试' : '发送失败'))
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
    id: d.id, fromId: d.fromId, kind: d.kind === 'IMAGE' ? 'IMAGE' : 'TEXT', content: d.content,
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
  clearPendingImages()
  releaseImages()
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
              <img v-if="m.kind === 'IMAGE'" :src="imgUrl(m) || BLANK_IMG" class="img" />
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

    <!-- 待发图片（可多张）：选图只到这里，点「发送」才逐张上传 -->
    <div v-if="pendingImages.length" class="pending-imgs">
      <div v-for="p in pendingImages" :key="p.id" class="pi-item">
        <img :src="p.url" :alt="p.name" :title="p.name" />
        <button type="button" class="pi-x" aria-label="移除这张" @click="removePendingImage(p.id)">×</button>
      </div>
      <span class="pi-hint">共 {{ pendingImages.length }} 张 · 点发送后逐张发出</span>
    </div>

    <footer class="foot">
      <button type="button" class="ic-btn" :class="{ on: emojiOpen }" aria-label="表情" @click="emojiOpen = !emojiOpen">😊</button>
      <button type="button" class="ic-btn" aria-label="选择图片" :disabled="!quota.peerAllowDm || sending || uploading" @click="pickImage">
        <el-icon :size="19"><Picture /></el-icon>
      </button>
      <el-input
        ref="inputRef" v-model="draft" type="textarea" :rows="1" resize="none"
        class="editor" :placeholder="quota.peerAllowDm ? '发消息…' : '对方未开放私信'"
        :disabled="!quota.peerAllowDm" />
      <button type="button" class="send" :disabled="!canSend" @click="send">{{ sending || uploading ? '发送中' : '发送' }}</button>
      <input ref="fileInput" type="file" accept="image/*" multiple class="hidden-file" @change="onPickImage" />
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
.ic-btn:disabled { opacity: .4; cursor: default; }
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
.hidden-file { display: none; }

/* 待发多图预览：横滑缩略图，逐张可移除 */
.pending-imgs {
  display: flex; align-items: center; gap: 8px; padding: 6px 10px;
  background: var(--m-card); border-top: 1px solid var(--m-border); overflow-x: auto;
}
.pi-item { position: relative; flex: none; }
.pending-imgs img { width: 46px; height: 46px; object-fit: cover; border-radius: 7px;
  display: block; -webkit-user-drag: none; user-select: none; }
.pi-hint { flex: none; font-size: 11px; color: var(--m-text-dim); white-space: nowrap; padding-left: 2px; }
.pi-x { position: absolute; top: -6px; right: -6px; width: 18px; height: 18px;
  border: 0; background: rgba(0, 0, 0, .72); color: #fff; border-radius: 50%; font-size: 13px;
  line-height: 1; cursor: pointer; display: grid; place-items: center; padding: 0; }
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
