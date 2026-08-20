<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ChatLineRound, ChatSquare, Promotion, Pointer, Stamp, DocumentChecked, Bell
} from '@element-plus/icons-vue'
import {
  notificationList, notificationRead, notificationReadAll,
  notificationDelete, notificationDeleteBatch, notificationDeleteAll
} from '../api/kb'
import { useNotificationStore } from '../store/notification'
import { fromNow, fmtDateTime } from '../utils/time'

const router = useRouter()
const notification = useNotificationStore()
const list = ref([])
const loading = ref(false)
const selected = ref([])          // 勾选的通知 id（跨 Tab 保留）
const tab = ref('all')            // 全部 / 互动 / 点赞 / 审核

// 各通知类型的语义图标与配色（大厂范式：类型可视化、层次分明）
const META = {
  COMMENT_MENTION: { icon: Promotion, color: '#185FA5' },
  COMMENT:         { icon: ChatLineRound, color: '#0F6E56' },
  COMMENT_REPLY:   { icon: ChatSquare, color: '#0F6E56' },
  LIKE:            { icon: Pointer, color: '#D4537E' },
  REVIEW:          { icon: Stamp, color: '#BA7517' },
  REVIEW_SUBMIT:   { icon: DocumentChecked, color: '#854F0B' },
}
const meta = (n) => META[n.type] || { icon: Bell, color: '#5F5E5A' }
const isReviewType = (t) => t === 'REVIEW' || t === 'REVIEW_SUBMIT'

const TAB_TYPES = {
  all: null,
  interact: ['COMMENT_MENTION', 'COMMENT', 'COMMENT_REPLY'],
  like: ['LIKE'],
  review: ['REVIEW', 'REVIEW_SUBMIT'],
}

const load = async () => {
  loading.value = true
  try { list.value = (await notificationList()).data || [] } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '加载失败')
  } finally { loading.value = false }
}

watch(() => notification.version, () => load())

const unreadCount = computed(() => list.value.filter(n => !n.read).length)

// 按 Tab 过滤 + 时间倒序
const filtered = computed(() => {
  const types = TAB_TYPES[tab.value]
  const arr = types ? list.value.filter(n => types.includes(n.type)) : list.value
  return [...arr].sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
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
  for (const n of filtered.value) {
    const b = bucketOf(n.createdAt)
    if (!map.has(b)) map.set(b, [])
    map.get(b).push(n)
  }
  return ORDER.filter(t => map.has(t)).map(t => ({ title: t, items: map.get(t) }))
})

// 勾选
const allSel = computed(() =>
  filtered.value.length > 0 && filtered.value.every(n => selected.value.includes(n.id)))
const toggleSel = (id) => {
  selected.value = selected.value.includes(id)
    ? selected.value.filter(x => x !== id)
    : [...selected.value, id]
}
const toggleAll = () => {
  if (allSel.value) selected.value = selected.value.filter(id => !filtered.value.some(n => n.id === id))
  else selected.value = [...new Set([...selected.value, ...filtered.value.map(n => n.id)])]
}

const open = async (n) => {
  if (!n.read) { try { await notificationRead(n.id) } catch (e) {} }
  if (n.docId) router.push('/post/' + n.docId)
}
// 内联「标记已读」（= 忽略，不跳转）
const ignore = async (n) => {
  try { await notificationRead(n.id) } catch (e) {}
  n.read = true
  notification.setUnread(list.value.filter(x => !x.read).length)
}

const readAll = async () => {
  try {
    await notificationReadAll()
    ElMessage.success('已全部标为已读')
    list.value.forEach(n => (n.read = true))
    notification.setUnread(0)
    load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '操作失败')
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
    ElMessage.error(e.response?.data?.error || '删除失败')
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
    ElMessage.error(e.response?.data?.error || '删除失败')
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
    ElMessage.error(e.response?.data?.error || '操作失败')
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="head">
      <h2>通知</h2>
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
      <span>全选当前（{{ filtered.length }}）</span>
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
              <template v-if="n.type === 'COMMENT_MENTION'">
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
              <template v-else>
                <div class="line"><span class="txt">{{ n.summary }}</span></div>
              </template>
              <p class="summary" v-if="n.summary && !isReviewType(n.type)">{{ n.summary }}</p>
              <span class="time" v-if="n.createdAt">{{ fromNow(n.createdAt) }}（{{ fmtDateTime(n.createdAt) }}）</span>
            </div>
            <div class="ops" @click.stop>
              <el-button v-if="!n.read" size="small" text @click="ignore(n)">标记已读</el-button>
              <el-button size="small" type="danger" plain @click="removeOne(n)">删除</el-button>
            </div>
          </li>
        </ul>
      </template>
      <el-empty v-if="!loading && !filtered.length" :description="tab === 'all' ? '暂无通知' : '该分类下暂无通知'" />
    </div>
  </div>
</template>

<style scoped>
.page { max-width: 880px; margin: 0 auto; color: #0f172a; }
.head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.head h2 { margin: 0; font-size: 20px; }
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
.item.unread { background: #eef2ff; border-color: #c7d2fe; }
.cb { margin-top: 2px; }
.ic { margin-top: 1px; flex: none; }
.body { flex: 1; min-width: 0; }
.line { font-size: 14px; }
.txt { color: #64748b; }
.summary { margin: 4px 0; color: #475569; font-size: 14px; line-height: 1.5; }
.time { color: #94a3b8; font-size: 12px; }
.ops { display: flex; align-items: center; gap: 6px; flex: none; }
</style>
