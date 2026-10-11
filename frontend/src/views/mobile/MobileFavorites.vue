<script setup>
/**
 * 移动端「我的收藏」（稍后读）
 *
 * 本地收藏：App 读的是空间文档（SharedDocument），后端 /api/favorites 针对的是
 * 公开文章（Document），两者不通；本地收藏还能离线可用（见 utils/bookmarks.js）。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { listBookmarks, removeBookmark, clearBookmarks } from '../../utils/bookmarks'

const router = useRouter()
const list = ref([])

function load() { list.value = listBookmarks() }

function fmtDay(t) {
  if (!t) return ''
  const d = new Date(t)
  const diff = Date.now() - d.getTime()
  const dd = 86400000
  if (diff < 3600000) return Math.max(1, Math.floor(diff / 60000)) + ' 分钟前'
  if (diff < dd) return Math.floor(diff / 3600000) + ' 小时前'
  if (diff < dd * 30) return Math.floor(diff / dd) + ' 天前'
  return `${d.getMonth() + 1}月${d.getDate()}日`
}

function open(it) { router.push(`/doc/${it.spaceId}/${it.docId}`) }

function remove(it, e) {
  e.stopPropagation()
  removeBookmark(it.spaceId, it.docId)
  load()
  window.__kbMsg?.success('已取消收藏')
}

async function clearAll() {
  try {
    await ElMessageBox.confirm('清空全部收藏？', '清空收藏', {
      confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning',
    })
  } catch { return }
  clearBookmarks()
  load()
}

const empty = computed(() => !list.value.length)

onMounted(() => {
  load()
  window.addEventListener('kb:bookmarks-changed', load)
})
onUnmounted(() => window.removeEventListener('kb:bookmarks-changed', load))
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">我的收藏</div>
      <button v-if="!empty" class="m-header-act" @click="clearAll"><el-icon :size="18"><Delete /></el-icon></button>
      <span v-else class="h-ph"></span>
    </header>

    <div class="body">
      <div class="hint">点右上角 ⋯ 收藏的文档会出现在这里，离线也能打开</div>

      <div v-if="empty" class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Star /></el-icon></span>
          <div>还没有收藏</div>
          <div style="font-size:12.5px;color:var(--m-text-dim)">读文档时点「更多 → 收藏」即可</div>
          <button class="m-btn sm" style="margin-top:6px" @click="router.push('/docs')">去文档库</button>
        </div>
      </div>

      <div v-else class="m-card">
        <div v-for="it in list" :key="it.spaceId + ':' + it.docId" class="m-row fav">
          <button class="fav-main" type="button" @click="open(it)">
            <span class="m-ico orange"><el-icon><Star /></el-icon></span>
            <span class="m-row-body">
              <span class="m-row-title">{{ it.title }}</span>
              <span class="m-row-desc">{{ it.spaceName || '文档' }} · {{ fmtDay(it.at) }}</span>
            </span>
          </button>
          <button class="fav-x" type="button" @click="remove(it, $event)">
            <el-icon :size="16"><Close /></el-icon>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100%; }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.body { padding: 4px 16px 28px; }
.hint { font-size: 12.5px; color: var(--m-text-dim); padding: 6px 2px 10px; }

.fav { padding: 0 12px 0 0; }
.fav-main {
  flex: 1 1 auto; min-width: 0; display: flex; align-items: center; gap: 12px;
  padding: 13px 0 13px 12px; border: none; background: transparent;
  text-align: left; color: inherit; cursor: pointer;
}
.fav-x {
  flex: none; width: 34px; height: 34px; border-radius: 50%; border: none;
  background: var(--m-card-2); color: var(--m-text-dim);
  display: grid; place-items: center; cursor: pointer;
}
</style>
