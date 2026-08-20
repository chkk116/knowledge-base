<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useHistoryStore } from '../store/history'
import { fromNow } from '../utils/time'

const router = useRouter()
const history = useHistoryStore()

const items = computed(() => history.items)

const goPost = (id) => router.push('/post/' + id)
const clearAll = () => {
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
      <li v-for="h in items" :key="h.id" class="hi" @click="goPost(h.id)">
        <div class="ht">
          <span class="title">{{ h.title }}</span>
          <button class="x" @click.stop="rm(h.id)" title="删除这条">×</button>
        </div>
        <div class="hm">
          <span v-if="h.category" class="cat">{{ h.category }}</span>
          <span v-if="h.author" class="au">{{ h.author }}</span>
          <span class="tm">浏览于 {{ fromNow(h.time) }}</span>
          <span class="vw">👁 {{ h.views || 0 }}</span>
        </div>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.hist { max-width: 860px; margin: 0 auto; color: #0f172a; }
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
</style>
