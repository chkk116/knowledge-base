<template>
  <div class="page">
    <el-tabs v-model="tab">
      <el-tab-pane label="课程" name="courses">
        <!-- 顶部操作条：面包屑 + 板块筛选 + 搜索 + 创建按钮（对齐对标后台"课程发布"主页） -->
        <div class="course-bar">
          <div class="crumbs">
            <span>课程</span>
            <span class="sep">/</span>
            <span class="current">课程发布</span>
          </div>
          <div class="actions">
            <el-select v-model="filterCat" placeholder="选择板块" clearable filterable size="default" style="width: 140px;">
              <el-option v-for="b in categoryOptions" :key="b" :value="b" :label="b" />
            </el-select>
            <el-input v-model="keyword" placeholder="课程检索" clearable size="default" style="width: 200px;">
              <template #append><el-button @click="load">🔍</el-button></template>
            </el-input>
            <el-button type="success" size="default" @click="openCourse()">+ 新建课程</el-button>
          </div>
        </div>

        <!-- 课程卡片列表 -->
        <div v-loading="loading" class="course-list">
          <div v-if="!filteredCourses.length && !loading" class="empty-tip">暂无课程，点击右上角"+ 新建课程"创建</div>
          <div v-for="c in filteredCourses" :key="c.id" class="course-row">
            <div class="row-cover" :style="coverStyle(c)">
              <span class="status-tag">{{ c.status === 'PUBLISHED' ? '已发布' : '草稿' }}</span>
            </div>
            <div class="row-main">
              <div class="row-title">{{ c.title }}</div>
              <div class="row-meta">
                <span class="stat" v-if="c.chapterCount != null">📘 {{ c.chapterCount || 0 }}章</span>
                <span class="stat" v-if="c.totalDurationMin != null">⏱ {{ c.totalDurationMin || 0 }}分</span>
                <span class="cat" v-if="c.category">{{ c.category }}</span>
                <span class="lv" :class="'lv' + c.level">{{ learningModeLabel(c.learningMode) }}</span>
                <span v-for="t in splitTags(c.tags)" :key="t" class="tag-chip">{{ t }}</span>
              </div>
            </div>
            <div class="row-ops">
              <el-button size="small" plain @click="openChapters(c)">📚 章节</el-button>
              <el-button size="small" plain @click="openCourse(c)">编辑</el-button>
              <el-button size="small" type="danger" plain @click="delCourse(c)">删除</el-button>
            </div>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane :label="'章节 (' + (selCourse ? selCourse.title : '请选择课程') + ')'" name="chapters">
        <div class="bar" v-if="selCourse">
          <el-button type="primary" @click="openChapter()">新建章节</el-button>
          <el-button @click="loadChapters()">刷新</el-button>
        </div>
        <el-alert v-else type="info" :closable="false" title="请先在「课程」标签下点击某课程的「章节」按钮" />
        <el-table v-stack-table v-if="selCourse" :data="chapters" border stripe>
          <el-table-column prop="id" label="ID" width="70"  class-name="stack-hide" />
          <el-table-column prop="title" label="标题" min-width="180" />
          <el-table-column prop="sortOrder" label="排序" width="80" />
          <el-table-column prop="durationMin" label="时长(分)" width="90" />
          <el-table-column label="关联文章" width="100">
            <template #default="{ row }">
              <span v-if="row.articleId" class="linked-article">✓ #{{ row.articleId }}</span>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button size="small" @click="openChapter(row)">编辑</el-button>
              <el-button size="small" type="danger" @click="delChapter(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 课程发布：2 步式侧边步进 dialog，对齐对标后台"课程创建 / 课时设置" -->
    <el-dialog
      v-model="courseDlg"
      :title="editingCourse ? '编辑课程' : '课程发布'"
      width="880px"
      :close-on-click-modal="false"
      top="6vh"
    >
      <div class="publish-layout">
        <!-- 左侧步进 -->
        <div class="publish-steps">
          <div class="step" :class="{ active: step === 1, done: step > 1 }" @click="gotoStep(1)">
            <span class="num">1</span>
            <span class="txt">课程创建</span>
          </div>
          <div class="step" :class="{ active: step === 2, done: false }" @click="gotoStep(2)">
            <span class="num">2</span>
            <span class="txt">课时设置</span>
          </div>
        </div>

        <!-- 右侧表单 -->
        <div class="publish-form">
          <!-- 步骤 1：课程基本信息 + 富文本正文 + 封面 -->
          <template v-if="step === 1">
            <el-form label-width="96px" :model="courseForm" class="form">
              <div class="row-2col">
                <div class="col-left">
                  <el-form-item label="课程名称" required>
                    <el-input v-model="courseForm.title" placeholder="请输入课程名称" />
                  </el-form-item>
                  <el-form-item label="课程板块" required>
                    <el-select v-model="courseForm.category" placeholder="请选择或输入课程板块" filterable allow-create style="width:100%">
                      <el-option v-for="b in categoryOptions" :key="b" :value="b" :label="b" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="学习模式" required>
                    <el-select v-model="courseForm.learningMode" style="width:100%">
                      <el-option value="FREE" label="自由学习" />
                      <el-option value="GUIDED" label="引导学习" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="排序号">
                    <el-input-number v-model="courseForm.sortOrder" :min="0" controls-position="right" />
                  </el-form-item>
                  <el-form-item label="自由学习">
                    <el-switch v-model="courseForm.freeLearn" />
                  </el-form-item>
                  <el-form-item label="进度条">
                    <el-switch v-model="courseForm.progressBar" />
                  </el-form-item>
                  <el-form-item label="课程标签">
                    <div class="tag-input-wrap">
                      <el-tag
                        v-for="t in formTags"
                        :key="t"
                        closable
                        type="warning"
                        effect="light"
                        @close="removeTag(t)"
                        class="tag-chip-input"
                      >{{ t }}</el-tag>
                      <el-input
                        v-model="tagInput"
                        size="small"
                        placeholder="添加课程标签"
                        class="tag-input"
                        @keyup.enter="addTag"
                        @blur="addTag"
                      />
                    </div>
                  </el-form-item>
                  <el-form-item label="状态">
                    <el-select v-model="courseForm.status" style="width:100%">
                      <el-option value="PUBLISHED" label="已发布" />
                      <el-option value="DRAFT" label="草稿" />
                    </el-select>
                  </el-form-item>
                  <el-form-item label="结业考试">
                    <el-select
                      v-model="courseForm.examPaperId"
                      filterable
                      clearable
                      placeholder="选择结业试卷（可选，不绑则不考试）"
                      style="width:100%"
                      @focus="paperOptions.length || loadPapers()"
                    >
                      <el-option
                        v-for="p in paperOptions"
                        :key="p.id"
                        :value="p.id"
                        :label="p.title + '（' + (p.totalScore||0) + '分·及格' + (p.passScore||60) + '）'"
                      />
                    </el-select>
                    <div class="field-hint">绑定后，学员在课程详情页可参加该试卷的结业考试，成绩计入学习档案</div>
                  </el-form-item>
                </div>
                <div class="col-right">
                  <div class="cover-block">
                    <div class="cover-label">课程封面</div>
                    <el-upload
                      class="cover-uploader"
                      :show-file-list="false"
                      :http-request="uploadCover"
                      :before-upload="beforeCover"
                      accept="image/jpeg,image/png,image/gif"
                      drag
                    >
                      <img v-if="courseForm.cover" :src="courseForm.cover" class="cover-preview" />
                      <div v-else class="cover-empty">
                        <el-icon class="cover-icon"><Picture /></el-icon>
                        <div class="cover-text">拖动文件到此处或 <em>点击上传</em></div>
                      </div>
                    </el-upload>
                    <div class="cover-hint">
                      允许文件：jpg / png / gif<br />尺寸 360×180px，附件大小 1.0MB
                    </div>
                  </div>
                </div>
              </div>

              <el-form-item label="课程正文">
                <RichEditor v-model="courseForm.content" />
                <div class="editor-insert-bar">
                  <el-button size="small" :disabled="!courseForm.cover && !editingCourse" @click="insertCoverImage">插入封面图到正文</el-button>
                  <el-button size="small" @click="alertComing('视频')">插入视频</el-button>
                  <el-button size="small" @click="alertComing('音频')">插入音频</el-button>
                  <el-button size="small" @click="alertComing('附件')">插入附件</el-button>
                </div>
                <div class="editor-divider"><span>上传录入正文</span></div>
              </el-form-item>
            </el-form>
          </template>

          <!-- 步骤 2：课时设置（章节管理） -->
          <template v-else>
            <div v-if="!savedCourseId" class="hint-warn">
              请先在「步骤 1」保存课程，再配置课时。
            </div>
            <div v-else>
              <div class="bar" style="margin-bottom: 10px;">
                <el-button type="primary" @click="openChapter()">新建章节</el-button>
                <el-button @click="loadChapters">刷新</el-button>
              </div>
              <el-table v-stack-table :data="chapters" border size="small">
                <el-table-column prop="id" label="ID" width="60"  class-name="stack-hide" />
                <el-table-column prop="title" label="标题" min-width="160" />
                <el-table-column prop="sortOrder" label="排序" width="70" />
                <el-table-column prop="durationMin" label="时长(分)" width="80" />
                <el-table-column label="操作" width="150">
                  <template #default="{ row }">
                    <el-button size="small" @click="openChapter(row)">编辑</el-button>
                    <el-button size="small" type="danger" @click="delChapter(row)">删除</el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="!chapters.length" description="还没有课时，点击「新建章节」添加" />
            </div>
          </template>
        </div>
      </div>

      <template #footer>
        <div class="dialog-footer">
          <el-button v-if="step === 2" @click="step = 1">上一步</el-button>
          <el-button @click="courseDlg = false">取消</el-button>
          <el-button
            v-if="step === 1"
            type="primary"
            class="primary-wide"
            :loading="saving"
            @click="saveCourseAndNext"
          >保存 &amp; （下一步配置课时）</el-button>
          <el-button v-else type="primary" @click="courseDlg = false">完成</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 章节弹窗：富文本编辑（对齐课程发布页的编辑体验） -->
    <el-dialog
      v-model="chapterDlg"
      :title="editingChapter ? '编辑章节' : '新建章节'"
      width="760px"
      top="6vh"
      :close-on-click-modal="false"
    >
      <el-form label-width="80px" class="chapter-form">
        <el-form-item label="章节标题" required>
          <el-input v-model="chapterForm.title" placeholder="如：第 1 节 · 环境准备" />
        </el-form-item>
        <el-form-item label="关联文章">
          <el-select
            v-model="chapterForm.articleId"
            filterable
            remote
            clearable
            placeholder="搜索并选择延伸阅读资料（可选）"
            :remote-method="searchArticles"
            :loading="articleSearching"
            style="width: 100%"
            @focus="!articleOptions.length && searchArticles('')"
          >
            <el-option
              v-for="a in articleOptions"
              :key="a.id"
              :value="a.id"
              :label="a.title"
            />
          </el-select>
          <div class="field-hint">关联后，学员在该章节旁会看到「看资料」按钮，点击跳转这篇文章（延伸阅读）</div>
        </el-form-item>
        <div class="chapter-row2">
          <el-form-item label="排序">
            <el-input-number v-model="chapterForm.sortOrder" :min="0" controls-position="right" style="width: 100%" />
          </el-form-item>
          <el-form-item label="时长(分)">
            <el-input-number v-model="chapterForm.durationMin" :min="0" controls-position="right" style="width: 100%" />
          </el-form-item>
        </div>
        <el-form-item label="章节正文">
          <div class="chapter-editor">
            <RichEditor v-model="chapterForm.content" />
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="chapterDlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveChapter">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Picture } from '@element-plus/icons-vue'
import RichEditor from '../../components/RichEditor.vue'
import { uploadFile } from '../../api/file'
import { search as searchDocs, getDoc } from '../../api/kb'
import { courseCategories } from '../../api/training'
import { adminCourses, adminCreateCourse, adminUpdateCourse, adminDeleteCourse, adminChapters, adminCreateChapter, adminUpdateChapter, adminDeleteChapter, listPapers } from '../../api/training'

