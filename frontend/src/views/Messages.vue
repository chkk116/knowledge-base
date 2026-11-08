<template>
  <div class="dm">
    <!-- 左：会话列表 -->
    <aside class="dm-side">
      <div class="dm-side-hd">
        <!-- 消息中心统一入口：私信 / 通知 两个 Tab（原先通知在顶栏铃铛、私信在侧栏，入口分散） -->
        <div class="mc-switch">
          <span class="on">私信</span>
          <span @click="router.push('/notifications')">通知</span>
        </div>
        <el-tooltip content="私信设置" placement="bottom">
          <el-button text size="small" @click="openSettings"><el-icon><Setting /></el-icon></el-button>
        </el-tooltip>
      </div>
      <div class="dm-search">
        <el-input v-model="kw" size="small" placeholder="搜索会话" clearable />
      </div>
      <div v-if="showArchived" class="dm-archived-bar">
        <el-button text size="small" @click="showArchived = false">← 已归档</el-button>
      </div>
      <div v-else-if="archivedCount" class="dm-archived-entry" @click="showArchived = true">
        <el-icon><Box /></el-icon>
        <span class="t">已归档</span>
        <span class="n">{{ archivedCount }}</span>
      </div>
      <div class="dm-convs">
        <div v-if="!currentList.length" class="dm-empty-s">{{ showArchived ? '没有归档的会话' : '还没有会话' }}</div>
        <div v-for="c in currentList" :key="c.peerId" class="dm-conv-wrap">
          <div class="dm-swipe-act act-archive" :style="{ '--p': Math.min(1, Math.max(0, (swipe[c.peerId]?.x || 0) / 60)) }">
            <span class="sa"><el-icon><Box /></el-icon><span>{{ showArchived ? '取消归档' : '归档' }}</span></span>
          </div>
          <div class="dm-swipe-act act-delete" :style="{ '--p': Math.min(1, Math.max(0, -(swipe[c.peerId]?.x || 0) / 60)) }">
            <span class="sa"><el-icon><Delete /></el-icon><span>删除</span></span>
          </div>
          <div class="dm-conv"
               :class="{ on: peer && peer.peerId === c.peerId, dragging: swipe[c.peerId]?.dragging }"
               :style="{ transform: 'translateX(' + (swipe[c.peerId]?.x || 0) + 'px)' }"
               @pointerdown="onSwipeStart($event, c)"
               @pointermove="onSwipeMove($event, c)"
               @pointerup="onSwipeEnd($event, c)"
               @pointercancel="onSwipeEnd($event, c)"
               @click="onConvClick(c)">
          <el-avatar :size="34" :src="c.avatar || undefined">{{ (c.name || '?').slice(0, 1) }}</el-avatar>
          <div class="dm-conv-body">
            <div class="dm-conv-top">
              <span class="nm">{{ c.name }}</span>
              <span class="tm">{{ shortTime(c.lastAt) }}</span>
            </div>
            <div class="dm-conv-bot">
              <span class="msg">{{ c.lastFromMe ? '我：' : '' }}{{ plainPreview(c.lastMessage) }}</span>
              <span v-if="c.unread" class="badge">{{ c.unread > 99 ? '99+' : c.unread }}</span>
            </div>
          </div>
          </div>
        </div>
      </div>
    </aside>

    <!-- 右：聊天区 -->
    <section class="dm-main">
      <template v-if="peer">
        <header class="dm-chat-hd">
          <div class="who">
            <el-avatar :size="30" :src="peer.avatar || undefined">{{ (peer.name || '?').slice(0, 1) }}</el-avatar>
            <span class="nm">{{ peer.name }}</span>
            <el-tag v-if="quota.mutual" size="small" type="success" effect="plain">互相关注</el-tag>
            <el-tag v-else-if="quota.replied" size="small" effect="plain">对方已回复</el-tag>
          </div>
          <div class="hd-acts">
            <el-button text size="small" @click="toggleFollow">{{ following ? '已关注' : '+ 关注' }}</el-button>
            <el-button text size="small" @click="reportOpen = true">举报</el-button>
          </div>
        </header>

        <div ref="scroller" class="dm-msgs">
          <div v-if="loading" class="dm-tip">加载中…</div>
          <template v-for="(m, i) in msgs" :key="m.id">
            <div v-if="showDay(i)" class="day-sep"><span>{{ dayLabel(m.createdAt) }}</span></div>
            <div v-if="m.kind === 'RECALL'" class="recalled">对方撤回了一条消息</div>
            <div v-else class="dm-row" :class="{ mine: m.fromId === meId }">
              <el-avatar v-if="m.fromId !== meId" :size="32" :src="peer.avatar || undefined" class="row-av">
                {{ (peer.name || '?').slice(0, 1) }}
              </el-avatar>
              <div class="row-main">
                <div v-if="showName(i)" class="row-name">{{ peer.name }}</div>
                <div class="bubble-wrap">
                  <div class="bubble" :class="{ img: m.kind === 'IMAGE' }">
                    <div v-if="m.replyId" class="quote">
                      <span class="q-from">{{ m.replyFrom === meId ? '我' : (peer.name || '对方') }}</span>
                      <span class="q-txt">{{ m.replyText }}</span>
                    </div>
                    <img v-if="m.kind === 'IMAGE'" :src="m.content" class="img" />
                    <DmContent v-else :text="m.content" />
                  </div>
                  <div class="row-acts">
                    <button v-if="m.kind !== 'RECALL'" type="button" @click="quote(m)">引用</button>
                    <button v-if="m.fromId === meId && canRecall(m)" type="button" @click="recall(m)">撤回</button>
                  </div>
                </div>
                <div class="row-meta">
                  <span>{{ clock(m.createdAt) }}</span>
                  <span v-if="m.fromId === meId && m.read" class="read">已读</span>
                </div>
              </div>
              <el-avatar v-if="m.fromId === meId" :size="32" :src="auth.user?.avatar || undefined" class="row-av">
                {{ (auth.user?.username || '我').slice(0, 1) }}
              </el-avatar>
            </div>
          </template>
        </div>

        <div v-if="quota.limited" class="dm-quota">
          你们还没有互相关注，最多先发 {{ 3 - quota.sent }} 条（对方回复后可继续）
        </div>

        <div v-if="quoteRef" class="quote-bar">
          <span class="q-body">{{ quoteRef.fromMe ? '我' : peer.name }}：{{ quoteRef.text.slice(0, 60) }}</span>
          <el-button text size="small" @click="quoteRef = null">取消</el-button>
        </div>
        <footer class="dm-input">
          <!-- 待发送图片：选图只到这里，点「发送」才上传发出 -->
          <div v-if="pendingImage" class="pending-img">
            <img :src="pendingImage.url" alt="待发送图片预览" />
            <span class="pi-name" :title="pendingImage.name">{{ pendingImage.name }}</span>
            <span class="pi-hint">点「发送」后发出</span>
            <button type="button" class="pi-x" title="移除图片" @click="clearPendingImage">×</button>
          </div>
          <div class="dm-editor">
            <el-input
              ref="inputRef" v-model="draft" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" resize="none"
              :placeholder="quota.peerAllowDm ? '输入消息…' : '对方未开放私信'"
              :disabled="!quota.peerAllowDm || (quota.limited && quota.sent >= 3)"
              @keydown.enter.exact.prevent="send" />
            <EmojiPanel v-if="emojiOpen" class="emoji-pop" @insert="insertEmoji" />
          </div>
          <div class="dm-input-bot">
            <div class="dm-tools">
              <button type="button" class="tool" :class="{ on: emojiOpen }" title="表情" @click="emojiOpen = !emojiOpen">😊</button>
              <button type="button" class="tool" title="选择图片（发送前可预览）" :disabled="!quota.peerAllowDm || sending || uploading" @click="pickImage">
                <el-icon><Picture /></el-icon>
              </button>
              <span class="tool-hint">Enter 发送 · Shift+Enter 换行</span>
            </div>
            <el-button type="primary" class="send-btn" :disabled="!canSend" :loading="sending || uploading" @click="send">发送</el-button>
          </div>
          <input ref="fileInput" type="file" accept="image/*" class="hidden-file" @change="onPickImage" />
        </footer>
      </template>
      <div v-else class="dm-blank">选择左侧会话开始聊天</div>
    </section>

    <ReportDialog v-model="reportOpen" target-type="USER" :target-id="peer?.peerId"
                  :target-label="'举报用户：' + (peer?.name || '')" />

    <!-- 私信设置 -->
    <el-dialog v-model="settings.open" title="私信设置" width="420px">
      <div class="st-row">
        <div>
          <div class="st-t">允许别人给我发私信</div>
          <div class="st-s">关闭后，任何人（包括互相关注的人）都无法给你发新消息</div>
        </div>
        <el-switch v-model="settings.allowDm" @change="saveSettings" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../store/auth'
