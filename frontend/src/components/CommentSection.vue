<script setup>
import { ref, reactive, computed, nextTick, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../store/auth'
import {
  getCommentsGeneric, addCommentGeneric, deleteComment,
  myCommentLikes, toggleCommentLike
} from '../api/kb'
import { fromNow, fmtDateTime } from '../utils/time'
import EmojiPanel from './EmojiPanel.vue'
import { errMsg } from '../utils/errMsg'

const props = defineProps({
  targetType: { type: String, required: true },  // 'DOC' | 'CHAPTER'
  targetId: { type: [Number, String], required: true },
})

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const comments = ref([])
const loading = ref(false)
const posting = ref(false)
const commentInputRef = ref(null)
const commentSort = ref('latest')
const commentLikedIds = ref(new Set())
const expandedReplies = reactive({})
const replyTo = ref(null)

const commentMap = computed(() => {
  const m = {}
  for (const c of comments.value) m[c.id] = c
  return m
})
const commentTree = computed(() => {
  const map = {}
  for (const c of comments.value) map[c.id] = c
  const tops = comments.value.filter(c => !c.parentId)
  const children = {}
  for (const c of comments.value) {
    if (c.parentId) (children[c.parentId] = children[c.parentId] || []).push(c)
  }
  const byTime = (arr) => [...arr].sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''))
  return byTime(tops).map(t => ({
    ...t,
    replies: byTime(children[t.id] || []).map(r => ({ ...r, parentName: map[r.parentId] ? map[r.parentId].username : '' }))
  }))
})
const sortedComments = computed(() => {
  const arr = commentTree.value.slice()
  if (commentSort.value === 'hot') {
    arr.sort((a, b) => (b.likes || 0) - (a.likes || 0) || new Date(b.createdAt || 0) - new Date(a.createdAt || 0))
  } else {
    arr.sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0))
  }
  return arr
})

const startReply = (c) => {
  if (!auth.loggedIn) { ElMessage.warning('请先登录'); router.push('/login'); return }
  const topId = c.parentId || c.id
  const top = commentMap.value[topId]
  replyTo.value = { id: topId, name: top ? top.username : c.username }
  nextTick(() => { if (commentInputRef.value) commentInputRef.value.focus() })
}
const cancelReply = () => { replyTo.value = null }

const commentEmpty = ref(true)
const syncEmpty = () => {
  const el = commentInputRef.value
  if (!el) return
  commentEmpty.value = !el.textContent.trim() && !el.querySelector('img')
}
const onPaste = (e) => {
  e.preventDefault()
  const t = (e.clipboardData || window.clipboardData).getData('text/plain')
  document.execCommand('insertText', false, t)
  syncEmpty()
}
const insertEmoji = (p) => {
  const el = commentInputRef.value
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
    document.execCommand('insertHTML', false, `<img class="kb-emoji" src="/emojis/${p.name}.png" alt="表情">`)
  } else {
    document.execCommand('insertText', false, p.text)
  }
  syncEmpty()
}
const clearCommentBox = () => {
  const el = commentInputRef.value
  if (el) el.innerHTML = ''
  commentEmpty.value = true
}

const loadComments = async () => {
  loading.value = true
  try {
    const { data } = await getCommentsGeneric(props.targetType, props.targetId)
    comments.value = data || []
  } catch (e) { comments.value = [] }
  finally { loading.value = false }
  if (auth.loggedIn) {
    try { commentLikedIds.value = new Set((await myCommentLikes()).data || []) } catch (e) {}
  }
}

