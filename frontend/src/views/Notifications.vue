<script setup>
import { ref, computed, onMounted, watch } from 'vue'

/**
 * 通知摘要净化（N32）：summary 走 v-html，存量数据里可能已有存储型载荷。
 * 策略：先整体转义，再把「站内表情」标签恢复——形态固定
 * （<img class="kb-emoji" src="/emojis|/uploads/…" alt="表情">），src 白名单前缀，无可执行属性。
 */
function safeSummary(raw) {
  if (!raw) return ''
  const esc = raw.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return esc.replace(
    /&lt;img\s+class=&quot;kb-emoji&quot;\s+src=&quot;([^&]+)&quot;\s+alt=&quot;([^&]*)&quot;\s*&gt;/g,
    (m, src) => {
      const url = String(src).replace(/&amp;/g, '&')
      if (!/^(\/emojis\/|\/uploads\/)/.test(url)) return ''
      return '<img class="kb-emoji" src="' + url + '" alt="表情">'
    })
}
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatLineRound, ChatSquare, Promotion, Pointer, Stamp, DocumentChecked, Bell, Medal, UserFilled, Warning
} from '@element-plus/icons-vue'
import {
  notificationList, notificationRead, notificationReadAll,
  notificationDelete, notificationDeleteBatch, notificationDeleteAll
} from '../api/kb'
import { useNotificationStore } from '../store/notification'
import { fromNow, fmtDateTime } from '../utils/time'
import PageBar from '../components/PageBar.vue'
import { errMsg } from '../utils/errMsg'

const router = useRouter()
const notification = useNotificationStore()
const list = ref([])
const loading = ref(false)
const selected = ref([])          // 勾选的通知 id（跨 Tab 保留）
const tab = ref('all')            // 全部 / 互动 / 点赞 / 审核

// 前端分页：通知累积后会越来越多，按「当前 Tab 过滤后的结果」分页
const currentPage = ref(1)
const pageSize = ref(15)

// 各通知类型的语义图标与配色（大厂范式：类型可视化、层次分明）
const META = {
  SECURITY_ALERT:  { icon: Warning, color: '#C0392B' },
  COMMENT_MENTION: { icon: Promotion, color: '#185FA5' },
  DOC_MENTION:     { icon: Promotion, color: '#185FA5' },
  COMMENT:         { icon: ChatLineRound, color: '#0F6E56' },
  COMMENT_REPLY:   { icon: ChatSquare, color: '#0F6E56' },
  LIKE:            { icon: Pointer, color: '#D4537E' },
  REVIEW:          { icon: Stamp, color: '#BA7517' },
  REVIEW_SUBMIT:   { icon: DocumentChecked, color: '#854F0B' },
  SHARE_CAPACITY_APPROVED: { icon: DocumentChecked, color: '#0F6E56' },
  SHARE_CAPACITY_REJECTED: { icon: Stamp, color: '#BA7517' },
  SHARE_CREATE_APPROVED:   { icon: DocumentChecked, color: '#0F6E56' },
  SHARE_CREATE_REJECTED:   { icon: Stamp, color: '#BA7517' },
  SHARE_CREATE_PENDING:    { icon: DocumentChecked, color: '#854F0B' },
  SHARE_CAPACITY_PENDING:  { icon: DocumentChecked, color: '#854F0B' },
  CERTIFICATE:             { icon: Medal, color: '#B45309' },
  // 头像审核流（提交 / 通过 / 驳回）
  AVATAR_PENDING:   { icon: UserFilled, color: '#854F0B' },
  AVATAR_APPROVED:  { icon: UserFilled, color: '#0F6E56' },
  AVATAR_REJECTED:  { icon: UserFilled, color: '#BA7517' },
  AVATAR_REVIEW_SUBMIT: { icon: UserFilled, color: '#854F0B' },
}
const meta = (n) => META[n.type] || { icon: Bell, color: '#5F5E5A' }
const isReviewType = (t) => t === 'REVIEW' || t === 'REVIEW_SUBMIT'
// 共享空间审批类（结果 + 待审批）归入「审核」Tab
const isShareReviewType = (t) => t != null && t.startsWith('SHARE_')

