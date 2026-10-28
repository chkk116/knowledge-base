<template>
  <div class="page" v-if="course">
    <div class="hd">
      <h1>{{ course.title }}</h1>
      <p class="sub">{{ course.summary || '暂无简介' }}</p>
      <div class="meta">
        <span>{{ course.ownerName || '官方' }}</span>
        <span v-if="course.category">· {{ course.category }}</span>
        <span>· {{ levelText(course.level) }}</span>
      </div>
    </div>

    <div class="chapter-wrap">
      <h2>课程章节（{{ chapters.length }}）</h2>
      <div v-if="!chapters.length" class="empty">该课程尚未添加章节。</div>
      <div v-for="(ch, i) in chapters" :key="ch.id" class="ch" :class="{ done: isDone(ch.id) }">
        <div class="ch-row">
          <div class="idx">{{ i + 1 }}</div>
          <div class="info" @click="goChapter(ch)">
            <div class="ct">{{ ch.title }}</div>
            <div class="cm" v-if="ch.durationMin">约 {{ ch.durationMin }} 分钟</div>
            <div class="ctip">进入学习 →</div>
          </div>
          <div class="act" @click.stop>
            <el-tag v-if="isDone(ch.id)" type="success" effect="plain" size="small">已完成</el-tag>
            <!-- 不再提供「标记完成」：完成状态由学习页按停留时长 + 滑到底自动判定 -->
            <el-button v-if="loggedIn && isDone(ch.id)" size="small" text :loading="relearning === ch.id" @click="relearn(ch)">重学</el-button>
            <el-button size="small" text @click.stop="goChapter(ch)">{{ isDone(ch.id) ? '复习' : '去学习' }}</el-button>
            <el-button v-if="ch.articleId" size="small" text @click.stop="openArticle(ch.articleId)">看资料</el-button>
          </div>
        </div>
      </div>
    </div>

    <div class="bar" v-if="loggedIn">
      <span>已完成 {{ doneCount }} / {{ chapters.length }} 章</span>
      <el-button text type="primary" @click="$router.push('/study/courses')">返回课程列表</el-button>
    </div>

    <!-- 结业考试卡片：仅当课程绑定了试卷时显示 -->
    <div class="exam-card" v-if="loggedIn && course.examPaperId">
      <div class="exam-hd">
        <span class="exam-title">🎓 结业考试</span>
        <span class="exam-sub" v-if="examPaper">{{ examPaper.title }}（满分 {{ examPaper.totalScore }} · 及格 {{ examPaper.passScore }}）</span>
      </div>
      <div class="exam-body">
        <template v-if="myExamRecord">
          <el-tag :type="myExamRecord.passed ? 'success' : 'danger'" effect="plain">
            {{ myExamRecord.passed ? '已通过' : '未通过' }}
          </el-tag>
          <span class="exam-score">得分 {{ myExamRecord.score }} / {{ examPaper?.totalScore }}</span>
        </template>
        <span v-else class="exam-tip">完成全部章节后可参加结业考试</span>
        <el-button
          type="primary"
          size="small"
          :disabled="!allChaptersDone"
          @click="goExam"
        >{{ myExamRecord ? '重新考试' : '开始考试' }}</el-button>
        <el-button v-if="myCert" type="success" size="small" @click="certVisible = true">🏆 查看结业证书</el-button>
      </div>
    </div>

    <!-- 结业证书弹窗 -->
    <CertDialog :visible="certVisible" :cert="myCert" @update:visible="certVisible = $event" />
  </div>
  <div v-else class="tip">加载中…</div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCourse, courseProgress, finishChapter, resetChapter, listPapers, myExams, courseCertificate } from '../api/training'
import { useAuthStore } from '../store/auth'
import CertDialog from '../components/CertDialog.vue'
import { errMsg } from '../utils/errMsg'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loggedIn = computed(() => auth.loggedIn)
const course = ref(null)
const chapters = ref([])
const progress = ref([])

const isDone = (cid) => progress.value.some(p => p.chapterId === cid && p.finished)
const doneCount = computed(() => chapters.value.filter(c => isDone(c.id)).length)
const allChaptersDone = computed(() => chapters.value.length > 0 && doneCount.value === chapters.value.length)
const levelText = (l) => ({ 1: '入门', 2: '进阶', 3: '高级' }[l] || '入门')

