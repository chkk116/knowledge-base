<script setup>
/**
 * 移动端「回收站」：我移入回收站的文档 + 恢复 / 彻底删除
 * 复用 api/kb.js 的 listRecycle / restoreDoc / purgeDoc
 * 返回：[{ id, title, deletedAt }]
 */
import { onMounted, ref } from 'vue'
import { listRecycle, restoreDoc, purgeDoc } from '../../api/kb'
import { ElMessageBox } from 'element-plus'

const loading = ref(true)
const docs = ref([])

function fromNow(s) {
  if (!s) return ''
  const d = new Date(s); if (isNaN(d)) return ''
  const diff = (Date.now() - d.getTime()) / 1000
  if (diff < 60) return '刚刚'
  if (diff < 3600) return Math.floor(diff / 60) + ' 分钟前'
  if (diff < 86400) return Math.floor(diff / 3600) + ' 小时前'
  if (diff < 2592000) return Math.floor(diff / 86400) + ' 天前'
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

async function load() {
  loading.value = true
  try {
    const r = await listRecycle().then((x) => (Array.isArray(x.data) ? x.data : [])).catch(() => [])
    docs.value = r
  } finally { loading.value = false }
}
async function restore(d) {
  try { await ElMessageBox.confirm(`恢复《${d.title || '（无标题）'}》？`, '恢复文档', { type: 'info' }) } catch { return }
  try { await restoreDoc(d.id); docs.value = docs.value.filter((x) => x.id !== d.id); window.__kbMsg?.success('已恢复') }
  catch { window.__kbMsg?.error('恢复失败') }
}
async function purge(d) {
  try { await ElMessageBox.confirm(`彻底删除《${d.title || '（无标题）'}》？此操作不可撤销。`, '彻底删除', { type: 'warning', confirmButtonText: '彻底删除' }) } catch { return }
  try { await purgeDoc(d.id); docs.value = docs.value.filter((x) => x.id !== d.id); window.__kbMsg?.success('已彻底删除') }
  catch { window.__kbMsg?.error('删除失败') }
}
onMounted(load)
</script>

<template>
  <div class="rc">
    <div class="m-header">
      <div><div class="m-title">回收站</div><div class="m-sub">移入回收站的文档可恢复</div></div>
    </div>
    <div class="body">
      <div v-if="loading" class="m-card"><div style="padding:14px"><div class="m-skel" style="height:40px"></div></div></div>
      <div v-else-if="docs.length" class="m-card">
        <div v-for="d in docs" :key="d.id" class="row">
          <span class="m-ico gray"><el-icon><Delete /></el-icon></span>
          <span class="m-row-body">
            <span class="m-row-title">{{ d.title || '（无标题）' }}</span>
            <span class="m-row-desc">{{ fromNow(d.deletedAt) }} 移入</span>
          </span>
          <button class="act restore" @click="restore(d)">恢复</button>
          <button class="act purge" @click="purge(d)">删除</button>
        </div>
      </div>
      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Delete /></el-icon></span>
          <div>回收站是空的</div>
        </div>
      </div>
    </div>
    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.rc { min-height: 100%; }
.body { padding: 0 16px; }
.row { display: flex; align-items: center; gap: 8px; padding: 12px 4px; border-bottom: 1px solid var(--m-border); }
.row:last-child { border-bottom: none; }
.act { flex: none; border-radius: 8px; font-size: 12px; padding: 5px 10px; cursor: pointer; border: 1px solid var(--m-border); background: var(--m-card); }
.act.restore { color: var(--m-primary); }
.act.purge { color: var(--m-danger); border-color: #fecaca; }
</style>