const TAB_TYPES = {
  all: null,
  interact: ['COMMENT_MENTION', 'DOC_MENTION', 'COMMENT', 'COMMENT_REPLY'],
  like: ['LIKE'],
  review: ['REVIEW', 'REVIEW_SUBMIT',
    'SHARE_CREATE_APPROVED', 'SHARE_CREATE_REJECTED', 'SHARE_CREATE_PENDING',
    'SHARE_CAPACITY_APPROVED', 'SHARE_CAPACITY_REJECTED', 'SHARE_CAPACITY_PENDING',
    'AVATAR_PENDING', 'AVATAR_APPROVED', 'AVATAR_REJECTED', 'AVATAR_REVIEW_SUBMIT'],
}

const load = async () => {
  loading.value = true
  try { list.value = (await notificationList()).data || [] } catch (e) {
    ElMessage.error(errMsg(e, '加载失败'))
  } finally { loading.value = false }
}

watch(() => notification.version, () => load())

// Tab 切换时回到第一页
watch(() => tab.value, () => { currentPage.value = 1 })

const unreadCount = computed(() => list.value.filter(n => !n.read).length)

// 按 Tab 过滤 + 时间倒序
const filtered = computed(() => {
  const types = TAB_TYPES[tab.value]
  const arr = types ? list.value.filter(n => types.includes(n.type)) : list.value
  return [...arr].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
})

// 删除导致条目减少后，若当前页已越界则回退，避免停在空页。
// 注意：必须放在 filtered 定义之后 —— watch 立即求值源 getter，写在 filtered 之前会触发 TDZ。
watch(() => filtered.value.length, (len) => {
  const maxPage = Math.max(1, Math.ceil(len / pageSize.value))
  if (currentPage.value > maxPage) currentPage.value = maxPage
})

// 当前页数据（过滤后切片）
const pagedFiltered = computed(() => {
  const start = (currentPage.value - 1) * pageSize.value
  return filtered.value.slice(start, start + pageSize.value)
})

// 时间分组：今天 / 昨天 / 本周更早 / 更早（知乎范式：按天分段小标题）
const ORDER = ['今天', '昨天', '本周更早', '更早']
function bucketOf(iso) {
  const d = new Date(iso), now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const yest = new Date(today); yest.setDate(yest.getDate() - 1)
  const week = new Date(today); week.setDate(week.getDate() - 6)
  if (d >= today) return '今天'
  if (d >= yest) return '昨天'
  if (d >= week) return '本周更早'
  return '更早'
}
const grouped = computed(() => {
  const map = new Map()
  for (const n of pagedFiltered.value) {
    const b = bucketOf(n.createdAt)
    if (!map.has(b)) map.set(b, [])
    map.get(b).push(n)
  }
  return ORDER.filter(t => map.has(t)).map(t => ({ title: t, items: map.get(t) }))
})

// 勾选（作用范围为「当前页」）
const allSel = computed(() =>
  pagedFiltered.value.length > 0 && pagedFiltered.value.every(n => selected.value.includes(n.id)))
const toggleSel = (id) => {
  selected.value = selected.value.includes(id)
    ? selected.value.filter(x => x !== id)
    : [...selected.value, id]
}
const toggleAll = () => {
  if (allSel.value) selected.value = selected.value.filter(id => !pagedFiltered.value.some(n => n.id === id))
  else selected.value = [...new Set([...selected.value, ...pagedFiltered.value.map(n => n.id)])]
}

