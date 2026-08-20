<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getDoc } from '../api/kb'

const route = useRoute()
const doc = ref(null)
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  // 继承主窗口的暗色偏好（同域 localStorage 共享）
  if (localStorage.getItem('kb_theme') === 'dark') {
    document.documentElement.classList.add('dark')
  }
  try {
    const { data } = await getDoc(route.params.id)
    doc.value = data
  } catch (e) {
    error.value = '加载失败（请确认已登录且后端已启动）'
  } finally {
    loading.value = false
  }
})

// 关闭这个独立窗口（OS 窗口）
function closeWin() { window.close() }
</script>

<template>
  <div class="reader-win-page">
    <div class="rw-bar">
      <span class="rw-title">{{ doc?.title || '文章阅读' }}</span>
      <button class="rw-close" type="button" @click="closeWin" title="关闭窗口">×</button>
    </div>
    <div class="rw-body">
      <div v-if="loading" class="rw-tip">加载中…</div>
      <div v-else-if="error" class="rw-tip rw-err">{{ error }}</div>
      <div v-else class="reader-win content" v-html="doc.content"></div>
    </div>
  </div>
</template>

<style scoped>
.reader-win-page { display: flex; flex-direction: column; height: 100vh; background: #fff; }
html.dark .reader-win-page { background: #17171f; }
.rw-bar { flex: none; height: 42px; display: flex; align-items: center; justify-content: space-between; padding: 0 8px 0 14px; background: #f8fafc; border-bottom: 1px solid #eef2f7; }
html.dark .rw-bar { background: #23232e; border-bottom-color: #2a2a35; }
.rw-title { font-size: 13px; font-weight: 600; color: #334155; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
html.dark .rw-title { color: #e7e7ee; }
.rw-close { border: none; background: none; font-size: 20px; line-height: 1; color: #94a3b8; cursor: pointer; padding: 0 6px; }
.rw-close:hover { color: #ef4444; }
.rw-body { flex: 1; overflow: auto; }
.rw-tip { padding: 24px; color: #94a3b8; font-size: 14px; }
.rw-err { color: #ef4444; }
</style>
