<script setup>
/**
 * 移动端原生「文章审核」页（替代 iframe 嵌桌面 ReviewQueue）
 *
 * 拉取待审核队列（GET /api/admin/docs/pending），逐条审核：
 *   - 通过：POST /api/admin/docs/{id}/approve
 *   - 驳回：POST /api/admin/docs/{id}/reject { reason }（弹窗填驳回理由）
 * 驳回后退回作者草稿，作者可在 Web/App 编辑后重新提交。
 */
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { listPending, approveDoc, rejectDoc } from '../../api/admin'

const router = useRouter()
const list = ref([])
const loading = ref(false)
const busy = ref(false)

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    const { data } = await listPending()
    list.value = Array.isArray(data) ? data : []
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '加载失败')
  } finally {
    loading.value = false
  }
}

function excerpt(c) {
  const t = (c || '').replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim()
  return t.length > 80 ? t.slice(0, 80) + '…' : t
}

async function approve(d) {
  if (busy.value) return
  busy.value = true
  try {
    await approveDoc(d.id)
    window.__kbMsg?.success('已通过')
    list.value = list.value.filter((x) => x.id !== d.id)
  } catch (e) {
    window.__kbMsg?.error(e?.response?.data?.error || '操作失败')
  } finally { busy.value = false }
}

function reject(d) {
  if (busy.value) return
  ElMessageBox.prompt('请填写驳回理由（将退回作者草稿，作者可见）', '驳回文章', {
    confirmButtonText: '驳回', cancelButtonText: '取消', inputType: 'textarea',
    inputAttrs: { maxlength: 200, rows: 3 },
  }).then(({ value }) => {
    busy.value = true
    return rejectDoc(d.id, value || '').then(() => {
      window.__kbMsg?.success('已驳回')
      list.value = list.value.filter((x) => x.id !== d.id)
    }).catch((e) => {
      window.__kbMsg?.error(e?.response?.data?.error || '驳回失败')
    }).finally(() => { busy.value = false })
  }).catch(() => {})
}

const fmtDate = (s) => (s ? String(s).slice(0, 10) : '—')
load()
</script>

<template>
  <div class="page">
    <header class="m-header solid">
      <button class="m-header-act" @click="router.back()"><el-icon :size="20"><ArrowLeft /></el-icon></button>
      <div class="h-title">文章审核 <span class="cnt" v-if="list.length">{{ list.length }}</span></div>
      <button class="h-ph refresh" @click="load()"><el-icon><Refresh /></el-icon></button>
    </header>

    <div class="list">
      <div v-if="!list.length && !loading" class="empty">
        <el-icon :size="34"><CircleCheckFilled /></el-icon>
        <div>审核队列已清空 🎉</div>
      </div>
      <div v-for="d in list" :key="d.id" class="m-card rv">
        <div class="rv-head">
          <span class="rv-title">{{ d.title || '未命名' }}</span>
        </div>
        <div class="rv-meta">{{ d.ownerName || '匿名' }} · 提交于 {{ fmtDate(d.createdAt) }}</div>
        <div class="rv-excerpt" v-if="excerpt(d.content)">{{ excerpt(d.content) }}</div>
        <div class="rv-actions">
          <button class="m-btn ghost" :disabled="busy" @click="reject(d)">
            <el-icon><CircleCloseFilled /></el-icon> 驳回
          </button>
          <button class="m-btn" :disabled="busy" @click="approve(d)">
            <el-icon><CircleCheckFilled /></el-icon> 通过
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.page { min-height: 100%; display: flex; flex-direction: column; background: var(--m-bg); }
.h-title { flex: 1 1 auto; text-align: center; font-size: 16.5px; font-weight: 600; }
.h-ph { width: 38px; }
.refresh { border: none; background: transparent; color: var(--m-text-sub); display: inline-flex; padding: 6px; }
.cnt { font-size: 12px; color: #fff; background: var(--m-primary); border-radius: 10px; padding: 0 7px; margin-left: 4px; vertical-align: 2px; }
.list { flex: 1 1 auto; padding: 12px 16px 24px; display: flex; flex-direction: column; gap: 12px; }
.empty { padding: 56px 0; text-align: center; color: var(--m-text-sub); display: flex; flex-direction: column; align-items: center; gap: 10px; font-size: 14px; }
.rv { padding: 14px; }
.rv-head { display: flex; align-items: flex-start; gap: 8px; }
.rv-title { font-size: 15.5px; font-weight: 600; color: var(--m-text); line-height: 1.4; }
.rv-meta { margin-top: 6px; font-size: 12.5px; color: var(--m-text-sub); }
.rv-excerpt { margin-top: 8px; font-size: 13.5px; color: var(--m-text-dim); line-height: 1.6; background: var(--m-bg); border-radius: 8px; padding: 8px 10px; }
.rv-actions { margin-top: 12px; display: flex; gap: 10px; }
.rv-actions .m-btn { flex: 1 1 0; }
</style>
