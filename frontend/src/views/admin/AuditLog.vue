<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  UserFilled, Delete, EditPen, Switch, Key, Coin,
  DocumentAdd, DocumentDelete, Lock, Unlock, Select, Close, Search,
  DocumentCopy, User, FolderOpened, Reading, Tickets,
} from '@element-plus/icons-vue'
import { listAudit } from '../../api/admin'
import { errMsg } from '../../utils/errMsg'

const logs = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const domain = ref('all')   // all | user | doc

// —— 搜索条件 ——
const actor = ref('')               // 操作人（模糊）
const dateRange = ref([])           // [开始Date, 结束Date]

const USER_QUOTA_ACTIONS = new Set(['UPDATE_USER_QUOTA'])

const SHARE_SPACE_ACTIONS = new Set(['CREATE_SHARE_SPACE', 'UPDATE_SHARE_SPACE', 'DELETE_SHARE_SPACE', 'UPDATE_SHARE_CAPACITY'])
const SHARE_MEMBER_ACTIONS = new Set(['UPDATE_SHARE_MEMBER'])
const SHARE_DOC_ACTIONS = new Set(['CREATE_SHARE_DOC', 'UPDATE_SHARE_DOC', 'DELETE_SHARE_DOC'])

const ACTION_META = {
  CREATE_USER:    { label: '创建用户', icon: UserFilled,  color: '#3b82f6', bg: '#eff6ff' },
  DELETE_USER:    { label: '删除用户', icon: Delete,      color: '#ef4444', bg: '#fef2f2' },
  UPDATE_USER:    { label: '更新用户', icon: EditPen,     color: '#6366f1', bg: '#eef2ff' },
  TOGGLE_USER:    { label: '启用/禁用', icon: Switch,     color: '#f59e0b', bg: '#fffbeb' },
  RESET_PWD:      { label: '重置密码', icon: Key,         color: '#f59e0b', bg: '#fffbeb' },
  ADJUST_POINTS:  { label: '调整积分', icon: Coin,        color: '#10b981', bg: '#ecfdf5' },
  UPDATE_USER_QUOTA: { label: '调整用户配额', icon: Coin,   color: '#f59e0b', bg: '#fffbeb' },
  CREATE_DOC:     { label: '创建文档', icon: DocumentAdd, color: '#3b82f6', bg: '#eff6ff' },
  DELETE_DOC:     { label: '删除文档', icon: Delete,      color: '#ef4444', bg: '#fef2f2' },
  BLOCK_DOC:      { label: '封禁文档', icon: Lock,        color: '#ef4444', bg: '#fef2f2' },
  UNBLOCK_DOC:    { label: '解封文档', icon: Unlock,      color: '#10b981', bg: '#ecfdf5' },
  APPROVE_DOC:    { label: '通过审核', icon: Select,      color: '#10b981', bg: '#ecfdf5' },
  REJECT_DOC:     { label: '驳回审核', icon: Close,       color: '#ef4444', bg: '#fef2f2' },
  CREATE_SHARE_SPACE:    { label: '创建团队空间', icon: FolderOpened,  color: '#3b82f6', bg: '#eff6ff' },
  UPDATE_SHARE_SPACE:    { label: '更新团队空间', icon: EditPen,       color: '#6366f1', bg: '#eef2ff' },
  DELETE_SHARE_SPACE:    { label: '删除团队空间', icon: Delete,        color: '#ef4444', bg: '#fef2f2' },
  UPDATE_SHARE_CAPACITY: { label: '调整空间容量', icon: EditPen,       color: '#f59e0b', bg: '#fffbeb' },
  UPDATE_SHARE_MEMBER:   { label: '调整空间成员', icon: User,          color: '#8b5cf6', bg: '#f5f3ff' },
  CREATE_SHARE_DOC:      { label: '创建空间文档', icon: DocumentAdd,   color: '#3b82f6', bg: '#eff6ff' },
  UPDATE_SHARE_DOC:      { label: '更新空间文档', icon: EditPen,       color: '#6366f1', bg: '#eef2ff' },
  DELETE_SHARE_DOC:      { label: '删除空间文档', icon: DocumentDelete,color: '#ef4444', bg: '#fef2f2' },
}
const meta = (a) => ACTION_META[a] || { label: a, icon: EditPen, color: '#64748b', bg: '#f1f5f9' }

// 域分组（用户/文档/共享空间）过滤已下沉服务端（domain 参数），分页计数才准确

