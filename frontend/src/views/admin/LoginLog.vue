<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search, CircleCheck, CircleClose } from '@element-plus/icons-vue'
import { listLoginLogs } from '../../api/admin'

const logs = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(20)
const total = ref(0)

// —— 搜索条件 ——
const username = ref('')            // 用户名（模糊）
const result = ref('all')           // all | success | fail
const dateRange = ref([])           // [开始Date, 结束Date]

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
    if (username.value.trim()) params.username = username.value.trim()
    if (result.value === 'success') params.success = true
    if (result.value === 'fail') params.success = false
    if (dateRange.value && dateRange.value.length === 2) {
      params.start = fmt(dateRange.value[0])                       // 当天 00:00:00
      const e = new Date(dateRange.value[1])                       // 当天 23:59:59
      e.setHours(23, 59, 59, 0)
      params.end = fmt(e)
    }
    const { data } = await listLoginLogs(params)
    logs.value = data.content || []
    total.value = data.totalElements || 0
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '加载登录日志失败')
  } finally {
    loading.value = false
  }
}

const doSearch = () => { page.value = 1; load() }
const onReset = () => { username.value = ''; result.value = 'all'; dateRange.value = []; page.value = 1; load() }
const onPage = (p) => { page.value = p; load() }
const onSize = (s) => { size.value = s; page.value = 1; load() }
const onResult = () => { page.value = 1; load() }

onMounted(load)
</script>

<template>
  <div class="ll">
    <div class="ll-header">
      <h2>登录日志</h2>
      <span class="tip">记录每次登录尝试（成功 / 失败及原因），用于安全审计与异常登录排查</span>
    </div>

    <!-- 搜索条：用户名 + 结果 + 时间范围 -->
    <div class="search-bar">
      <el-input v-model="username" class="s-name" placeholder="用户名（模糊）" clearable
                @keyup.enter="doSearch" @clear="doSearch" />
      <el-select v-model="result" class="s-result" @change="onResult">
        <el-option label="全部结果" value="all" />
        <el-option label="仅成功" value="success" />
        <el-option label="仅失败" value="fail" />
      </el-select>
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
            <div><b>用户名：</b>{{ row.username || '-' }} <span class="sub" v-if="row.userId">#{{ row.userId }}</span></div>
            <div><b>结果：</b>{{ row.success ? '成功' : '失败' }}<template v-if="!row.success">（{{ row.reason || '-' }}）</template></div>
            <div><b>IP：</b>{{ row.ip || '-' }}</div>
            <div><b>UA：</b><span class="ua">{{ row.userAgent || '-' }}</span></div>
            <div><b>时间：</b>{{ (row.createdAt || '').replace('T', ' ') }}</div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="100">
        <template #default="{ row }">
          <span class="res" :class="row.success ? 'ok' : 'bad'">
            <el-icon><component :is="row.success ? CircleCheck : CircleClose" /></el-icon>
            {{ row.success ? '成功' : '失败' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="用户名" width="150">
        <template #default="{ row }">
          {{ row.username || '-' }} <span class="sub" v-if="row.userId">#{{ row.userId }}</span>
        </template>
      </el-table-column>
      <el-table-column label="失败原因" min-width="180" show-overflow-tooltip class-name="stack-hide">
        <template #default="{ row }">{{ row.success ? '-' : (row.reason || '-') }}</template>
      </el-table-column>
      <el-table-column prop="ip" label="IP" width="140" />
      <el-table-column prop="userAgent" label="浏览器 / 设备" min-width="200" show-overflow-tooltip class-name="stack-hide" />
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
.ll { padding: 20px; max-width: 1200px; margin: 0 auto; }
.ll-header { display: flex; align-items: baseline; gap: 12px; margin-bottom: 12px; }
.ll-header h2 { margin: 0; font-size: 20px; color: #111827; }
.tip { color: #94a3b8; font-size: 13px; }
.search-bar { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 12px; }
.search-bar .s-name { width: 180px; }
.search-bar .s-result { width: 120px; }
.search-bar .s-date { width: 280px; }
.res { display: inline-flex; align-items: center; gap: 5px; padding: 3px 10px; border-radius: 999px; font-size: 13px; font-weight: 500; }
.res.ok { color: #10b981; background: #ecfdf5; }
.res.bad { color: #ef4444; background: #fef2f2; }
.res .el-icon { font-size: 15px; }
.sub { color: #94a3b8; font-size: 12px; }
.time { color: #64748b; font-size: 13px; }
.ua { word-break: break-all; }
.pager { margin-top: 14px; justify-content: flex-end; }
.expand { padding: 6px 14px; line-height: 1.9; color: #475569; font-size: 13px; }
.expand .sub { margin-left: 4px; }

/* 暗色模式适配 */
html.dark .ll-header h2 { color: var(--el-text-color-primary); }
html.dark .expand { color: var(--el-text-color-regular); }

/* ============================================================
   响应式布局（断点统一：≤1024 平板 / ≤640 手机）
   只写 width / flex / padding / margin / font-size
   ============================================================ */
@media (max-width: 1024px) {
  .ll { padding: 14px; }
  .ll-header { flex-wrap: wrap; gap: 8px; }
  .search-bar { gap: 8px; }
  .search-bar .s-name { width: 160px; }
  .search-bar .s-date { width: 260px; }
  .pager { margin-top: 12px; }
}

@media (max-width: 640px) {
  .ll { padding: 12px; }
  .ll-header h2 { font-size: 18px; }
  .tip { font-size: 12px; }
  .search-bar .s-name,
  .search-bar .s-result,
  .search-bar .s-date { width: 100%; }
  .expand { padding: 6px 12px; font-size: 12px; line-height: 1.7; }
  :deep(.el-table) { font-size: 13px; }
}
</style>