import { dmConversations, dmMessages, dmSend, dmRead, dmQuota, dmSettings, dmSaveSettings, dmRecall, dmArchiveConv, dmDeleteConv, dmArchivedConversations } from '../api/dm'
import { refreshDmUnread } from '../store/dm'
import { uploadFile } from '../api/file'
import ReportDialog from '../components/ReportDialog.vue'
import EmojiPanel from '../components/EmojiPanel.vue'
import DmContent from '../components/DmContent.vue'
import { followUser, unfollowUser } from '../api/kb'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const meId = computed(() => auth.user?.id)

const convs = ref([])
const archivedConvs = ref([])
const archivedCount = ref(0)
const showArchived = ref(false)
const kw = ref('')
const peer = ref(null)
const msgs = ref([])
const draft = ref('')
const quoteRef = ref(null)
const loading = ref(false)
const sending = ref(false)
const uploading = ref(false)
// 待发送图片：选图只进输入区预览，点「发送」才上传并发出（不再选完即发）
// peerId 用于锁定会话，切走会话时预览会被清掉，避免误发给别人
const pendingImage = ref(null)   // { file, url, name, peerId } | null
const following = ref(false)
const quota = reactive({ mutual: false, replied: false, sent: 0, remaining: -1, limited: false, peerAllowDm: true })
const settings = reactive({ open: false, allowDm: true })
const scroller = ref(null)
const emojiOpen = ref(false)
const inputRef = ref(null)
let openSeq = 0

