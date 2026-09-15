<script setup>
import { ref, reactive, computed, nextTick, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../store/auth'
import { useHistoryStore } from '../store/history'
import { openReader } from '../store/reader'
import { getDoc, getComments, addComment, deleteComment, myCommentLikes, toggleCommentLike, toggleFavorite, myFavoriteIds, toggleLike, myLikeIds, getUserCard, followUser, unfollowUser, getUserArticles, exportDoc, getRelatedDocs, subscribeDoc, unsubscribeDoc, getDocSubscription, docBreadcrumb, checkDocBanned } from '../api/kb'
import { approveDoc, rejectDoc } from '../api/admin'
import { fromNow, fmtDateTime } from '../utils/time'
import { getLevel } from '../utils/level'
import EmojiPanel from '../components/EmojiPanel.vue'
import TocNode from '../components/TocNode.vue'
import WornBadgeStrip from '../components/WornBadgeStrip.vue'
import { splitTags } from '../utils/text'
import { errMsg } from '../utils/errMsg'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const history = useHistoryStore()

const doc = ref(null)
// 文档面包屑：从最顶层祖先到当前文档（含自身）的链路
const crumbs = ref([])
// 审核工具条：一键违禁词检查 + 正文高亮
const bannedHits = ref([])
const checking = ref(false)

const escapeReg = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')

/** 一键检查：对正文纯文本跑违禁词匹配，命中则在正文文本节点上包 <mark> 高亮 */
const checkBanned = async () => {
  if (!doc.value) return
  checking.value = true
  try {
    const { data } = await checkDocBanned(doc.value.id)
    bannedHits.value = data.hits || []
    if (!bannedHits.value.length) {
      clearHighlight()
      ElMessage.success('未检测到违禁词')
      return
    }
    ElMessage.warning(`发现 ${bannedHits.value.length} 个疑似违禁词，已在正文中高亮`)
    await nextTick()
    highlightBanned()
  } catch (e) {
    ElMessage.error(errMsg(e, '检查失败'))
  } finally {
    checking.value = false
  }
}

/** 在 contentRef 文本节点中把命中词包成 <mark class="banned-hl">（保留原有 DOM 结构） */
const highlightBanned = () => {
  const root = contentRef.value
  if (!root || !bannedHits.value.length) return
  const terms = bannedHits.value.filter(Boolean).map(escapeReg).sort((a, b) => b.length - a.length)
  if (!terms.length) return
  const re = new RegExp('(' + terms.join('|') + ')', 'g')
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      if (!node.textContent || !node.textContent.trim()) return NodeFilter.FILTER_REJECT
      if (node.parentNode && node.parentNode.nodeName === 'MARK') return NodeFilter.FILTER_REJECT
      return NodeFilter.FILTER_ACCEPT
    }
  })
  const targets = []
  let n
  while ((n = walker.nextNode())) targets.push(n)
  for (const textNode of targets) {
    const text = textNode.textContent
    re.lastIndex = 0
    let m, last = 0, matched = false
    const frag = document.createDocumentFragment()
    while ((m = re.exec(text))) {
      matched = true
      if (m.index > last) frag.appendChild(document.createTextNode(text.slice(last, m.index)))
      const mark = document.createElement('mark')
      mark.className = 'banned-hl'
      mark.textContent = m[0]
      frag.appendChild(mark)
      last = m.index + m[0].length
    }
    if (matched) {
      if (last < text.length) frag.appendChild(document.createTextNode(text.slice(last)))
      textNode.parentNode.replaceChild(frag, textNode)
    }
  }
}

/** 清除高亮：把 <mark> 还原为纯文本 */
const clearHighlight = () => {
  const root = contentRef.value
  if (!root) return
  root.querySelectorAll('mark.banned-hl').forEach((m) => {
    const parent = m.parentNode
    if (!parent) return
    parent.replaceChild(document.createTextNode(m.textContent), m)
    parent.normalize()
  })
  bannedHits.value = []
}

// 在桌面弹出真实浏览器窗口独立阅读（window.open 带名字，同一文章不重复开，只聚焦已有窗口）
const openStandalone = () => {
  if (!doc.value) return
  const url = `${location.origin}/reader/${doc.value.id}`
  window.open(url, 'kb_reader_' + doc.value.id, 'width=480,height=680')
}
const comments = ref([])
const loading = ref(false)
const posting = ref(false)
const commentInputRef = ref(null)
const commentSort = ref('latest')          // 'latest' 最新 | 'hot' 最热（点赞数）
const commentLikedIds = ref(new Set())     // 我点过赞的评论 id 集合（标记点赞填充态）
const expandedReplies = reactive({})       // 顶层评论 id -> 是否展开全部楼中楼（默认仅显示前 3 条）

// 文章「小窗阅读」浮窗已提升为全局单例（见 ../store/reader），在 App.vue 渲染、随路由切换而保留；
// 这里只负责把当前正文喂给它打开。

// 楼中楼：把扁平评论按 parentId 建成树（最多两层，回复统一挂到顶层评论）
const replyTo = ref(null) // 当前正在回复的顶层评论 { id, name }
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
// 评论排序：按选择的维度对顶层评论排序（楼中楼回复始终按时间正序，在 commentTree 内已处理）
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

