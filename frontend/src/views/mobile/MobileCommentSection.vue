<script setup>
/**
 * 移动端评论组件（功能对齐 Web 端 components/CommentSection.vue）
 *
 * 功能：二级回复（楼中楼）/ 最新·最热排序 / 表情（图片 + 颜文字）/ 评论点赞 / 删除 / @回复提示
 * 移动端适配：内联输入 + 内联表情面板（不失焦，避开虚拟键盘遮挡）、
 *            首字母彩色头像、相对时间、大点击区（≥38px）、未登录引导
 * 挂载类型 targetType：'DOC'（公开文章）/ 'SDOC'（空间文档）
 */
import { ref, reactive, computed, nextTick, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAuthStore } from '../../store/auth'
import {
  getCommentsGeneric, addCommentGeneric, deleteComment,
  myCommentLikes, toggleCommentLike,
} from '../../api/kb'
import { fromNow } from '../../utils/time'
import { resolveUrl } from '../../utils/runtimeBase'
import EmojiPanel from '../../components/EmojiPanel.vue'

const props = defineProps({
  targetType: { type: String, required: true },
  targetId: { type: [Number, String], required: true },
})
const emit = defineEmits(['count'])

const router = useRouter()
const auth = useAuthStore()

const comments = ref([])
const loading = ref(false)
const posting = ref(false)
const inputRef = ref(null)
const sort = ref('latest')
const likedIds = ref(new Set())
const expanded = reactive({})
const replyTo = ref(null)
const emojiOpen = ref(false)
const empty = ref(true)

const total = computed(() => comments.value.length)

const commentMap = computed(() => {
  const m = {}
  for (const c of comments.value) m[c.id] = c
  return m
})
const commentTree = computed(() => {
  const map = commentMap.value
  const tops = comments.value.filter((c) => !c.parentId)
  const children = {}
  for (const c of comments.value) {
    if (c.parentId) (children[c.parentId] = children[c.parentId] || []).push(c)
  }
  const byTime = (arr) => [...arr].sort((a, b) => String(a.createdAt || '').localeCompare(String(b.createdAt || '')))
  return byTime(tops).map((t) => ({
    ...t,
    replies: byTime(children[t.id] || []).map((r) => ({
      ...r,
      parentName: map[r.parentId] ? (map[r.parentId].displayName || map[r.parentId].username || map[r.parentId].userName) : '',
    })),
  }))
})
const sortedComments = computed(() => {
  const arr = commentTree.value.slice()
  if (sort.value === 'hot') {
    arr.sort((a, b) => (b.likes || 0) - (a.likes || 0) || new Date(b.createdAt || 0) - new Date(a.createdAt || 0))
  } else {
    arr.sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0))
  }
  return arr
})

