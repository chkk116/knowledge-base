<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMyDocs, deleteMyDoc, submitReview, withdrawReview } from '../api/kb'
import PageBar from '../components/PageBar.vue'

const router = useRouter()
const docs = ref([])

// 前端分页：帖子多了分页浏览
const currentPage = ref(1)
const pageSize = ref(10)
const total = computed(() => docs.value.length)
const pagedDocs = computed(() =>
  docs.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value)
)

const statusText = (s) => {
  if (s === 'BLOCKED') return '已封禁'
  if (s === 'DRAFT') return '草稿'
  if (s === 'PENDING_REVIEW') return '待审核'
  if (s === 'PUBLISHED') return '已发布'
  return '正常'
}

const load = async () => {
  docs.value = (await listMyDocs()).data || []
  // 删除/操作后当前页可能越界，回退到最后一页
  const maxPage = Math.max(1, Math.ceil(docs.value.length / pageSize.value))
  if (currentPage.value > maxPage) currentPage.value = maxPage
}

const edit = (d) => router.push('/write/edit/' + d.id)
const goPost = (d) => router.push('/post/' + d.id)

const submit = async (d) => {
  try {
    await submitReview(d.id)
    ElMessage.success('已提交审核，等待管理员通过')
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '提交失败')
  }
}

const withdraw = async (d) => {
  const published = d.status === 'PUBLISHED'
  const tip = published
    ? '撤回《' + d.title + '》？撤回后文章将下架为草稿，其他人不再可见；发布获得的积分会一并扣回，修改后重新提交审核通过即可恢复。'
    : '撤回《' + d.title + '》的审核申请？撤回后将退回草稿，可修改后再次提交。'
  try {
    await ElMessageBox.confirm(tip, published ? '撤回已发布文章' : '撤回审核', { type: 'warning' })
  } catch {
    return
  }
  try {
    await withdrawReview(d.id)
    ElMessage.success(published ? '已下架，退回草稿' : '已撤回，退回草稿')
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '撤回失败')
  }
}

const remove = async (d) => {
  try {
    await ElMessageBox.confirm('确定将《' + d.title + '》移入回收站？可在「回收站」中恢复，超过保留期或手动彻底删除后将永久消失。', '移入回收站', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteMyDoc(d.id)
    ElMessage.success('已移入回收站')
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '移入回收站失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <el-button type="primary" @click="router.push('/write')">写文章</el-button>
      <el-button @click="router.push('/recycle')">回收站</el-button>
      <span class="hint">这里显示你本人的帖子；草稿需「提交审核」，管理员通过后才对外公开；待审核和已发布的帖子都可「撤回」到草稿</span>
    </div>

    <el-empty v-if="!docs.length" description="你还没有发布过帖子" />

    <el-table v-stack-table v-else :data="pagedDocs" border stripe>
      <el-table-column prop="id" label="ID" width="70"  class-name="stack-hide" />
      <el-table-column prop="title" label="标题" min-width="220">
        <template #default="{ row }">
          <span class="ttl" @click="goPost(row)">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="110" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'BLOCKED' ? 'danger' : (row.status === 'PENDING_REVIEW' ? 'warning' : (row.status === 'DRAFT' ? 'info' : 'success'))">
            {{ statusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updatedAt" label="更新时间" width="190" />
      <el-table-column label="驳回理由" min-width="200">
        <template #default="{ row }">
          <span v-if="row.rejectReason" class="reason">⚠ {{ row.rejectReason }}</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="280">
        <template #default="{ row }">
          <el-button size="small" @click="edit(row)">编辑</el-button>
          <el-button size="small" type="primary" v-if="row.status === 'DRAFT'" @click="submit(row)">提交</el-button>
          <el-button size="small" v-if="row.status === 'PENDING_REVIEW' || row.status === 'PUBLISHED'" @click="withdraw(row)">撤回</el-button>
          <el-button size="small" @click="router.push('/post/' + row.id + '/versions')">版本</el-button>
          <el-button size="small" type="danger" @click="remove(row)">移回收站</el-button>
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
.reason { color: #b45309; font-size: 13px; }
.muted { color: #cbd5e1; }
.ttl { color: #1d4ed8; cursor: pointer; }
.ttl:hover { color: #4338ca; text-decoration: underline; }

/* 暗色模式适配（el-table / el-tag 由 Element 暗色变量自动处理，这里只覆盖自定义文字色） */
html.dark .hint { color: var(--el-text-color-secondary); }
html.dark .reason { color: #fbbf24; }
html.dark .muted { color: var(--el-text-color-placeholder); }
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
