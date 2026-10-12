<script setup>
/**
 * 移动端文档阅读页
 *
 * 要点：
 *  - 阅读优先：去掉桌面端的目录树、右侧操作栏，正文全宽（左右 18px 边距）
 *  - 顶部栏随滚动渐显（下滑内容时标题栏半透明过渡），贴近原生阅读 App
 *  - 底部固定「编辑」按钮（有权限时），拇指可达
 *  - 离线可读：优先用本地缓存，无网且无缓存给明确提示
 */
import { computed, nextTick, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { enhanceVideos, bindImgLightbox, absolutizeMedia, absolutizeMediaHtml } from '../../utils/mediaEnhance'
import { useRoute, useRouter } from 'vue-router'
import { getShareDoc } from '../../api/docShare'
import {
  getCommentsGeneric, addCommentGeneric, deleteComment, toggleCommentLike, myCommentLikes,
  getUserCard, followUser, unfollowUser,
  toggleLikeGeneric, likeCountGeneric, myLikeIdsGeneric,
} from '../../api/kb'
import { getCachedDoc, cacheDoc } from '../../utils/offlineStore'
import { isBookmarked, toggleBookmark } from '../../utils/bookmarks'
import { toWordHtml, toMarkdown, shareLink, qrDataUrl } from '../../utils/docExport'
import { shareText, saveTextFile, safeName, copyText } from '../../utils/shareOut'
import { recordDocOpen } from '../../utils/readStats'
// 只读渲染用 Tiptap 实例：流程图 / 脑图 / 电子表格是 Vue 组件节点（NodeView），
// v-html 渲染不出来（会显示成空白），必须走编辑器内核才能画出来。
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import Table from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableHeader from '@tiptap/extension-table-header'
import TableCell from '@tiptap/extension-table-cell'
import MindmapNode from '../../extensions/MindmapNode'
import FlowchartNode from '../../extensions/FlowchartNode'
import SpreadsheetNode from '../../extensions/SpreadsheetNode'
import ImageNode from '../../extensions/ImageNode'
import VideoNode from '../../extensions/VideoNode'

const route = useRoute()
const router = useRouter()

const spaceId = Number(route.params.spaceId)
const docId = Number(route.params.docId)

const doc = ref(null)
const loading = ref(true)
const fromCache = ref(false)
const scrolled = ref(false)

const html = computed(() => doc.value?.content || '')

// 只读编辑器：只为把图块（流程图 / 脑图 / 电子表格）正确画出来，不参与协同、不可编辑
const editor = useEditor({
  content: '',
  editable: false,
  extensions: [
    StarterKit.configure({ history: false }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Table, TableRow, TableHeader, TableCell,
    MindmapNode, FlowchartNode, SpreadsheetNode,
    // ⚠️ 必须有这两个：StarterKit 不含 Image / Video 扩展，缺了它们正文里的
    // <img> / <video> 会被 ProseMirror schema 直接丢掉 → App 里看不到图片和视频
    // （网页端用 v-html 所以一直正常，2026-10-11 修）
    ImageNode, VideoNode,
  ],
  editorProps: { attributes: { class: 'm-prose' } },
})
// 内容到位后灌进编辑器——与下方媒体增强合并为单个 watch（外部审查 2026-10-11：
// 两个 watch 各自触发时 setContent 与 nextTick 后的增强存在时序竞争）
onBeforeUnmount(() => { try { editor.value?.destroy() } catch { /* ignore */ } })

// 正文里的图块类型——本页 v-html 渲染不了（它们是 Vue 组件节点），需要去编辑器看
const blockKinds = computed(() => {
  const h = html.value || ''
  const out = []
  if (/data-type="flowchart-block"/.test(h)) out.push('流程图')
  if (/data-type="mindmap-block"/.test(h)) out.push('脑图')
  if (/data-type="sheet-block"/.test(h)) out.push('电子表格')
  return out
})
function openInEditor() {
  const sid = route.params.spaceId
  const did = route.params.docId
  window.location.href = `/doc-shares/${sid}?doc=${did}`
}

// 图片灯箱：点正文图片全屏看原图
const lightboxSrc = ref('')

onMounted(async () => {
  try {
    const { data } = await getShareDoc(spaceId, docId)
    doc.value = data
    cacheDoc(spaceId, data).catch(() => {})
    // 本机阅读统计（「我的数据」页与首页概览用）
    try { recordDocOpen(spaceId, docId, data.title, data.content) } catch { /* ignore */ }
    // 社交：评论 / 关注作者 / 点赞状态（失败不阻塞阅读）
    loadComments().catch(() => {})
    loadAuthor().catch(() => {})
    loadLikeState().catch(() => {})
  } catch (e) {
    // 离线 / 失败：读缓存
    const c = await getCachedDoc(spaceId, docId).catch(() => null)
    if (c) {
      doc.value = { id: docId, title: c.title, content: c.html, updatedAt: c.updatedAt }
      fromCache.value = true
    } else {
      window.__kbMsg?.error('无法打开文档：' + (e.response?.data?.error || e.message || '未知错误'))
    }
  } finally {
    loading.value = false
  }
})

// 正文渲染后：图片点开放大 + 视频增强（16:9 占位防跳变、倍速/全屏/画中画控制条）
// 渲染根是 Tiptap 的 ProseMirror DOM（EditorContent），不是旧 v-html 容器——
// 旧 proseRef 已随模板删除，必须改取 editor.view.dom，否则灯箱/视频增强/媒体绝对化全部失效（外部复审 2026-10-08）
// 媒体增强（绝对化/视频/灯箱）内部已用 dataset 标记幂等，重复触发不会叠加监听
watch(html, async (v) => {
  // 先用字符串级绝对化：只靠 absolutizeMedia 改 DOM 会在 ProseMirror 的下次渲染被还原
  if (editor.value && v) editor.value.commands.setContent(absolutizeMediaHtml(v), false)
  await nextTick()
  const el = editor.value ? editor.value.view.dom : null
  if (!el) return
  absolutizeMedia(el)
  enhanceVideos(el)
  bindImgLightbox(el, (src) => { if (src) lightboxSrc.value = src })
}, { immediate: true })

function onScroll(e) {
  scrolled.value = e.target.scrollTop > 32
}

// ---------- 评论（空间文档走通用评论接口，targetType=SDOC） ----------
const SDOC_TARGET = 'SDOC'   // 评论挂载对象类型 & 文档点赞对象类型，均为 SDOC
const comments = ref([])
const commentText = ref('')
const commentLikes = ref([])   // 我点过赞的评论 id
const sending = ref(false)
const cmtRef = ref(null)

async function loadComments() {
  try {
    const [{ data }, likes] = await Promise.all([
      getCommentsGeneric(SDOC_TARGET, docId),
      myCommentLikes().catch(() => ({ data: [] })),
    ])
    comments.value = data || []
    commentLikes.value = (likes?.data || []).map(Number)
  } catch { comments.value = [] }
}

async function sendComment() {
  const t = (commentText.value || '').trim()
  if (!t) { window.__kbMsg?.warning('请输入评论内容'); return }
  if (sending.value) return
  sending.value = true
  try {
    await addCommentGeneric(SDOC_TARGET, docId, t)
    commentText.value = ''
    await loadComments()
    window.__kbMsg?.success('评论已发表')
  } catch (e) {
    window.__kbMsg?.error('评论失败：' + (e?.response?.data?.error || e.message || '未知错误'))
  } finally { sending.value = false }
}

async function removeComment(cid) {
  try { await deleteComment(cid); await loadComments(); window.__kbMsg?.success('已删除') }
  catch { window.__kbMsg?.warning('删除失败') }
}

async function likeComment(c) {
  try {
    const { data } = await toggleCommentLike(c.id)
    c.likes = data?.likes ?? c.likes
    const set = new Set(commentLikes.value)
    if (data?.liked) set.add(Number(c.id)); else set.delete(Number(c.id))
    commentLikes.value = [...set]
  } catch { /* ignore */ }
}

function isMine(c) {
  const me = Number(localStorage.getItem('kb_user_id') || 0)
  return !!me && Number(c.userId || 0) === me
}

function scrollToComments() {
  cmtRef.value?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
}

// ---------- 关注作者 ----------
const authorCard = ref(null)
const following = ref(false)
async function loadAuthor() {
  const uid = doc.value?.ownerId
  if (!uid) return
  try {
    const { data } = await getUserCard(uid)
    authorCard.value = data
    following.value = !!data?.isFollowing
  } catch { /* 拿不到就不显示关注按钮 */ }
}
async function toggleFollow() {
  const uid = authorCard.value?.id || doc.value?.ownerId
  if (!uid) return
  try {
    if (following.value) await unfollowUser(uid); else await followUser(uid)
    following.value = !following.value
    window.__kbMsg?.success(following.value ? '已关注' : '已取消关注')
  } catch { window.__kbMsg?.warning('操作失败，请稍后重试') }
}

// ---------- 点赞（空间文档通用点赞，targetType=SDOC） ----------
const liked = ref(false)
const likeCount = ref(0)
async function onLike() {
  try {
    const { data } = await toggleLikeGeneric(SDOC_TARGET, docId, doc.value?.ownerId)
    liked.value = !!data?.liked
    likeCount.value = data?.likes ?? likeCount.value
  } catch { window.__kbMsg?.warning('操作失败，请稍后重试') }
}
async function loadLikeState() {
  try {
    const [mine, cnt] = await Promise.all([
      myLikeIdsGeneric(SDOC_TARGET).catch(() => ({ data: [] })),
      likeCountGeneric(SDOC_TARGET, docId).catch(() => ({ data: { likes: 0 } })),
    ])
    liked.value = (mine?.data || []).map(Number).includes(docId)
    likeCount.value = cnt?.data?.likes ?? 0
  } catch { /* ignore */ }
}

// ---------- 更多操作（导出 / 分享 / 收藏 / 历史版本） ----------
const showMore = ref(false)
const qrSrc = ref('')
const bookmarked = ref(false)

function openMore() {
  bookmarked.value = isBookmarked(spaceId, docId)
  showMore.value = true
}

function goEdit() {
  showMore.value = false
  router.push(`/doc/${spaceId}/${docId}/edit`)
}

function doFav() {
  const on = toggleBookmark({
    spaceId, docId,
    title: doc.value?.title || '未命名文档',
    spaceName: doc.value?.spaceName || '',
  })
  bookmarked.value = on
  showMore.value = false
  window.__kbMsg?.success(on ? '已加入收藏' : '已取消收藏')
}

/** 导出文件：原生走「写缓存 + 系统分享」，Web 走下载 */
async function exportAs(kind) {
  showMore.value = false
  const title = doc.value?.title || 'document'
  const body = doc.value?.content || ''
  if (!body) { window.__kbMsg?.warning('文档内容为空'); return }
  try {
    const r = kind === 'word'
      ? await saveTextFile({ filename: safeName(title, 'doc'), content: toWordHtml(title, body), mime: 'application/msword' })
      : await saveTextFile({ filename: safeName(title, 'md'), content: toMarkdown(body, title), mime: 'text/markdown' })
    if (r === 'share') window.__kbMsg?.success('已打开分享面板，可保存到文件或用 WPS 打开')
    else if (r === 'download') window.__kbMsg?.success('已开始下载')
    else window.__kbMsg?.error('导出失败，请重试')
  } catch (e) {
    window.__kbMsg?.error('导出失败：' + ((e && e.message) || e))
  }
}

function link() { return shareLink(spaceId, docId) }

async function doShare() {
  showMore.value = false
  const r = await shareText({ title: doc.value?.title || '知识库文档', text: doc.value?.title || '', url: link() })
  if (r === 'copy') window.__kbMsg?.success('链接已复制到剪贴板')
  else if (!r) window.__kbMsg?.error('分享失败')
}

async function doCopyLink() {
  showMore.value = false
  const ok = await copyText(link())
  ok ? window.__kbMsg?.success('链接已复制') : window.__kbMsg?.error('复制失败')
}

async function doQr() {
  showMore.value = false
  try { qrSrc.value = await qrDataUrl(link()) } catch { window.__kbMsg?.error('二维码生成失败') }
}

function doVersions() {
  showMore.value = false
  router.push(`/versions/${spaceId}/${docId}`)
}

function fmtDate(s) {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日`
}
</script>

<template>
  <div class="dv">
    <header class="dv-head" :class="{ solid: scrolled }">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="dv-title" :class="{ show: scrolled }">{{ doc?.title || '' }}</div>
      <button class="m-header-act" @click="openMore">
        <el-icon><MoreFilled /></el-icon>
      </button>
    </header>

    <div class="dv-scroll" @scroll="onScroll">
      <div v-if="loading" class="body">
        <div class="m-skel" style="height:26px;width:72%"></div>
        <div class="m-skel" style="height:15px;width:40%;margin-top:12px"></div>
        <div style="margin-top:24px">
          <div v-for="i in 6" :key="i" class="m-skel" style="height:15px;margin-bottom:11px"
               :style="{ width: [96, 88, 92, 75, 90, 60][i - 1] + '%' }"></div>
        </div>
      </div>

      <template v-else-if="doc">
        <div class="body">
          <h1 class="dv-h1">{{ doc.title || '未命名文档' }}</h1>
          <div class="dv-meta">
            <span>{{ doc.ownerName || '匿名' }}</span>
            <button v-if="authorCard?.id" class="follow-mini" :class="{ on: following }"
                    type="button" @click="toggleFollow">{{ following ? '已关注' : '关注' }}</button>
            <span class="dot">·</span>
            <span>{{ fmtDate(doc.updatedAt || doc.createdAt) }}</span>
            <span v-if="fromCache" class="m-chip orange">离线缓存</span>
          </div>
        </div>
        <div class="body">
          <!-- 走 Tiptap 只读实例渲染：这样流程图 / 脑图 / 电子表格这类组件节点才能画出来 -->
          <EditorContent :editor="editor" />
          <p v-if="!html" style="color:var(--m-text-dim)">（空文档）</p>
        </div>

        <!-- 评论 -->
        <div ref="cmtRef" class="body">
          <div class="m-sec-title">评论<span v-if="comments.length"> ({{ comments.length }})</span></div>
          <div class="m-card">
            <div class="cmt-input">
              <textarea v-model="commentText" rows="2" maxlength="500" placeholder="写下你的评论…"></textarea>
              <button class="m-btn sm" :disabled="sending" @click="sendComment">{{ sending ? '发送中…' : '发送' }}</button>
            </div>
            <div v-if="!comments.length" class="cmt-empty">还没有评论，来说两句吧</div>
            <div v-for="c in comments" :key="c.id" class="cmt">
              <div class="cmt-top">
                <span class="cmt-name">{{ c.username || c.userName || '匿名' }}</span>
                <span class="cmt-time">{{ fmtDate(c.createdAt) }}</span>
              </div>
              <div class="cmt-text">{{ c.content }}</div>
              <div class="cmt-acts">
                <button class="cmt-act" :class="{ on: commentLikes.includes(Number(c.id)) }"
                        type="button" @click="likeComment(c)">
                  <el-icon :size="14"><component :is="commentLikes.includes(Number(c.id)) ? 'StarFilled' : 'Star'" /></el-icon>
                  <span>{{ c.likes || 0 }}</span>
                </button>
                <button v-if="isMine(c)" class="cmt-act" type="button" @click="removeComment(c.id)">删除</button>
              </div>
            </div>
          </div>
        </div>

        <div style="height:90px"></div>
      </template>

      <div v-else class="m-empty">
        <span class="ico"><el-icon :size="30"><Document /></el-icon></span>
        <div>文档不存在或无权访问</div>
        <button class="m-btn sm" style="margin-top:8px" @click="router.back()">返回</button>
      </div>
    </div>

    <!-- 底部操作条：点赞 + 收藏 + 评论 -->
    <div v-if="doc" class="m-actionbar pv-bar">
      <button class="bar-btn" :class="{ on: liked }" @click="onLike">
        <el-icon :size="19"><component :is="liked ? 'StarFilled' : 'Star'" /></el-icon>
        <span>{{ likeCount || '点赞' }}</span>
      </button>
      <button class="bar-btn" :class="{ on: bookmarked }" @click="doFav">
        <el-icon :size="19"><component :is="bookmarked ? 'StarFilled' : 'Star'" /></el-icon>
        <span>{{ bookmarked ? '已收藏' : '收藏' }}</span>
      </button>
      <button class="bar-btn" type="button" @click="scrollToComments">
        <el-icon :size="19"><ChatDotRound /></el-icon>
        <span>{{ comments.length || '评论' }}</span>
      </button>
    </div>

    <!-- 更多操作面板 -->
    <transition name="sheet-up">
      <div v-if="showMore" class="m-sheet-mask" @click="showMore = false">
        <div class="m-sheet" @click.stop>
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">更多操作</div>
          <button class="m-sheet-item" @click="goEdit"><span class="mi"><el-icon><EditPen /></el-icon></span>编辑这篇文档</button>
          <button class="m-sheet-item" @click="doFav">
            <span class="mi"><el-icon><component :is="bookmarked ? 'StarFilled' : 'Star'" /></el-icon></span>
            {{ bookmarked ? '取消收藏' : '收藏（稍后读）' }}
          </button>
          <button class="m-sheet-item" @click="exportAs('word')"><span class="mi"><el-icon><Download /></el-icon></span>导出为 Word</button>
          <button class="m-sheet-item" @click="exportAs('md')"><span class="mi"><el-icon><Notebook /></el-icon></span>导出为 Markdown</button>
          <button class="m-sheet-item" @click="doShare"><span class="mi"><el-icon><Share /></el-icon></span>分享 / 发送给…</button>
          <button class="m-sheet-item" @click="doCopyLink"><span class="mi"><el-icon><Link /></el-icon></span>复制链接</button>
          <button class="m-sheet-item" @click="doQr"><span class="mi"><el-icon><Picture /></el-icon></span>分享二维码</button>
          <button class="m-sheet-item" @click="doVersions"><span class="mi"><el-icon><Clock /></el-icon></span>历史版本</button>
          <button class="m-sheet-item cancel" @click="showMore = false">取消</button>
        </div>
      </div>
    </transition>

    <!-- 二维码 -->
    <div v-if="qrSrc" class="qr-mask" @click="qrSrc = ''">
      <div class="qr-box" @click.stop>
        <div class="qr-t">{{ doc?.title || '分享文档' }}</div>
        <img :src="qrSrc" alt="二维码" />
        <div class="qr-d">用手机扫码打开（需登录且有访问权限）</div>
        <button class="qr-btn" @click="doCopyLink">复制链接</button>
      </div>
    </div>

    <!-- 图片灯箱：点正文图片全屏预览，点任意处关闭 -->
    <div v-if="lightboxSrc" class="kb-lightbox" @click="lightboxSrc = ''">
      <img :src="lightboxSrc" alt="" @click.stop />
      <button class="lb-x" type="button" @click="lightboxSrc = ''">×</button>
    </div>
  </div>
</template>

<style scoped>
.dv { height: 100%; display: flex; flex-direction: column; }
.body { padding: 0 18px; }

.dv-head {
  position: sticky; top: 0; z-index: 30; flex: none;
  display: flex; align-items: center; gap: 6px;
  padding: calc(env(safe-area-inset-top) + 8px) 8px 8px;
  background: var(--m-bg);
  transition: background-color .2s, box-shadow .2s;
}
.dv-head.solid { background: var(--m-card); box-shadow: 0 1px 0 var(--m-border); }
.dv-title {
  flex: 1 1 auto; min-width: 0; text-align: center;
  font-size: 15.5px; font-weight: 600;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  opacity: 0; transition: opacity .2s;
}
.dv-title.show { opacity: 1; }

.dv-scroll { flex: 1 1 auto; min-height: 0; overflow-y: auto; -webkit-overflow-scrolling: touch; }

/* 底部条：收藏按钮 + 编辑按钮 */
.bar { display: flex; align-items: center; gap: 10px; }
.bar .m-btn { flex: 1 1 auto; }
.bar-fav {
  flex: none; width: 48px; height: 48px; border-radius: 12px; cursor: pointer;
  border: 1px solid var(--m-border); background: var(--m-card); color: var(--m-text-dim);
  display: inline-flex; align-items: center; justify-content: center;
}
.bar-fav.on { color: var(--m-warn); border-color: var(--m-warn); }

/* 更多面板里的图标 */
.mi { display: inline-flex; width: 22px; color: var(--m-text-sub); }

/* 二维码弹层 */
.qr-mask { position: fixed; inset: 0; z-index: 2500; background: var(--m-mask); display: grid; place-items: center; padding: 24px; }
.qr-box {
  background: var(--m-card); border-radius: 16px; padding: 20px 20px 18px;
  text-align: center; width: 100%; max-width: 300px;
}
.qr-t { font-size: 15px; font-weight: 600; color: var(--m-text); margin-bottom: 14px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.qr-box img { width: 200px; height: 200px; display: block; margin: 0 auto; border-radius: 8px; }
.qr-d { margin-top: 12px; font-size: 12px; color: var(--m-text-dim); line-height: 1.6; }
.qr-btn {
  margin-top: 14px; width: 100%; height: 40px; border-radius: 10px; border: none;
  background: var(--m-primary); color: #fff; font-size: 14.5px; cursor: pointer;
}
.dv-h1 { font-size: 23px; line-height: 1.35; margin: 4px 0 8px; letter-spacing: -.4px; }
.dv-meta { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--m-text-dim); margin-bottom: 18px; }
/* 图块提示条 */
.dv-blocks {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  margin: -8px 0 16px; padding: 9px 12px; border-radius: 10px;
  background: var(--m-card-2); border: 1px solid var(--m-border);
}
.dv-blocks-t { flex: 1 1 auto; font-size: 12.5px; color: var(--m-text-dim); }
.dv-blocks-btn {
  flex: none; border: none; border-radius: 8px; padding: 6px 12px;
  background: var(--m-primary); color: #fff; font-size: 12.5px; cursor: pointer;
}

/* 图片灯箱（.kb-video-bar 样式在全局 responsive.css，v-html 动态节点吃不到 scoped） */
.kb-lightbox {
  position: fixed; inset: 0; z-index: 3000;
  background: rgba(0, 0, 0, .92);
  display: grid; place-items: center;
}
.kb-lightbox img { max-width: 100%; max-height: 100%; object-fit: contain; }
.kb-lightbox .lb-x {
  position: absolute; top: calc(env(safe-area-inset-top) + 12px); right: 14px;
  width: 38px; height: 38px; border-radius: 50%; border: none;
  background: rgba(255, 255, 255, .16); color: #fff; font-size: 20px;
}

/* 元信息分隔点 */
.dv-meta .dot { opacity: .5; }

/* 底部条：点赞 / 收藏 / 评论 */
.pv-bar { gap: 10px; }
.bar-btn {
  flex: 1 1 0; height: 44px; border-radius: 12px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; gap: 6px;
  border: 1px solid var(--m-border); background: var(--m-card); color: var(--m-text-sub); font-size: 14.5px;
}
.bar-btn.on { color: var(--m-warn); border-color: var(--m-warn); background: var(--m-card); }

/* 关注作者 */
.follow-mini {
  margin-left: 6px; padding: 2px 9px; border-radius: 999px;
  border: 1px solid var(--m-primary); background: transparent; color: var(--m-primary);
  font-size: 11.5px; cursor: pointer;
}
.follow-mini.on { border-color: var(--m-border); color: var(--m-text-dim); }

/* 评论区 */
.m-sec-title { padding: 0 0 8px; font-size: 15px; font-weight: 600; color: var(--m-text); }
.cmt-input { padding: 12px; display: flex; flex-direction: column; gap: 8px; align-items: flex-end; }
.cmt-input textarea {
  width: 100%; box-sizing: border-box; resize: none; padding: 9px 10px;
  border: 1px solid var(--m-border); border-radius: 8px; background: var(--m-card-2);
  color: var(--m-text); font-size: 14.5px; font-family: inherit;
}
.cmt-empty { padding: 4px 12px 14px; font-size: 13px; color: var(--m-text-dim); text-align: center; }
.cmt { padding: 10px 12px; border-top: 1px solid var(--m-border); }
.cmt-top { display: flex; align-items: baseline; gap: 8px; }
.cmt-name { font-size: 13.5px; font-weight: 600; color: var(--m-text); }
.cmt-time { font-size: 11.5px; color: var(--m-text-dim); }
.cmt-text { margin-top: 4px; font-size: 14px; line-height: 1.65; color: var(--m-text-sub); white-space: pre-wrap; word-break: break-word; }
.cmt-acts { margin-top: 6px; display: flex; gap: 14px; }
.cmt-act {
  display: inline-flex; align-items: center; gap: 4px; border: none; background: transparent;
  color: var(--m-text-dim); font-size: 12.5px; cursor: pointer; padding: 0;
}
.cmt-act.on { color: var(--m-warn); }
</style>
