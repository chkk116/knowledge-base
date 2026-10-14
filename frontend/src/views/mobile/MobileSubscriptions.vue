<script setup>
/**
 * 移动端「我的订阅」：订阅的文档列表 + 取消订阅
 * 复用 api/kb.js 的 listMySubscriptions / unsubscribeDoc
 * 返回：[{ docId, title, deleted }]
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listMySubscriptions, unsubscribeDoc } from '../../api/kb'
import { ElMessageBox } from 'element-plus'

const router = useRouter()
const loading = ref(true)
const list = ref([])

async function load() {
  loading.value = true
  try {
    const r = await listMySubscriptions().then((x) => (Array.isArray(x.data) ? x.data : [])).catch(() => [])
    list.value = r
  } finally { loading.value = false }
}
function open(s) { if (!s.deleted) router.push('/post/' + s.docId) }
async function unsub(s) {
  try {
    await ElMessageBox.confirm(`取消订阅《${s.title}》？`, '提示', { type: 'warning' })
  } catch { return }
  try {
    await unsubscribeDoc(s.docId)
    list.value = list.value.filter((x) => x.docId !== s.docId)
    window.__kbMsg?.success('已取消订阅')
  } catch { window.__kbMsg?.error('操作失败') }
}
onMounted(load)
</script>

<template>
  <div class="subs">
    <div class="m-header"><div class="m-title">我的订阅</div></div>
    <div class="body">
      <div v-if="loading" class="m-card"><div style="padding:14px"><div class="m-skel" style="height:40px"></div></div></div>
      <div v-else-if="list.length" class="m-card">
        <div v-for="s in list" :key="s.docId" class="row" :class="{ dead: s.deleted }">
          <span class="m-ico blue" @click="open(s)"><el-icon><Bell /></el-icon></span>
          <span class="m-row-body" @click="open(s)">
            <span class="m-row-title">{{ s.title || '（无标题）' }}</span>
            <span class="m-row-desc">{{ s.deleted ? '文档已删除' : '更新将通知你' }}</span>
          </span>
          <button class="unsub" @click.stop="unsub(s)">取消</button>
        </div>
      </div>
      <div v-else class="m-card">
        <div class="m-empty">
          <span class="ico"><el-icon :size="30"><Bell /></el-icon></span>
          <div>还没有订阅任何文档</div>
          <div style="font-size:12px;color:var(--m-text-dim);margin-top:2px">在文章详情页点「订阅」，更新会通知你</div>
        </div>
      </div>
    </div>
    <div style="height:12px"></div>
  </div>
</template>

<style scoped>
.subs { min-height: 100%; }
.body { padding: 0 16px; }
.row { display: flex; align-items: center; gap: 10px; padding: 12px 4px; border-bottom: 1px solid var(--m-border); }
.row:last-child { border-bottom: none; }
.row.dead { opacity: .6; }
.unsub { flex: none; border: 1px solid var(--m-border); background: var(--m-card); color: var(--m-text-sub);
  font-size: 12px; padding: 5px 12px; border-radius: 8px; cursor: pointer; }
</style>
