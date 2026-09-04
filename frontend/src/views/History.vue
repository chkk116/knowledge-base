<script setup>
import { ref, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useHistoryStore } from '../store/history'
import { fromNow } from '../utils/time'
import PageBar from '../components/PageBar.vue'

const router = useRouter()
const history = useHistoryStore()

const items = computed(() => history.items)

// 前端分页：浏览历史多了分页浏览
const currentPage = ref(1)
const pageSize = ref(12)
const total = computed(() => items.value.length)
const pagedItems = computed(() =>
  items.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value)
)
// 删除单条后当前页可能越界，回退到最后一页
watch(items, () => {
  const maxPage = Math.max(1, Math.ceil(items.value.length / pageSize.value))
  if (currentPage.value > maxPage) currentPage.value = maxPage
})

const goPost = (id) => router.push('/post/' + id)
// 清空是破坏性操作且不可恢复，必须二次确认（历史记录无回收站）
const clearAll = async () => {
  try {
    await ElMessageBox.confirm('确定清空全部浏览历史？此操作不可恢复。', '清空浏览历史', {
      type: 'warning', confirmButtonText: '清空', cancelButtonText: '取消',
    })
  } catch { return }
  history.clear()
  ElMessage.success('已清空浏览历史')
}
const rm = (id) => history.remove(id)
</script>

<template>
  <div class="hist">
    <div class="top">
      <h2>浏览历史</h2>
      <el-button v-if="items.length" size="small" text type="danger" @click="clearAll">清空</el-button>
    </div>

    <el-empty v-if="!items.length" description="还没有浏览记录，去看看文章吧" />

    <ul v-else class="hlist">
      <li v-for="h in pagedItems" :key="h.id" class="hi" @click="goPost(h.id)">
        <div class="ht">
          <span class="title">{{ h.title }}</span>
          <el-popconfirm title="删除这条浏览记录？" confirm-button-text="删除" cancel-button-text="取消"
                         :width="200" @confirm="rm(h.id)">
            <template #reference>
              <button class="x" @click.stop title="删除这条">×</button>
            </template>
          </el-popconfirm>
        </div>
        <div class="hm">
          <span v-if="h.category" class="cat">{{ h.category }}</span>
          <span v-if="h.author" class="au">{{ h.author }}</span>
          <span class="tm">浏览于 {{ fromNow(h.time) }}</span>
          <span class="vw">👁 {{ h.views || 0 }}</span>
        </div>
      </li>
    </ul>

    <PageBar v-if="items.length" v-model="currentPage" :total="total" :page-size="pageSize" />
  </div>
</template>

<style scoped>
.hist { max-width: 860px; margin: 0 auto; color: #0f172a; }
/* 分页样式由 PageBar 组件提供 */
.top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.top h2 { font-size: 20px; margin: 0; }
.hlist { list-style: none; padding: 0; margin: 0; }
.hi {
  padding: 12px 14px; border: 1px solid #eef2f7; border-radius: 10px;
  margin-bottom: 10px; background: #fff; cursor: pointer; transition: .15s;
}
.hi:hover { border-color: #c7d2fe; box-shadow: 0 2px 8px rgba(99, 102, 241, .08); }
.ht { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.title { font-weight: 600; color: #1e293b; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.x {
  flex: none; border: none; background: none; color: #94a3b8; cursor: pointer;
  font-size: 18px; line-height: 1; padding: 0 2px;
}
.x:hover { color: #ef4444; }
.hm { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; margin-top: 6px; color: #94a3b8; font-size: 12px; }
.cat { color: #6366f1; background: #eef2ff; padding: 1px 8px; border-radius: 10px; }
.au { color: #475569; }

/* 暗色适配 */
html.dark .hist { color: #e7e9ee; }
html.dark .hi { background: #181820; border-color: #2c2c36; }
html.dark .hi:hover { border-color: #4f46e5; }
html.dark .title { color: #e7e9ee; }
html.dark .cat { background: #232347; color: #a5b4fc; }
html.dark .au { color: #c2c6d2; }

/* ---------- 响应式布局（断点统一 1024 / 640）---------- */
@media (max-width: 1024px) {
  .hist { max-width: 100%; }
  .hi { padding: 12px 14px; }
}
@media (max-width: 640px) {
  .top { margin-bottom: 10px; }
  .top h2 { font-size: 18px; }
  .hi { padding: 10px 12px; margin-bottom: 8px; }
  .ht { gap: 8px; }
  .title { font-size: 14px; }
  .hm { gap: 8px; font-size: 11px; }
}
</style>