/** 评论正文里的表情图（/emojis/xxx.png）在 App 内需绝对化 */
function renderHtml(html) {
  return String(html || '').replace(/src="\/([^"]*)"/g, (_m, p) => 'src="' + resolveUrl('/' + p) + '"')
}
const AV_COLORS = ['#1677ff', '#f59e0b', '#10b981', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#f97316']
function avColor(name) {
  const s = String(name || '')
  let h = 0
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0
  return AV_COLORS[h % AV_COLORS.length]
}
function initial(name) {
  const s = String(name || '').trim()
  return s ? s[0].toUpperCase() : '匿'
}
const nameOf = (c) => c.displayName || c.username || c.userName || '匿名'

async function loadComments() {
  loading.value = true
  try {
    const { data } = await getCommentsGeneric(props.targetType, props.targetId)
    comments.value = data || []
    emit('count', comments.value.length)
  } catch { comments.value = [] }
  finally { loading.value = false }
  if (auth.loggedIn) {
    try { likedIds.value = new Set(((await myCommentLikes()).data || []).map(Number)) } catch { /* ignore */ }
  }
}

function requireLogin() {
  window.__kbMsg?.warning('请先登录')
  router.push('/login')
}

function startReply(c) {
  if (!auth.loggedIn) return requireLogin()
  const topId = c.parentId || c.id
  const top = commentMap.value[topId]
  replyTo.value = { id: topId, name: top ? nameOf(top) : nameOf(c) }
  nextTick(() => { inputRef.value?.focus() })
}
function cancelReply() { replyTo.value = null }

function syncEmpty() {
  const el = inputRef.value
  empty.value = !el || (!el.textContent.trim() && !el.querySelector('img'))
}
function onPaste(e) {
  e.preventDefault()
  const t = (e.clipboardData || window.clipboardData).getData('text/plain')
  document.execCommand('insertText', false, t)
  syncEmpty()
}
function insertEmoji(p) {
  const el = inputRef.value
  if (!el) return
  el.focus()
  const sel = window.getSelection()
  if (!sel.rangeCount || !el.contains(sel.getRangeAt(0).commonAncestorContainer)) {
    const r = document.createRange()
    r.selectNodeContents(el)
    r.collapse(false)
    sel.removeAllRanges()
    sel.addRange(r)
  }
  if (p.kind === 'img') {
    document.execCommand('insertHTML', false, `<img class="kb-emoji" src="${resolveUrl('/emojis/' + p.name + '.png')}" alt="表情">`)
  } else {
    document.execCommand('insertText', false, p.text)
  }
  syncEmpty()
}
function clearBox() {
  const el = inputRef.value
  if (el) el.innerHTML = ''
  empty.value = true
  emojiOpen.value = false
}

async function submit() {
  if (!auth.loggedIn) return requireLogin()
  const el = inputRef.value
  if (!el) return
  if (!el.textContent.trim() && !el.querySelector('img')) { window.__kbMsg?.warning('请输入评论内容'); return }
  if (posting.value) return
  posting.value = true
  try {
    await addCommentGeneric(props.targetType, props.targetId, el.innerHTML.trim(), replyTo.value ? replyTo.value.id : null)
    clearBox()
    replyTo.value = null
    await loadComments()
    window.__kbMsg?.success('评论已发表')
  } catch (e) {
    window.__kbMsg?.error('评论失败：' + (e?.response?.data?.error || e.message || '未知错误'))
  } finally { posting.value = false }
}

function canDelete(c) {
  if (!auth.loggedIn) return false
  const me = Number(localStorage.getItem('kb_user_id') || 0)
  const owner = Number(c.userId || c.ownerId || 0)
  return (!!me && owner === me) || !!auth.isAdmin
}
async function remove(c) {
  try {
    await ElMessageBox.confirm('确定删除这条评论吗？此操作不可撤销。', '删除评论', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger',
    })
  } catch { return }
  try {
    await deleteComment(c.id)
    comments.value = comments.value.filter((x) => x.id !== c.id && x.parentId !== c.id)
    emit('count', comments.value.length)
    window.__kbMsg?.success('评论已删除')
  } catch { window.__kbMsg?.warning('删除失败') }
}

async function like(c) {
  if (!auth.loggedIn) return requireLogin()
  try {
    const { data } = await toggleCommentLike(c.id)
    c.likes = data?.likes ?? c.likes
    const s = new Set(likedIds.value)
    if (data?.liked) s.add(Number(c.id)); else s.delete(Number(c.id))
    likedIds.value = s
  } catch { /* ignore */ }
}

onMounted(loadComments)
watch(() => [props.targetType, props.targetId], () => {
  replyTo.value = null
  clearBox()
  comments.value = []
  loadComments()
})
</script>