const filtered = computed(() => {
  const k = kw.value.trim()
  return k ? convs.value.filter((c) => (c.name || '').includes(k) || (c.lastMessage || '').includes(k)) : convs.value
})
const currentList = computed(() => (showArchived.value ? archivedConvs.value : filtered.value))
const canSend = computed(() => (!!draft.value.trim() || !!pendingImage.value) && !sending.value && !uploading.value
  && quota.peerAllowDm && !(quota.limited && quota.sent >= 3))

/* ---------- 消息分组 / 日期分隔（飞书式） ---------- */
const sameDay = (a, b) => new Date(a).toDateString() === new Date(b).toDateString()

function showDay(i) {
  if (i === 0) return true
  const prev = msgs.value[i - 1]?.createdAt
  const cur = msgs.value[i]?.createdAt
  return !prev || !cur || !sameDay(prev, cur)
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

/** 同一人连续发言只显示一次昵称；间隔超过 5 分钟再显示一次 */
function showName(i) {
  const m = msgs.value[i]
  if (!m || m.fromId === meId.value) return false
  if (i === 0) return true
  const prev = msgs.value[i - 1]
  if (!prev || prev.fromId !== m.fromId) return true
  return new Date(m.createdAt) - new Date(prev.createdAt) > 5 * 60 * 1000
}

/* ---------- 表情 ---------- */
/** 把面板选中的表情插入输入框光标处：贴吧/emoji 用令牌，颜文字用文本 */
function insertEmoji(p) {
  let token = ''
  if (p.kind === 'img') {
    token = p.name && p.name.startsWith('tw:') ? `[[t:${p.name.slice(3)}]]` : `[[e:${p.name}]]`
  } else {
    token = p.text || ''
  }
  const el = inputRef.value?.textarea
  if (el) {
    const s = el.selectionStart ?? draft.value.length
    const e = el.selectionEnd ?? s
    draft.value = draft.value.slice(0, s) + token + draft.value.slice(e)
    nextTick(() => {
      el.focus()
      const pos = s + token.length
      try { el.setSelectionRange(pos, pos) } catch (_) { /* ignore */ }
    })
  } else {
    draft.value += token
  }
}

/** 会话列表预览：把表情令牌折叠成 [表情]，避免列表里出现一串方括号 */
const plainPreview = (s) => String(s || '').replace(/\[\[(e|t):[A-Za-z0-9_-]+\]\]/g, '[表情]')

const clock = (t) => {
  if (!t) return ''
  const d = new Date(t)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}
const shortTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  const today = new Date()
  return d.toDateString() === today.toDateString() ? clock(t) : `${d.getMonth() + 1}/${d.getDate()}`
}

async function loadConvs() {
  try {
    const { data } = await dmConversations()
    convs.value = data || []
    const { data: arch } = await dmArchivedConversations()
    archivedConvs.value = arch || []
    archivedCount.value = (arch || []).length
  } catch (e) { /* 未登录等情况静默 */ }
}

async function openPeer(peerId, info) {
  const known = info || convs.value.find((c) => c.peerId === peerId)
  peer.value = { peerId, name: known?.name || `用户 ${peerId}`, avatar: known?.avatar }
  following.value = !!known?.isFollowing
  loading.value = true
  draft.value = ''
  clearPendingImage()   // 切会话丢弃未发图片，避免错发给新会话
  msgs.value = []          // 立刻清空，避免上一个会话的消息闪现（审查 r20 High）
  quoteRef.value = null
  const stamp = ++openSeq   // 请求序号：返回时若已切走则丢弃，防止串会话
  try {
    const [m, q] = await Promise.all([dmMessages(peerId), dmQuota(peerId)])
    if (openSeq !== stamp || peer.value?.peerId !== peerId) return
    msgs.value = m.data || []
    Object.assign(quota, q.data || {})
    await dmRead(peerId)
    refreshDmUnread()          // 清侧栏红点
    const c = convs.value.find((x) => x.peerId === peerId)
    if (c) c.unread = 0
    await scrollBottom()
  } catch (e) {
    if (openSeq === stamp) ElMessage.error(e?.response?.data?.error || '加载失败')
  } finally {
    if (openSeq === stamp) loading.value = false
  }
}

