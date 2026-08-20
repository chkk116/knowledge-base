<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  UserFilled, Delete, EditPen, Switch, Key, Coin,
  DocumentAdd, Lock, Unlock, Select, Close, Search,
} from '@element-plus/icons-vue'
import { listAudit } from '../../api/admin'

const logs = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)
const domain = ref('all')   // all | user | doc

// —— 搜索条件 ——
const actor = ref('')               // 操作人（模糊）
const dateRange = ref([])           // [开始Date, 结束Date]

const USER_ACTIONS = new Set([
  'CREATE_USER', 'DELETE_USER', 'UPDATE_USER', 'TOGGLE_USER', 'RESET_PWD', 'ADJUST_POINTS',
])

const ACTION_META = {
  CREATE_USER:    { label: '创建用户', icon: UserFilled,  color: '#3b82f6', bg: '#eff6ff' },
  DELETE_USER:    { label: '删除用户', icon: Delete,      color: '#ef4444', bg: '#fef2f2' },
  UPDATE_USER:    { label: '更新用户', icon: EditPen,     color: '#6366f1', bg: '#eef2ff' },
  TOGGLE_USER:    { label: '启用/禁用', icon: Switch,     color: '#f59e0b', bg: '#fffbeb' },
  RESET_PWD:      { label: '重置密码', icon: Key,         color: '#f59e0b', bg: '#fffbeb' },
  ADJUST_POINTS:  { label: '调整积分', icon: Coin,        color: '#10b981', bg: '#ecfdf5' },
  CREATE_DOC:     { label: '创建文档', icon: DocumentAdd, color: '#3b82f6', bg: '#eff6ff' },
  DELETE_DOC:     { label: '删除文档', icon: Delete,      color: '#ef4444', bg: '#fef2f2' },
  BLOCK_DOC:      { label: '封禁文档', icon: Lock,        color: '#ef4444', bg: '#fef2f2' },
  UNBLOCK_DOC:    { label: '解封文档', icon: Unlock,      color: '#10b981', bg: '#ecfdf5' },
  APPROVE_DOC:    { label: '通过审核', icon: Select,      color: '#10b981', bg: '#ecfdf5' },
  REJECT_DOC:     { label: '驳回审核', icon: Close,       color: '#ef4444', bg: '#fef2f2' },
}
const meta = (a) => ACTION_META[a] || { label: a, icon: EditPen, color: '#64748b', bg: '#f1f5f9' }

// 客户端的领域分组（用户/文档）过滤，作用于当前页数据
const filtered = computed(() => {
  if (domain.value === 'all') return logs.value
  return logs.value.filter((l) =>
    (USER_ACTIONS.has(l.action) ? 'user' : 'doc') === domain.value,
  )
})

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
    ElMessage.error(e.response?.data?.error || '加载审计日志失败')
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
      <h2>操作审计</h2>
      <span class="tip">记录管理员的关键操作（用户 / 文档管理、权限变更、重置密码等）</span>
    </div>

    <el-tabs v-model="domain" class="seg" @tab-change="onDomain">
      <el-tab-pane label="全部" name="all" />
      <el-tab-pane label="用户操作" name="user" />
      <el-tab-pane label="文档操作" name="doc" />
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

    <el-table :data="filtered" border stripe v-loading="loading" style="width: 100%">
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
      <el-table-column label="动作" width="160">
        <template #default="{ row }">
          <span class="act" :style="{ color: meta(row.action).color, background: meta(row.action).bg }">
            <el-icon><component :is="meta(row.action).icon" /></el-icon>
            {{ meta(row.action).label }}
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
      <el-table-column prop="detail" label="说明" min-width="200" show-overflow-tooltip />
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
.sub { color: #94a3b8; font-size: 12px; }
.target { color: #334155; font-weight: 500; }
.time { color: #64748b; font-size: 13px; }
.pager { margin-top: 14px; justify-content: flex-end; }
.expand { padding: 6px 14px; line-height: 1.9; color: #475569; font-size: 13px; }
.expand .sub { margin-left: 4px; }
</style>
