<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listDocVersions, restoreDocVersion } from '../api/kb'
import { fromNow, fmtDateTime } from '../utils/time'
import { errMsg } from '../utils/errMsg'

const route = useRoute()
const router = useRouter()
const docId = route.params.id
const versions = ref([])
const loading = ref(false)
const preview = ref(null)     // 当前预览的版本 { title, content }
const restoring = ref(0)      // 正在恢复的版本 id

// 取纯文本摘要（去掉 HTML 标签）
const snippet = (html) => {
  if (!html) return ''
  // DOMParser 替代 div.innerHTML（游离 img 仍会加载触发 onerror，2026-10-13 审查 H9）
  const t = (new DOMParser().parseFromString(html, 'text/html').body.textContent || '').replace(/\s+/g, ' ').trim()
  return t.length > 120 ? t.slice(0, 120) + '…' : t
}

const load = async () => {
  loading.value = true
  try {
    versions.value = (await listDocVersions(docId)).data || []
  } catch (e) {
    ElMessage.error(errMsg(e, '加载版本失败'))
  } finally {
    loading.value = false
  }
}

const openPreview = async (v) => {
  preview.value = { title: v.title, content: v.content }
}

const restore = async (v) => {
  try {
    await ElMessageBox.confirm('确定将文档回滚到该版本？回滚前会自动保存当前状态为一份快照，便于随时再退回来。',
      '回滚到历史版本', { type: 'warning' })
  } catch { return }
  restoring.value = v.id
  try {
    await restoreDocVersion(docId, v.id)
    ElMessage.success('已回滚，正在打开文档…')
    // 跳回文档详情：路由切换到 /post/:id 会重新挂载并拉取最新内容
    router.replace('/post/' + docId + '?v=' + Date.now())
  } catch (e) {
    ElMessage.error(errMsg(e, '回滚失败'))
  } finally {
    restoring.value = 0
  }
}

onMounted(load)
</script>

<template>
  <div v-loading="loading">
    <div class="bar">
      <el-button @click="router.push('/post/' + docId)">← 返回文档</el-button>
      <span class="hint">每次保存都会自动留档；可预览任意历史版本，或一键回滚。</span>
    </div>

    <el-empty v-if="!versions.length" description="暂无历史版本（文档被编辑保存后才会生成）" />

    <ul class="vlist" v-else>
      <li class="vitem" v-for="v in versions" :key="v.id">
        <div class="vinfo">
          <span class="vt">{{ v.title || '（无标题）' }}</span>
          <span class="vmeta">
            #{{ v.id }}
            · 编辑于 {{ fromNow(v.createdAt) }}（{{ fmtDateTime(v.createdAt) }}）
            <template v-if="v.editorName"> · {{ v.editorName }}</template>
            <template v-else-if="v.editorId"> · 用户#{{ v.editorId }}</template>
          </span>
          <span class="vsnip" v-if="snippet(v.content)">{{ snippet(v.content) }}</span>
        </div>
        <div class="vacts">
          <el-button size="small" @click="openPreview(v)">预览</el-button>
          <el-button size="small" type="warning" :loading="restoring === v.id" @click="restore(v)">回滚到此版本</el-button>
        </div>
      </li>
    </ul>

    <el-dialog v-model="preview" title="版本预览（只读）" width="760px" top="6vh">
      <template v-if="preview">
        <h3 style="margin:0 0 10px">{{ preview.title }}</h3>
        <div class="pv" v-html="preview.content"></div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.bar { margin-bottom: 12px; display: flex; align-items: center; gap: 12px; }
.hint { color: #94a3b8; font-size: 13px; }
.vlist { list-style: none; padding: 0; margin: 0; border: 1px solid #eef2f7; border-radius: 12px; overflow: hidden; }
.vitem { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 18px; border-top: 1px solid #eef2f7; }
.vitem:first-child { border-top: none; }
.vinfo { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.vt { font-weight: 600; color: #1e293b; }
.vmeta { color: #94a3b8; font-size: 12px; }
.vsnip { color: #64748b; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 60vw; }
.vacts { display: flex; gap: 8px; flex: none; }
.pv { line-height: 1.8; font-size: 14px; max-height: 64vh; overflow: auto; }
.pv :deep(img) { max-width: 100%; }

/* 暗色模式适配 */
html.dark .hint { color: var(--el-text-color-secondary); }
html.dark .vlist { border-color: var(--el-border-color-lighter); background: var(--el-bg-color); }
html.dark .vitem { border-top-color: var(--el-border-color-lighter); }
html.dark .vt { color: var(--el-text-color-primary); }
html.dark .vmeta { color: var(--el-text-color-secondary); }
html.dark .vsnip { color: var(--el-text-color-regular); }
html.dark .pv { color: var(--el-text-color-regular); }

/* 版本正文由 v-html 注入，内部标签不带 scoped 属性，需 :deep() 命中 */
html.dark .pv :deep(h1),
html.dark .pv :deep(h2),
html.dark .pv :deep(h3),
html.dark .pv :deep(h4),
html.dark .pv :deep(h5),
html.dark .pv :deep(h6),
html.dark .pv :deep(strong) { color: var(--el-text-color-primary); }
html.dark .pv :deep(a) { color: #93c5fd; }
html.dark .pv :deep(blockquote) { border-left-color: var(--el-border-color); color: var(--el-text-color-secondary); background: var(--el-fill-color-light); }
html.dark .pv :deep(pre),
html.dark .pv :deep(code) { background: var(--el-fill-color); color: #e8e8f0; }
html.dark .pv :deep(hr) { border-color: var(--el-border-color-lighter); }
html.dark .pv :deep(th),
html.dark .pv :deep(td) { border-color: var(--el-border-color); }
html.dark .pv :deep(table) { border-color: var(--el-border-color); }

/* ============================================================
   响应式：≤1024 顶部说明换行、摘要不再限 60vw
          ≤ 640 版本行改竖排（标题信息在上，按钮在下）
   ============================================================ */
@media (max-width: 1024px) {
  .bar { flex-wrap: wrap; gap: 8px; }
  .hint { flex: 1 1 100%; }
  .vsnip { max-width: 100%; }
}

@media (max-width: 640px) {
  .vitem { flex-direction: column; align-items: stretch; gap: 10px; padding: 12px 14px; }
  .vt { font-size: 14px; }
  .vacts { width: 100%; flex-wrap: wrap; gap: 8px; }
  .vacts .el-button { margin-left: 0; }
  .pv { max-height: 70vh; font-size: 13px; }
}
</style>