const subGroup = (action) => {
  if (USER_QUOTA_ACTIONS.has(action)) return { label: '配额', color: '#f59e0b' }
  if (SHARE_SPACE_ACTIONS.has(action)) return { label: '空间', color: '#3b82f6' }
  if (SHARE_MEMBER_ACTIONS.has(action)) return { label: '成员', color: '#8b5cf6' }
  if (SHARE_DOC_ACTIONS.has(action)) return { label: '文档', color: '#10b981' }
  return null
}

const relTime = (iso) => {
  if (!iso) return '-'
  const t = new Date(iso)
  if (isNaN(t.getTime())) return iso
  const now = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  const hhmm = pad(t.getHours()) + ':' + pad(t.getMinutes())
  if (t.toDateString() === now.toDateString()) return '今天 ' + hhmm
  const y = new Date(now); y.setDate(now.getDate() - 1)
  if (t.toDateString() === y.toDateString()) return '昨天 ' + hhmm
  return iso.slice(0, 10) + ' ' + hhmm
}

// Date → 'YYYY-MM-DDTHH:mm:ss'（LocalDateTime 无时区，不带 Z）
const fmt = (d) => {
  const p = (n) => String(n).padStart(2, '0')
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) +
         'T' + p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds())
}

const load = async () => {
  loading.value = true
  try {
    const params = { page: page.value - 1, size: size.value }
    if (domain.value !== 'all') params.domain = domain.value              // 服务端过滤
    if (actor.value.trim()) params.actor = actor.value.trim()
    if (dateRange.value && dateRange.value.length === 2) {
      params.start = fmt(dateRange.value[0])                       // 当天 00:00:00
      const e = new Date(dateRange.value[1])                       // 当天 23:59:59
      e.setHours(23, 59, 59, 0)
      params.end = fmt(e)
    }
    const { data } = await listAudit(params)
    logs.value = data.content || []
    total.value = data.totalElements || 0
  } catch (e) {
    ElMessage.error(errMsg(e, '加载审计日志失败'))
  } finally {
    loading.value = false
  }
}

const doSearch = () => { page.value = 1; load() }
const onReset = () => { actor.value = ''; dateRange.value = []; page.value = 1; load() }
const onPage = (p) => { page.value = p; load() }
const onSize = (s) => { size.value = s; page.value = 1; load() }
const onDomain = () => { page.value = 1; load() }

onMounted(load)
</script>

