<script setup>
/**
 * 移动端原生「内容管理」页（替代 iframe 嵌桌面 DocManage：手机上桌面表格不可用）
 *
 * 能力：搜索 / 状态筛选 / 分页加载；行点开底部弹层做高频操作——
 *   ① 置顶 / 取消置顶（POST /api/admin/docs/{id}/pin|unpin）
 *   ② 屏蔽（下架）/ 解屏蔽（POST /api/admin/docs/{id}/block|unblock）
 *   ③ 删除（DELETE /api/admin/docs/{id}，危险操作二次确认）
 * 低频操作（新建 / 编辑正文 / 回收站）仍去 Web 端做，手机上保持精简。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { listDocsAdmin, pinDoc, unpinDoc, blockDoc, unblockDoc, deleteDoc } from '../../api/admin'

const router = useRouter()

const keyword = ref('')
const filter = ref('all')
const docs = ref([])
const page = ref(0)
const hasMore = ref(false)
const loading = ref(false)
const busy = ref(false)

const STATUS = {
  PUBLISHED: { label: '已发布', cls: 'green' },
  PENDING_REVIEW: { label: '待审核', cls: 'orange' },
  DRAFT: { label: '草稿', cls: 'gray' },
  BLOCKED: { label: '已屏蔽', cls: 'red' },
}
const FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'PUBLISHED', label: '已发布' },
  { key: 'PENDING_REVIEW', label: '待审核' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'BLOCKED', label: '已屏蔽' },
]

async function load(reset = false) {
  if (loading.value) return
  loading.value = true
  try {
    const p = reset ? 0 : page.value
    const params = { page: p, size: 20, sort: 'newest' }
    if (keyword.value.trim()) params.keyword = keyword.value.trim()
    if (filter.value !== 'all') params.status = filter.value
    const { data } = await listDocsAdmin(params)
    const rows = Array.isArray(data) ? data : (data.content || [])
    docs.value = reset ? rows : [...docs.value, ...rows]
    page.value = p + 1
    hasMore.value = !data.last
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

function doSearch() { load(true) }
function pickFilter(k) { filter.value = k; load(true) }

// ---------- 行操作（底部弹层） ----------
const active = ref(null)
const sheetOpen = ref(false)
function openSheet(d) { active.value = d; sheetOpen.value = true }

async function run(fn, okMsg) {
  if (busy.value) return
  busy.value = true
  try {
    await fn()
    window.__kbMsg?.success(okMsg)
    sheetOpen.value = false
    load(true)
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '操作失败')
  } finally {
    busy.value = false
  }
}

const togglePin = () => run(
  () => active.value.pinned ? unpinDoc(active.value.id) : pinDoc(active.value.id),
  active.value.pinned ? '已取消置顶' : '已置顶'
)
const toggleBlock = () => run(
  () => active.value.status === 'BLOCKED' ? unblockDoc(active.value.id) : blockDoc(active.value.id),
  active.value.status === 'BLOCKED' ? '已解除屏蔽' : '已屏蔽（下架）'
)
const doDelete = () => {
  if (busy.value || !active.value) return
  ElMessageBox.confirm(
    `确定删除《${active.value.title || '该文档'}》？删除后进入回收站，可从 Web 端回收站恢复。`,
    '删除文档',
    { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' }
  ).then(() => run(() => deleteDoc(active.value.id), '已删除')).catch(() => {})
}

const fmtDate = (s) => (s ? String(s).slice(0, 10) : '—')
const statusOf = (d) => STATUS[d.status] || { label: d.status || '—', cls: 'gray' }

load()
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">内容管理</div>
      <span class="h-ph"></span>
    </header>

    <div class="tools">
      <div class="searchbox">
        <el-icon><Search /></el-icon>
        <input v-model="keyword" placeholder="搜索标题 / 作者" @keyup.enter="doSearch" />
        <button v-if="keyword" class="sb-clear" @click="keyword = ''; doSearch()"><el-icon><Close /></el-icon></button>
      </div>
      <div class="chips">
        <button v-for="f in FILTERS" :key="f.key" class="chip" :class="{ on: filter === f.key }"
                @click="pickFilter(f.key)">{{ f.label }}</button>
      </div>
    </div>

    <div class="list">
      <div class="m-card">
        <button v-for="d in docs" :key="d.id" class="m-row" type="button" @click="openSheet(d)">
          <span class="m-row-body">
            <span class="m-row-title">
              <el-icon v-if="d.pinned" class="pin"><StarFilled /></el-icon>
              {{ d.title || '未命名' }}
            </span>
            <span class="m-row-desc">{{ d.ownerName || '匿名' }} · {{ fmtDate(d.updatedAt || d.createdAt) }} · 👁 {{ d.views || 0 }}</span>
          </span>
          <span class="m-chip" :class="statusOf(d).cls">{{ statusOf(d).label }}</span>
        </button>
        <div v-if="!docs.length && !loading" class="empty">没有匹配的文档</div>
      </div>
      <button v-if="hasMore" class="m-btn ghost more" :disabled="loading" @click="load()">
        {{ loading ? '加载中…' : '加载更多' }}
      </button>
    </div>

    <transition name="sheet-up">
      <div v-if="sheetOpen" class="m-sheet-mask" @click.self="sheetOpen = false">
        <div class="m-sheet">
          <div class="m-sheet-grip"></div>
          <div class="m-sheet-title">{{ active?.title || '文档' }}</div>
          <button class="m-sheet-item" :disabled="busy" @click="togglePin()">
            <el-icon><component :is="active?.pinned ? 'Star' : 'StarFilled'" /></el-icon>
            {{ active?.pinned ? '取消置顶' : '置顶' }}
          </button>
          <button class="m-sheet-item" :class="{ danger: active?.status !== 'BLOCKED' }" :disabled="busy"
                  @click="toggleBlock()">
            <el-icon><component :is="active?.status === 'BLOCKED' ? 'Unlock' : 'Lock'" /></el-icon>
            {{ active?.status === 'BLOCKED' ? '解除屏蔽' : '屏蔽（下架）' }}
          </button>
          <button class="m-sheet-item danger" :disabled="busy" @click="doDelete()">
            <el-icon><Delete /></el-icon> 删除文档
          </button>
          <button class="m-sheet-item cancel" @click="sheetOpen = false">取消</button>
        </div>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.page { min-height: 100%; display: flex; flex-direction: column; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.tools { padding: 8px 16px 4px; }
.searchbox {
  display: flex; align-items: center; gap: 8px;
  height: 42px; padding: 0 14px;
  background: var(--m-card); border-radius: 21px; box-shadow: var(--m-shadow);
}
.searchbox input {
  flex: 1 1 auto; min-width: 0; border: none; outline: none; background: transparent;
  font-size: 16px; color: var(--m-text); font-family: inherit;
}
.sb-clear { border: none; background: transparent; color: var(--m-text-sub); display: inline-flex; padding: 4px; }
.chips { display: flex; gap: 8px; padding: 10px 0 8px; overflow-x: auto; }
.chip {
  flex: none; height: 30px; padding: 0 14px; border-radius: 15px; border: none;
  background: var(--m-card); color: var(--m-text-sub); font-size: 13px;
}
.chip.on { background: var(--m-primary); color: #fff; font-weight: 500; }
.list { flex: 1 1 auto; padding: 4px 16px 16px; }
.pin { color: #f59e0b; font-size: 14px; margin-right: 2px; vertical-align: -1px; }
.empty { padding: 34px 0; text-align: center; color: var(--m-text-sub); font-size: 13.5px; }
.more { width: 100%; margin-top: 12px; }
</style>