const tab = ref('courses')
const courses = ref([])
const loading = ref(false)
const selCourse = ref(null)
const filterCat = ref('')
const keyword = ref('')
const filteredCourses = computed(() => {
  const kw = (keyword.value || '').trim().toLowerCase()
  return courses.value.filter(c => {
    if (filterCat.value && c.category !== filterCat.value) return false
    if (kw && !(c.title || '').toLowerCase().includes(kw)) return false
    return true
  })
})
const coverStyle = (c) => c.cover
  ? { backgroundImage: `url(${c.cover})`, backgroundSize: 'cover', backgroundPosition: 'center' }
  : { background: 'linear-gradient(135deg,#fb923c,#f97316)' }

const courseDlg = ref(false)
const editingCourse = ref(null)
const saving = ref(false)
const step = ref(1)
const savedCourseId = ref(null)
const courseForm = ref(blankCourse())
function blankCourse() {
  return {
    title: '', category: '', level: 1,
    cover: '', summary: '', status: 'PUBLISHED', sortOrder: 0,
    learningMode: 'FREE', freeLearn: true, progressBar: true,
    tags: '', content: '', examPaperId: null,
  }
}

// 课程板块：拉取课程自身的分类接口（GET /courses/categories，由 CourseService 从已发布课程动态汇总），
// 与文档分类体系（/categories，后台「分类标签」维护）完全解耦，不会混入文档分类
const categoryOptions = ref([])
const loadCategories = () => {
  courseCategories().then(r => {
    const arr = (r.data || []).map(x => String(x).trim()).filter(Boolean)
    categoryOptions.value = Array.from(new Set(arr))
  }).catch(() => { categoryOptions.value = [] })
}