const open = async (n) => {
  if (!n.read) { try { await notificationRead(n.id) } catch (e) {} }
  // 共享文档里的 @提及：带 shareId 才能拼出空间文档的地址（不是文章页）
  if (n.type === 'DOC_MENTION' && n.shareId && n.docId) {
    router.push(`/doc-shares/${n.shareId}?doc=${n.docId}`); return
  }
  if (n.docId) { router.push('/post/' + n.docId); return }
  // 共享空间待审批（管理员收到）→ 管理端审批页；审批结果 → 我的共享文档
  if (n.type === 'SHARE_CREATE_PENDING' || n.type === 'SHARE_CAPACITY_PENDING') {
    router.push('/admin/doc-shares'); return
  }
  if (n.type && n.type.startsWith('SHARE_')) { router.push('/doc-shares'); return }
  if (n.type === 'CERTIFICATE') { router.push('/profile'); return }
  // 头像审核流 → 个人资料页（那里有审核中横幅与头像角标）
  if (n.type === 'AVATAR_PENDING' || n.type === 'AVATAR_APPROVED' || n.type === 'AVATAR_REJECTED') {
    router.push('/profile'); return
  }
  // 头像提审（管理员收到）→ 头像审核队列
  if (n.type === 'AVATAR_REVIEW_SUBMIT') { router.push('/admin/review'); return }
  // 安全告警（管理员收到）→ 登录日志页核对来源
  if (n.type === 'SECURITY_ALERT') { router.push('/admin/loginlog'); return }
}
// 内联「标记已读」（= 忽略，不跳转）
const ignore = async (n) => {
  try {
    await notificationRead(n.id)
    n.read = true
    notification.setUnread(list.value.filter(x => !x.read).length)
    ElMessage.success('已标记为已读')
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const readAll = async () => {
  try {
    await notificationReadAll()
    ElMessage.success('已全部标为已读')
    list.value.forEach(n => (n.read = true))
    notification.setUnread(0)
    load()
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

const removeOne = async (n) => {
  try {
    await ElMessageBox.confirm('确定删除这条通知？', '提示', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await notificationDelete(n.id)
    list.value = list.value.filter(x => x.id !== n.id)
    selected.value = selected.value.filter(x => x !== n.id)
    notification.setUnread(list.value.filter(x => !x.read).length)
    ElMessage.success('已删除')
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}

const removeSelected = async () => {
  if (!selected.value.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selected.value.length} 条通知？`, '提示', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch { return }
  try {
    await notificationDeleteBatch(selected.value)
    ElMessage.success('已删除选中通知')
    load()
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}

const clearAll = async () => {
  if (!list.value.length) return
  try {
    await ElMessageBox.confirm('确定清空全部通知？此操作不可恢复', '提示', { type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消' })
  } catch { return }
  try {
    await notificationDeleteAll()
    list.value = []
    selected.value = []
    notification.setUnread(0)
    ElMessage.success('已清空全部通知')
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="head">
      <!-- 消息中心统一入口：与 /messages 共用同一组 Tab，避免「通知」「私信」两个入口分散 -->
      <div class="mc-switch">
        <span @click="router.push('/messages')">私信</span>
        <span class="on">通知</span>
      </div>
      <div class="actions">
        <el-button size="small" :disabled="!unreadCount" @click="readAll">全部已读</el-button>
        <el-button size="small" type="danger" plain :disabled="!selected.length" @click="removeSelected">
          删除选中{{ selected.length ? `（${selected.length}）` : '' }}
        </el-button>
        <el-button size="small" type="danger" plain :disabled="!list.length" @click="clearAll">清空全部</el-button>
      </div>
    </div>

    <div class="tabs">
      <button :class="{ active: tab === 'all' }" @click="tab = 'all'">全部</button>
      <button :class="{ active: tab === 'interact' }" @click="tab = 'interact'">互动</button>
      <button :class="{ active: tab === 'like' }" @click="tab = 'like'">点赞</button>
      <button :class="{ active: tab === 'review' }" @click="tab = 'review'">审核</button>
    </div>

    <label class="selall">
      <el-checkbox :model-value="allSel" @change="toggleAll" />
      <span>全选当前（{{ pagedFiltered.length }}）</span>
    </label>

    <div v-loading="loading">
      <template v-for="g in grouped" :key="g.title">
        <div class="group-title">{{ g.title }}</div>
        <ul class="list">
          <li v-for="n in g.items" :key="n.id" class="item" :class="{ unread: !n.read }" @click="open(n)">
            <el-checkbox class="cb" :model-value="selected.includes(n.id)" @change.stop="toggleSel(n.id)" />
            <span class="ic" :style="{ color: meta(n).color }">
              <el-icon :size="18"><component :is="meta(n).icon" /></el-icon>
            </span>
            <div class="body">
              <template v-if="n.type === 'DOC_MENTION'">
                <div class="line"><b>{{ n.actor }}</b><span class="txt">在文档里 @ 了你</span></div>
              </template>
              <template v-else-if="n.type === 'COMMENT_MENTION'">
                <div class="line"><b>{{ n.actor }}</b><span class="txt">在评论中 @ 了你</span></div>
              </template>
              <template v-else-if="n.type === 'COMMENT'">
                <div class="line"><b>{{ n.actor }}</b><span class="txt">评论了你的文章</span></div>
              </template>
              <template v-else-if="n.type === 'COMMENT_REPLY'">
                <div class="line"><b>{{ n.actor }}</b><span class="txt">回复了你的评论</span></div>
              </template>
              <template v-else-if="n.type === 'LIKE'">
                <div class="line"><b>{{ n.actor }}</b><span class="txt">赞了你的文章</span></div>
              </template>
              <template v-else-if="n.type === 'REVIEW_SUBMIT'">
                <div class="line"><b>{{ n.actor }}</b><span class="txt">提交了文章，待你审核</span></div>
              </template>
              <template v-else-if="n.type === 'AVATAR_REVIEW_SUBMIT'">
                <div class="line"><b>{{ n.actor }}</b><span class="txt">提交了新头像，待你审核</span></div>
              </template>
              <template v-else>
                <!-- 标题只放类型，正文由下面的 .summary 渲染：原来两处都输出 summary，同一段长文本显示两遍 -->
                <div class="line"><span class="txt">{{ n.type === 'SECURITY_ALERT' ? '安全提醒' : '通知' }}</span></div>
              </template>
              <p class="summary" v-if="n.summary && !isReviewType(n.type)" v-html="safeSummary(n.summary)"></p>
              <span class="time" v-if="n.createdAt">{{ fromNow(n.createdAt) }}（{{ fmtDateTime(n.createdAt) }}）</span>
            </div>
            <div class="ops" @click.stop>
              <el-tag v-if="!n.read" class="tag-unread" type="primary" size="small" effect="light" round>未读</el-tag>
              <el-button v-if="!n.read" size="small" type="primary" plain @click="ignore(n)">标记已读</el-button>
              <el-button v-else size="small" text disabled>已读</el-button>
              <el-button size="small" type="danger" plain @click="removeOne(n)">删除</el-button>
            </div>
          </li>
        </ul>
      </template>
      <el-empty v-if="!loading && !filtered.length" :description="tab === 'all' ? '暂无通知' : '该分类下暂无通知'" />

      <PageBar v-if="filtered.length > 0" v-model="currentPage" :total="filtered.length" :page-size="pageSize" />
    </div>
  </div>
</template>

<style scoped>
.page { max-width: 880px; margin: 0 auto; color: #0f172a; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.head h2 { margin: 0; font-size: 20px; }
/* 消息中心 Tab 切换（私信 / 通知），与 Messages.vue 保持一致 */
.mc-switch { display: inline-flex; gap: 2px; background: #eef2f7; border-radius: 8px; padding: 3px; }
.mc-switch span { font-size: 13px; font-weight: 500; color: #64748b; padding: 5px 13px; border-radius: 6px; cursor: pointer; line-height: 1; }
.mc-switch span:hover { color: #2563eb; }
.mc-switch span.on { background: #fff; color: #2563eb; font-weight: 600; box-shadow: 0 1px 2px rgba(15, 23, 42, .1); }
:deep(html.dark) .mc-switch { background: #23233a; }
:deep(html.dark) .mc-switch span { color: #a5a7bd; }
:deep(html.dark) .mc-switch span.on { background: #2f2f4a; color: #a9c2ff; box-shadow: none; }
.actions { display: flex; align-items: center; gap: 10px; }
.tabs { display: flex; gap: 8px; margin-bottom: 10px; }
.tabs button {
  border: 0.5px solid var(--color-border-tertiary, #d3d1c7);
  background: transparent; color: #5f5e5a; font-size: 13px;
  padding: 5px 14px; border-radius: 999px; cursor: pointer;
}
.tabs button.active { background: #185FA5; color: #fff; border-color: #185FA5; }
.selall { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #64748b; margin: 4px 0 10px; cursor: pointer; }
.group-title { font-size: 12px; color: #94a3b8; font-weight: 500; margin: 14px 0 6px; }
.list { list-style: none; padding: 0; margin: 0; }
.item { display: flex; gap: 10px; align-items: flex-start; padding: 12px; border: 0.5px solid #e2e8f0; border-radius: 12px; margin-bottom: 8px; cursor: pointer; transition: .15s; }
.item:hover { border-color: #4f46e5; }
/* 未读：左侧强调条 + 浅底色 + 正文加粗；已读：白底 + 正文/摘要降饱和，一眼可分 */
.item.unread {
  background: #eef2ff; border-color: #c7d2fe;
  box-shadow: inset 3px 0 0 0 #4f46e5;   /* inset 阴影代替 border-left，避免挤压内容 */
}
.item.unread .line { font-weight: 600; color: #0f172a; }
.item.unread .time { color: #64748b; }
.item:not(.unread) { background: #fff; }
.item:not(.unread) .line,
.item:not(.unread) .txt { color: #94a3b8; }
.item:not(.unread) .summary { color: #a8b0bd; }
.item:not(.unread) .ic { opacity: .55; }
.tag-unread { flex: none; }
.cb { margin-top: 2px; }
.ic { margin-top: 1px; flex: none; }
.body { flex: 1; min-width: 0; }
.line { font-size: 14px; }
.txt { color: #64748b; }
.summary { margin: 4px 0; color: #475569; font-size: 14px; line-height: 1.5; }
.summary :deep(img.kb-emoji) { height: 18px; vertical-align: middle; margin: 0 1px; }
.time { color: #94a3b8; font-size: 12px; }
.ops { display: flex; align-items: center; gap: 6px; flex: none; }

/* 分页样式由 PageBar 组件提供 */

/* 暗色模式适配（自定义列表项 / 边框 / 文字色；el-checkbox、el-button、el-empty 由 Element 暗色变量自动处理） */
html.dark .page { color: var(--el-text-color-primary); }
html.dark .tabs button { border-color: var(--el-border-color); color: var(--el-text-color-regular); }
html.dark .tabs button.active { background: #2563eb; border-color: #2563eb; }
html.dark .selall { color: var(--el-text-color-secondary); }
html.dark .group-title { color: var(--el-text-color-secondary); }
html.dark .item { border-color: var(--el-border-color-light); }
html.dark .item:hover { border-color: #818cf8; }
html.dark .item.unread { background: rgba(99,102,241,.14); border-color: rgba(129,140,248,.35); box-shadow: inset 3px 0 0 0 #818cf8; }
html.dark .item.unread .line { color: var(--el-text-color-primary); }
html.dark .item:not(.unread) .line,
html.dark .item:not(.unread) .txt { color: var(--el-text-color-disabled); }
html.dark .item:not(.unread) .summary { color: var(--el-text-color-disabled); }
html.dark .txt { color: var(--el-text-color-secondary); }
html.dark .summary { color: var(--el-text-color-regular); }
html.dark .time { color: var(--el-text-color-secondary); }
/* 类型图标颜色由 script 以内联样式注入（蓝/绿/棕等偏深色），暗色下统一提亮保证可辨识 */
html.dark .ic { filter: brightness(1.45); }

/* ---------- 响应式布局（断点统一 1024 / 640）---------- */
@media (max-width: 1024px) {
  .item { padding: 12px; }
  .summary { overflow-wrap: anywhere; }
}
@media (max-width: 640px) {
  .head { flex-wrap: wrap; gap: 8px; }
  .head h2 { font-size: 18px; }
  .actions { flex-wrap: wrap; gap: 8px; }
  .tabs { flex-wrap: wrap; }
  .item { gap: 8px; padding: 10px; }
  .line { font-size: 13px; overflow-wrap: anywhere; }
  .summary { font-size: 13px; }
}
</style>
