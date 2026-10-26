<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { myFavorites, toggleFavorite, setFavoriteGroup, favoriteGroups, favoriteGroupMap } from '../api/kb'
import PageBar from '../components/PageBar.vue'
import { errMsg } from '../utils/errMsg'

const router = useRouter()
const posts = ref([])
const groups = ref([])                 // [{name, count}]
const groupMap = ref({})               // { docId: groupName }
const activeGroup = ref('all')         // all | none | 具体分组名
const shown = computed(() => {
  const list = posts.value
  if (activeGroup.value === 'all') return list
  if (activeGroup.value === 'none') return list.filter((d) => !groupMap.value[d.id])
  return list.filter((d) => groupMap.value[d.id] === activeGroup.value)
})
const loading = ref(false)

// 前端分页：收藏变多时分页浏览
const currentPage = ref(1)
const pageSize = ref(12)
const total = computed(() => shown.value.length)
const pagedPosts = computed(() =>
  shown.value.slice((currentPage.value - 1) * pageSize.value, currentPage.value * pageSize.value)
)

// 去掉 HTML 标签得到纯文本摘要
const stripHtml = (html) => {
  if (!html) return ''
  // DOMParser 替代 div.innerHTML（游离 img 仍会加载触发 onerror，2026-10-13 审查 H9）
  return (new DOMParser().parseFromString(html, 'text/html').body.textContent || '').replace(/\s+/g, ' ').trim()
}
const snippetOf = (c) => {
  const t = stripHtml(c)
  return t.length > 90 ? t.slice(0, 90) + '…' : t
}

const load = async () => {
  loading.value = true
  try {
    posts.value = (await myFavorites()).data || []
    const [gs, gm] = await Promise.all([favoriteGroups(), favoriteGroupMap()])
    groups.value = (gs && gs.data) || []
    groupMap.value = (gm && gm.data) || {}
  } catch (e) {
    ElMessage.error(errMsg(e, '加载失败'))
  } finally {
    loading.value = false
  }
}

const openPost = (id) => router.push('/post/' + id)

// 设置分组：弹窗输入（已有分组作为提示，输入空 = 移出分组）
async function editGroup(d) {
  const cur = groupMap.value[d.id] || ''
  let value
  try {
    const r = await ElMessageBox.prompt('输入分组名（留空表示移出分组）', '设置收藏分组', {
      inputValue: cur,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputPlaceholder: groups.value.length ? '例如：' + groups.value.map((g) => g.name).slice(0, 3).join(' / ') : '例如：培训资料'
    })
    value = r.value || ''
  } catch { return }
  try {
    await setFavoriteGroup(d.id, value.trim())
    ElMessage.success(value.trim() ? '已归入「' + value.trim() + '」' : '已移出分组')
    await load()
  } catch (e) {
    ElMessage.error(errMsg(e, '设置分组失败'))
  }
}

