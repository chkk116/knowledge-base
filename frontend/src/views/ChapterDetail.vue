<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '../store/auth'
import { getCourse, courseProgress, finishChapter } from '../api/training'
import { getChapterNote, saveChapterNote } from '../api/kb'
import CommentSection from '../components/CommentSection.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const course = ref(null)
const chapters = ref([])
const chapter = ref(null)
const progress = ref([])
const loading = ref(false)

const isDone = (cid) => progress.value.some(p => p.chapterId === cid && p.finished)
const levelText = (l) => ({ 1: '入门', 2: '进阶', 3: '高级' }[l] || '入门')

const idx = computed(() => chapters.value.findIndex(c => c.id === Number(route.params.chapterId)))
const prevCh = computed(() => (idx.value > 0 ? chapters.value[idx.value - 1] : null))
const nextCh = computed(() => (idx.value >= 0 && idx.value < chapters.value.length - 1 ? chapters.value[idx.value + 1] : null))

const load = () => {
  loading.value = true
  const courseId = route.params.courseId
  const chapterId = Number(route.params.chapterId)
  getCourse(courseId).then(r => {
    const d = r.data || r
    course.value = d.course
    chapters.value = d.chapters || []
    chapter.value = chapters.value.find(c => c.id === chapterId) || null
    if (!chapter.value) { ElMessage.error('章节不存在'); router.replace('/study/courses/' + courseId); return }
    if (auth.loggedIn) courseProgress(courseId).then(x => { progress.value = (x.data || x) || [] }).catch(() => {})
  }).catch(() => { ElMessage.error('课程不存在'); router.replace('/study/courses') })
    .finally(() => { loading.value = false })
}

// ===== 笔记（私有，自动保存）=====
const noteContent = ref('')
const noteStatus = ref('idle')       // idle | saving | saved
const noteSavedAt = ref('')
let saveTimer = null
const loadNote = () => {
  noteContent.value = ''
  noteStatus.value = 'idle'
  if (!auth.loggedIn) return
  getChapterNote(route.params.chapterId).then(r => {
    noteContent.value = (r.data && r.data.content) || ''
  }).catch(() => {})
}
const scheduleSave = () => {
  if (!auth.loggedIn) return
  noteStatus.value = 'saving'
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(() => {
    saveChapterNote(route.params.chapterId, noteContent.value).then(() => {
      noteStatus.value = 'saved'
      const n = new Date()
      noteSavedAt.value = `${String(n.getHours()).padStart(2, '0')}:${String(n.getMinutes()).padStart(2, '0')}`
    }).catch(() => { noteStatus.value = 'idle' })
  }, 800)
}
watch(noteContent, scheduleSave)

const finish = (ch) => {
  finishChapter(route.params.courseId, ch.id).then(() => {
    ElMessage.success('已标记完成 +10 积分')
    load()
  }).catch(e => ElMessage.error(e.response?.data?.error || '操作失败'))
}

const goChapter = (ch) => { if (ch) router.push(`/study/courses/${route.params.courseId}/chapters/${ch.id}`) }
const openArticle = (id) => router.push('/post/' + id)

onMounted(() => { load(); loadNote() })
watch(() => route.params.chapterId, () => { load(); loadNote() })
onBeforeUnmount(() => { if (saveTimer) clearTimeout(saveTimer) })
</script>

<template>
  <div class="page" v-if="chapter">
    <!-- 面包屑 -->
    <div class="crumb">
      <span class="lk" @click="router.push('/study/courses')">培训课程</span>
      <span class="sep">/</span>
      <span class="lk" @click="router.push('/study/courses/' + route.params.courseId)">{{ course && course.title }}</span>
      <span class="sep">/</span>
      <span class="cur">第 {{ idx + 1 }} 章 · {{ chapter.title }}</span>
    </div>

    <h1 class="title">{{ chapter.title }}</h1>
    <div class="meta">
      <span v-if="chapter.durationMin">约 {{ chapter.durationMin }} 分钟</span>
      <el-tag v-if="isDone(chapter.id)" type="success" effect="plain" size="small">已完成</el-tag>
      <el-button v-if="auth.loggedIn && !isDone(chapter.id)" size="small" type="primary" @click="finish(chapter)">标记完成</el-button>
      <el-button v-if="chapter.articleId" size="small" text @click="openArticle(chapter.articleId)">看资料</el-button>
    </div>

    <!-- 正文 -->
    <article class="content" v-html="chapter.content || '<p style=\'color:#94a3b8\'>本章暂无正文内容，可在管理后台「培训管理」中编辑章节正文。</p>'"></article>

    <!-- 上/下章导航 -->
    <div class="nav">
      <button class="nav-btn" :disabled="!prevCh" @click="goChapter(prevCh)">← 上一章</button>
      <button class="nav-btn primary" :disabled="!nextCh" @click="goChapter(nextCh)">下一章 →</button>
    </div>

    <el-divider />

    <!-- 评论（与帖子同款体验）-->
    <CommentSection target-type="CHAPTER" :target-id="route.params.chapterId" />

    <el-divider />

    <!-- 我的笔记 -->
    <section class="note">
      <div class="note-hd">
        <h3>📝 我的笔记</h3>
        <span class="note-state" v-if="auth.loggedIn">
          <template v-if="noteStatus === 'saving'">保存中…</template>
          <template v-else-if="noteStatus === 'saved'">已保存 {{ noteSavedAt }}</template>
        </span>
        <span v-else class="note-state">登录后可使用笔记</span>
      </div>
      <el-input
        v-if="auth.loggedIn"
        v-model="noteContent"
        type="textarea"
        :rows="6"
        resize="vertical"
        maxlength="5000"
        show-word-limit
        placeholder="边学边记，自动保存到你的账号（仅本人可见）"
        :disabled="!auth.loggedIn"
      />
      <el-empty v-else description="登录后记录学习笔记" style="padding: 12px 0">
        <el-button @click="router.push('/login')">去登录</el-button>
      </el-empty>
    </section>
  </div>
  <div v-else-if="loading" class="tip">加载中…</div>
  <div v-else class="tip">未找到该章节</div>
