<template>
  <div class="dm">
    <!-- 左：会话列表 -->
    <aside class="dm-side">
      <div class="dm-side-hd">
        <span>私信</span>
        <el-tooltip content="私信设置" placement="bottom">
          <el-button text size="small" @click="openSettings"><el-icon><Setting /></el-icon></el-button>
        </el-tooltip>
      </div>
      <div class="dm-search">
        <el-input v-model="kw" size="small" placeholder="搜索会话" clearable />
      </div>
      <div class="dm-convs">
        <div v-if="!filtered.length" class="dm-empty-s">还没有会话</div>
        <div v-for="c in filtered" :key="c.peerId" class="dm-conv" :class="{ on: peer && peer.peerId === c.peerId }"
             @click="openPeer(c.peerId, c)">
          <el-avatar :size="34" :src="c.avatar || undefined">{{ (c.name || '?').slice(0, 1) }}</el-avatar>
          <div class="dm-conv-body">
            <div class="dm-conv-top">
              <span class="nm">{{ c.name }}</span>
              <span class="tm">{{ shortTime(c.lastAt) }}</span>
            </div>
            <div class="dm-conv-bot">
              <span class="msg">{{ c.lastFromMe ? '我：' : '' }}{{ c.lastMessage }}</span>
              <span v-if="c.unread" class="badge">{{ c.unread > 99 ? '99+' : c.unread }}</span>
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
          <el-button text size="small" @click="toggleFollow">{{ following ? '已关注' : '+ 关注' }}</el-button>
        </header>

        <div ref="scroller" class="dm-msgs">
          <div v-if="loading" class="dm-tip">加载中…</div>
          <div v-for="(m, i) in msgs" :key="m.id" class="dm-row" :class="{ mine: m.fromId === meId }">
            <div class="bubble" :class="{ img: m.kind === 'IMAGE' }">
              <img v-if="m.kind === 'IMAGE'" :src="m.content" class="img" />
              <span v-else>{{ m.content }}</span>
            </div>
            <div class="meta">
              <span class="t">{{ clock(m.createdAt) }}</span>
              <span v-if="m.fromId === meId && m.read" class="read">已读</span>
            </div>
          </div>
        </div>

        <div v-if="quota.limited" class="dm-quota">
          你们还没有互相关注，最多先发 {{ 3 - quota.sent }} 条（对方回复后可继续）
        </div>

        <footer class="dm-input">
          <el-input
            v-model="draft" type="textarea" :rows="2" resize="none"
            :placeholder="quota.peerAllowDm ? '输入消息，Enter 发送，Shift+Enter 换行' : '对方未开放私信'"
            :disabled="!quota.peerAllowDm || (quota.limited && quota.sent >= 3)"
            @keydown.enter.exact.prevent="send" />
          <el-button type="primary" :disabled="!canSend" :loading="sending" @click="send">发送</el-button>
        </footer>
      </template>
      <div v-else class="dm-blank">选择左侧会话开始聊天</div>
    </section>

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
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../store/auth'
import { dmConversations, dmMessages, dmSend, dmRead, dmQuota, dmSettings, dmSaveSettings } from '../api/dm'
import { refreshDmUnread } from '../store/dm'
import { followUser, unfollowUser } from '../api/kb'

const route = useRoute()
const auth = useAuthStore()
const meId = computed(() => auth.user?.id)

const convs = ref([])
const kw = ref('')
const peer = ref(null)
const msgs = ref([])
const draft = ref('')
const loading = ref(false)
const sending = ref(false)
const following = ref(false)
const quota = reactive({ mutual: false, replied: false, sent: 0, remaining: -1, limited: false, peerAllowDm: true })
const settings = reactive({ open: false, allowDm: true })
const scroller = ref(null)

const filtered = computed(() => {
  const k = kw.value.trim()
  return k ? convs.value.filter((c) => (c.name || '').includes(k) || (c.lastMessage || '').includes(k)) : convs.value
})
const canSend = computed(() => !!draft.value.trim() && !sending.value && quota.peerAllowDm
  && !(quota.limited && quota.sent >= 3))

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
  } catch (e) { /* 未登录等情况静默 */ }
}

