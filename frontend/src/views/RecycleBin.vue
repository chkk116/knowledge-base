<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listRecycle, restoreDoc, purgeDoc,
  listRecycleAdmin, restoreDocAdmin, purgeDocAdmin,
} from '../api/kb'
import { useAuthStore } from '../store/auth'
import { fromNow, fmtDateTime } from '../utils/time'
import PageBar from '../components/PageBar.vue'
import { errMsg } from '../utils/errMsg'

const router = useRouter()
const auth = useAuthStore()
const docs = ref([])
const loading = ref(false)

// 前端分页：回收站文档变多时分页浏览
const currentPage = ref(1)
const pageSize = ref(10)
const total = computed(() => docs.value.length)
const pagedDocs = computed(() =>
  docs.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value)
)

// 管理员可见全部回收站文档；普通用户仅看自己移入回收站的
const load = async () => {
  loading.value = true
  try {
    const fn = auth.isAdmin ? listRecycleAdmin : listRecycle
    docs.value = (await fn()).data || []
    // 删除/恢复后当前页可能越界，回退到最后一页
    const maxPage = Math.max(1, Math.ceil(docs.value.length / pageSize.value))
    if (currentPage.value > maxPage) currentPage.value = maxPage
  } catch (e) {
    ElMessage.error(errMsg(e, '加载失败'))
  } finally {
    loading.value = false
  }
}

const restore = async (d) => {
  try {
    await ElMessageBox.confirm('确定恢复《' + d.title + '》？恢复后文档重新对外可见（若已发布过）。', '恢复文档', { type: 'info' })
  } catch { return }
  try {
    const fn = auth.isAdmin ? restoreDocAdmin : restoreDoc
    await fn(d.id)
    ElMessage.success('已恢复')
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '恢复失败'))
  }
}

const purge = async (d) => {
  try {
    await ElMessageBox.confirm('彻底删除《' + d.title + '》？此操作不可撤销，文档及其历史版本将永久消失。',
      '彻底删除', { type: 'warning', confirmButtonText: '彻底删除', cancelButtonText: '取消', confirmButtonClass: 'el-button--danger' })
  } catch { return }
  try {
    const fn = auth.isAdmin ? purgeDocAdmin : purgeDoc
    await fn(d.id)
    ElMessage.success('已彻底删除')
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '删除失败'))
  }
}

const goPost = (d) => router.push('/post/' + d.id)

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <el-button type="primary" @click="router.push('/my/posts')">返回我的帖子</el-button>
      <span class="hint">
        这里是你（{{ auth.isAdmin ? '管理员可查看全部' : '本人' }}）移入回收站的文档；
        可在 30 天内恢复，或彻底删除。
      </span>
    </div>

    <el-empty v-if="!docs.length" description="回收站是空的" />

    <el-table v-stack-table v-else :data="pagedDocs" border stripe>
      <el-table-column prop="id" label="ID" width="70"  class-name="stack-hide" />
      <el-table-column prop="title" label="标题" min-width="220">
        <template #default="{ row }">
          <span class="ttl" @click="goPost(row)">{{ row.title || '（无标题）' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="110" />
      <el-table-column prop="ownerName" label="作者" width="130" v-if="auth.isAdmin" />
      <el-table-column label="移入时间" width="190">
        <template #default="{ row }">{{ row.deletedAt ? fromNow(row.deletedAt) + '（' + fmtDateTime(row.deletedAt) + '）' : '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button size="small" type="success" @click="restore(row)">恢复</el-button>
          <el-button size="small" type="danger" @click="purge(row)">彻底删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <PageBar v-if="docs.length" v-model="currentPage" :total="total" :page-size="pageSize" />
  </div>
</template>

<style scoped>
.bar { margin-bottom: 12px; display: flex; align-items: center; gap: 12px; }
/* 分页样式由 PageBar 组件提供 */
.hint { color: #94a3b8; font-size: 13px; }
.ttl { color: #1d4ed8; cursor: pointer; }
.ttl:hover { color: #4338ca; text-decoration: underline; }

/* 暗色模式适配 */
html.dark .hint { color: var(--el-text-color-secondary); }
html.dark .ttl { color: #93c5fd; }
html.dark .ttl:hover { color: #a5b4fc; }

/* ============================================================
   响应式：顶部操作栏换行（表格横向溢出由 el-table 自带滚动条处理）
   ============================================================ */
@media (max-width: 1024px) {
  .bar { flex-wrap: wrap; gap: 8px; }
  .hint { flex: 1 1 100%; }
}

@media (max-width: 640px) {
  .hint { font-size: 12px; line-height: 1.6; }
}
</style>