<template>
  <div class="mcs">
    <!-- 输入区 -->
    <div v-if="auth.loggedIn" class="mcs-form">
      <div v-if="replyTo" class="mcs-reply-hint">
        <span>回复 @{{ replyTo.name }}</span>
        <button class="x" type="button" title="取消回复" @click="cancelReply">×</button>
      </div>
      <div ref="inputRef" class="mcs-box" :class="{ empty }"
           contenteditable="true" data-ph="写下你的评论…"
           @input="syncEmpty" @paste="onPaste"></div>
      <div class="mcs-bar">
        <button class="mcs-emoji" :class="{ on: emojiOpen }" type="button" @click="emojiOpen = !emojiOpen">
          <span class="em">🙂</span><span>表情</span>
        </button>
        <button class="m-btn sm" :disabled="posting" @click="submit">{{ posting ? '发送中…' : '发表' }}</button>
      </div>
      <div v-if="emojiOpen" class="mcs-emoji-panel">
        <EmojiPanel @insert="insertEmoji" />
      </div>
    </div>
    <div v-else class="mcs-login">
      <span>登录后参与评论</span>
      <button class="m-btn sm" type="button" @click="router.push('/login')">去登录</button>
    </div>

    <!-- 头部：数量 + 排序 -->
    <div class="mcs-head">
      <span class="mcs-title">评论<b v-if="total"> {{ total }}</b></span>
      <div class="mcs-sort">
        <button type="button" :class="{ on: sort === 'latest' }" @click="sort = 'latest'">最新</button>
        <button type="button" :class="{ on: sort === 'hot' }" @click="sort = 'hot'">最热</button>
      </div>
    </div>

    <!-- 列表 -->
    <div v-if="sortedComments.length" class="mcs-list">
      <div v-for="c in sortedComments" :key="c.id" class="mcs-item">
        <div class="mcs-row">
          <img v-if="c.avatar" class="mcs-av mcs-av-img" :src="c.avatar" alt="" @error="c.avatar = null" />
          <span v-else class="mcs-av" :style="{ background: avColor(nameOf(c)) }">{{ initial(nameOf(c)) }}</span>
          <div class="mcs-main">
            <div class="mcs-meta">
              <b class="mcs-name">{{ nameOf(c) }}</b>
              <span class="mcs-time">{{ fromNow(c.createdAt) }}</span>
            </div>
            <div class="mcs-text" v-html="renderHtml(c.content)"></div>
            <div class="mcs-acts">
              <button class="mcs-act" :class="{ on: likedIds.has(Number(c.id)) }" type="button" @click="like(c)">
                <svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="currentColor" d="M2 21h2V9H2v12zm20-10.09c0-.58-.47-1.05-1.05-1.05H13.8l.94-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.3 1.3 6.72 7.9A1.98 1.98 0 0 0 5.3 9.3v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-1.91z"/></svg>
                <span>{{ c.likes || 0 }}</span>
              </button>
              <button class="mcs-act" type="button" @click="startReply(c)">回复</button>
              <button v-if="canDelete(c)" class="mcs-act danger" type="button" @click="remove(c)">删除</button>
            </div>

            <!-- 楼中楼回复 -->
            <div v-if="c.replies && c.replies.length" class="mcs-replies">
              <div v-for="r in (expanded[c.id] ? c.replies : c.replies.slice(0, 2))" :key="r.id" class="mcs-reply">
                <div class="mcs-meta">
                  <b class="mcs-name">{{ nameOf(r) }}</b>
                  <span v-if="r.parentName" class="mcs-to">回复 @{{ r.parentName }}</span>
                  <span class="mcs-time">{{ fromNow(r.createdAt) }}</span>
                </div>
                <div class="mcs-text" v-html="renderHtml(r.content)"></div>
                <div class="mcs-acts">
                  <button class="mcs-act" :class="{ on: likedIds.has(Number(r.id)) }" type="button" @click="like(r)">
                    <svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path fill="currentColor" d="M2 21h2V9H2v12zm20-10.09c0-.58-.47-1.05-1.05-1.05H13.8l.94-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.3 1.3 6.72 7.9A1.98 1.98 0 0 0 5.3 9.3v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-1.91z"/></svg>
                    <span>{{ r.likes || 0 }}</span>
                  </button>
                  <button class="mcs-act" type="button" @click="startReply(r)">回复</button>
                  <button v-if="canDelete(r)" class="mcs-act danger" type="button" @click="remove(r)">删除</button>
                </div>
              </div>
              <button v-if="c.replies.length > 2" class="mcs-more" type="button" @click="expanded[c.id] = !expanded[c.id]">
                {{ expanded[c.id] ? '收起回复' : '查看全部 ' + c.replies.length + ' 条回复' }}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
    <div v-else-if="loading" class="mcs-empty">加载中…</div>
    <div v-else class="mcs-empty">还没有评论，来说两句吧</div>
  </div>
</template>

<style scoped>
.mcs { padding: 0 12px 4px; }
.mcs-form { padding: 12px 0 4px; }
.mcs-box {
  min-height: 72px; max-height: 180px; overflow: auto;
  padding: 10px 12px; border: 1px solid var(--m-border); border-radius: 12px;
  background: var(--m-card-2); font-size: 14.5px; line-height: 1.6; color: var(--m-text);
  outline: none; word-break: break-word; -webkit-user-select: text; user-select: text;
}
.mcs-box:focus { border-color: var(--m-primary); background: var(--m-card); }
.mcs-box.empty::before { content: attr(data-ph); color: var(--m-text-dim); pointer-events: none; }
.mcs-box :deep(img.kb-emoji) { height: 20px; vertical-align: middle; margin: 0 1px; }