const learningModeLabel = (m) => m === 'GUIDED' ? '引导学习' : '自由学习'

// 标签 chip
const tagInput = ref('')
const formTags = ref([])
function splitTags(s) { return s ? s.split(/[,，;；\s]+/).filter(Boolean) : [] }
function syncTagsToForm() { courseForm.value.tags = formTags.value.join(',') }
function syncFormToTags() { formTags.value = splitTags(courseForm.value.tags) }
function addTag() {
  const v = (tagInput.value || '').trim()
  if (!v) return
  if (!formTags.value.includes(v)) formTags.value.push(v)
  tagInput.value = ''
  syncTagsToForm()
}
function removeTag(t) {
  formTags.value = formTags.value.filter(x => x !== t)
  syncTagsToForm()
}

// 插入封面图到正文（富文本由 wangEditor 自身的工具栏插入图片，本按钮作为快捷方式）
function insertCoverImage() {
  if (!courseForm.value.cover) { ElMessage.warning('请先上传封面图'); return }
  // wangEditor 插入图片通过工具栏走 uploadImage 通道；这里简单提示用户使用工具栏图片按钮
  ElMessage.info('请使用上方编辑器的"图片"按钮上传并插入（已自动支持拖拽）')
}
function alertComing(name) { ElMessage.info('「' + name + '」功能即将上线') }

