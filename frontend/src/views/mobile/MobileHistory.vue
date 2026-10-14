<script setup>
/**
 * 移动端「浏览历史」：本机浏览记录（纯前端 localStorage，按用户隔离）
 * 复用 store/history.js
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useHistoryStore } from '../../store/history'
import { ElMessageBox } from 'element-plus'

const router = useRouter()
const history = useHistoryStore()
const items = computed(() => history.items)

function fromNow(t) {
  if (!t) return ''
  const diff = (Date.now() - t) / 1000
  if (diff < 60) return '刚刚'
  if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前'
  if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前'
  if (diff < 2592000) return Math.floor(diff / 86400) + ' 天前'
  const d = new Date(t)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function open(it) { router.push('/post/' + it.id) }
async function clearAll() {
  try { await ElMessageBox.confirm('清空全部浏览历史？', '提示', { type: 'warning' }) } catch { return }
  history.clear(); window.__kbMsg?.success('已清空')
}
</script>

<template>
  <div class="his">
    <div class="m-header">
      <div><div class="m-title">浏览历史</div><div class="m-sub">仅本机 · 最近 {{ items.length }} 条</div></div>
      <button v-if="items.length" class="m-header-act" @click="clearAll">清空</button>
    </div>
    <div class="body">
      <div v-if="items.length" class="m-card">
        <div v-for="it in items" :key="it.id" class="row">
          <span class="m-ico green" @click="open(it)"><el-icon><Clock /></el-icon></span>
          <span class="m-row-body" @click="open(it)">
            <span class="m-row-title">{{ it.title }}</span>
            <span class="m-row-desc">{{ fromNow(it.time) }}<template v-if="it.author"> · {{ it.author }}</template></span>
          </span>
          <button class="del" @click.stop="history.remove(it.id)"><el-icon><Close /></el-icon></button>
        </div>
      </div>
      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Clock /></el-icon></span>
          <div>还没有浏览记录</div>
        </div>
      </div>
    </div>
    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.his { min-height: 100%; }
.body { padding: 0 16px; }
.row { display: flex; align-items: center; gap: 10px; padding: 12px 4px; border-bottom: 1px solid var(--m-border); }
.row:last-child { border-bottom: none; }
.del { flex: none; width: 28px; height: 28px; border: none; background: transparent; color: var(--m-text-dim); cursor: pointer; display: grid; place-items: center; }
</style>