const goChapter = (ch) => router.push('/study/courses/' + route.params.id + '/chapters/' + ch.id)

// 结业考试：拉试卷详情 + 我的成绩
const examPaper = ref(null)
const myExamRecord = ref(null)
const loadExam = () => {
  const pid = course.value?.examPaperId
  if (!pid) { examPaper.value = null; myExamRecord.value = null; return }
  listPapers().then(r => {
    const list = r.data || r || []
    examPaper.value = list.find(p => p.id === pid) || null
  }).catch(() => { examPaper.value = null })
  myExams().then(r => {
    const list = r.data || r || []
    myExamRecord.value = list.find(x => x.paperId === pid) || null
  }).catch(() => { myExamRecord.value = null })
}
const goExam = () => {
  if (!course.value?.examPaperId) return
  if (!allChaptersDone.value) { ElMessage.warning('请先完成全部章节再参加结业考试'); return }
  router.push('/exams/' + course.value.examPaperId + '/take')
}

// 结业证书：学完 + 考试及格后由后端派生，非 null 表示已获得
const myCert = ref(null)
const certVisible = ref(false)
const loadCert = () => {
  courseCertificate(route.params.id).then(r => {
    myCert.value = (r.data && r.data.courseId) ? r.data : null
  }).catch(() => { myCert.value = null })
}

const load = () => {
  getCourse(route.params.id).then(r => {
    const d = r.data || r
    course.value = d.course
    chapters.value = d.chapters || []
    if (loggedIn.value) loadExam()
  }).catch(() => {})
  if (loggedIn.value) {
    courseProgress(route.params.id).then(r => { progress.value = (r.data || r) || [] }).catch(() => {})
    loadCert()
  }
}
const finish = (ch) => {
  finishChapter(route.params.id, ch.id).then(() => {
    ElMessage.success('已标记完成')
    load()
  }).catch(e => ElMessage.error(errMsg(e, '操作失败')))
}
/** 重学：进度归零（服务端扣回该章积分，重新完成再发） */
const relearning = ref(null)
const relearn = async (ch) => {
  if (relearning.value) return
  relearning.value = ch.id
  try {
    await resetChapter(route.params.id, ch.id)
    await load()
    ElMessage.success('已重置，进入章节重新学习')
  } catch (e) {
    ElMessage.error(errMsg(e, '重置失败，请重试'))
  } finally {
    relearning.value = null
  }
}
const openArticle = (id) => router.push('/post/' + id)

onMounted(load)
</script>