// 封面拖拽上传
function beforeCover(file) {
  const ok = file.type === 'image/jpeg' || file.type === 'image/png' || file.type === 'image/gif'
  if (!ok) { ElMessage.error('仅支持 jpg/png/gif'); return false }
  if (file.size > 1024 * 1024) { ElMessage.error('附件大小不能超过 1.0MB'); return false }
  return true
}
async function uploadCover(opts) {
  try {
    const { data } = await uploadFile(opts.file)
    if (data && data.errno === 0 && data.data && data.data.url) {
      courseForm.value.cover = data.data.url
      ElMessage.success('封面上传成功')
    } else {
      ElMessage.error(data?.message || '上传失败')
    }
  } catch (e) {
    ElMessage.error('上传失败')
  }
}

// 章节
const chapterDlg = ref(false)
const editingChapter = ref(null)
const chapters = ref([])
const chapterForm = ref(blankChapter())
function blankChapter() { return { title: '', content: '', articleId: null, sortOrder: 0, durationMin: 0 } }

// 关联文章：远程搜索已发布文章（标题），存 id；编辑时回显原标题
const articleOptions = ref([])
const articleSearching = ref(false)
let articleSearchTimer = null
function searchArticles(kw) {
  clearTimeout(articleSearchTimer)
  articleSearchTimer = setTimeout(async () => {
    articleSearching.value = true
    try {
      const { data } = await searchDocs((kw || '').trim())
      const list = Array.isArray(data) ? data : (data?.content || [])
      articleOptions.value = list.map(d => ({ id: d.id, title: d.title || ('#' + d.id) })).slice(0, 30)
    } catch { articleOptions.value = [] }
    finally { articleSearching.value = false }
  }, 250)
}
async function ensureArticleLabel(id) {
  // 编辑回显：下拉里没有该 id 的选项时，单独拉这篇补进 options
  if (!id) return
  if (articleOptions.value.some(a => a.id === id)) return
  try {
    const { data } = await getDoc(id)
    if (data?.id) articleOptions.value.unshift({ id: data.id, title: data.title || ('#' + data.id) })
  } catch { /* 文章可能已删除，忽略 */ }
}

const load = () => { loading.value = true; adminCourses().then(r => {
  const list = r.data || r || []
  // 列表里附上章节数 / 总时长（按需懒加载章节详情，这里只简单按 id 串行拉一次）
  courses.value = list
  loading.value = false
  hydrateStats(list)
}).catch(() => { loading.value = false; courses.value = [] }) }

