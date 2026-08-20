<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listMyDocs, deleteMyDoc, submitReview, withdrawReview } from '../api/kb'

const router = useRouter()
const docs = ref([])

const statusText = (s) => {
  if (s === 'BLOCKED') return '已封禁'
  if (s === 'DRAFT') return '草稿'
  if (s === 'PENDING_REVIEW') return '待审核'
  if (s === 'PUBLISHED') return '已发布'
  return '正常'
}

const load = async () => {
  docs.value = (await listMyDocs()).data || []
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
    await ElMessageBox.confirm('确定删除《' + d.title + '》？', '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteMyDoc(d.id)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="bar">
      <el-button type="primary" @click="router.push('/write')">写文章</el-button>
      <span class="hint">这里显示你本人的帖子；草稿需「提交审核」，管理员通过后才对外公开；待审核和已发布的帖子都可「撤回」到草稿</span>
    </div>

    <el-empty v-if="!docs.length" description="你还没有发布过帖子" />

    <el-table v-else :data="docs" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
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
      <el-table-column label="操作" width="220">
        <template #default="{ row }">
          <el-button size="small" @click="edit(row)">编辑</el-button>
          <el-button size="small" type="primary" v-if="row.status === 'DRAFT'" @click="submit(row)">提交</el-button>
          <el-button size="small" v-if="row.status === 'PENDING_REVIEW' || row.status === 'PUBLISHED'" @click="withdraw(row)">撤回</el-button>
          <el-button size="small" type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.bar { margin-bottom: 12px; display: flex; align-items: center; gap: 12px; }
.hint { color: #94a3b8; font-size: 13px; }
.reason { color: #b45309; font-size: 13px; }
.muted { color: #cbd5e1; }
.ttl { color: #1d4ed8; cursor: pointer; }
.ttl:hover { color: #4338ca; text-decoration: underline; }
</style>