</template>

<style scoped>
.page { max-width: 820px; margin: 0 auto; padding: 20px 16px 60px; }
.crumb { color: #94a3b8; font-size: 13px; margin-bottom: 10px; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.crumb .lk { cursor: pointer; }
.crumb .lk:hover { color: #6366f1; }
.crumb .sep { color: #cbd5e1; }
.crumb .cur { color: #475569; }
.title { margin: 6px 0 10px; font-size: 24px; }
.meta { display: flex; gap: 10px; align-items: center; color: #64748b; font-size: 13px; margin-bottom: 14px; flex-wrap: wrap; }
.content { line-height: 1.8; font-size: 15px; color: #334155; overflow-wrap: anywhere; }
.content :deep(h1), .content :deep(h2), .content :deep(h3) { margin: 16px 0 8px; line-height: 1.4; }
.content :deep(p) { margin: 8px 0; }
.content :deep(img) { max-width: 100%; border-radius: 8px; }
.content :deep(pre) { background: #0f172a; color: #e2e8f0; padding: 12px 14px; border-radius: 8px; overflow: auto; }
.content :deep(code) { background: #f1f5f9; padding: 1px 5px; border-radius: 4px; font-size: 13px; }
.content :deep(a) { color: #6366f1; }
.content :deep(ul), .content :deep(ol) { padding-left: 22px; margin: 8px 0; }
.content :deep(blockquote) { margin: 8px 0; padding: 6px 14px; border-left: 3px solid #c7d2fe; background: #f8fafc; color: #475569; }
.nav { display: flex; justify-content: space-between; gap: 12px; margin: 18px 0; }
.nav-btn { border: 1px solid #e2e8f0; background: #fff; border-radius: 10px; padding: 9px 18px; cursor: pointer; color: #475569; font-size: 14px; transition: .15s; }
.nav-btn:hover:not(:disabled) { border-color: #c7d2fe; color: #4f46e5; }
.nav-btn:disabled { opacity: .45; cursor: not-allowed; }
.nav-btn.primary { background: #6366f1; color: #fff; border-color: #6366f1; }
.nav-btn.primary:hover:not(:disabled) { background: #4f46e5; }
.note-hd { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.note-hd h3 { margin: 0; font-size: 16px; color: #1e293b; }
.note-state { color: #94a3b8; font-size: 12px; }
.tip { color: #94a3b8; padding: 40px 0; text-align: center; }
:deep(html.dark) .title { color: #e6e7f2; }
:deep(html.dark) .content { color: #c7c9d9; }
:deep(html.dark) .content :deep(code) { background: #2a2a40; }
:deep(html.dark) .content :deep(blockquote) { background: #20203a; border-left-color: #4f46e5; color: #a5a7bd; }
:deep(html.dark) .nav-btn { background: #1b1b2e; border-color: #2c2c44; color: #a5a7bd; }
:deep(html.dark) .note-hd h3 { color: #e6e7f2; }
:deep(html.dark) .crumb .cur { color: #a5a7bd; }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 1024px) {
  .page { padding: 18px 14px 48px; }
  .content { font-size: 14.5px; }
  .content :deep(table) { display: block; width: 100%; overflow-x: auto; }
}

@media (max-width: 640px) {
  .page { padding: 14px 12px 40px; }
  .crumb { font-size: 12px; }
  .title { font-size: 20px; }
  .meta { gap: 8px; font-size: 12.5px; }
  .content { font-size: 14px; }
  .content :deep(pre) { padding: 10px 12px; font-size: 12.5px; }
  .nav { flex-wrap: wrap; gap: 8px; margin: 16px 0; }
  .nav-btn { flex: 1 1 calc(50% - 4px); padding: 9px 10px; font-size: 13px; }
  .note-hd { flex-wrap: wrap; gap: 6px; }
  .note-hd h3 { font-size: 15px; }
}
</style>