.mcs-reply-hint {
  display: inline-flex; align-items: center; gap: 8px; margin-bottom: 8px;
  padding: 5px 10px; background: var(--m-primary-soft); border-radius: 8px;
  color: var(--m-primary); font-size: 12.5px;
}
.mcs-reply-hint .x {
  border: none; background: none; color: var(--m-primary);
  font-size: 17px; line-height: 1; padding: 0 2px;
}

.mcs-bar { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 8px; }
.mcs-emoji {
  display: inline-flex; align-items: center; gap: 5px; height: 38px; padding: 0 12px;
  border: 1px solid var(--m-border); background: var(--m-card); border-radius: 11px;
  font-size: 14px; color: var(--m-text-sub);
  -webkit-tap-highlight-color: transparent;
}
.mcs-emoji.on { border-color: var(--m-primary); color: var(--m-primary); background: var(--m-primary-soft); }
.mcs-emoji .em { font-size: 16px; }
.mcs-emoji-panel { margin-top: 10px; }
.mcs-emoji-panel :deep(.emoji-panel) { width: 100%; }

.mcs-login {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 14px 0 6px; color: var(--m-text-dim); font-size: 14px;
}

.mcs-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 0 6px; }
.mcs-title { font-size: 15px; font-weight: 600; color: var(--m-text); }
.mcs-title b { color: var(--m-primary); font-weight: 700; }
.mcs-sort { display: inline-flex; background: var(--m-card-2); border-radius: 9px; padding: 2px; }
.mcs-sort button {
  border: none; background: none; font-size: 12.5px; color: var(--m-text-dim);
  padding: 5px 13px; border-radius: 7px; transition: .15s;
}
.mcs-sort button.on { background: var(--m-card); color: var(--m-primary); font-weight: 600; box-shadow: 0 1px 3px rgba(0, 0, 0, .06); }

.mcs-item { padding: 12px 0; border-top: 1px solid var(--m-border); }
.mcs-item:first-child { border-top: none; }
.mcs-row { display: flex; gap: 10px; }
.mcs-av {
  flex: none; width: 34px; height: 34px; border-radius: 50%;
  color: #fff; font-size: 14px; font-weight: 600;
  display: flex; align-items: center; justify-content: center;
}
/* 真实头像：接口带 avatar 时用图，加载失败回退首字母圆（@error 已把 avatar 置空） */
.mcs-av-img { object-fit: cover; background: #f1f5f9; }
html.dark .mcs-av-img { background: #2a2a35; }
.mcs-main { flex: 1 1 auto; min-width: 0; }
.mcs-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.mcs-name { font-size: 13.5px; font-weight: 600; color: var(--m-text); }
.mcs-to { font-size: 12px; color: var(--m-primary); }
.mcs-time { font-size: 11.5px; color: var(--m-text-dim); }
.mcs-text { margin-top: 4px; font-size: 14.5px; line-height: 1.7; color: var(--m-text-sub); word-break: break-word; }
.mcs-text :deep(img.kb-emoji) { height: 20px; vertical-align: middle; margin: 0 1px; }
.mcs-text :deep(img) { max-width: 100%; border-radius: 6px; }
.mcs-acts { display: flex; align-items: center; gap: 18px; margin-top: 7px; }
.mcs-act {
  display: inline-flex; align-items: center; gap: 4px;
  border: none; background: none; color: var(--m-text-dim); font-size: 12.5px;
  padding: 5px 0; -webkit-tap-highlight-color: transparent;
}
.mcs-act.on { color: #ef4444; font-weight: 600; }
.mcs-act.danger { color: #ef4444; }
.mcs-replies { margin-top: 10px; padding: 6px 0 2px 10px; border-left: 2px solid var(--m-border); }
.mcs-reply { padding: 8px 0; }
.mcs-more { border: none; background: none; color: var(--m-primary); font-size: 12.5px; padding: 4px 0; }
.mcs-empty { padding: 20px 0; text-align: center; font-size: 13px; color: var(--m-text-dim); }
</style>