/* ---------- 会话项滑动：左滑删除 / 右滑归档 ---------- */
const swipe = reactive({})          // peerId -> { x, dragging }
let sw = null                        // 当前拖拽：{ peerId, startX, startY, moved }
let suppressClick = false            // 拖拽结束后的 click 不当作「打开会话」

function onSwipeStart(e, c) {
  if (e.button !== undefined && e.button !== 0) return
  sw = { peerId: c.peerId, startX: e.clientX, startY: e.clientY, moved: false }
  swipe[c.peerId] = { x: 0, dragging: false }
}

function onSwipeMove(e, c) {
  if (!sw || sw.peerId !== c.peerId) return
  const dx = e.clientX - sw.startX
  const dy = e.clientY - sw.startY
  if (!sw.moved) {
    // 横向位移明显大于纵向才进入滑动，避免影响列表纵向滚动
    if (Math.abs(dx) < 8 || Math.abs(dx) < Math.abs(dy)) return
    sw.moved = true
    swipe[c.peerId].dragging = true
  }
  swipe[c.peerId].x = Math.max(-90, Math.min(90, dx))
}

function onSwipeEnd(e, c) {
  if (!sw || sw.peerId !== c.peerId) return
  const x = swipe[c.peerId]?.x || 0
  const moved = sw.moved
  sw = null
  const st = swipe[c.peerId]
  if (st) { st.dragging = false; st.x = 0 }
  if (!moved) return
  suppressClick = true
  setTimeout(() => { suppressClick = false }, 350)
  if (x < -60) doDeleteConv(c)
  else if (x > 60) doArchiveConv(c)
}

function onConvClick(c) {
  if (suppressClick) return
  openPeer(c.peerId, c)
}