<template>
  <div class="audit">
    <div class="audit-header">
      <div class="head-left">
        <div class="head-icon"><el-icon :size="22"><Tickets /></el-icon></div>
        <h2>操作审计</h2>
      <span class="tip">记录管理员的关键操作（用户 / 文档 / 共享空间管理、权限变更、重置密码等）</span>
      </div>
    </div>

    <el-tabs v-model="domain" class="seg" @tab-change="onDomain">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="用户操作" name="user" />
      <el-tab-pane label="文档操作" name="doc" />
      <el-tab-pane label="空间操作" name="share" />
    </el-tabs>

    <!-- 搜索条：操作人 + 时间范围 -->
    <div class="search-bar">
      <el-input v-model="actor" class="s-actor" placeholder="操作人（用户名）" clearable
                @keyup.enter="doSearch" @clear="doSearch" />
      <el-date-picker v-model="dateRange" type="daterange" unlink-panels
                      range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期"
                      class="s-date" />
      <el-button type="primary" @click="doSearch">
        <el-icon><Search /></el-icon> 搜索
      </el-button>
      <el-button @click="onReset">重置</el-button>
    </div>

    <el-table v-stack-table :data="logs" border stripe v-loading="loading" style="width: 100%">
      <el-table-column type="expand" width="44">
        <template #default="{ row }">
          <div class="expand">
            <div><b>操作人：</b>{{ row.actorName || '-' }} <span class="sub">#{{ row.actorId }}</span></div>
            <div><b>目标：</b>{{ row.targetName || (row.targetType ? row.targetType + ' #' + row.targetId : '-') }}</div>
            <div><b>说明：</b>{{ row.detail || '-' }}</div>
            <div><b>IP：</b>{{ row.ip || '-' }}</div>
            <div><b>时间：</b>{{ (row.createdAt || '').replace('T', ' ') }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="动作" width="180">
        <template #default="{ row }">
          <span class="act" :style="{ color: meta(row.action).color, background: meta(row.action).bg }">
            <el-icon><component :is="meta(row.action).icon" /></el-icon>
            {{ meta(row.action).label }}
          </span>
          <span v-if="subGroup(row.action)" class="sub-group"
                :style="{ color: subGroup(row.action).color, background: subGroup(row.action).color + '15' }">
            {{ subGroup(row.action).label }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作人" width="150">
        <template #default="{ row }">
          {{ row.actorName || '-' }} <span class="sub">#{{ row.actorId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="目标" min-width="160">
        <template #default="{ row }">
          <span v-if="row.targetName" class="target">{{ row.targetName }}</span>
          <span v-else-if="row.targetType" class="target">{{ row.targetType }} #{{ row.targetId }}</span>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <!-- 说明：手机卡片里隐藏（展开行有完整详情，避免一张卡片十几行） -->
      <el-table-column prop="detail" label="说明" min-width="200" show-overflow-tooltip class-name="stack-hide" />
      <el-table-column label="时间" width="150">
        <template #default="{ row }"><span class="time">{{ relTime(row.createdAt) }}</span></template>
      </el-table-column>
    </el-table>

    <el-pagination class="pager"
                   layout="total, sizes, prev, pager, next, jumper"
                   :total="total" :page-size="size" :current-page="page"
                   :page-sizes="[10, 20, 50, 100]"
                   @current-change="onPage" @size-change="onSize" />
  </div>
</template>

<style scoped>
.audit { padding: 20px; max-width: 1200px; margin: 0 auto; }
.audit-header { display: flex; align-items: baseline; gap: 12px; margin-bottom: 8px; }
.audit-header h2 { margin: 0; font-size: 20px; color: #111827; }
.tip { color: #94a3b8; font-size: 13px; }
.seg { margin-bottom: 4px; }
.search-bar { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 12px; }
.search-bar .s-actor { width: 200px; }
.search-bar .s-date { width: 280px; }
.act { display: inline-flex; align-items: center; gap: 6px; padding: 3px 10px; border-radius: 999px; font-size: 13px; font-weight: 500; }
.act .el-icon { font-size: 15px; }
.sub-group { display: inline-flex; align-items: center; margin-left: 6px; padding: 2px 8px; border-radius: 999px; font-size: 12px; font-weight: 500; }
.sub { color: #94a3b8; font-size: 12px; }
.target { color: #334155; font-weight: 500; }
.time { color: #64748b; font-size: 13px; }
.pager { margin-top: 14px; justify-content: flex-end; }
.expand { padding: 6px 14px; line-height: 1.9; color: #475569; font-size: 13px; }
.expand .sub { margin-left: 4px; }

/* 暗色模式适配：本页 .tip / .time 已由全局 theme.css 覆盖，此处只补未被覆盖的深色文字 */
html.dark .audit-header h2 { color: var(--el-text-color-primary); }
html.dark .target { color: var(--el-text-color-regular); }
html.dark .expand { color: var(--el-text-color-regular); }

/* ============================================================
   响应式布局（断点统一：≤1024 平板 / ≤640 手机）
   只写 width / flex / padding / margin / font-size
   ============================================================ */
@media (max-width: 1024px) {
  .audit { padding: 14px; }
  /* 标题与说明挤不下时换行，不再硬撑一行 */
  .audit-header { flex-wrap: wrap; gap: 8px; }
  .search-bar { gap: 8px; }
  .search-bar .s-actor { width: 180px; }
  .search-bar .s-date { width: 260px; }
  .pager { margin-top: 12px; }
}

@media (max-width: 640px) {
  .audit { padding: 12px; }
  .audit-header h2 { font-size: 18px; }
  .tip { font-size: 12px; }
  /* 操作人 / 时间范围各占整行，避免和搜索按钮挤成一团 */
  .search-bar .s-actor,
  .search-bar .s-date { width: 100%; }
  /* 展开详情收紧（≤640 表格已由 v-stack-table 转成卡片，不再横向滚动） */
  .expand { padding: 6px 12px; font-size: 12px; line-height: 1.7; }
  :deep(.el-table) { font-size: 13px; }
}

/* 页头彩色图标徽章（与用户管理页统一） */
.head-left { display: flex; align-items: center; gap: 14px; min-width: 0; }
.head-icon { width: 46px; height: 46px; border-radius: 12px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; background: #e0e7ff; color: #4f46e5; }
html.dark .head-icon { background: rgb(79 70 229 / .2); color: #a5b4fc; }
</style>