<style scoped>
.page { max-width: 860px; margin: 0 auto; padding: 24px 16px 60px; }
.hd h1 { margin: 0; font-size: 24px; }
.hd .sub { color: #64748b; margin: 8px 0; line-height: 1.6; }
.hd .meta { color: #94a3b8; font-size: 13px; }
.chapter-wrap h2 { font-size: 17px; margin: 26px 0 14px; }
.empty { color: #94a3b8; padding: 20px 0; }
.ch { background: #fff; border: 1px solid #eef2f7; border-radius: 12px; margin-bottom: 10px; overflow: hidden; }
.ch.done { background: #f6fdf8; border-color: #c7ecd5; }
.ch-row { display: flex; align-items: center; gap: 14px; padding: 14px 16px; }
.idx { width: 28px; height: 28px; border-radius: 50%; background: #6366f1; color: #fff; display: flex; align-items: center; justify-content: center; font-size: 13px; flex: none; }
.info { flex: 1; cursor: pointer; }
.ct { font-weight: 600; }
.cm { color: #94a3b8; font-size: 12px; margin-top: 2px; }
.ctip { color: #6366f1; font-size: 12px; margin-top: 4px; }
.act { display: flex; align-items: center; gap: 8px; flex: none; }
.body { padding: 6px 24px 22px; border-top: 1px dashed #eef2f7; color: #334155; line-height: 1.8; font-size: 14px; }
.body :deep(h1), .body :deep(h2), .body :deep(h3) { margin: 16px 0 8px; line-height: 1.4; }
.body :deep(p) { margin: 8px 0; }
.body :deep(img) { max-width: 100%; border-radius: 8px; }
.body :deep(pre) { background: #0f172a; color: #e2e8f0; padding: 12px 14px; border-radius: 8px; overflow: auto; }
.body :deep(code) { background: #f1f5f9; padding: 1px 5px; border-radius: 4px; font-size: 13px; }
.body :deep(a) { color: #6366f1; }
.body :deep(ul), .body :deep(ol) { padding-left: 22px; margin: 8px 0; }
.body :deep(blockquote) { margin: 8px 0; padding: 6px 14px; border-left: 3px solid #c7d2fe; background: #f8fafc; color: #475569; }
.bar { display: flex; justify-content: space-between; align-items: center; margin-top: 22px; color: #64748b; font-size: 13px; }
.exam-card { margin-top: 22px; padding: 16px 18px; background: #fff; border: 1px solid #e9d5ff; border-radius: 12px; }
.exam-hd { display: flex; align-items: baseline; gap: 10px; margin-bottom: 12px; }
.exam-title { font-weight: 700; color: #7c3aed; font-size: 15px; }
.exam-sub { color: #64748b; font-size: 13px; }
.exam-body { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.exam-score { color: #0f172a; font-size: 13px; font-weight: 600; }
.exam-tip { color: #94a3b8; font-size: 13px; }
:deep(html.dark) .ch { background: #1b1b2e; border-color: #2c2c44; }
:deep(html.dark) .ch.done { background: #16241c; border-color: #2f5a3f; }
:deep(html.dark) .body { border-top-color: #2c2c44; color: #c7c9d9; }
:deep(html.dark) .body :deep(code) { background: #2a2a40; }
:deep(html.dark) .body :deep(blockquote) { background: #20203a; border-left-color: #4f46e5; color: #a5a7bd; }

/* 课程详情页其余区块暗色适配：章节标题/副标题、页头简介、进度条、结业卡片 */
:deep(html.dark) .chapter-wrap h2 { color: var(--el-text-color-primary); }
:deep(html.dark) .hd .sub,
:deep(html.dark) .hd .meta { color: var(--el-text-color-secondary); }
:deep(html.dark) .empty { color: var(--el-text-color-secondary); }
:deep(html.dark) .ct { color: var(--el-text-color-primary); }
:deep(html.dark) .cm { color: var(--el-text-color-secondary); }
:deep(html.dark) .bar { color: var(--el-text-color-secondary); }
:deep(html.dark) .idx { background: var(--el-color-primary); color: #fff; }
:deep(html.dark) .exam-card { background: var(--el-bg-color-overlay); border-color: #3a2f5e; }
:deep(html.dark) .exam-sub { color: var(--el-text-color-secondary); }
:deep(html.dark) .exam-score { color: var(--el-text-color-primary); }
:deep(html.dark) .exam-tip { color: var(--el-text-color-secondary); }

/* ===================== 响应式布局（追加在末尾） ===================== */
@media (max-width: 1024px) {
  .page { padding: 20px 14px 48px; }
  .chapter-wrap h2 { font-size: 16px; margin: 22px 0 12px; }
  .ch-row { flex-wrap: wrap; gap: 10px; padding: 12px 14px; }
  .act { flex-wrap: wrap; }
  .body { padding: 6px 14px 18px; }
}

@media (max-width: 640px) {
  .page { padding: 14px 12px 40px; }
  .hd h1 { font-size: 20px; }
  .hd .sub { font-size: 13px; }
  .ch-row { gap: 8px 10px; padding: 12px; }
  .idx { width: 26px; height: 26px; font-size: 12px; }
  .info { flex: 1 1 calc(100% - 40px); min-width: 0; }
  .ct { font-size: 14px; overflow-wrap: anywhere; }
  .act { width: 100%; padding-left: 34px; }
  .bar { flex-wrap: wrap; gap: 8px; font-size: 12.5px; }
  .exam-card { padding: 14px; margin-top: 18px; }
  .exam-hd { flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
  .exam-title { font-size: 14px; }
  .exam-sub { font-size: 12.5px; }
  .exam-body { gap: 8px 10px; }
  .body { padding: 6px 12px 16px; font-size: 13.5px; }
}
</style>