// 评论输入框用 contenteditable，表情以真实图片显示（所见即所得，不再出现 [e:xxx] 占位文本）
const commentEmpty = ref(true)
const syncEmpty = () => {
  const el = commentInputRef.value
  if (!el) return
  commentEmpty.value = !el.textContent.trim() && !el.querySelector('img')
}
// 粘贴只取纯文本，避免带入外部样式/脚本
const onPaste = (e) => {
  e.preventDefault()
  const t = (e.clipboardData || window.clipboardData).getData('text/plain')
  document.execCommand('insertText', false, t)
  syncEmpty()
}
// 表情面板：插入到评论框当前光标处（主评论与回复复用同一输入框）
const insertEmoji = (p) => {
  const el = commentInputRef.value
  if (!el) return
  el.focus()
  // 光标不在输入框内时（例如刚点开面板），补一个末尾光标
  const sel = window.getSelection()
  if (!sel.rangeCount || !el.contains(sel.getRangeAt(0).commonAncestorContainer)) {
    const r = document.createRange()
    r.selectNodeContents(el)
    r.collapse(false)
    sel.removeAllRanges()
    sel.addRange(r)
  }
  if (p.kind === 'img') {
    document.execCommand('insertHTML', false,
      `<img class="kb-emoji" src="/emojis/${p.name}.png" alt="表情">`)
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

// 文章目录 TOC：解析正文（真实 h1~h6 标题 + 编号式段落），注入锚点 id 并生成可折叠的嵌套导航树
const toc = ref([]) // 嵌套树：{ id, text, level, children:[], open:true }
const tocOpen = ref(true) // 整块目录默认展开；点标题按钮可收起，目录不再常驻占高
const renderedContent = ref('')
const activeToc = ref('')
// 正文容器 ref（用于渲染后注入代码复制按钮 / 图片灯箱）
const contentRef = ref(null)
// 图片灯箱当前放大图
const lightboxSrc = ref('')

// 复制文本：优先 Clipboard API，非安全上下文（http）回退到 execCommand
const copyText = async (text) => {
  try {
    if (navigator.clipboard && window.isSecureContext) { await navigator.clipboard.writeText(text); return }
  } catch (e) {}
  const ta = document.createElement('textarea')
  ta.value = text
  ta.style.position = 'fixed'
  ta.style.opacity = '0'
  document.body.appendChild(ta)
  ta.select()
  try { document.execCommand('copy') } catch (e) {}
  document.body.removeChild(ta)
}

// 判断颜色是否接近纯黑（rgb(0,0,0) / #000 / black），用于清理富文本残留的黑色内联字色
const isNearBlack = (color) => {
  if (!color) return false
  const c = color.trim().toLowerCase()
  if (c === 'black' || c === '#000' || c === '#000000') return true
  const rgb = c.match(/^rgba?\(([^)]+)\)$/)
  if (rgb) {
    const [r, g, b] = rgb[1].split(',').map(v => parseFloat(v.trim()))
    if (Number.isFinite(r) && Number.isFinite(g) && Number.isFinite(b)) return r < 30 && g < 30 && b < 30
  }
  const hex3 = c.match(/^#([0-9a-f])([0-9a-f])([0-9a-f])$/)
  if (hex3) {
    return parseInt(hex3[1] + hex3[1], 16) < 30 && parseInt(hex3[2] + hex3[2], 16) < 30 && parseInt(hex3[3] + hex3[3], 16) < 30
  }
  const hex6 = c.match(/^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/)
  if (hex6) {
    return parseInt(hex6[1], 16) < 30 && parseInt(hex6[2], 16) < 30 && parseInt(hex6[3], 16) < 30
  }
  return false
}

// 正文渲染后：清理纯黑内联字色、给每个 <pre> 加「复制」按钮、给 <img>（非表情）绑定点击放大
const decorateContent = async () => {
  await nextTick()
  const el = contentRef.value
  if (!el) return
  // 富文本残留 color: rgb(0,0,0) 等纯黑字色：暗色模式下会看不见，改成继承正文色（随主题切换）
  el.querySelectorAll('[style]').forEach((node) => {
    if (isNearBlack(node.style.color)) node.style.color = ''
  })
  el.querySelectorAll('pre').forEach((pre) => {
    if (pre.querySelector('.code-copy')) return
    const code = pre.querySelector('code') ? pre.querySelector('code').textContent : pre.textContent
    const btn = document.createElement('button')
    btn.type = 'button'
    btn.className = 'code-copy'
    btn.textContent = '复制'
    btn.addEventListener('click', (e) => {
      e.stopPropagation()
      copyText(code)
      btn.textContent = '已复制'
      setTimeout(() => { btn.textContent = '复制' }, 1400)
    })
    pre.appendChild(btn)
  })
  el.querySelectorAll('img').forEach((img) => {
    if (img.classList.contains('kb-emoji')) return
    // 屏幕外的图不解码进 GPU 内存：长文多图时显著省内存、防老机卡顿
    img.loading = 'lazy'
    img.decoding = 'async'
    img.style.cursor = 'zoom-in'
    const srcAttr = img.getAttribute('src') || ''
    // 本站上传图按容器宽度请求缩略图（后端等比缩放并重编码），避免老机解码数兆像素原图
    if (!img.dataset.thumb) {
      let u = null
      try { u = new URL(srcAttr, location.origin) } catch (e) { u = null }
      if (u && u.pathname.startsWith('/uploads/')) {
        const rel = u.pathname.slice('/uploads/'.length)
        const W = Math.max(320, Math.min(1280, Math.ceil((contentRef.value?.clientWidth || 900) / 80) * 80))
        img.dataset.full = srcAttr // 灯箱仍用原图
        img.dataset.thumb = '1'
        img.src = '/api/thumb?src=' + encodeURIComponent(rel) + '&w=' + W
      }
    }
    if (!img.dataset.full) img.dataset.full = srcAttr
    img.addEventListener('click', () => { lightboxSrc.value = img.dataset.full || srcAttr })
  })
}
// 把树压平成有序数组（用于滚动时高亮当前章节）
const tocFlat = computed(() => {
  const out = []
  const walk = (arr) => { for (const n of arr) { out.push(n); if (n.children && n.children.length) walk(n.children) } }
  walk(toc.value)
  return out
})
const processContent = (html) => {
  if (!html) { renderedContent.value = ''; toc.value = []; return }
  const div = document.createElement('div')
  div.innerHTML = html
  // 目录候选来源：
  //  ① 真实的 h1~h6 标题（点过 wangEditor「标题」样式）
  //  ② 以编号开头的段落（很多人不点标题样式，直接打字写章节：一、项目概述 / 1.1 方案优势 / 1.1.1 细节）
  //     后者按编号推断层级：一、→1，1.1→2，1.1.1→3…，层级越深缩进越多
  const isHeadingTag = (t) => /^h[1-6]$/.test(t)
  const pseudoLevel = (t) => {
    // 中文数字顶层：一、二、…（覆盖到千/百等）
    if (/^[一二三四五六七八九十百千零两]+、/.test(t)) return 1
    // 大纲数字：1.1 / 2.3.4（至少一个点，且后面跟空格或中文/冒号，避免误伤「1.1GB」这类正文）
    const m = /^(\d+(?:\.\d+)+)(?=[\s\u4e00-\u9fa5：:])/.exec(t)
    if (m) return Math.min((m[1].match(/\./g) || []).length + 1, 6)
    return null
  }
  const candidates = []
  Array.from(div.querySelectorAll('h1, h2, h3, h4, h5, h6, p')).forEach((el) => {
    const tag = el.tagName.toLowerCase()
    const text = (el.textContent || '').trim()
    if (!text) return
    let level = null
    if (isHeadingTag(tag)) level = Number(tag.substring(1))
    else if (tag === 'p') level = pseudoLevel(text)
    if (level == null) return
    const id = 'toc-h-' + candidates.length
    el.id = id
    candidates.push({ id, text, level, children: [], open: true })
  })
  // 按文档顺序构建可折叠的嵌套树（大标题包裹小标题）
  const tree = []
  const stack = []
  candidates.forEach((node) => {
    while (stack.length && stack[stack.length - 1].level >= node.level) stack.pop()
    if (stack.length) stack[stack.length - 1].children.push(node)
    else tree.push(node)
    stack.push(node)
  })
  renderedContent.value = div.innerHTML
  toc.value = tree
}
const goToc = (id) => {
  const h = document.getElementById(id)
  if (h) h.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
const favorited = ref(false)
const liked = ref(false)
// 作者卡片
const authorCard = ref(null)
const authorArticles = ref([])
// 作者等级（帖子王主题，由卡片返回的积分计算）
const authorLevel = computed(() => authorCard.value ? getLevel(authorCard.value.points || 0) : null)
// 作者佩戴的勋章 id 列表（最多 3 枚，后端 getCard 返回 wornBadges）
const authorWornIds = computed(() => authorCard.value?.wornBadges || [])
const loadAuthorCard = async (ownerId) => {
  if (!ownerId) return
  try { authorCard.value = (await getUserCard(ownerId)).data } catch (e) {}
  try { authorArticles.value = (await getUserArticles(ownerId, 0, 5)).data.content || [] } catch (e) {}
}
const toggleFollow = async () => {
  if (!auth.loggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  const card = authorCard.value
  if (!card) return
  if (auth.user && card.id === auth.user.id) {
    ElMessage.warning('不能关注自己')
    return
  }
  try {
    if (card.isFollowing) {
      await unfollowUser(card.id)
      card.isFollowing = false
      card.fans = Math.max(0, card.fans - 1)
    } else {
      await followUser(card.id)
      card.isFollowing = true
      card.fans = card.fans + 1
    }
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}
// 返回顶部：滚动超过一屏后显示
// 注意：本应用布局是 el-container(height:100vh)，真正滚动的是内部 .el-main（class="main"），而非 window
// 所以必须监听 .main 的 scroll 事件、读取它的 scrollTop，否则 window.scrollY 恒为 0、图标永不出现
const showTop = ref(false)
let scrollTarget = null
const getMain = () => document.querySelector('.main')
const onScroll = () => {
  const el = getMain()
  const y = el ? el.scrollTop : (window.scrollY || document.documentElement.scrollTop || 0)
  showTop.value = y > 300
  // TOC 高亮当前章节
  if (tocFlat.value.length) {
    let cur = tocFlat.value[0].id
    for (const t of tocFlat.value) {
      const h = document.getElementById(t.id)
      if (h && h.getBoundingClientRect().top <= 160) cur = t.id
    }
    activeToc.value = cur
  }
}
const goTop = () => {
  const el = getMain()
  if (el) el.scrollTo({ top: 0, behavior: 'smooth' })
  else window.scrollTo({ top: 0, behavior: 'smooth' })
}

// 管理员审核（仅对非发布状态的帖子显示）
const reviewing = ref(false)
const onApprove = async () => {
  reviewing.value = true
  try {
    await approveDoc(doc.value.id)
    doc.value.status = 'PUBLISHED'
    ElMessage.success('已通过审核并发布')
  } catch (e) { ElMessage.error('审核失败') } finally { reviewing.value = false }
}
const onReject = async () => {
  let reason = ''
  try {
    const r = await ElMessageBox.prompt('请填写驳回理由（将退回作者草稿，作者可见，必填）', '驳回审核',
      { confirmButtonText: '确定驳回', cancelButtonText: '取消', inputType: 'textarea',
        inputPlaceholder: '例如：内容不完整 / 含敏感词 / 需补充来源',
        inputValidator: (v) => (v && v.trim()) ? true : '请填写驳回理由后再提交' })
    reason = (r && r.value) ? r.value.trim() : ''
  } catch {
    return
  }
  reviewing.value = true
  try {
    await rejectDoc(doc.value.id, reason)
    doc.value.status = 'DRAFT'
    ElMessage.success('已驳回，退回作者草稿')
  } catch (e) { ElMessage.error('操作失败') } finally { reviewing.value = false }
}

// 相关文档 & 文档订阅
const relatedDocs = ref([])
const subscribed = ref(false)
const subCount = ref(0)

const loadRelated = async () => {
  try {
    const { data } = await getRelatedDocs(route.params.id, 6)
    relatedDocs.value = data || []
  } catch (e) { relatedDocs.value = [] }   // 推荐失败不影响正文与评论
}

const loadSubscription = async () => {
  try {
    const { data } = await getDocSubscription(route.params.id)
    subscribed.value = !!data?.subscribed
    subCount.value = data?.count || 0
  } catch (e) { subscribed.value = false; subCount.value = 0 }
}

const onToggleSubscribe = async () => {
  if (!auth.loggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  try {
    const { data } = await (subscribed.value ? unsubscribeDoc(route.params.id) : subscribeDoc(route.params.id))
    subscribed.value = !!data?.subscribed
    subCount.value = data?.count || 0
    ElMessage.success(subscribed.value ? '已订阅，文档更新时会通知你' : '已取消订阅')
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

const load = async () => {
  loading.value = true
  try {
    const { data } = await getDoc(route.params.id)
    doc.value = data
    // 面包屑：层级链路（根→…→父→自身），平铺文档无 parent 时返回空，不展示
    try {
      crumbs.value = (await docBreadcrumb(route.params.id)).data || []
    } catch (e) {
      crumbs.value = []
    }
    processContent(data.content)
    decorateContent()
    loadRelated()
    loadSubscription()
    // 记录浏览历史（localStorage，按本机存储）
    history.add({ id: data.id, title: data.title, category: data.category, author: data.authorName || data.ownerName || '', views: data.views })
    loadAuthorCard(data.ownerId)
    comments.value = (await getComments(route.params.id)).data || []
    focusCommentFromQuery()   // 数据面板「点击直达」：?c=评论id → 滚动定位并高亮
    if (auth.loggedIn) {
      try {
        favorited.value = ((await myFavoriteIds()).data || []).includes(Number(route.params.id))
      } catch (e) {}
      try {
        liked.value = ((await myLikeIds()).data || []).includes(Number(route.params.id))
      } catch (e) {}
      try {
        commentLikedIds.value = new Set((await myCommentLikes()).data || [])
      } catch (e) {}
    }
  } catch (e) {
    ElMessage.error('文档不存在或已下架')
    router.back()
  } finally {
    loading.value = false
  }
}

const onToggleFav = async () => {
  if (!auth.loggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  try {
    const { data } = await toggleFavorite(route.params.id)
    favorited.value = data.favorited
    ElMessage.success(data.favorited ? '已收藏' : '已取消收藏')
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

const onToggleLike = async () => {
  if (!auth.loggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  try {
    const { data } = await toggleLike(route.params.id)
    liked.value = data.liked
    if (doc.value) doc.value.likes = data.likes
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

// 切换某条评论的点赞：更新计数与本地点赞态
const onToggleCommentLike = async (c) => {
  if (!auth.loggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
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
  if (!auth.loggedIn) {
    ElMessage.warning('请先登录')
    router.push('/login')
    return
  }
  const el = commentInputRef.value
  if (!el) return
  const html = el.innerHTML.trim()
  // 空内容判断：既没文字也没表情图则不提交
  if (!el.textContent.trim() && !el.querySelector('img')) return
  posting.value = true
  try {
    const r = await addComment(route.params.id, html, replyTo.value ? replyTo.value.id : null)
    // 业务性失败（内容为空/敏感词）：后端返回 200 + { ok:false, error }
    if (r?.data && r.data.ok === false) {
      ElMessage.error(r.data.error || '评论失败')
      return
    }
    clearCommentBox()
    replyTo.value = null
    comments.value = (await getComments(route.params.id)).data || []
    ElMessage.success('评论成功')
  } catch (e) {
    ElMessage.error(errMsg(e, '评论失败'))
  } finally {
    posting.value = false
  }
}

// 仅评论作者本人或管理员可删除
const canDeleteComment = (c) =>
  auth.loggedIn && !!auth.user && (c.username === auth.user.username || auth.isAdmin)

const onDeleteComment = async (c) => {
  try {
    await ElMessageBox.confirm('确定删除这条评论吗？此操作不可撤销。', '删除评论', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger'
    })
  } catch (e) {
    return // 用户取消
  }
  try {
    await deleteComment(c.id)
    comments.value = comments.value.filter(x => x.id !== c.id)
    ElMessage.success('评论已删除')
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}

// 导出 Word：后端返回 HTML 封装的 .doc（中文无乱码，Word/WPS 可打开）
const exportWord = async () => {
  if (!doc.value) return
  try {
    const { data } = await exportDoc(doc.value.id, 'word')
    const blob = new Blob([data], { type: 'application/msword' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = (doc.value.title || 'document').replace(/[\\/:*?"<>|]+/g, '_').slice(0, 80) + '.doc'
    document.body.appendChild(a); a.click(); a.remove()
    URL.revokeObjectURL(url)
    ElMessage.success('已导出 Word（.doc）')
  } catch (e) {
    ElMessage.error(errMsg(e, '导出失败'))
  }
}

// 导出 PDF：纯前端 window.print，把正文装进独立打印窗口（CJK 排版最佳、零依赖）
const exportPdf = () => {
  if (!doc.value) return
  const w = window.open('', '_blank', 'width=900,height=700')
  if (!w) { ElMessage.warning('请允许浏览器弹出窗口以导出 PDF'); return }
  const safeTitle = (doc.value.title || '').replace(/[<>]/g, '')
  const html = renderedContent.value || ''
  w.document.open()
  w.document.write(
    '<!DOCTYPE html><html><head><meta charset="utf-8"><title>' + safeTitle + '</title>' +
    '<style>@page{margin:16mm}body{font-family:"Microsoft YaHei","SimSun",sans-serif;line-height:1.8;font-size:14px;color:#222;max-width:780px;margin:0 auto;padding:24px 8px}' +
    'h1{font-size:24px;text-align:center;margin:0 0 10px}h2{font-size:20px;margin:18px 0 6px}h3{font-size:17px;margin:14px 0 4px}' +
    'img{max-width:100%}table{border-collapse:collapse;width:100%;margin:12px 0}td,th{border:1px solid #ccc;padding:6px 10px}' +
    'pre{background:#1e293b;color:#e2e8f0;padding:12px;border-radius:6px;white-space:pre-wrap;word-break:break-all;font-size:13px}' +
    'code{font-family:Consolas,monospace;background:#f1f5f9;padding:1px 5px;border-radius:4px}blockquote{border-left:3px solid #a5b4fc;background:#f8faff;margin:10px 0;padding:8px 16px}' +
    'a{color:#4f46e5}</style></head><body><h1>' + safeTitle + '</h1>' + html + '</body></html>'
  )
  w.document.close()
  w.focus()
  setTimeout(() => { try { w.print() } catch (e) {} }, 400)
}

// 文档归属者或管理员可查看版本历史
const canManageDoc = computed(() => auth.loggedIn && doc.value &&
  (auth.isAdmin || (auth.user && doc.value.ownerId === auth.user.id)))

// 拆分标签
// 点击标签跳首页按该标签筛选
const goTag = (t) => router.push({ path: '/', query: { tag: t } })
// 点击「他的全部文章」跳首页按作者筛选
const goAuthor = (id) => router.push({ path: '/', query: { author: id } })
// 进入他人视角个人主页（资料 / 文章 / 关注 / 粉丝）
const goUser = (id) => router.push('/user/' + id)
// 去评论：平滑滚动到评论区
const goComment = () => {
  const el = document.getElementById('comment-section')
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

// 站内从本页跳另一篇文章（如右侧「他的文章」）时，路由只变 params、组件被复用，
// onMounted 不会再执行 —— 必须监听 id 变化重新加载，否则点了看着"没反应"。
watch(() => route.params.id, (nid, old) => {
  if (!nid || nid === old) return
  // 重置上一篇的局部状态，避免串数据
  doc.value = null
  comments.value = []
  authorCard.value = null
  authorArticles.value = []
  toc.value = []
  activeToc.value = ''
  replyTo.value = null
  clearCommentBox()
  load()
  decorateContent()
  const m = getMain()
  if (m) m.scrollTo({ top: 0 })
})

// ESC 关闭图片灯箱
const onKeydown = (e) => { if (e.key === 'Escape') lightboxSrc.value = '' }

/**
 * 直达单条评论：数据面板「实时动态」点击后跳到 /post/:id?c=评论id。
 * 评论是异步加载的，等 DOM 出现后再滚动定位并短暂高亮；
 * 若目标是折叠在「展开更多回复」里的子评论，先展开它的父楼。
 */
const flashCommentId = ref(null)
let focusTimer = null
async function focusCommentFromQuery() {
  const cid = Number(route.query.c)
  if (!cid) return
  let waited = 0
  while (waited < 3000) {
    await nextTick()
    const el = document.getElementById('pc' + cid)
    if (el) {
      const owner = comments.value.find((x) => (x.replies || []).some((r) => r.id === cid))
      if (owner && !expandedReplies[owner.id]) {
        expandedReplies[owner.id] = true
        await nextTick()
      }
      const target = document.getElementById('pc' + cid) || el
      target.scrollIntoView({ behavior: 'smooth', block: 'center' })
      flashCommentId.value = cid
      clearTimeout(focusTimer)
      focusTimer = setTimeout(() => { flashCommentId.value = null }, 2200)
      return
    }
    await new Promise((r) => setTimeout(r, 120))
    waited += 120
  }
}

onMounted(() => {
  load()
  scrollTarget = getMain() || window
  scrollTarget.addEventListener('scroll', onScroll, { passive: true })
  window.addEventListener('keydown', onKeydown)
  onScroll()
})
onBeforeUnmount(() => {
  if (scrollTarget) scrollTarget.removeEventListener('scroll', onScroll)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<template>
  <div class="post" v-loading="loading">
    <el-page-header @back="router.back()" content="文档详情" />

    <template v-if="doc">
      <div class="post-body">
        <!-- 左侧操作栏：点赞 / 收藏 / 去评论 + 发布时间（悬浮贴左） -->
        <aside class="action-bar">
          <button class="act like" :class="{ on: liked }" @click="onToggleLike" :title="liked ? '取消点赞' : '点赞'">
            <span class="ai"><svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path fill="currentColor" d="M2 21h2V9H2v12zm20-10.09c0-.58-.47-1.05-1.05-1.05H13.8l.94-4.57.03-.32c0-.41-.17-.79-.44-1.06L13.3 1.3 6.72 7.9A1.98 1.98 0 0 0 5.3 9.3v10c0 1.1.9 2 2 2h9c.83 0 1.54-.5 1.84-1.22l3.02-7.05c.09-.23.14-.47.14-.73v-1.91z"/></svg></span>
            <span class="at">{{ liked ? '已赞' : '点赞' }}</span>
            <span class="an">{{ doc.likes || 0 }}</span>
          </button>
          <button class="act fav" :class="{ on: favorited }" @click="onToggleFav" :title="favorited ? '取消收藏' : '收藏'">
            <span class="ai">★</span>
            <span class="at">{{ favorited ? '已收藏' : '收藏' }}</span>
          </button>
          <button class="act comment" @click="goComment" title="去评论">
            <span class="ai">💬</span>
            <span class="at">评论</span>
            <span class="an">{{ comments.length }}</span>
          </button>
          <button class="act top" v-show="showTop" @click="goTop" title="返回顶部">
            <span class="ai">↑</span>
            <span class="at">顶部</span>
          </button>
        </aside>

        <div class="post-main">
          <!-- 文档层级面包屑：上级 / … / 当前（文档树路径），仅当存在祖先时展示 -->
          <nav v-if="crumbs.length" class="crumbs">
            <router-link v-for="(c, i) in crumbs" :key="c.id" :to="c.id === doc.id ? '#' : ('/post/' + c.id)" class="crumb" :class="{ current: c.id === doc.id }">
              {{ c.title }}
              <span v-if="i < crumbs.length - 1" class="sep">/</span>
            </router-link>
          </nav>
          <div v-if="auth.isAdmin || (doc.ownerId && auth.user && doc.ownerId === auth.user.id)" class="doc-actions">
            <el-button size="small" @click="router.push('/write?parent=' + doc.id)">在该文档下新建子文档</el-button>
          </div>
          <h1 class="title">{{ doc.title }}</h1>

          <!-- 管理员审核条：仅对非发布状态（待审核/草稿/封禁）显示 -->
          <div class="review-bar" v-if="auth.isAdmin && doc.status && doc.status !== 'PUBLISHED'">
            <el-tag :type="doc.status === 'PENDING_REVIEW' ? 'warning' : (doc.status === 'BLOCKED' ? 'danger' : 'info')" size="small">
              {{ doc.status === 'BLOCKED' ? '已封禁' : (doc.status === 'PENDING_REVIEW' ? '待审核' : '草稿') }}
            </el-tag>
            <span class="tip">该文章尚未发布，仅你（管理员）可见预览</span>
            <el-button v-if="doc.status === 'PENDING_REVIEW'" size="small" type="success" :loading="reviewing" @click="onApprove">通过审核</el-button>
            <el-button v-if="doc.status === 'PENDING_REVIEW'" size="small" type="warning" :loading="reviewing" @click="onReject">驳回</el-button>
            <el-button size="small" type="danger" plain :loading="checking" @click="checkBanned">违禁词检查</el-button>
            <el-button v-if="bannedHits.length" size="small" @click="clearHighlight">清除高亮</el-button>
          </div>

          <div class="meta">
            <el-tag v-if="doc.category" size="small">{{ doc.category }}</el-tag>
            <el-tag v-for="t in splitTags(doc.tags)" :key="t" type="info" size="small"
                    class="lk" @click="goTag(t)">{{ t }}</el-tag>
            <span class="time" v-if="doc.updatedAt">更新于 {{ fromNow(doc.updatedAt) }}（{{ fmtDateTime(doc.updatedAt) }}）</span>
            <span class="stat">👁 {{ doc.views || 0 }}</span>
          </div>

      <!-- 富文本正文（后端已做 XSS 净化） -->
      <div class="content-bar">
        <button class="mini-reader-btn" type="button" @click="openStandalone" title="在桌面弹出独立窗口阅读（可单独最小化/移动）">⧉ 独立窗口</button>
        <button class="mini-reader-btn" type="button" @click="openReader(doc.title, renderedContent)" title="把文章拖到页面内小窗阅读（切页不关闭）">⊞ 小窗阅读</button>
        <button class="mini-reader-btn" type="button" v-if="canManageDoc" @click="router.push('/post/' + doc.id + '/versions')" title="查看/回滚历史版本">🕑 版本历史</button>
        <button class="mini-reader-btn" type="button" v-if="auth.hasPerm('DOC_EXPORT')" @click="exportWord" title="导出为 Word 文档（.doc）">⬇ Word</button>
        <button class="mini-reader-btn" type="button" v-if="auth.hasPerm('DOC_EXPORT')" @click="exportPdf" title="导出为 PDF（浏览器打印另存）">⬇ PDF</button>
        <button class="mini-reader-btn" type="button" :class="{ 'on': subscribed }" @click="onToggleSubscribe"
                :title="subscribed ? '取消订阅（文档更新时不再通知）' : '订阅该文档，更新时收到站内通知'">
          {{ subscribed ? '★ 已订阅' : '☆ 订阅' }}<span v-if="subCount > 0" class="sub-cnt">{{ subCount }}</span>
        </button>
      </div>
      <div class="content" ref="contentRef" v-html="renderedContent"></div>

      <!-- 相关文档推荐：同分类 + 标签重叠，避免内容孤岛 -->
      <div v-if="relatedDocs.length" class="related-box">
        <div class="related-title">相关文档</div>
        <div class="related-list">
          <div v-for="r in relatedDocs" :key="r.id" class="related-item" @click="router.push('/post/' + r.id)">
            <span class="related-name">{{ r.title }}</span>
            <span class="related-meta">
              <el-tag v-if="r.category" size="small" type="info">{{ r.category }}</el-tag>
              <span class="related-views">👁 {{ r.views || 0 }}</span>
            </span>
          </div>
        </div>
      </div>

      <el-divider />

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
        <!-- contenteditable：表情以图片形式所见即所得 -->
        <div ref="commentInputRef" class="cbox" :class="{ empty: commentEmpty }"
             contenteditable="true" data-ph="写下你的评论…"
             @input="syncEmpty" @paste="onPaste"></div>
        <el-button type="primary" :loading="posting" @click="submitComment" style="margin-top:8px">
          发表评论
        </el-button>
      </div>
      <el-empty v-else description="登录后参与评论">
        <el-button @click="router.push('/login')">去登录</el-button>
      </el-empty>

      <div class="ch-row" id="comment-section">
        <h3 class="ch">评论（{{ comments.length }}）</h3>
        <div class="csort">
          <button type="button" :class="{ on: commentSort === 'latest' }" @click="commentSort = 'latest'">最新</button>
          <button type="button" :class="{ on: commentSort === 'hot' }" @click="commentSort = 'hot'">最热</button>
        </div>
      </div>

      <ul class="clist" v-if="sortedComments.length">
        <li v-for="c in sortedComments" :key="c.id" class="citem" :id="'pc' + c.id" :class="{ 'cmt-flash': flashCommentId === c.id }">
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
            <li v-for="r in (expandedReplies[c.id] ? c.replies : c.replies.slice(0, 3))" :key="r.id" class="ritem" :id="'pc' + r.id" :class="{ 'cmt-flash': flashCommentId === r.id }">
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
      <el-empty v-else description="还没有评论，快来抢沙发" />
        </div><!-- /post-main -->

        <!-- 右侧栏：作者卡片 + 他的文章 + 文章目录 -->
        <aside class="post-side">
          <template v-if="authorCard">
            <div class="author-card">
            <div class="author-top" style="cursor:pointer">
              <div class="ava-wrap" @click.stop="goUser(authorCard.id)">
                <img v-if="authorCard.avatar" class="ava" :src="authorCard.avatar" alt="" />
                <div v-else class="ava none">{{ (authorCard.displayName || authorCard.username || '?').slice(0, 1) }}</div>
              </div>
              <div class="ainfo" @click="goUser(authorCard.id)">
                <div class="nm-row">
                  <span class="nm">{{ authorCard.displayName || authorCard.username }}</span>
                  <WornBadgeStrip v-if="authorWornIds.length" :badge-ids="authorWornIds" :size="20" />
                </div>
                <span class="lv-tag" v-if="authorLevel">Lv{{ authorLevel.level }} · {{ authorLevel.title }}</span>
                <div class="astats">
                  <span><b>{{ authorCard.original }}</b> 原创</span>
                  <span><b>{{ authorCard.likes }}</b> 点赞</span>
                  <span><b>{{ authorCard.favorites }}</b> 收藏</span>
                  <span><b>{{ authorCard.fans }}</b> 粉丝</span>
                </div>
              </div>
              <button v-if="auth.loggedIn && auth.user && authorCard && authorCard.id !== auth.user.id"
                      class="follow-btn" :class="{ on: authorCard.isFollowing }" @click.stop="toggleFollow">
                {{ authorCard.isFollowing ? '已关注' : '+ 关注' }}
              </button>
            </div>
          </div>

          <!-- 他的文章列表（CSDN 风格侧边栏） -->
          <div class="author-articles" v-if="authorArticles.length">
            <div class="aa-head">
              <span class="aa-title">他的文章</span>
              <span class="aa-all" @click="goUser(authorCard.id)">他的全部文章 ›</span>
            </div>
            <ul class="aa-list">
              <li v-for="a in authorArticles" :key="a.id" @click="router.push('/post/' + a.id)">
                <span class="aa-name">{{ a.title }}</span>
                <span class="aa-views">{{ a.views || 0 }}</span>
              </li>
            </ul>
          </div>
          </template>

          <!-- 文章目录：放在「他的文章」下方；标题是可折叠按钮，点一下展开/收起整块 -->
          <div class="toc" v-if="toc.length">
            <button class="toc-toggle" type="button" @click="tocOpen = !tocOpen" :aria-expanded="tocOpen">
              <span class="toc-h">目录</span>
              <span class="toc-caret" :class="{ open: tocOpen }">▸</span>
            </button>
            <ul class="toc-list" v-show="tocOpen">
              <TocNode v-for="n in toc" :key="n.id" :node="n" :active="activeToc" @go="goToc" />
            </ul>
          </div>
        </aside>
      </div><!-- /post-body -->

    </template>

    <!-- 图片灯箱：点击正文图片放大预览，点击遮罩或按 ESC 关闭 -->
    <div class="lightbox" v-if="lightboxSrc" @click="lightboxSrc = ''">
      <img :src="lightboxSrc" alt="" @click.stop />
      <button class="lb-close" type="button" @click="lightboxSrc = ''" title="关闭">×</button>
    </div>
  </div>
</template>

<style scoped>
/* 从数据面板「点击直达」进来的评论：短暂高亮便于定位 */
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
.post { max-width: 1240px; margin: 0 auto; color: #0f172a; }
.title { margin: 14px 0 8px; font-size: 24px; }
/* 文档层级面包屑：上级 / … / 当前（文档树路径） */
.crumbs { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 0; margin: 2px 0 10px; font-size: 13px; color: #64748b; }
.crumbs .crumb { color: #6366f1; text-decoration: none; padding: 2px 4px; border-radius: 6px; transition: background .15s, color .15s; }
.crumbs .crumb:hover { background: #eef2ff; }
.crumbs .crumb.current { color: #0f172a; font-weight: 600; cursor: default; }
.crumbs .crumb.current:hover { background: transparent; }
.crumbs .sep { color: #cbd5e1; margin: 0 2px 0 4px; }
.doc-actions { margin: 0 0 10px; }
/* 管理员审核条（待审核/草稿/封禁预览时显示） */
.review-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 8px 12px; margin: 4px 0 12px; background: #fffbeb; border: 1px solid #fde68a; border-radius: 10px; }
.review-bar .tip { color: #92400e; font-size: 13px; }
/* 三栏布局：左操作栏 | 中文章主体(居中阅读列) | 右作者卡片 */
.post-body { display: flex; gap: 24px; align-items: flex-start; }
.post-main { flex: 1; min-width: 0; max-width: 820px; }
.post-side { flex: none; width: 300px; position: sticky; top: 104px; align-self: flex-start; max-height: calc(100vh - 124px); overflow-y: auto; }
/* 作者小卡片：右侧边栏 */
.author-card { background: #fff; border: 1px solid #eef2f7; border-radius: 12px; padding: 16px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.author-top { display: flex; align-items: center; gap: 12px; }
.author-card .ava-wrap { position: relative; flex: none; display: inline-flex; overflow: visible; }
.author-card .ava { width: 50px; height: 50px; border-radius: 50%; object-fit: cover; flex: none; background: #e2e8f0; }
.author-card .ava.none { display: flex; align-items: center; justify-content: center; color: #64748b; font-size: 20px; font-weight: 700; }
.author-card .ainfo { flex: 1; min-width: 0; }
.author-card .nm-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.author-card .nm { font-weight: 700; color: #1e293b; font-size: 15px; }
.author-card .lv-tag { display: inline-block; margin-top: 4px; padding: 1px 8px; border-radius: 10px; background: linear-gradient(120deg, #f59e0b, #ef4444); color: #fff; font-size: 11px; font-weight: 600; line-height: 18px; }
.author-card .astats { display: flex; gap: 12px; margin-top: 6px; color: #64748b; font-size: 12px; }
.author-card .astats b { color: #0f172a; }
.author-card .follow-btn { flex: none; border: 1px solid #6366f1; background: #6366f1; color: #fff; border-radius: 16px; padding: 6px 14px; cursor: pointer; font-size: 13px; transition: all .15s; }
.author-card .follow-btn:hover { background: #4f46e5; }
.author-card .follow-btn.on { background: #fff; color: #64748b; border-color: #e2e8f0; }
/* 他的文章列表（侧边栏） */
.author-articles { margin-top: 16px; background: #fff; border: 1px solid #eef2f7; border-radius: 12px; padding: 14px 16px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.author-articles .aa-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.author-articles .aa-title { font-weight: 700; color: #1e293b; font-size: 14px; }
.author-articles .aa-all { color: #6366f1; font-size: 12px; cursor: pointer; }
.author-articles .aa-all:hover { text-decoration: underline; }
.author-articles .aa-list { list-style: none; padding: 0; margin: 0; }
.author-articles .aa-list li { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 7px 0; border-top: 1px solid #f1f5f9; cursor: pointer; }
.author-articles .aa-list li:first-child { border-top: none; }
.author-articles .aa-name { flex: 1; min-width: 0; color: #334155; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.author-articles .aa-list li:hover .aa-name { color: #6366f1; }
.author-articles .aa-views { flex: none; color: #94a3b8; font-size: 12px; }
.meta { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.lk { cursor: pointer; }
.stat { color: #94a3b8; font-size: 13px; }
.time { color: #94a3b8; font-size: 12px; }

/* 帖子左侧竖向操作栏：点赞 / 收藏 / 去评论；随文章滚动吸顶跟随（CSDN 风格） */
.action-bar { flex: none; width: 72px; position: sticky; top: 104px; align-self: flex-start; display: flex; flex-direction: column; gap: 12px; align-items: center; }
.action-bar .act { width: 100%; border: 1px solid #e2e8f0; background: #fff; border-radius: 12px; padding: 10px 4px; cursor: pointer; display: flex; flex-direction: column; align-items: center; gap: 2px; color: #475569; transition: all .15s; }
.action-bar .act:hover { border-color: #c7d2fe; }
.action-bar .act.on.like { color: #ef4444; border-color: #fecaca; background: #fef2f2; }
.action-bar .act.on.fav { color: #f59e0b; border-color: #fde68a; background: #fffbeb; }
.action-bar .act.comment { color: #6366f1; }
.action-bar .act.comment:hover { border-color: #c7d2fe; background: #eef2ff; }
.action-bar .act.top { color: #64748b; margin-top: 4px; border-top: 1px dashed #eef2f7; border-radius: 0 0 12px 12px; }
.action-bar .act.top:hover { border-color: #c7d2fe; background: #eef2ff; }
.action-bar .ai { font-size: 20px; line-height: 1; }
.action-bar .at { font-size: 12px; }
.action-bar .an { font-size: 12px; font-weight: 700; }

.content { line-height: 1.8; font-size: 15px; overflow-wrap: anywhere; word-break: normal; }
/* 审核工具条「一键违禁词检查」高亮：v-html 内容需用 :deep 命中 */
.content :deep(mark.banned-hl) { background: #fef08a; color: #b91c1c; padding: 0 2px; border-radius: 3px; box-shadow: 0 0 0 1px #facc15; }
.content :deep(p), .content :deep(li), .content :deep(blockquote), .content :deep(td), .content :deep(th) { overflow-wrap: anywhere; }
.content :deep(pre) { white-space: pre-wrap; word-break: break-all; max-width: 100%; overflow-x: auto; position: relative; }
/* 代码块复制按钮：渲染后由 decorateContent 动态注入到每个 pre 内 */
.content :deep(.code-copy) {
  position: absolute; top: 8px; right: 8px; z-index: 2;
  border: 1px solid #e2e8f0; background: rgba(255,255,255,.9); color: #475569;
  border-radius: 6px; padding: 2px 10px; font-size: 12px; cursor: pointer; transition: .15s;
}
.content :deep(.code-copy:hover) { border-color: #6366f1; color: #4f46e5; background: #eef2ff; }
.content :deep(img.kb-emoji) { height: 20px; vertical-align: middle; margin: 0 1px; }
.content :deep(img) { max-width: 100%; }
/* 语音条：独占一行、限宽居中，暗色下边框随之主题 */
.content :deep(audio) { display: block; max-width: 100%; margin: 10px 0; }
/* wangEditor 表格可能超出阅读列宽度，强制限宽并允许单元格内换行，防止整体溢出到右侧 */
.content :deep(table) { width: 100%; border-collapse: collapse; margin: 14px 0; font-size: 14px; table-layout: fixed; }
.content :deep(th), .content :deep(td) { border: 1px solid #e2e8f0; padding: 8px 10px; text-align: left; vertical-align: top; overflow-wrap: anywhere; word-break: break-word; }
.content :deep(th) { background: #f8fafc; font-weight: 600; color: #334155; }
.content :deep(tbody tr:nth-child(even)) { background: #fcfcfd; }
.content :deep(tbody tr:hover) { background: #f1f5f9; }
.ch { margin: 0; font-size: 16px; font-weight: 700; color: #1e293b; }
.cform { margin-bottom: 16px; }
.cform-bar { margin-bottom: 8px; }
.emoji-btn { border: 1px solid #dcdfe6; background: #fff; border-radius: 6px; padding: 4px 10px; font-size: 13px; color: #475569; cursor: pointer; transition: .12s; }
.emoji-btn:hover { border-color: #c7d2fe; color: #4f46e5; background: #eef2ff; }
/* 评论输入框（contenteditable，表情所见即所得） */
.cbox {
  min-height: 78px; max-height: 220px; overflow: auto; padding: 8px 11px;
  border: 1px solid #dcdfe6; border-radius: 4px; background: #fff;
  font-size: 14px; line-height: 1.6; color: #0f172a; outline: none;
}
.cbox:focus { border-color: #6366f1; }
.cbox.empty::before { content: attr(data-ph); color: #a8abb2; pointer-events: none; }
.cbox :deep(img.kb-emoji), .cbox img.kb-emoji { height: 20px; vertical-align: middle; margin: 0 1px; }
.reply-hint { display: inline-flex; align-items: center; gap: 8px; margin-bottom: 8px; padding: 4px 10px; background: #eef2ff; border: 1px solid #c7d2fe; border-radius: 8px; color: #4f46e5; font-size: 13px; }
.reply-hint .x { border: none; background: none; color: #6366f1; cursor: pointer; font-size: 16px; line-height: 1; padding: 0 2px; }
.reply-hint .x:hover { color: #4338ca; }

.clist { list-style: none; padding: 0; margin: 0; }
.citem { padding: 12px 0; border-top: 1px solid #f1f5f9; }
.citem:first-child { border-top: none; }
.creply { margin-left: 10px; border: none; background: none; color: #6366f1; cursor: pointer; font-size: 12px; padding: 0; }
.creply:hover { text-decoration: underline; }

/* 评论区排序：最新 / 最热 分段控件（企业级简洁风格） */
.ch-row { display: flex; align-items: center; justify-content: space-between; margin: 14px 0 12px; }
.csort { display: inline-flex; background: #f1f5f9; border-radius: 8px; padding: 2px; }
.csort button { border: none; background: none; cursor: pointer; font-size: 13px; color: #64748b; padding: 4px 12px; border-radius: 6px; transition: .15s; }
.csort button.on { background: #fff; color: #4f46e5; font-weight: 600; box-shadow: 0 1px 3px rgba(0,0,0,.08); }

/* 评论点赞按钮 */
.clike { display: inline-flex; align-items: center; gap: 3px; margin-left: 10px; border: 1px solid #e2e8f0; background: #f8fafc; color: #64748b; cursor: pointer; font-size: 12px; padding: 2px 8px; border-radius: 999px; transition: .15s; }
.clike:hover { border-color: #fecaca; background: #fef2f2; color: #ef4444; }
.clike.on { border-color: #fecaca; background: #fef2f2; color: #ef4444; font-weight: 600; }
.clike .lic { font-size: 13px; line-height: 1; }
.clike .ln { min-width: 8px; text-align: center; }

.replies { list-style: none; padding: 8px 0 0 16px; margin: 8px 0 0; border-left: 2px solid #eef2f7; }
.ritem { padding: 10px 0; border-top: 1px solid #f1f5f9; }
.ritem:first-child { border-top: none; }
.r-to { color: #6366f1; font-size: 12px; }
/* 正文区「小窗阅读」触发按钮 */
.content-bar { display: flex; justify-content: flex-end; margin-bottom: 10px; }
.mini-reader-btn { display: inline-flex; align-items: center; gap: 4px; border: 1px solid #e2e8f0; background: #fff; color: #475569; border-radius: 8px; padding: 5px 11px; font-size: 13px; cursor: pointer; transition: .15s; }
.mini-reader-btn:hover { border-color: #6366f1; color: #6366f1; }
/* 小窗阅读浮窗样式已移至全局 theme.css（浮窗现渲染于 App.vue，脱离 PostDetail 作用域，切页不消失） */

/* 订阅按钮：已订阅高亮 + 订阅人数角标（用 Element 变量，暗色自动适配） */
.mini-reader-btn.on { border-color: #6366f1; background: #eef2ff; color: #4f46e5; font-weight: 600; }
.sub-cnt { margin-left: 4px; padding: 0 5px; border-radius: 999px; background: var(--el-fill-color); color: var(--el-text-color-secondary); font-size: 11px; line-height: 16px; }

/* 相关文档推荐 */
.related-box { margin-top: 22px; padding: 14px 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-lighter); }
.related-title { margin-bottom: 8px; color: var(--el-text-color-primary); font-size: 14px; font-weight: 600; }
.related-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 18px; }
.related-item { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-width: 0; padding: 6px 8px; border-radius: 6px; cursor: pointer; transition: .15s; }
.related-item:hover { background: var(--el-fill-color); }
.related-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--el-text-color-regular); font-size: 13px; }
.related-item:hover .related-name { color: #6366f1; }
.related-meta { display: inline-flex; align-items: center; gap: 8px; flex-shrink: 0; }
.related-views { color: var(--el-text-color-secondary); font-size: 12px; }

.more-replies { display: inline-block; margin: 4px 0 0 16px; border: none; background: none; color: #6366f1; cursor: pointer; font-size: 13px; padding: 2px 0; }
.more-replies:hover { text-decoration: underline; }

.chead { display: flex; gap: 10px; align-items: center; color: #334155; flex-wrap: wrap; }
.ctime { color: #94a3b8; font-size: 12px; }
.cdel { margin-left: auto; border: none; background: none; color: #ef4444; cursor: pointer; font-size: 12px; padding: 0; }
.cdel:hover { text-decoration: underline; }
.cbody { margin-top: 6px; color: #475569; line-height: 1.7; }
.cbody :deep(img.kb-emoji) { height: 20px; vertical-align: middle; margin: 0 1px; }
.cbody :deep(img) { max-width: 100%; }

/* 文章目录 TOC（右侧栏，位于「他的文章」下方） */
.toc { background: #fff; border: 1px solid #eef2f7; border-radius: 12px; padding: 14px 16px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); margin-bottom: 16px; }
/* 目录标题即折叠按钮：点一下展开/收起整块，解决「目录太长」又保留快捷收起 */
.toc-toggle { display: flex; align-items: center; justify-content: space-between; width: 100%; padding: 0; border: none; background: none; cursor: pointer; }
.toc-h { font-weight: 700; color: #1e293b; font-size: 14px; }
.toc-caret { color: #94a3b8; font-size: 12px; transition: transform .15s; }
.toc-caret.open { transform: rotate(90deg); }
.toc-list { list-style: none; padding: 0; margin: 8px 0 0; max-height: 38vh; overflow: auto; }
/* 单条目录的具体样式见 TocNode.vue（递归组件，scoped 样式不跨层级，故放在组件内） */
.vlist { list-style: none; padding: 0; margin: 0; }
.vitem { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 0; border-top: 1px solid #eef2f7; }
.vinfo { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.vt { font-weight: 600; color: #1e293b; }
.vmeta { color: #94a3b8; font-size: 12px; }

/* 图片灯箱（点击正文图片放大预览） */
.lightbox { position: fixed; inset: 0; z-index: 3000; background: rgba(15,23,42,.82); display: flex; align-items: center; justify-content: center; padding: 24px; cursor: zoom-out; }
.lightbox img { max-width: 92%; max-height: 92%; border-radius: 8px; box-shadow: 0 12px 40px rgba(0,0,0,.5); cursor: default; background: #fff; }
.lightbox .lb-close { position: absolute; top: 18px; right: 22px; width: 40px; height: 40px; border: none; border-radius: 50%; background: rgba(255,255,255,.15); color: #fff; font-size: 24px; line-height: 1; cursor: pointer; }
.lightbox .lb-close:hover { background: rgba(255,255,255,.3); }

/* 暗色模式适配：theme.css 已覆盖 .post/.author-card/.toc/.action-bar/.content 正文等，
   此处补它未覆盖的：评论排序切换、评论点赞胶囊、目录箭头、版本元信息、灯箱占位底 */
html.dark .csort { background: #23232e; }
html.dark .csort button { color: #9aa0ad; }
html.dark .csort button.on { background: var(--el-bg-color-overlay); color: #a5b4fc; box-shadow: 0 1px 3px rgba(0,0,0,.5); }
html.dark .clike { border-color: #2a2a35; background: #23232e; color: #9aa0ad; }
html.dark .clike:hover { border-color: #7f1d1d; background: #2a1a1a; color: #f87171; }
html.dark .clike.on { border-color: #7f1d1d; background: #2a1a1a; color: #f87171; }
html.dark .stat { color: #8b8fa0; }
html.dark .toc-caret { color: #8b8fa0; }
html.dark .vmeta { color: #8b8fa0; }
html.dark .lightbox img { background: transparent; }
/* 评论区标题「评论（N）」：原 #1e293b 深底深字不可读，改随主题翻转 */
html.dark .ch { color: var(--el-text-color-primary); }
/* 评论输入框与表情按钮：原写死白底深字，暗色下是刺眼白块 */
html.dark .cbox { background: var(--el-bg-color-overlay); border-color: var(--el-border-color); color: var(--el-text-color-primary); }
html.dark .emoji-btn { background: var(--el-bg-color-overlay); border-color: var(--el-border-color); color: var(--el-text-color-regular); }
html.dark .emoji-btn:hover { background: var(--el-fill-color-light); border-color: var(--el-color-primary); color: var(--el-color-primary); }

/* ============================================================
   响应式：≤1024 单列（正文在上，作者卡 / 目录落到正文下方）
          ≤ 640 手机：字号与内边距收紧，操作栏贴底常驻
   ============================================================ */
@media (max-width: 1024px) {
  .post-body { flex-direction: column; gap: 16px; }
  .post-main { max-width: 100%; }
  /* 右栏：不再吸顶、不再内部滚动，作为整块排在正文后面 */
  .post-side { position: static; width: 100%; max-height: none; overflow: visible; margin-bottom: 8px; }
  /* 左操作栏：单列后竖排会吃掉一屏高度，改横向一排 */
  .action-bar {
    position: static; width: 100%;
    flex-direction: row; flex-wrap: wrap; justify-content: center; gap: 8px;
  }
  .action-bar .act { width: auto; flex-direction: row; align-items: center; gap: 6px; padding: 8px 14px; }
  .action-bar .act.top { margin-top: 0; border-top-style: solid; border-radius: 12px; }
  /* 相关文档：两列网格在窄屏容易挤成两半，改单列 */
  .related-list { grid-template-columns: 1fr; gap: 4px; }
}

@media (max-width: 640px) {
  .title { margin: 10px 0 6px; font-size: 20px; }
  .content { font-size: 14px; }
  .content :deep(table) { font-size: 13px; }
  .content :deep(th), .content :deep(td) { padding: 6px 8px; }
  /* 正文工具条：按钮多，换行平铺不再右挤 */
  .content-bar { flex-wrap: wrap; gap: 6px; justify-content: flex-start; }
  .mini-reader-btn { padding: 5px 9px; font-size: 12px; }
  /* 操作栏贴底：长文滚到哪都能点赞 / 评论 / 回顶 */
  .action-bar { position: sticky; bottom: 8px; z-index: 10; gap: 6px; }
  .action-bar .act { padding: 6px 10px; }
  .action-bar .ai { font-size: 16px; }
  .action-bar .at, .action-bar .an { font-size: 12px; }
  .meta { gap: 6px; margin-bottom: 10px; }
  .time { font-size: 11px; }
  .review-bar { padding: 8px 10px; }
  .ch-row { flex-wrap: wrap; gap: 8px; }
  .related-box { margin-top: 16px; padding: 12px; }
  .toc-list { max-height: 46vh; }
  .lightbox { padding: 12px; }
}
</style>