// 在收藏页直接取消收藏
const onRemove = async (d) => {
  try {
    await toggleFavorite(d.id)
    posts.value = posts.value.filter(p => p.id !== d.id)
    ElMessage.success('已取消收藏')
  } catch (e) {
    ElMessage.error(errMsg(e, '操作失败'))
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <h2 class="h">我的收藏 <span class="count">共 {{ posts.length }} 篇</span></h2>

    <div v-if="groups.length || activeGroup !== 'all'" class="gtabs">
      <button class="gtab" :class="{ on: activeGroup === 'all' }" @click="activeGroup = 'all'">全部 {{ posts.length }}</button>
      <button class="gtab" :class="{ on: activeGroup === 'none' }" @click="activeGroup = 'none'">未分组</button>
      <button v-for="g in groups" :key="g.name" class="gtab" :class="{ on: activeGroup === g.name }"
              @click="activeGroup = g.name">{{ g.name }} {{ g.count }}</button>
    </div>

    <p v-if="loading" class="state">加载中…</p>
    <ul class="grid" v-else-if="posts.length">
      <li v-for="d in pagedPosts" :key="d.id" class="card" @click="openPost(d.id)">
        <div class="row">
          <span class="title">{{ d.title }}</span>
          <span class="side">
            <span class="hot" v-if="d.hot" title="热帖">HOT</span>
            <button class="fav" title="设置分组" @click.stop="editGroup(d)">🗂</button>
            <button class="fav on" title="取消收藏" @click.stop="onRemove(d)">★</button>
          </span>
        </div>
        <div class="meta">
          <span class="tag folder" v-if="groupMap[d.id]" :title="'分组：' + groupMap[d.id]">📁 {{ groupMap[d.id] }}</span>
          <span class="tag" v-if="d.category">{{ d.category }}</span>
          <span class="tag grey" v-if="d.tags">{{ d.tags }}</span>
        </div>
        <p class="snippet">{{ snippetOf(d.content) }}</p>
      </li>
    </ul>
    <el-empty v-else description="还没有收藏，去首页给喜欢的帖子点个★吧" />

    <PageBar v-if="posts.length" v-model="currentPage" :total="total" :page-size="pageSize" />
  </div>
</template>

<style scoped>
.page { max-width: 880px; margin: 0 auto; padding: 24px; color: #0f172a; }
.h { font-size: 20px; margin: 0 0 14px; }
.count { font-size: 13px; color: #64748b; font-weight: normal; margin-left: 8px; }
.state { color: #64748b; padding: 12px 0; }
.grid { list-style: none; padding: 0; margin: 0; display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.gtabs { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 14px; }
.gtab { border: 1px solid #e2e8f0; background: #fff; border-radius: 16px; padding: 4px 12px; font-size: 12.5px; color: #4b5563; cursor: pointer; }
.gtab.on { background: #4f46e5; border-color: #4f46e5; color: #fff; }
.tag.folder { background: #eef2ff; color: #4338ca; }
.card { border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; cursor: pointer; transition: .15s; }
.card:hover { border-color: #4f46e5; box-shadow: 0 4px 14px rgba(79, 70, 229,.12); }
.row { display: flex; justify-content: space-between; align-items: center; }
.side { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.hot { display: inline-flex; align-items: center; justify-content: center; padding: 1px 7px; border-radius: 999px; font-size: 11px; font-weight: 700; line-height: 16px; letter-spacing: .5px; color: #fff; background: linear-gradient(135deg, #ff7a45, #ff4d4f); box-shadow: 0 1px 3px rgba(255,77,79,.28); }
.fav { border: none; background: none; cursor: pointer; font-size: 18px; color: #cbd5e1; padding: 0 2px; line-height: 1; transition: .15s; }
.fav.on { color: #f59e0b; }
.fav:hover { color: #94a3b8; }
.title { font-weight: 600; font-size: 16px; }
.meta { margin: 6px 0; display: flex; gap: 6px; flex-wrap: wrap; }
.tag { background: #eef2ff; color: #4f46e5; padding: 2px 8px; border-radius: 999px; font-size: 12px; }
.tag.grey { background: #f1f5f9; color: #64748b; }
.snippet { margin: 6px 0 0; color: #475569; font-size: 14px; line-height: 1.5; }

/* 分页样式由 PageBar 组件提供 */

/* 暗色模式适配：本页 .page / .card / .tag / .snippet / .state 已由全局 theme.css 覆盖，
   .hot 徽标与 .fav 星标色在暗底对比度达标，此处只补计数文字 */
html.dark .count { color: var(--el-text-color-secondary); }

/* ---------- 响应式布局（断点统一 1024 / 640）---------- */
@media (max-width: 1024px) {
  .page { padding: 16px; }
  .row { gap: 8px; }
  .title { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}
@media (max-width: 640px) {
  .page { padding: 12px; }
  .h { font-size: 18px; margin: 0 0 10px; }
  .grid { grid-template-columns: 1fr; gap: 10px; }
  .card { padding: 12px; }
  .title { font-size: 15px; }
  .snippet { font-size: 13px; }
}
</style>