async function doArchiveConv(c) {
  try {
    if (showArchived.value) {
      // 归档列表里右滑 = 取消归档
      await dmArchiveConv(c.peerId, false)
      archivedConvs.value = archivedConvs.value.filter((x) => x.peerId !== c.peerId)
      archivedCount.value = archivedConvs.value.length
      ElMessage.success('已取消归档')
    } else {
      await dmArchiveConv(c.peerId, true)
      convs.value = convs.value.filter((x) => x.peerId !== c.peerId)
      if (peer.value?.peerId === c.peerId) { peer.value = null; clearPendingImage() }
      ElMessage.success('已归档')
    }
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

async function doDeleteConv(c) {
  try {
    await ElMessageBox.confirm(`删除与「${c.name}」的会话？（仅自己可见，不影响对方）`, '删除会话', { type: 'warning' })
    await dmDeleteConv(c.peerId)
    convs.value = convs.value.filter((x) => x.peerId !== c.peerId)
    archivedConvs.value = archivedConvs.value.filter((x) => x.peerId !== c.peerId)
    archivedCount.value = archivedConvs.value.length
    if (peer.value?.peerId === c.peerId) { peer.value = null; clearPendingImage() }
    ElMessage.success('已删除')
  } catch (e) { /* 取消 */ }
}

const fileInput = ref(null)
const reportOpen = ref(false)

function pickImage() { fileInput.value?.click() }

/** 释放预览用的 objectURL，避免内存泄漏 */
function clearPendingImage() {
  if (pendingImage.value?.url) URL.revokeObjectURL(pendingImage.value.url)
  pendingImage.value = null
}

/** 选图：只做本地预览并挂到输入区，不上传、不发送；真正发出统一走 send() */
function onPickImage(e) {
  const f = e.target.files?.[0]
  e.target.value = ''
  if (!f) return
  if (!/^image\//i.test(f.type)) { ElMessage.warning('请选择图片文件'); return }
  if (f.size > 8 * 1024 * 1024) { ElMessage.warning('图片请小于 8MB'); return }
  if (!peer.value) return
  clearPendingImage()
  pendingImage.value = {
    file: f,
    url: URL.createObjectURL(f),
    name: f.name,
    peerId: peer.value.peerId,
  }
  nextTick(() => inputRef.value?.focus?.())
}

/** 引用某条消息：记录 id + 摘要，发送时带上 replyId */
function quote(m) {
  quoteRef.value = {
    id: m.id,
    text: String(m.content || '').replace(/\[\[(e|t):[A-Za-z0-9_-]+\]\]/g, '[表情]'),
    fromMe: m.fromId === meId.value,
  }
}

/** 撤回窗口 2 分钟（与后端一致，前端只是隐藏按钮，最终由后端校验） */
function canRecall(m) {
  if (m.kind === 'RECALL') return false
  return Date.now() - new Date(m.createdAt).getTime() < 2 * 60 * 1000
}

async function recall(m) {
  try {
    await ElMessageBox.confirm('撤回后对方也会消失，确定？', '撤回消息', { type: 'warning' })
    await dmRecall(m.id)
    const t = msgs.value.find((x) => x.id === m.id)
    if (t) { t.kind = 'RECALL'; t.content = '' }
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e?.response?.data?.error || '撤回失败')
  }
}

/**
 * 统一发送入口：文字与待发图片都在输入区编辑好，点「发送」才真正提交。
 * 两者同时存在时按顺序发两条（文字 → 图片），引用只挂在第一条上。
 * 图片上传失败时保留预览，用户可直接重试；切会话时丢弃预览避免错发。
 */
async function send() {
  if (!canSend.value) return
  const text = draft.value.trim()
  const img = pendingImage.value
  const pid = peer.value.peerId
  const replyId = quoteRef.value?.id ?? null
  if (img && img.peerId !== pid) {
    ElMessage.warning('已切换会话，待发图片已清除，请重新选择')
    clearPendingImage()
    return
  }
  sending.value = true
  try {
    let replyUsed = false
    if (text) {
      const { data } = await dmSend(pid, text, 'TEXT', replyId)
      draft.value = ''
      quoteRef.value = null
      replyUsed = true
      msgs.value.push({
        id: data.id, fromId: meId.value, kind: 'TEXT', content: text,
        createdAt: new Date().toISOString(), read: false,
      })
      if (data.quota) Object.assign(quota, data.quota)
      loadConvs()
    }
    if (img) {
      uploading.value = true
      try {
        const { data: up } = await uploadFile(img.file)
        const url = up?.data?.url || up?.url
        if (!url) throw new Error('上传返回异常')
        if (!peer.value || peer.value.peerId !== pid) {
          ElMessage.warning('已切换会话，图片未发送，请重新选择')
          clearPendingImage()
          return
        }
        const { data: r } = await dmSend(pid, url, 'IMAGE', replyUsed ? null : replyId)
        quoteRef.value = null
        clearPendingImage()
        msgs.value.push({
          id: r.id, fromId: meId.value, kind: 'IMAGE', content: url,
          createdAt: new Date().toISOString(), read: false,
        })
        if (r.quota) Object.assign(quota, r.quota)
      } finally {
        uploading.value = false
      }
    }
    await scrollBottom()
    loadConvs()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || (img ? '图片发送失败，可重新点发送重试' : '发送失败'))
  } finally {
    sending.value = false
  }
}

async function toggleFollow() {
  if (!peer.value) return
  try {
    if (following.value) { await unfollowUser(peer.value.peerId); following.value = false }
    else { await followUser(peer.value.peerId); following.value = true }
    const q = await dmQuota(peer.value.peerId)
    Object.assign(quota, q.data || {})
  } catch (e) {
    ElMessage.warning(e?.response?.data?.error || '操作失败')
  }
}

async function openSettings() {
  try {
    const { data } = await dmSettings()
    settings.allowDm = !!data.allowDm
    settings.open = true
  } catch (e) { ElMessage.error('设置加载失败') }
}
async function saveSettings() {
  try {
    await dmSaveSettings(settings.allowDm)
    ElMessage.success(settings.allowDm ? '已开启私信' : '已关闭私信')
  } catch (e) { ElMessage.error('保存失败') }
}

function scrollBottom() {
  return nextTick(() => { const el = scroller.value; if (el) el.scrollTop = el.scrollHeight })
}

// 轮询：新消息 + 会话列表（页面可见时才拉，切走就停）
let timer = null
function startPoll() {
  stopPoll()
  timer = setInterval(async () => {
    if (document.hidden) return
    if (peer.value) {
      const pid = peer.value.peerId
      try {
        const { data } = await dmMessages(pid)
        if (!peer.value || peer.value.peerId !== pid) return   // 期间已切走，丢弃
        if ((data || []).length !== msgs.value.length) {
          const atBottom = scroller.value && (scroller.value.scrollHeight - scroller.value.scrollTop - scroller.value.clientHeight < 60)
          msgs.value = data || []
          await dmRead(peer.value.peerId)
          if (atBottom) await scrollBottom()
        }
      } catch (e) { /* ignore */ }
    }
    loadConvs()
  }, 30000)   // 实时靠 SSE（kb:dm 事件），这里只做兜底对账
}
function stopPoll() { if (timer) { clearInterval(timer); timer = null } }

// SSE 推来的新私信：正在聊的人直接追加并回已读，其它人只刷新会话列表（出未读徽标）
async function onDmEvent(ev) {
  const d = ev.detail || {}
  if (peer.value && Number(d.fromId) === Number(peer.value.peerId)) {
    if (!msgs.value.some((m) => m.id === d.id)) {
      const atBottom = scroller.value && (scroller.value.scrollHeight - scroller.value.scrollTop - scroller.value.clientHeight < 80)
      msgs.value.push({
        id: d.id, fromId: d.fromId, kind: 'TEXT', content: d.content,
        createdAt: d.createdAt || new Date().toISOString(), read: true,
        replyId: d.replyId, replyFrom: d.replyFrom, replyText: d.replyText,
      })
      try { await dmRead(peer.value.peerId); refreshDmUnread() } catch (e) { /* ignore */ }
      if (atBottom) await scrollBottom()
    }
  }
  loadConvs()
}

function onRecallEvent(ev) {
  const d = ev.detail || {}
  const t = msgs.value.find((x) => x.id === d.id)
  if (t) { t.kind = 'RECALL'; t.content = '' }
}

onMounted(async () => {
  window.addEventListener('kb:dm', onDmEvent)
  window.addEventListener('kb:dm-recall', onRecallEvent)
  await loadConvs()
  const p = Number(route.query.peer)
  if (p) await openPeer(p)
  startPoll()
})
onUnmounted(() => {
  stopPoll()
  clearPendingImage()
  window.removeEventListener('kb:dm', onDmEvent)
  window.removeEventListener('kb:dm-recall', onRecallEvent)
})
watch(() => route.query.peer, (v) => { if (v) openPeer(Number(v)) })
</script>

<style scoped>
.dm { display: flex; height: calc(100vh - 120px); background: #fff; border: 1px solid #ebeef5; border-radius: 12px; overflow: hidden; }
.dm-side { width: 264px; border-right: 1px solid #f0f0f0; display: flex; flex-direction: column; background: #fafbfc; }
.dm-side-hd { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; font-weight: 600; font-size: 14px; }
/* 消息中心 Tab 切换（私信 / 通知） */
.mc-switch { display: inline-flex; gap: 2px; background: #eef2f7; border-radius: 8px; padding: 3px; }
.mc-switch span { font-size: 13px; font-weight: 500; color: #64748b; padding: 4px 11px; border-radius: 6px; cursor: pointer; line-height: 1; }
.mc-switch span:hover { color: #2563eb; }
.mc-switch span.on { background: #fff; color: #2563eb; font-weight: 600; box-shadow: 0 1px 2px rgba(15, 23, 42, .1); }
:deep(html.dark) .mc-switch { background: #23233a; }
:deep(html.dark) .mc-switch span { color: #a5a7bd; }
:deep(html.dark) .mc-switch span.on { background: #2f2f4a; color: #a9c2ff; box-shadow: none; }
.dm-search { padding: 0 12px 8px; }
.dm-convs { flex: 1; overflow-y: auto; }
.dm-archived-entry { display: flex; align-items: center; gap: 8px; padding: 8px 12px; cursor: pointer; font-size: 13px; color: #1f2937; }
.dm-archived-entry:hover { background: #f2f4f7; }
.dm-archived-entry .t { flex: 1; }
.dm-archived-entry .n { color: #9ca3af; font-size: 12px; }
.dm-archived-bar { padding: 4px 8px; border-bottom: 1px solid #f0f0f0; }
.dm-empty-s { padding: 24px 14px; color: #9ca3af; font-size: 13px; text-align: center; }
.dm-conv-wrap { position: relative; overflow: hidden; }
/* 滑动露出的操作层：右滑=归档（蓝），左滑=删除（红） */
.dm-swipe-act { position: absolute; top: 6px; bottom: 6px; display: flex; align-items: center; gap: 6px;
  padding: 0 18px; color: #fff; font-size: 13px; font-weight: 500; pointer-events: none;
  border-radius: 12px; opacity: var(--p, 0); }
.dm-swipe-act .sa { display: flex; align-items: center; gap: 6px;
  transform: scale(calc(.82 + .18 * var(--p, 0))); transition: transform .12s ease; }
.dm-swipe-act .el-icon { font-size: 18px; }
.act-archive { left: 8px; background: linear-gradient(135deg, #4c9aff, #1677ff); box-shadow: 0 2px 8px rgba(22, 119, 255, .25); }
.act-delete { right: 8px; background: linear-gradient(135deg, #ff8a8a, #ef4444); box-shadow: 0 2px 8px rgba(239, 68, 68, .25); }
.dm-conv { position: relative; z-index: 1; background: #fafbfc; display: flex; gap: 10px; padding: 10px 12px; cursor: pointer;
  transition: transform .22s cubic-bezier(.22, .61, .36, 1), box-shadow .2s; touch-action: pan-y; will-change: transform;
  user-select: none; -webkit-user-select: none; }
/* 头像图片默认可拖拽，会吞掉右滑手势 —— 关掉 */
.dm-conv :deep(img) { -webkit-user-drag: none; }
.dm-conv.dragging { transition: none; box-shadow: 0 6px 18px rgba(15, 23, 42, .12); }
.dm-conv:hover { background: #f2f4f7; }
.dm-conv.on { background: #eaf2ff; }
.dm-conv-body { flex: 1; min-width: 0; }
.dm-conv-top { display: flex; justify-content: space-between; align-items: baseline; }
.dm-conv-top .nm { font-size: 13.5px; font-weight: 500; color: #1f2937; }
.dm-conv-top .tm { font-size: 11px; color: #9ca3af; }
.dm-conv-bot { display: flex; justify-content: space-between; gap: 8px; margin-top: 2px; }
.dm-conv-bot .msg { font-size: 12.5px; color: #6b7280; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.badge { background: #ef4444; color: #fff; font-size: 11px; border-radius: 9px; padding: 0 6px; line-height: 17px; }
.dm-main { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.dm-blank { flex: 1; display: flex; align-items: center; justify-content: center; color: #9ca3af; font-size: 13px; }
.dm-chat-hd { display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; border-bottom: 1px solid #f0f0f0; background: #fff; }
.dm-chat-hd .who { display: flex; align-items: center; gap: 8px; }
.dm-chat-hd .nm { font-size: 14px; font-weight: 600; }
.hd-acts { display: flex; gap: 4px; }
/* 消息贴底（大厂 IM 惯例）：内容少时贴着输入框，留白在上方；内容多时正常滚动 */
.dm-msgs { flex: 1; overflow-y: auto; padding: 20px 24px; background: #f5f6f7; display: flex; flex-direction: column; }
.dm-msgs::before { content: ''; margin-top: auto; }
.dm-tip { text-align: center; color: #9ca3af; font-size: 12px; padding: 12px; }
/* ---------- 消息列表（飞书式：头像 + 分组 + 悬停操作） ---------- */
.dm-row { display: flex; gap: 10px; align-items: flex-start; margin-bottom: 12px; animation: msgIn .2s ease-out; }
@keyframes msgIn { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
/* 自己发的消息：气泡在左、头像在右（标准 IM，头像贴右边缘）。
   DOM 顺序是 [气泡][头像]，所以用 row + 靠右对齐即可，不能用 row-reverse（会把头像翻到左边）。 */
.dm-row.mine { flex-direction: row; justify-content: flex-end; }
.row-av { flex: 0 0 auto; margin-top: 2px; }
.row-main { display: flex; flex-direction: column; align-items: flex-start; min-width: 0; max-width: 68%; }
.dm-row.mine .row-main { align-items: flex-end; }
.row-name { font-size: 12px; color: #8a94a6; margin: 0 0 4px 4px; }
.bubble-wrap { display: flex; align-items: center; gap: 6px; max-width: 100%; }
.dm-row.mine .bubble-wrap { flex-direction: row-reverse; }
.bubble { padding: 9px 13px; border-radius: 12px; background: #fff; border: 1px solid #e5e6eb;
  font-size: 13.5px; line-height: 1.65; color: #1f2937; white-space: pre-wrap; word-break: break-word;
  box-shadow: 0 1px 2px rgba(15, 23, 42, .04); }
.dm-row.mine .bubble { background: #1677ff; border-color: #1677ff; color: #fff; box-shadow: 0 1px 2px rgba(22, 119, 255, .2); }
.bubble.img { padding: 4px; background: #fff; }
.bubble .img { max-width: 220px; border-radius: 8px; display: block; }
.row-acts { display: flex; gap: 4px; opacity: 0; transition: opacity .12s; flex: 0 0 auto; }
.dm-row:hover .row-acts { opacity: 1; }
.row-acts button { border: 0; background: #fff; color: #64748b; font-size: 12px; padding: 3px 8px;
  border-radius: 6px; cursor: pointer; box-shadow: 0 1px 4px rgba(15, 23, 42, .12); }
.row-acts button:hover { color: #2563eb; }
.row-meta { display: flex; gap: 6px; margin-top: 4px; font-size: 10.5px; color: #b6bdc9; opacity: .55; transition: opacity .15s; }
.dm-row:hover .row-meta { opacity: 1; }
.row-meta .read { color: #9ca3af; }
.day-sep { text-align: center; margin: 8px 0 14px; }
.day-sep span { background: #eef0f3; color: #a8b0bd; font-size: 11.5px; padding: 2px 10px; border-radius: 999px; }
.dm-quota { padding: 6px 16px; background: #fffbeb; color: #b45309; font-size: 12px; border-top: 1px solid #fde68a; }
.dm-input { display: flex; flex-direction: column; gap: 8px; padding: 12px 16px 14px; border-top: 1px solid #f0f0f0; }
.dm-input-bot { display: flex; align-items: center; justify-content: space-between; }
.dm-input :deep(.el-textarea__inner) { border-radius: 10px; background: #fff; border: 1px solid #e5e6eb; transition: all .15s; }
.dm-input :deep(.el-textarea__inner:focus) { background: #fff; border-color: #1677ff; box-shadow: 0 0 0 3px rgba(22, 119, 255, .1); }
.dm-tools { display: flex; align-items: center; gap: 2px; }
.tool { width: 30px; height: 30px; border: 0; background: transparent; border-radius: 8px; cursor: pointer;
  font-size: 16px; color: #64748b; display: inline-flex; align-items: center; justify-content: center; }
.tool:hover, .tool.on { background: #eef2ff; color: #2563eb; }
.tool:disabled { opacity: .4; cursor: default; }
.tool-hint { font-size: 11px; color: #b6bdc9; margin-left: 4px; white-space: nowrap; }
.dm-editor { position: relative; width: 100%; }
/* 待发送图片预览条：与输入区同宽，浅底圆角，不抢发送按钮视觉 */
.pending-img { display: flex; align-items: center; gap: 9px; padding: 6px 8px;
  background: #f5f6f7; border: 1px solid #e5e6eb; border-radius: 10px; }
.pending-img img { width: 42px; height: 42px; object-fit: cover; border-radius: 7px;
  -webkit-user-drag: none; user-select: none; }
.pi-name { font-size: 12px; color: #1d2129; max-width: 200px; overflow: hidden;
  text-overflow: ellipsis; white-space: nowrap; }
.pi-hint { flex: 1; font-size: 11px; color: #86909c; white-space: nowrap; }
.pi-x { width: 24px; height: 24px; border: 0; background: transparent; border-radius: 6px;
  color: #86909c; font-size: 18px; line-height: 1; cursor: pointer; }
.pi-x:hover { background: #e5e6eb; color: #f53f3f; }
html.dark .pending-img { background: #1f2024; border-color: #2a2c33; }
html.dark .pi-name { color: #e5e6eb; }
html.dark .pi-x:hover { background: #2a2c33; }
.emoji-pop { position: absolute; bottom: calc(100% + 8px); left: 0; z-index: 30; }
.send-btn { height: 38px; border-radius: 8px; background: #1677ff; border: none; box-shadow: none; }
.st-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.st-t { font-size: 13.5px; font-weight: 600; }
.st-s { font-size: 12px; color: #6b7280; margin-top: 4px; line-height: 1.6; }

.recalled { font-size: 12px; color: #9ca3af; padding: 4px 10px; background: #f3f4f6; border-radius: 8px; align-self: center; }
.hidden-file { display: none; }

.quote { border-left: 2px solid #b4c7ff; background: #f5f7ff; color: #6b7280;
  font-size: 12px; padding: 4px 8px; border-radius: 6px; margin-bottom: 6px;
  display: flex; flex-direction: column; gap: 2px; }
.quote .q-from { font-weight: 600; color: #4b6bfb; }
.quote .q-txt { max-height: 44px; overflow: hidden; }
.quote-bar { display: flex; align-items: center; gap: 8px; padding: 6px 16px;
  background: #f5f7ff; border-top: 1px solid #e6ecff; font-size: 12.5px; color: #4b5563; }
.quote-bar .q-body { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ---------- 暗色模式 ---------- */
html.dark .dm { background: #17171f; border-color: #2a2a35; }
html.dark .dm-side { background: #1b1b24; border-right-color: #2a2a35; }
html.dark .dm-side-hd { color: #e7e7ee; }
html.dark .dm-conv:hover { background: #23232e; }
html.dark .dm-conv.on { background: #1f2a44; }
html.dark .dm-conv-top .nm { color: #e7e7ee; }
html.dark .dm-conv-bot .msg { color: #7b8190; }
html.dark .dm-empty-s { color: #5b5f6b; }
html.dark .dm-blank { color: #5b5f6b; }
html.dark .dm-tip { color: #5b5f6b; }
html.dark .dm-chat-hd { border-bottom-color: #2a2a35; }
html.dark .dm-chat-hd .nm { color: #e7e7ee; }
html.dark .dm-msgs { background: #13131a; }
html.dark .bubble { background: #23232e; border-color: #2a2a35; color: #d7dae2; }
html.dark .dm-row.mine .bubble { background: #2563eb; border-color: #2563eb; color: #fff; }
html.dark .meta { color: #5b5f6b; }
html.dark .recalled { background: #23232e; color: #7b8190; }
html.dark .quote { background: #1f2330; color: #8b90a0; }
html.dark .quote .q-from { color: #7aa2ff; }
html.dark .quote-bar { background: #1f2330; border-top-color: #2a2a35; color: #8b90a0; }
html.dark .dm-quota { background: #33270f; color: #fbbf24; border-top-color: #4d3a12; }
html.dark .dm-input { border-top-color: #2a2a35; }
html.dark .day-sep span { background: #2a2a35; color: #7b8190; }
html.dark .row-name { color: #7b8190; }
html.dark .row-meta { color: #5b5f6b; }
html.dark .row-acts button { background: #2a2a35; color: #a5a7bd; box-shadow: none; }
html.dark .row-acts button:hover { color: #a9c2ff; }
html.dark .tool { color: #9aa0ad; }
html.dark .tool:hover, html.dark .tool.on { background: #2a2a46; color: #a9c2ff; }
html.dark .tool-hint { color: #4b4f5c; }
</style>