async function openPeer(peerId, info) {
  const known = info || convs.value.find((c) => c.peerId === peerId)
  peer.value = { peerId, name: known?.name || `用户 ${peerId}`, avatar: known?.avatar }
  following.value = !!known?.isFollowing
  loading.value = true
  draft.value = ''
  try {
    const [m, q] = await Promise.all([dmMessages(peerId), dmQuota(peerId)])
    msgs.value = m.data || []
    Object.assign(quota, q.data || {})
    await dmRead(peerId)
    refreshDmUnread()          // 清侧栏红点
    const c = convs.value.find((x) => x.peerId === peerId)
    if (c) c.unread = 0
    await scrollBottom()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

async function send() {
  if (!canSend.value) return
  sending.value = true
  const text = draft.value.trim()
  try {
    const { data } = await dmSend(peer.value.peerId, text)
    draft.value = ''
    msgs.value.push({
      id: data.id, fromId: meId.value, kind: 'TEXT', content: text,
      createdAt: new Date().toISOString(), read: false,
    })
    if (data.quota) Object.assign(quota, data.quota)
    await scrollBottom()
    loadConvs()
  } catch (e) {
    ElMessage.error(e?.response?.data?.error || '发送失败')
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
      try {
        const { data } = await dmMessages(peer.value.peerId)
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
      })
      try { await dmRead(peer.value.peerId); refreshDmUnread() } catch (e) { /* ignore */ }
      if (atBottom) await scrollBottom()
    }
  }
  loadConvs()
}

onMounted(async () => {
  window.addEventListener('kb:dm', onDmEvent)
  await loadConvs()
  const p = Number(route.query.peer)
  if (p) await openPeer(p)
  startPoll()
})
onUnmounted(() => {
  stopPoll()
  window.removeEventListener('kb:dm', onDmEvent)
})
watch(() => route.query.peer, (v) => { if (v) openPeer(Number(v)) })
</script>

<style scoped>
.dm { display: flex; height: calc(100vh - 120px); background: #fff; border: 1px solid #ebeef5; border-radius: 12px; overflow: hidden; }
.dm-side { width: 264px; border-right: 1px solid #f0f0f0; display: flex; flex-direction: column; background: #fafbfc; }
.dm-side-hd { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; font-weight: 600; font-size: 14px; }
.dm-search { padding: 0 12px 8px; }
.dm-convs { flex: 1; overflow-y: auto; }
.dm-empty-s { padding: 24px 14px; color: #9ca3af; font-size: 13px; text-align: center; }
.dm-conv { display: flex; gap: 10px; padding: 10px 12px; cursor: pointer; }
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
.dm-chat-hd { display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; border-bottom: 1px solid #f0f0f0; }
.dm-chat-hd .who { display: flex; align-items: center; gap: 8px; }
.dm-chat-hd .nm { font-size: 14px; font-weight: 600; }
.dm-msgs { flex: 1; overflow-y: auto; padding: 16px; background: #f7f8fa; }
.dm-tip { text-align: center; color: #9ca3af; font-size: 12px; padding: 12px; }
.dm-row { display: flex; flex-direction: column; align-items: flex-start; margin-bottom: 12px; }
.dm-row.mine { align-items: flex-end; }
.bubble { max-width: 62%; padding: 8px 12px; border-radius: 10px; background: #fff; border: 1px solid #eef0f3;
  font-size: 13.5px; line-height: 1.65; color: #1f2937; white-space: pre-wrap; word-break: break-word; }
.dm-row.mine .bubble { background: #1677ff; border-color: #1677ff; color: #fff; }
.bubble.img { padding: 4px; background: #fff; }
.bubble .img { max-width: 220px; border-radius: 8px; display: block; }
.meta { display: flex; gap: 6px; margin-top: 3px; font-size: 11px; color: #9ca3af; }
.meta .read { color: #9ca3af; }
.dm-quota { padding: 6px 16px; background: #fffbeb; color: #b45309; font-size: 12px; border-top: 1px solid #fde68a; }
.dm-input { display: flex; gap: 10px; padding: 12px 16px; border-top: 1px solid #f0f0f0; align-items: flex-end; }
.dm-input :deep(.el-textarea__inner) { border-radius: 10px; }
.st-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.st-t { font-size: 13.5px; font-weight: 600; }
.st-s { font-size: 12px; color: #6b7280; margin-top: 4px; line-height: 1.6; }
</style>
