<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listBlockedDocs, unblockDoc } from '../../api/admin'

const router = useRouter()
const docs = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(15)
const total = ref(0)

const load = async () => {
  loading.value = true
  try {
    const { data } = await listBlockedDocs({ page: page.value - 1, size: size.value })
    docs.value = data.content || []
    total.value = data.totalElements || 0
  } catch (e) {
    ElMessage.error('加载黑名单失败')
  } finally {
    loading.value = false
  }
}

const goDetail = (d) => router.push('/post/' + d.id)

const unblock = async (d) => {
  try {
    await unblockDoc(d.id)
    ElMessage.success('已解封')
    await load()
    // 解封后该条离开列表：末页删完会停在空页码，回退一页再拉一次
    if (docs.value.length === 0 && page.value > 1) { page.value--; await load() }
  } catch (e) {
    ElMessage.error('操作失败')
  }
}

const onPage = (p) => { page.value = p; load() }
const onSize = (s) => { size.value = s; page.value = 1; load() }

onMounted(load)
</script>

<template>
  <div>
    <el-alert
      v-if="!loading && !docs.length"
      title="黑名单为空，当前没有被封禁的帖子"
      type="success"
      :closable="false"
      style="margin-bottom: 12px;"
    />
    <el-table v-stack-table v-else :data="docs" border stripe v-loading="loading">
      <el-table-column prop="id" label="ID" width="70"  class-name="stack-hide" />
      <el-table-column label="标题">
        <template #default="{ row }">
          <span class="link" @click="goDetail(row)">{{ row.title }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="category" label="分类" width="120" />
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="success" @click="unblock(row)">解封</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="total > 0" class="pager"
                   layout="total, sizes, prev, pager, next, jumper"
                   :total="total" :page-size="size" :current-page="page"
                   :page-sizes="[10, 15, 30, 50]"
                   @current-change="onPage" @size-change="onSize" />
  </div>
</template>

<style scoped>
.link { color: #4f46e5; cursor: pointer; }
.link:hover { text-decoration: underline; }
.pager { margin-top: 14px; justify-content: flex-end; }

/* ============================================================
   响应式布局（断点统一：≤1024 平板 / ≤640 手机）
   本页无定宽容器，表格靠 el-table 自带横向滚动，只需收紧字号与间距
   ============================================================ */
@media (max-width: 1024px) {
  .pager { margin-top: 12px; justify-content: center; }
}

@media (max-width: 640px) {
  :deep(.el-table) { font-size: 13px; }
  .link { word-break: break-word; }
  .pager { margin-top: 10px; }
}
</style>