const hydrateStats = (list) => {
  // 轻量做法：并发拉章节列表（每门课一次）→ 把章数和总时长挂到 row 上
  Promise.all(list.map(c => adminChapters(c.id).then(r => {
    const chs = r.data || r || []
    c.chapterCount = chs.length
    c.totalDurationMin = chs.reduce((a, b) => a + (b.durationMin || 0), 0)
  }).catch(() => { c.chapterCount = 0; c.totalDurationMin = 0 })))
}

const loadChapters = () => { if (!selCourse.value && !savedCourseId.value) return; const id = selCourse.value?.id || savedCourseId.value; adminChapters(id).then(r => { chapters.value = r.data || r || [] }).catch(() => {}) }

// 结业考试：拉已发布试卷供绑定（下拉延迟加载，仅取标题/分数/及格线）
const paperOptions = ref([])
function loadPapers() {
  listPapers().then(r => { paperOptions.value = (r.data || r || []) }).catch(() => { paperOptions.value = [] })
}

function openCourse(row) {
  editingCourse.value = row || null
  courseForm.value = row ? { ...row } : blankCourse()
  syncFormToTags()
  // 编辑旧课程时，若其分类不在真实分类列表里，先补进选项，避免下拉空白/保存时清空原分类
  if (row && row.category && !categoryOptions.value.includes(row.category)) categoryOptions.value.push(row.category)
  savedCourseId.value = row?.id || null
  step.value = 1
  courseDlg.value = true
}

function gotoStep(s) {
  // 没保存不允许跳到步骤 2
  if (s === 2 && !savedCourseId.value) { ElMessage.warning('请先保存课程'); return }
  step.value = s
  if (s === 2) {
    selCourse.value = courses.value.find(c => c.id === savedCourseId.value) || { id: savedCourseId.value, title: courseForm.value.title }
    loadChapters()
  }
}

function saveCourseAndNext() {
  if (!courseForm.value.title?.trim()) { ElMessage.error('请输入课程名称'); return }
  if (!courseForm.value.category?.trim()) { ElMessage.error('请选择课程板块'); return }
  syncTagsToForm()
  saving.value = true
  const p = {
    title: courseForm.value.title,
    category: courseForm.value.category,
    level: courseForm.value.level,
    cover: courseForm.value.cover,
    summary: courseForm.value.summary,
    status: courseForm.value.status,
    sortOrder: courseForm.value.sortOrder,
    learningMode: courseForm.value.learningMode,
    freeLearn: courseForm.value.freeLearn,
    progressBar: courseForm.value.progressBar,
    tags: courseForm.value.tags,
    content: courseForm.value.content,
    examPaperId: courseForm.value.examPaperId || null,
  }
  const req = editingCourse.value ? adminUpdateCourse(editingCourse.value.id, p) : adminCreateCourse(p)
  req.then(r => {
    const c = r.data || r
    savedCourseId.value = c.id
    if (!editingCourse.value) editingCourse.value = c
    ElMessage.success('已保存')
    load()
    step.value = 2
    selCourse.value = c
    loadChapters()
  }).catch(e => ElMessage.error(e.response?.data?.error || '保存失败'))
    .finally(() => saving.value = false)
}

const delCourse = (row) => ElMessageBox.confirm('删除课程会同时删除其章节，确定？', '提示', { type: 'warning' })
  .then(() => adminDeleteCourse(row.id)).then(() => { ElMessage.success('已删除'); load() }).catch(() => {})

const openChapters = (row) => { selCourse.value = row; tab.value = 'chapters'; loadChapters() }
const openChapter = async (row) => {
  editingChapter.value = row || null
  chapterForm.value = row ? { ...row } : blankChapter()
  // 回显已关联的文章标题；清空搜索缓存避免旧列表干扰
  articleOptions.value = []
  chapterDlg.value = true
  if (row?.articleId) await ensureArticleLabel(row.articleId)
  else searchArticles('')
}
const saveChapter = () => {
  saving.value = true
  const f = chapterForm.value
  const p = { title: f.title, content: f.content, articleId: f.articleId || null, sortOrder: f.sortOrder, durationMin: f.durationMin, courseId: (selCourse.value?.id || savedCourseId.value) }
  const req = editingChapter.value ? adminUpdateChapter(editingChapter.value.id, p) : adminCreateChapter(p)
  req.then(() => { ElMessage.success('已保存'); chapterDlg.value = false; loadChapters() })
    .catch(e => ElMessage.error(e.response?.data?.error || '保存失败'))
    .finally(() => saving.value = false)
}
const delChapter = (row) => ElMessageBox.confirm('确定删除该章节？', '提示', { type: 'warning' })
  .then(() => adminDeleteChapter(row.id)).then(() => { ElMessage.success('已删除'); loadChapters() }).catch(() => {})

