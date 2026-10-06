<script setup>
import { ref, onMounted, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getDoc } from '../api/kb'
import { enhanceVideos, bindImgLightbox, absolutizeMedia } from '../utils/mediaEnhance'

const route = useRoute()
const doc = ref(null)
const loading = ref(true)
const error = ref('')
const rwRef = ref(null)
const lightboxSrc = ref('')

function decorate() {
  const el = rwRef.value
  if (!el) return
  absolutizeMedia(el)
  enhanceVideos(el)
  bindImgLightbox(el, (src) => { if (src) lightboxSrc.value = src })
}

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

watch(() => doc.value?.content, async () => { await nextTick(); decorate() })

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
      <div v-else ref="rwRef" class="reader-win content" v-html="doc.content"></div>
    </div>

    <!-- 图片灯箱 -->
    <div v-if="lightboxSrc" class="kb-lightbox" @click="lightboxSrc = ''">
      <img :src="lightboxSrc" alt="" @click.stop />
      <button class="lb-x" type="button" @click="lightboxSrc = ''">×</button>
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

/* ===================== 响应式布局（追加在末尾） ===================== */
/* 平板 / 窄屏：正文定宽容器放开为满宽，内边距收紧 */
@media (max-width: 1024px) {
  .reader-win { padding: 14px; width: 100%; max-width: 100%; box-sizing: border-box; }
  .rw-body { overflow-x: hidden; }
  .reader-win :deep(table) { display: block; width: 100%; overflow-x: auto; }
}

/* 手机：标题栏与正文进一步收紧，表格自身横向滚动 */
@media (max-width: 640px) {
  .rw-bar { height: 38px; padding: 0 6px 0 12px; }
  .rw-title { font-size: 12.5px; }
  .reader-win { padding: 12px; font-size: 14px; }
  .reader-win :deep(th),
  .reader-win :deep(td) { padding: 6px 8px; }
  .rw-tip { padding: 16px 12px; font-size: 13px; }
}
</style>