const onToggleCommentLike = async (c) => {
  if (!auth.loggedIn) { ElMessage.warning('请先登录'); router.push('/login'); return }
  try {
    const { data } = await toggleCommentLike(c.id)
    c.likes = data.likes
    const s = new Set(commentLikedIds.value)
    if (data.liked) s.add(c.id); else s.delete(c.id)
    commentLikedIds.value = s
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

const submitComment = async () => {
  if (!auth.loggedIn) { ElMessage.warning('请先登录'); router.push('/login'); return }
  const el = commentInputRef.value
  if (!el) return
  if (!el.textContent.trim() && !el.querySelector('img')) return
  posting.value = true
  try {
    await addCommentGeneric(props.targetType, props.targetId, el.innerHTML.trim(), replyTo.value ? replyTo.value.id : null)
    clearCommentBox()
    replyTo.value = null
    comments.value = (await getCommentsGeneric(props.targetType, props.targetId)).data || []
    ElMessage.success('评论成功')
  } catch (e) {
    ElMessage.error(errMsg(e, '评论失败'))
  } finally {
    posting.value = false
  }
}

const canDeleteComment = (c) =>
  auth.loggedIn && !!auth.user && (c.username === auth.user.username || auth.isAdmin)

const onDeleteComment = async (c) => {
  try {
    await ElMessageBox.confirm('确定删除这条评论吗？此操作不可撤销。', '删除评论', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger'
    })
  } catch (e) { return }
  try {
    await deleteComment(c.id)
    comments.value = comments.value.filter(x => x.id !== c.id)
    ElMessage.success('评论已删除')
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}


/**
 * 直达单条评论：数据面板「实时动态」点击后跳到 /post/:id?c=评论id。
 * 加载评论完成后定位：优先匹配主评论，其次匹配回复（回复默认只显示 3 条，会先展开）。
 */
const flashId = ref(null)
async function focusCommentFromQuery() {
  const cid = Number(route.query.c)
  if (!cid) return
  await nextTick()
  let rest = 0
  while (rest++ < 20) {                      // 评论是异步加载的，最多等 2s
    const el = document.getElementById('c' + cid)
    if (el) {
      // 若是折叠在「展开更多回复」里的子评论，先展开再滚动
      const parent = el.closest('li.citem')
      const parentId = parent && parent.id ? Number(parent.id.slice(1)) : null
      const hit = comments.value.find(x => (x.replies || []).some(r => r.id === cid))
      if (hit && parentId && !expandedReplies[hit.id]) {
        expandedReplies[hit.id] = true
        await nextTick()
      }
      const target = document.getElementById('c' + cid) || el
      target.scrollIntoView({ behavior: 'smooth', block: 'center' })
      flashId.value = cid
      setTimeout(() => { if (flashId.value === cid) flashId.value = null }, 2200)
      return
    }
    await new Promise(r => setTimeout(r, 100))
  }
}

onMounted(async () => {
  await loadComments()
  focusCommentFromQuery()
})
</script>

<template>
  <section class="cs">
    <div v-if="auth.loggedIn" class="cform">
      <div v-if="replyTo" class="reply-hint">
        回复 @{{ replyTo.name }}
        <button class="x" @click="cancelReply" title="取消回复">×</button>
      </div>
      <div class="cform-bar">
        <el-popover placement="top-start" trigger="click" :width="300" popper-class="emoji-pop">
          <template #reference>
            <button class="emoji-btn" type="button">😊 表情</button>
          </template>
          <EmojiPanel @insert="insertEmoji" />
        </el-popover>
      </div>
      <div ref="commentInputRef" class="cbox" :class="{ empty: commentEmpty }"
           contenteditable="true" data-ph="写下你的评论…"
           @input="syncEmpty" @paste="onPaste"></div>
      <el-button type="primary" :loading="posting" @click="submitComment" style="margin-top:8px">发表评论</el-button>
    </div>
    <el-empty v-else description="登录后参与评论" style="padding: 12px 0">
      <el-button @click="router.push('/login')">去登录</el-button>
    </el-empty>

    <div class="ch-row">
      <h3 class="ch">评论（{{ comments.length }}）</h3>
      <div class="csort">
        <button type="button" :class="{ on: commentSort === 'latest' }" @click="commentSort = 'latest'">最新</button>
        <button type="button" :class="{ on: commentSort === 'hot' }" @click="commentSort = 'hot'">最热</button>
      </div>
    </div>

    <ul class="clist" v-if="sortedComments.length" v-loading="loading">
      <li v-for="c in sortedComments" :key="c.id" class="citem" :id="'c' + c.id" :class="{ 'cmt-flash': flashId === c.id }">
        <div class="chead">
          <b>{{ c.username }}</b>
          <span class="ctime" v-if="c.createdAt">{{ fromNow(c.createdAt) }}（{{ fmtDateTime(c.createdAt) }}）</span>
          <button class="clike" :class="{ on: commentLikedIds.has(c.id) }" @click="onToggleCommentLike(c)" title="点赞">
            <span class="lic"><svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path fill="currentColor" d="M2 21h2V9H2v12zm20-10.09c0-.58-.47-1.05-1.05-1.05H13.8l.94-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.3 1.3 6.72 7.9A1.98 1.98 0 0 0 5.3 9.3v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-1.91z"/></svg></span><span class="ln">{{ c.likes || 0 }}</span>
          </button>
          <button class="creply" v-if="auth.loggedIn" @click="startReply(c)">回复</button>
          <button class="cdel" v-if="canDeleteComment(c)" @click="onDeleteComment(c)">删除</button>
        </div>
        <div class="cbody" v-html="c.content"></div>
        <ul class="replies" v-if="c.replies && c.replies.length">
          <li v-for="r in (expandedReplies[c.id] ? c.replies : c.replies.slice(0, 3))" :key="r.id" class="ritem" :id="'c' + r.id" :class="{ 'cmt-flash': flashId === r.id }">
            <div class="chead">
              <b>{{ r.username }}</b>
              <span class="r-to" v-if="r.parentName">回复 @{{ r.parentName }}</span>
              <span class="ctime" v-if="r.createdAt">{{ fromNow(r.createdAt) }}（{{ fmtDateTime(r.createdAt) }}）</span>
              <button class="clike" :class="{ on: commentLikedIds.has(r.id) }" @click="onToggleCommentLike(r)" title="点赞">
                <span class="lic"><svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path fill="currentColor" d="M2 21h2V9H2v12zm20-10.09c0-.58-.47-1.05-1.05-1.05H13.8l.94-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.3 1.3 6.72 7.9A1.98 1.98 0 0 0 5.3 9.3v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-1.91z"/></svg></span><span class="ln">{{ r.likes || 0 }}</span>
              </button>
              <button class="creply" v-if="auth.loggedIn" @click="startReply(r)">回复</button>
              <button class="cdel" v-if="canDeleteComment(r)" @click="onDeleteComment(r)">删除</button>
            </div>
            <div class="cbody" v-html="r.content"></div>
          </li>
        </ul>
        <button v-if="c.replies && c.replies.length > 3" class="more-replies"
                @click="expandedReplies[c.id] = !expandedReplies[c.id]">
          {{ expandedReplies[c.id] ? '收起回复' : '查看全部 ' + c.replies.length + ' 条回复' }}
        </button>
      </li>
    </ul>
    <el-empty v-else-if="!loading" description="还没有评论，快来抢沙发" />
    <div v-if="loading" class="tip">加载中…</div>
  </section>
</template>

<style scoped>
/* 从数据面板「点击直达」进来的评论：短暂高亮，帮助一眼定位 */
.cmt-flash { animation: cmtFlash 2s ease-out; border-radius: 8px; }
@keyframes cmtFlash {
  0%   { background: rgba(99, 102, 241, .22); }
  70%  { background: rgba(99, 102, 241, .12); }
  100% { background: transparent; }
}
html.dark .cmt-flash { animation: cmtFlashDark 2s ease-out; }
@keyframes cmtFlashDark {
  0%   { background: rgba(129, 140, 248, .28); }
  70%  { background: rgba(129, 140, 248, .14); }
  100% { background: transparent; }
}
@media (prefers-reduced-motion: reduce) { .cmt-flash { animation: none; } }
.cs { margin-top: 18px; }
.cform { margin-bottom: 16px; }
.cform-bar { margin-bottom: 8px; }
.emoji-btn { border: 1px solid #dcdfe6; background: #fff; border-radius: 6px; padding: 4px 10px; font-size: 13px; color: #475569; cursor: pointer; transition: .12s; }
.emoji-btn:hover { border-color: #c7d2fe; color: #4f46e5; background: #eef2ff; }
.cbox {
  min-height: 78px; max-height: 220px; overflow: auto; padding: 8px 11px;
  border: 1px solid #dcdfe6; border-radius: 4px; background: #fff;
  font-size: 14px; line-height: 1.6; color: #0f172a; outline: none;
}
.cbox:focus { border-color: #6366f1; }
.cbox.empty::before { content: attr(data-ph); color: #a8abb2; pointer-events: none; }
.cbox :deep(img.kb-emoji) { height: 20px; vertical-align: middle; margin: 0 1px; }
.reply-hint { display: inline-flex; align-items: center; gap: 8px; margin-bottom: 8px; padding: 4px 10px; background: #eef2ff; border: 1px solid #c7d2fe; border-radius: 8px; color: #4f46e5; font-size: 13px; }
.reply-hint .x { border: none; background: none; color: #6366f1; cursor: pointer; font-size: 16px; line-height: 1; padding: 0 2px; }
.reply-hint .x:hover { color: #4338ca; }

.ch-row { display: flex; align-items: center; justify-content: space-between; margin: 14px 0 12px; }
.ch { margin: 0; font-size: 16px; font-weight: 700; color: #1e293b; }
.csort { display: inline-flex; background: #f1f5f9; border-radius: 8px; padding: 2px; }
.csort button { border: none; background: none; cursor: pointer; font-size: 13px; color: #64748b; padding: 4px 12px; border-radius: 6px; transition: .15s; }
.csort button.on { background: #fff; color: #4f46e5; font-weight: 600; box-shadow: 0 1px 3px rgba(0,0,0,.08); }

.clist { list-style: none; padding: 0; margin: 0; }
.citem { padding: 12px 0; border-top: 1px solid #f1f5f9; }
.citem:first-child { border-top: none; }
.creply { margin-left: 10px; border: none; background: none; color: #6366f1; cursor: pointer; font-size: 12px; padding: 0; }
.creply:hover { text-decoration: underline; }
.clike { display: inline-flex; align-items: center; gap: 3px; margin-left: 10px; border: 1px solid #e2e8f0; background: #f8fafc; color: #64748b; cursor: pointer; font-size: 12px; padding: 2px 8px; border-radius: 999px; transition: .15s; }
.clike:hover { border-color: #fecaca; background: #fef2f2; color: #ef4444; }
.clike.on { border-color: #fecaca; background: #fef2f2; color: #ef4444; font-weight: 600; }
.clike .lic { font-size: 13px; line-height: 1; }
.clike .ln { min-width: 8px; text-align: center; }
.replies { list-style: none; padding: 8px 0 0 16px; margin: 8px 0 0; border-left: 2px solid #eef2f7; }
.ritem { padding: 10px 0; border-top: 1px solid #f1f5f9; }
.ritem:first-child { border-top: none; }
.r-to { color: #6366f1; font-size: 12px; }
.more-replies { display: inline-block; margin: 4px 0 0 16px; border: none; background: none; color: #6366f1; cursor: pointer; font-size: 13px; padding: 2px 0; }
.more-replies:hover { text-decoration: underline; }
.chead { display: flex; gap: 10px; align-items: center; color: #334155; flex-wrap: wrap; }
.ctime { color: #94a3b8; font-size: 12px; }
.cdel { margin-left: auto; border: none; background: none; color: #ef4444; cursor: pointer; font-size: 12px; padding: 0; }
.cdel:hover { text-decoration: underline; }
.cbody { margin-top: 6px; color: #475569; line-height: 1.7; }
.cbody :deep(img.kb-emoji) { height: 20px; vertical-align: middle; margin: 0 1px; }
.cbody :deep(img) { max-width: 100%; }
.tip { color: #94a3b8; font-size: 13px; padding: 12px 0; }
:deep(html.dark) .cbox { background: #1b1b2e; border-color: #2c2c44; color: #c7c9d9; }
:deep(html.dark) .cbox.empty::before { color: #6b6f8a; }
:deep(html.dark) .emoji-btn { background: #1b1b2e; border-color: #2c2c44; color: #a5a7bd; }
:deep(html.dark) .csort { background: #14142a; }
:deep(html.dark) .ch { color: #e6e7f2; }
:deep(html.dark) .citem { border-top-color: #26264a; }
:deep(html.dark) .chead { color: #c7c9d9; }
:deep(html.dark) .cbody { color: #a5a7bd; }
:deep(html.dark) .clike { background: #14142a; border-color: #26264a; color: #8b8fa8; }
:deep(html.dark) .replies { border-left-color: #26264a; }
:deep(html.dark) .ritem { border-top-color: #26264a; }
</style>
