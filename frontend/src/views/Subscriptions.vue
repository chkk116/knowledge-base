<template>
  <div class="wrap">
    <div class="bar">
      <h2>我的订阅</h2>
      <span class="hint">订阅的文档被更新时，会收到站内通知（右上角铃铛）</span>
    </div>

    <div v-if="loading" class="tip">加载中…</div>
    <div v-else-if="!list.length" class="empty">
      还没有订阅任何文档。打开一篇文档，点正文上方「☆ 订阅」即可。
    </div>

    <div v-else class="sub-list">
      <div v-for="s in pagedList" :key="s.docId" class="sub-item" :class="{ dead: s.deleted }">
        <div class="sub-main" @click="!s.deleted && router.push('/post/' + s.docId)">
          <span class="sub-title">{{ s.title }}</span>
          <span class="sub-meta">
            <el-tag v-if="s.category" size="small" type="info">{{ s.category }}</el-tag>
            <span class="sub-time">订阅于 {{ fmtDateTime(s.subscribedAt) }}</span>
          </span>
        </div>
        <el-button size="small" type="danger" plain @click="remove(s)">取消订阅</el-button>
      </div>
    </div>

    <PageBar v-if="total > 0" v-model="currentPage" :total="total" :page-size="pageSize" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMySubscriptions, unsubscribeDoc } from '../api/kb'
import { fmtDateTime } from '../utils/time'
import PageBar from '../components/PageBar.vue'

const router = useRouter()
const list = ref([])
const loading = ref(false)

// 前端分页：订阅数变多时分页浏览，避免一次性渲染整张表
const currentPage = ref(1)
const pageSize = ref(10)
const total = computed(() => list.value.length)
const pagedList = computed(() =>
  list.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value)
)

const load = async () => {
  loading.value = true
  try {
    const { data } = await listMySubscriptions()
    list.value = data || []
  } catch (e) {
    ElMessage.error('加载订阅失败')
  } finally {
    loading.value = false
  }
}

const remove = async (s) => {
  try {
    await ElMessageBox.confirm('取消订阅《' + s.title + '》？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await unsubscribeDoc(s.docId)
    ElMessage.success('已取消订阅')
    list.value = list.value.filter(x => x.docId !== s.docId)
    // 删完当前页变空且不是第一页时回退一页
    if (pagedList.value.length === 0 && currentPage.value > 1) currentPage.value--
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '操作失败')
  }
}

onMounted(load)
</script>

<style scoped>
.wrap { max-width: 900px; margin: 0 auto; padding: 8px 4px 40px; }
.bar { display: flex; align-items: baseline; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.bar h2 { margin: 0; font-size: 20px; color: var(--el-text-color-primary); }
.hint, .tip { color: var(--el-text-color-secondary); font-size: 13px; }
.empty { padding: 40px; text-align: center; color: var(--el-text-color-secondary); background: var(--el-fill-color-lighter); border-radius: 10px; font-size: 14px; }

.sub-list { display: flex; flex-direction: column; gap: 8px; }
.sub-item { display: flex; align-items: center; gap: 12px; padding: 12px 14px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-bg-color); }
.sub-item:hover { border-color: #6366f1; }
.sub-main { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex: 1; min-width: 0; cursor: pointer; }
.sub-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--el-text-color-primary); font-size: 14px; }
.sub-main:hover .sub-title { color: #6366f1; }
.sub-meta { display: inline-flex; align-items: center; gap: 8px; flex-shrink: 0; }
.sub-time { color: var(--el-text-color-secondary); font-size: 12px; }
.sub-item.dead { opacity: .6; }
.sub-item.dead .sub-main { cursor: not-allowed; }

/* 分页样式由 PageBar 组件提供 */

/* ---------- 响应式布局（断点统一 1024 / 640）---------- */
@media (max-width: 1024px) {
  .wrap { max-width: 100%; }
  .sub-item { gap: 10px; }
  .sub-meta { gap: 6px; }
}
@media (max-width: 640px) {
  .wrap { padding: 4px 0 32px; }
  .bar { gap: 8px; margin-bottom: 12px; }
  .bar h2 { font-size: 18px; }
  .empty { padding: 28px 12px; font-size: 13px; }
  .sub-item { flex-wrap: wrap; gap: 8px; padding: 10px 12px; }
  .sub-main { flex: 1 1 100%; flex-direction: column; align-items: flex-start; gap: 4px; }
  .sub-title { width: 100%; font-size: 14px; }
  .sub-meta { flex-wrap: wrap; }
  .sub-item > .el-button { margin-left: auto; }
}
</style>
