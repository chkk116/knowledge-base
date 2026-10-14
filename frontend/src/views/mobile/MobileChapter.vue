<script setup>
/**
 * 移动端章节学习页：渲染章节内容（HTML）+ 标记完成 + 上/下一章
 * 章节内容随课程返回，这里取 getCourse 后按 chapterId 定位（training.js 无单独章节接口）
 */
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getCourse, finishChapter } from '../../api/training'

const route = useRoute()
const router = useRouter()
const courseId = route.params.id
const chapterId = route.params.chId
const loading = ref(true)
const course = ref(null)
const chapter = ref(null)
const idx = ref(-1)
const finishing = ref(false)
const finished = ref(false)

const chapters = computed(() => (course.value && course.value.chapters) || [])

async function load() {
  loading.value = true
  try {
    const c = await getCourse(courseId).then((r) => r.data || r).catch(() => null)
    course.value = c
    const list = (c && c.chapters) || []
    idx.value = list.findIndex((x) => String(x.id) === String(chapterId))
    chapter.value = list[idx.value] || null
  } finally {
    loading.value = false
  }
}

async function markDone() {
  if (finishing.value) return
  finishing.value = true
  try {
    await finishChapter(courseId, chapterId).catch(() => {})
    finished.value = true
    window.__kbMsg?.success('已标记完成')
    if (idx.value < chapters.value.length - 1) setTimeout(() => go(idx.value + 1), 400)
  } finally { finishing.value = false }
}
function go(i) {
  const ch = chapters.value[i]
  if (ch) router.replace(`/course/${courseId}/chapter/${ch.id}`)
}

onMounted(load)
</script>

<template>
  <div class="ch" v-if="chapter">
    <div class="topbar">
      <button class="bk" @click="router.back()"><el-icon><ArrowLeft /></el-icon></button>
      <div class="t">{{ chapter.title }}</div>
      <button class="nav" :disabled="idx <= 0" @click="go(idx - 1)"><el-icon><ArrowLeft /></el-icon></button>
      <button class="nav" :disabled="idx >= chapters.length - 1" @click="go(idx + 1)"><el-icon><Right /></el-icon></button>
    </div>

    <div class="body">
      <!-- 章节正文（HTML） -->
      <div class="m-card prose" v-html="chapter.content || '<p style=\'color:#86909c\'>本章暂无内容</p>'"></div>

      <button class="m-btn" :disabled="finished" @click="markDone">
        {{ finished ? '已完成' : '标记完成' }}
      </button>
      <div v-if="idx < chapters.length - 1" style="margin-top:8px">
        <button class="m-btn ghost" @click="go(idx + 1)">下一章</button>
      </div>
    </div>
  </div>
  <div v-else class="loading">{{ loading ? '加载中…' : '章节不存在' }}</div>
</template>

<style scoped>
.ch { min-height: 100%; background: var(--m-bg); }
.topbar { position: sticky; top: 0; z-index: 10; display: flex; align-items: center; gap: 6px;
  padding: 10px 12px; background: var(--m-card); border-bottom: 1px solid var(--m-border); }
.bk { width: 32px; height: 32px; border: none; background: transparent; color: var(--m-text); cursor: pointer; display: grid; place-items: center; }
.t { flex: 1; font-size: 15.5px; font-weight: 600; color: var(--m-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.nav { width: 32px; height: 32px; border: none; background: var(--m-card-2); color: var(--m-text-sub);
  border-radius: 8px; cursor: pointer; display: grid; place-items: center; }
.nav:disabled { opacity: .4; }
.body { padding: 12px 16px; }
.prose { font-size: 14.5px; line-height: 1.8; color: var(--m-text); }
.loading { padding: 40px; text-align: center; color: var(--m-text-dim); }
</style>