onMounted(() => { load(); loadCategories() })
</script>

<style scoped>
.page { padding: 20px; max-width: 1080px; margin: 0 auto; }
.bar { margin-bottom: 12px; display: flex; gap: 10px; }

.tag-chip { display: inline-block; margin: 2px 4px 2px 0; padding: 1px 8px; background: #fdf6ec; color: #e6a23c; border-radius: 10px; font-size: 12px; }

/* 课程发布主页 · 顶部栏 */
.course-bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; flex-wrap: wrap; gap: 10px; }
.course-bar .crumbs { color: #64748b; font-size: 14px; }
.course-bar .crumbs .sep { margin: 0 8px; color: #cbd5e1; }
.course-bar .crumbs .current { color: #0f172a; font-weight: 600; }
.course-bar .actions { display: flex; align-items: center; gap: 10px; }

/* 课程发布主页 · 横向卡片列表（对齐对标后台） */
.course-list { display: flex; flex-direction: column; gap: 12px; }
.empty-tip { text-align: center; color: #94a3b8; padding: 60px 0; }
.course-row { display: flex; align-items: center; background: #fff; border: 1px solid #e2e8f0; border-radius: 10px; padding: 12px 16px; gap: 16px; transition: .15s; }
.course-row:hover { border-color: #fb923c; box-shadow: 0 4px 12px rgba(251,146,60,.1); }
.row-cover { width: 200px; height: 90px; flex-shrink: 0; border-radius: 6px; position: relative; background: #f1f5f9; background-size: cover; background-position: center; }
.row-cover .status-tag { position: absolute; top: 6px; right: 6px; background: #22c55e; color: #fff; font-size: 11px; padding: 1px 8px; border-radius: 8px; }
.row-main { flex: 1; min-width: 0; }
.row-title { font-size: 15px; font-weight: 600; color: #0f172a; margin-bottom: 6px; }
.row-meta { color: #64748b; font-size: 12px; display: flex; flex-wrap: wrap; align-items: center; gap: 10px; }
.row-meta .stat { display: inline-block; padding: 1px 8px; background: #eef2ff; color: #6366f1; border-radius: 4px; }
.row-meta .cat { color: #64748b; }
.row-meta .lv { padding: 1px 8px; border-radius: 4px; }
.row-meta .lv.lv1 { background: #ecfdf5; color: #059669; }
.row-meta .lv.lv2 { background: #eff6ff; color: #2563eb; }
.row-meta .lv.lv3 { background: #fef3c7; color: #d97706; }
.row-ops { display: flex; gap: 6px; flex-shrink: 0; }

/* 2 步式 dialog 布局 */
.publish-layout { display: flex; min-height: 480px; }
.publish-steps { width: 120px; border-right: 1px solid #ebeef5; padding-top: 8px; }
.publish-steps .step { display: flex; align-items: center; gap: 10px; padding: 14px 12px; cursor: pointer; color: #909399; position: relative; }
.publish-steps .step .num { width: 22px; height: 22px; border-radius: 50%; border: 1px solid #dcdfe6; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; background: #fff; }
.publish-steps .step.active { color: #409eff; font-weight: 600; }
.publish-steps .step.active .num { background: #409eff; color: #fff; border-color: #409eff; }
.publish-steps .step.done { color: #67c23a; }
.publish-steps .step.done .num { background: #67c23a; color: #fff; border-color: #67c23a; }
.publish-form { flex: 1; padding: 4px 8px 0 16px; min-width: 0; }

.form { padding-right: 8px; }
.row-2col { display: grid; grid-template-columns: 1fr 210px; gap: 14px; }
.col-left { min-width: 0; }
.col-right { width: 210px; flex-shrink: 0; }

/* 课程标签 chip 输入 */
.tag-input-wrap { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; min-height: 32px; padding: 2px 6px; border: 1px solid #dcdfe6; border-radius: 4px; }
.tag-input { width: 120px; }
.tag-chip-input { margin: 2px 0; }

/* 封面上传：独立块（不进 el-form-item，避免 label 96px 挤压内容区导致横向溢出——这是上一版溢出的根因） */
.cover-block { width: 210px; }
.cover-label { font-size: 13px; color: #606266; margin-bottom: 6px; }
.cover-uploader { width: 210px; display: block; }
.cover-uploader :deep(.el-upload) { width: 210px !important; max-width: 210px; height: 116px !important; max-height: 116px; border: 1px dashed #d9d9d9; border-radius: 6px; overflow: hidden; display: block; box-sizing: border-box; }
.cover-uploader :deep(.el-upload-dragger) { width: 210px !important; height: 116px !important; padding: 0 !important; display: flex; flex-direction: column; align-items: center; justify-content: center; box-sizing: border-box; overflow: hidden; }
.cover-preview { width: 100%; height: 100%; object-fit: cover; display: block; }
.cover-empty { color: #909399; text-align: center; font-size: 12px; padding: 0 8px; }
.cover-icon { font-size: 26px; margin-bottom: 4px; }
.cover-text em { color: #409eff; font-style: normal; }
.cover-hint { font-size: 12px; color: #909399; line-height: 1.5; margin-top: 6px; }

/* 富文本：固定高度 + overflow，避免 wangEditor 在 dialog 里高度失控 */
.editor-insert-bar { margin-top: 8px; display: flex; gap: 8px; }
.editor-divider { text-align: center; color: #409eff; margin: 12px 0 4px; position: relative; font-size: 12px; }
.editor-divider::before, .editor-divider::after { content: ''; display: inline-block; width: 80px; height: 1px; background: #ebeef5; vertical-align: middle; margin: 0 10px; }
:deep(.publish-form .w-e-toolbar), :deep(.publish-form .w-e-text-container) { max-width: 100% !important; }

/* 章节弹窗 */
.chapter-form .chapter-row2 { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.chapter-editor { width: 100%; }
.chapter-editor :deep(.w-e-toolbar), .chapter-editor :deep(.w-e-text-container) { max-width: 100% !important; }
/* el-form-item 内容区是 flex，富文本工具栏需要整行宽度 */
.chapter-form :deep(.el-form-item__content) { display: block; }
.field-hint { font-size: 12px; color: #909399; line-height: 1.5; margin-top: 4px; }
.linked-article { color: #6366f1; font-size: 12px; }
.muted { color: #c0c4cc; }

/* dialog footer 蓝色全宽 */
.dialog-footer { display: flex; justify-content: flex-end; gap: 10px; }
.primary-wide { min-width: 200px; }

.hint-warn { padding: 20px; color: #e6a23c; background: #fdf6ec; border-radius: 4px; }

/* 暗色模式适配（自定义卡片/边框/文字色，el-table 与 dialog 由 Element 暗色变量自动处理） */
html.dark .course-row { background: #1d1d2b; border-color: #2c2c3e; }
html.dark .course-row:hover { border-color: #fb923c; box-shadow: 0 4px 12px rgba(251,146,60,.16); }
html.dark .row-cover { background: #2c2c3e; }
html.dark .row-title { color: #e8e8f0; }
html.dark .row-meta { color: #9aa0b4; }
html.dark .row-meta .stat { background: rgba(99,102,241,.16); color: #a5b4fc; }
html.dark .row-meta .cat { color: #9aa0b4; }
html.dark .lv.lv1 { background: rgba(5,150,105,.16); color: #34d399; }
html.dark .lv.lv2 { background: rgba(37,99,235,.18); color: #93c5fd; }
html.dark .lv.lv3 { background: rgba(217,119,6,.18); color: #fbbf24; }
html.dark .tag-chip { background: rgba(230,162,60,.16); color: #f0b969; }
html.dark .course-bar .crumbs { color: #9aa0b4; }
html.dark .course-bar .crumbs .sep { color: #4b4f63; }
html.dark .course-bar .crumbs .current { color: #e8e8f0; }
html.dark .empty-tip { color: #6b7185; }
html.dark .publish-steps { border-right-color: #2c2c3e; }
html.dark .publish-steps .step { color: #8b90a3; }
html.dark .publish-steps .step .num { background: #1d1d2b; border-color: #3a3a4d; }
html.dark .tag-input-wrap { border-color: #3a3a4d; }
html.dark .cover-label { color: #b7b8cd; }
html.dark .cover-uploader :deep(.el-upload) { border-color: #3a3a4d; }
html.dark .cover-hint { color: #8b90a3; }
html.dark .cover-text em { color: #6cb0ff; }
html.dark .editor-divider { color: #6cb0ff; }
html.dark .editor-divider::before, html.dark .editor-divider::after { background: #2c2c3e; }
html.dark .field-hint { color: #8b90a3; }
html.dark .linked-article { color: #a5b4fc; }
html.dark .muted { color: #5b6072; }
html.dark .hint-warn { color: #f0b969; background: rgba(230,162,60,.12); }

/* wangEditor 内部 DOM 由 JS 生成、不带 scoped 属性，必须用 :deep() 命中；仅作用于本页课程/章节编辑器 */
html.dark .publish-form :deep(.w-e-toolbar),
html.dark .chapter-editor :deep(.w-e-toolbar) { background-color: #1d1d2b !important; border-color: #2c2c3e !important; }
html.dark .publish-form :deep(.w-e-toolbar .w-e-bar-item button),
html.dark .chapter-editor :deep(.w-e-toolbar .w-e-bar-item button) { color: #c7c8d4 !important; }
html.dark .publish-form :deep(.w-e-text-container),
html.dark .chapter-editor :deep(.w-e-text-container) { background-color: #1d1d2b !important; }
html.dark .publish-form :deep(.w-e-text-container .w-e-text),
html.dark .chapter-editor :deep(.w-e-text-container .w-e-text) { color: #d4d4d8 !important; }

/* ===== 响应式（统一断点 1024 / 640，只写布局与尺寸） ===== */
@media (max-width: 1024px) {
  .page { padding: 14px; }
  .course-bar .actions { flex-wrap: wrap; }
  .course-row { padding: 10px 12px; gap: 12px; }
  .row-cover { width: 150px; height: 76px; }
  .publish-layout { min-height: 0; }
  .publish-steps { width: 100px; }
  .publish-form { padding: 4px 4px 0 10px; }
  .row-2col { grid-template-columns: 1fr 180px; gap: 12px; }
  .col-right { width: 180px; }
  .cover-block { width: 180px; }
  .cover-uploader { width: 180px; }
  .cover-uploader :deep(.el-upload) { width: 180px !important; max-width: 180px; }
  .cover-uploader :deep(.el-upload-dragger) { width: 180px !important; }
  .bar { flex-wrap: wrap; }
  .publish-form :deep(.el-table),
  .el-tabs :deep(.el-table) { max-width: 100%; }
}
@media (max-width: 640px) {
  .page { padding: 12px; }
  .course-bar { gap: 8px; margin-bottom: 12px; }
  .course-bar .actions { width: 100%; }
  .course-bar .actions :deep(.el-select),
  .course-bar .actions :deep(.el-input) { width: 100% !important; max-width: 100%; }
  .course-bar .actions :deep(.el-button) { flex: 1 1 auto; }

  /* 课程行：封面独占一行，标题与操作纵向排布，避免横向挤压 */
  .course-row { flex-direction: column; align-items: stretch; padding: 10px; gap: 10px; }
  .row-cover { width: 100%; height: 120px; }
  .row-title { font-size: 14px; }
  .row-meta { gap: 6px; font-size: 12px; }
  .row-ops { flex-wrap: wrap; gap: 8px; }
  .row-ops :deep(.el-button) { flex: 1 1 auto; margin-left: 0; }

  /* 发布弹窗：步进条改横向，表单区单列 */
  .publish-layout { flex-direction: column; }
  .publish-steps { display: flex; width: 100%; border-right: none; padding: 0 0 8px; gap: 8px; }
  .publish-steps .step { flex: 1; justify-content: center; padding: 8px 6px; font-size: 13px; }
  .publish-form { padding: 0; }
  .form { padding-right: 0; }
  .row-2col { grid-template-columns: 1fr; }
  .col-right { width: 100%; }
  .cover-block { width: 100%; }
  .cover-uploader { width: 100%; }
  .cover-uploader :deep(.el-upload) { width: 100% !important; max-width: 100%; height: 140px !important; }
  .cover-uploader :deep(.el-upload-dragger) { width: 100% !important; height: 140px !important; }
  .tag-input { width: 100%; }
  .editor-insert-bar { flex-wrap: wrap; }
  .editor-insert-bar :deep(.el-button) { margin-left: 0; }
  .editor-divider::before, .editor-divider::after { width: 40px; margin: 0 6px; }
  .primary-wide { min-width: 0; width: 100%; }
  .dialog-footer { flex-wrap: wrap; }
  .dialog-footer :deep(.el-button) { flex: 1 1 auto; margin-left: 0; }
  .chapter-form .chapter-row2 { grid-template-columns: 1fr; }
  .publish-form :deep(.w-e-toolbar) { flex-wrap: wrap; max-width: 100%; }
}
</style>
