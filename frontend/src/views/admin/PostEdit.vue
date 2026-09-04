<script setup>
// 注册 wangEditor audio 自定义元素（副作用，须先于编辑器创建执行）
import '../../utils/wangeditorAudio'
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import RichEditor from '../../components/RichEditor.vue'
import EmojiPanel from '../../components/EmojiPanel.vue'
import { getDoc, createMyDoc, updateMyDoc, importDoc, getCategoryTree, listTags, listDocVersions, getDocVersion, restoreDocVersion } from '../../api/kb'
import { updateDoc } from '../../api/admin'
import { uploadFile } from '../../api/file'
import { useAuthStore } from '../../store/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const id = route.params.id
const isEdit = !!id

// 文章正文表情：复用评论区的 EmojiPanel，插入 <img class="kb-emoji"> 到编辑器光标处
const editorRef = ref(null)
const insertEmoji = (p) => {
  if (!editorRef.value) return
  if (p.kind === 'img') {
    editorRef.value.insertHtml(`<img class="kb-emoji" src="/emojis/${p.name}.png" alt="表情">`)
  } else {
    editorRef.value.insertHtml(p.text)
  }
}

// 插入语音条：选本地音频 → 上传到 /api/files（复用图片上传通道，后端已放开音频类型）→ 在光标处插入 <audio controls>
const audioInput = ref(null)
const pickAudio = () => audioInput.value?.click()
const onAudioPicked = async (e) => {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  if (!file.type.startsWith('audio/')) {
    ElMessage.warning('请选择音频文件')
    return
  }
  try {
    const { data } = await uploadFile(file)
    if (data && data.data && data.data.url) {
      editorRef.value?.insertHtml(`<audio controls src="${data.data.url}" style="max-width:100%"></audio>`)
    } else {
      ElMessage.error('音频上传失败')
    }
  } catch {
    ElMessage.error('音频上传失败，请重试')
  }
}

const form = ref({ title: '', category: '', tags: '', content: '' })

// 文档层级归属：由「在该文档下新建」跳转带入（/write?parent=yy），将挂到指定父文档下
const toId = (v) => { const n = Number(v); return v && !Number.isNaN(n) ? n : null }
const parentId = ref(toId(route.query.parent))
const parentTitle = ref('')
const categoryTree = ref([])      // 分类树（发帖选择）
const tagOptions = ref([])        // 规范标签（发帖多选）
const selectedTags = ref([])      // 当前选中的标签名数组（写回 form.tags 逗号串）
const splitTags = (s) => (s ? s.split(',').map(x => x.trim()).filter(Boolean) : [])
const loading = ref(false)
const importing = ref(false)
const fileInput = ref(null)
const currentStatus = ref('')
// 自管全屏写作：整块编辑面板变固定全屏，保存/取消/导入按钮随面板一起、不会错位
const fullscreen = ref(false)
const exitFullscreen = () => { fullscreen.value = false }

// ---------- 草稿自动保存（localStorage，防误关丢稿） ----------
const ready = ref(false) // onMounted 加载完成后再开始监听，避免把已加载内容误写成草稿
const draftKey = computed(() => 'kb-draft-' + (id || 'new'))
const pendingDraft = ref(null) // 进入页面时检测到的未提交草稿（用于"恢复/放弃"提示条）
const lastSavedAt = ref(0) // 上次自动保存时间戳（用于提示"已自动保存"）
let saveTimer = null
const loadDraft = () => {
  try {
    const raw = localStorage.getItem(draftKey.value)
    if (!raw) return null
    const d = JSON.parse(raw)
    if (!d || (!d.title && !d.content)) return null
    return d
  } catch { return null }
}
const writeDraft = () => {
  const { title, category, tags, content } = form.value
  if (!title && !content) { clearDraft(); return }
  const payload = { title, category, tags, content, updatedAt: Date.now() }
  localStorage.setItem(draftKey.value, JSON.stringify(payload))
  lastSavedAt.value = payload.updatedAt
}
const clearDraft = () => {
  localStorage.removeItem(draftKey.value)
  pendingDraft.value = null
}
const restoreDraft = () => {
  if (!pendingDraft.value) return
  form.value = {
    title: pendingDraft.value.title || '',
    category: pendingDraft.value.category || '',
    tags: pendingDraft.value.tags || '',
    content: pendingDraft.value.content || '',
  }
  selectedTags.value = splitTags(pendingDraft.value.tags)
  pendingDraft.value = null
}
const discardDraft = () => { clearDraft() }
const fmtDraftTime = (ts) =>
  ts ? new Date(ts).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : ''
// 防抖写入：输入后 800ms 落地
watch(form, () => {
  if (!ready.value) return
  if (saveTimer) clearTimeout(saveTimer)
  saveTimer = setTimeout(writeDraft, 800)
}, { deep: true })

// 从文档文件一键导入为帖子（后端解析 .md/.txt/.html/.docx/.pdf）
const onPickFile = () => fileInput.value?.click()
const onFilePicked = async (e) => {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  importing.value = true
  try {
    const { data } = await importDoc(file)
    form.value.title = data.title || form.value.title
    form.value.content = data.content || form.value.content
    ElMessage.success('已导入到编辑器，可修改后「存草稿」或「提交审核」')
  } catch (err) {
    ElMessage.error(err.response?.data?.error || err.response?.data?.message || '导入失败')
  } finally {
    importing.value = false
  }
}

onMounted(async () => {
  try { categoryTree.value = (await getCategoryTree()).data || [] } catch (e) {}
  try { tagOptions.value = (await listTags()).data || [] } catch (e) {}
  if (isEdit) {
    try {
      const { data } = await getDoc(id)
      form.value = {
        title: data.title || '',
        category: data.category || '',
        tags: data.tags || '',
        content: data.content || '',
      }
      selectedTags.value = splitTags(data.tags)
      currentStatus.value = data.status || ''
    } catch (e) {
      ElMessage.error('加载文档失败')
    }
  }
  // 归属提示：把父文档标题查出来，避免只给用户看 id
  if (parentId.value) {
    try { parentTitle.value = ((await getDoc(parentId.value)).data || {}).title || '' } catch (e) {}
  }
  // 进入页面时，若 localStorage 里有未提交草稿，弹出"恢复/放弃"提示
  const d = loadDraft()
  if (d) pendingDraft.value = d
  ready.value = true
  window.addEventListener('keydown', onKeydown)
})

const onKeydown = (e) => { if (e.key === 'Escape' && fullscreen.value) fullscreen.value = false }

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  if (ready.value && (form.value.title || form.value.content)) writeDraft()
})

// status: 'DRAFT' 存草稿 / 'PENDING_REVIEW' 提交审核 / null 仅保存（已发布帖编辑）
const save = async (status) => {
  if (!form.value.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning('请填写正文')
    return
  }
  loading.value = true
  try {
    form.value.tags = selectedTags.value.join(',')
    const payload = { ...form.value }
    if (status) payload.status = status
    // 层级归属（新建时生效；编辑接口只采纳标题/正文/分类/标签，不会覆盖层级）
    if (parentId.value) payload.parentId = parentId.value
    if (isEdit) {
      // 管理员可改任意帖子；普通用户只能改自己的
      if (auth.isAdmin) await updateDoc(id, payload)
      else await updateMyDoc(id, payload)
      ElMessage.success(status === 'PENDING_REVIEW' ? '已提交审核' : (status === 'DRAFT' ? '已存草稿' : '已保存'))
    } else {
      await createMyDoc(payload)
      ElMessage.success(status === 'PENDING_REVIEW' ? '已提交审核，等待管理员通过' : '已存草稿')
    }
    clearDraft() // 保存成功后清掉本地草稿
    router.push(auth.isAdmin ? '/admin/docs' : '/my/posts')
  } catch (e) {
    ElMessage.error(e.response?.data?.error || e.response?.data?.message || '保存失败')
  } finally {
    loading.value = false
  }
}

// ---------- 文档版本历史 ----------
const versionsDrawer = ref(false)
const versions = ref([])
const loadingVersions = ref(false)
const versionDetail = ref(null)
const loadingDetail = ref(false)

const openVersions = async () => {
  versionsDrawer.value = true
  versionDetail.value = null
  loadingVersions.value = true
  try {
    const { data } = await listDocVersions(id)
    versions.value = data || []
  } catch (e) {
    ElMessage.error('加载版本历史失败')
  } finally {
    loadingVersions.value = false
  }
}

const viewVersion = async (v) => {
  loadingDetail.value = true
  try {
    const { data } = await getDocVersion(id, v.id)
    versionDetail.value = data
  } catch (e) {
    ElMessage.error('加载版本详情失败')
  } finally {
    loadingDetail.value = false
  }
}

const restoreVersion = async (v) => {
  try {
    await ElMessageBox.confirm('将把当前内容替换为该历史版本（当前内容会另存为一版，可再回滚）。确定恢复？', '恢复历史版本', { type: 'warning' })
  } catch {
    return
  }
  try {
    await restoreDocVersion(id, v.id)
    const { data } = await getDoc(id)
    form.value = {
      title: data.title || '',
      category: data.category || '',
      tags: data.tags || '',
      content: data.content || '',
    }
    selectedTags.value = splitTags(data.tags)
    currentStatus.value = data.status || ''
    ElMessage.success('已恢复到该版本')
    versionsDrawer.value = false
    clearDraft()
  } catch (e) {
    ElMessage.error(e.response?.data?.error || '恢复失败')
  }
}
</script>

<template>
  <div class="edit" :class="{ 'writing-fullscreen': fullscreen }">
    <el-page-header @back="router.back()" :content="isEdit ? '编辑文章' : '写文章'" />
    <el-alert
      v-if="pendingDraft"
      type="warning" :closable="false" class="draft-tip"
      :title="'检测到未提交的本地草稿（保存于 ' + fmtDraftTime(pendingDraft.updatedAt) + '），是否恢复？'"
    >
      <template #default>
        <el-button size="small" type="primary" @click="restoreDraft">恢复草稿</el-button>
        <el-button size="small" @click="discardDraft">放弃</el-button>
      </template>
    </el-alert>
    <div class="autosaved" v-else-if="lastSavedAt">✓ 已自动保存于 {{ fmtDraftTime(lastSavedAt) }}</div>
    <el-alert
      v-if="!auth.isAdmin"
      title="提交后由管理员通过才会对外公开；审核通过前仅你和管理员可见"
      type="info" :closable="false" style="margin: 14px 0;"
    />
    <el-form label-width="80px" style="margin-top: 18px;">
      <el-alert v-if="parentId" type="info" :closable="false" show-icon class="parent-tip">
        <template #title>
          将创建为「{{ parentTitle || ('#' + parentId) }}」的子文档
        </template>
      </el-alert>
      <el-form-item label="标题">
        <el-input v-model="form.title" placeholder="文章标题" />
      </el-form-item>
      <el-form-item label="分类">
        <el-tree-select v-model="form.category" :data="categoryTree" node-key="name"
          :props="{ label: 'name', children: 'children' }" placeholder="如：教程" clearable style="width:100%" />
      </el-form-item>
      <el-form-item label="标签">
        <el-select v-model="selectedTags" multiple filterable allow-create default-first-option
          placeholder="选择或输入标签" style="width:100%">
          <el-option v-for="t in tagOptions" :key="t.id" :label="t.name" :value="t.name" />
        </el-select>
      </el-form-item>
      <el-form-item label="正文">
        <div class="editor-extra">
          <el-popover placement="bottom-start" trigger="click" :width="300" popper-class="emoji-pop">
            <template #reference>
              <el-button size="small" text>😊 表情</el-button>
            </template>
            <EmojiPanel @insert="insertEmoji" />
          </el-popover>
          <el-button size="small" text @click="pickAudio">🎵 语音</el-button>
          <input ref="audioInput" type="file" accept="audio/*" hidden @change="onAudioPicked" />
        </div>
        <RichEditor ref="editorRef" v-model="form.content" />
      </el-form-item>
      <el-form-item>
        <template v-if="isEdit && currentStatus === 'PUBLISHED'">
          <el-button type="primary" :loading="loading" @click="save(null)">保存修改</el-button>
        </template>
        <template v-else-if="auth.isAdmin">
          <el-button :loading="loading" @click="save('DRAFT')">存草稿</el-button>
          <el-button type="primary" :loading="loading" @click="save(null)">发布</el-button>
        </template>
        <template v-else>
          <el-button :loading="loading" @click="save('DRAFT')">存草稿</el-button>
          <el-button type="primary" :loading="loading" @click="save('PENDING_REVIEW')">提交</el-button>
        </template>
        <el-button :loading="importing" @click="onPickFile">
          <span v-if="!importing">从文档导入</span>
          <span v-else>导入中…</span>
        </el-button>
        <el-button @click="router.back()">取消</el-button>
        <el-button v-if="isEdit" @click="openVersions">历史版本</el-button>
        <el-button @click="fullscreen = !fullscreen">
          <span v-if="!fullscreen">全屏写作</span>
          <span v-else>退出全屏</span>
        </el-button>
        <input ref="fileInput" type="file" accept=".md,.markdown,.txt,.text,.html,.htm,.docx,.pdf"
               style="display:none" @change="onFilePicked" />
      </el-form-item>
    </el-form>

    <!-- 版本历史抽屉：列出历史版本，可查看/恢复 -->
    <el-drawer v-model="versionsDrawer" title="历史版本" direction="rtl" size="460px">
      <div v-loading="loadingVersions">
        <div v-if="!versions.length" class="ver-empty">暂无历史版本（首次保存修改后才会生成）</div>
        <el-timeline v-else>
          <el-timeline-item
            v-for="v in versions"
            :key="v.id"
            :timestamp="(v.createdAt || '').replace('T', ' ').slice(0, 19)"
            placement="top"
          >
            <div class="ver-item">
              <div class="ver-meta">
                <span class="ver-editor">编辑者 #{{ v.editorId || '-' }}</span>
                <el-button size="small" text type="primary" @click="viewVersion(v)">查看</el-button>
                <el-button size="small" text type="warning" @click="restoreVersion(v)">恢复此版本</el-button>
              </div>
              <div class="ver-title">{{ v.title || '(无标题)' }}</div>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>

      <el-divider v-if="versionDetail">版本内容</el-divider>
      <div v-if="versionDetail" v-loading="loadingDetail" class="ver-detail">
        <div class="ver-detail-title">{{ versionDetail.title }}</div>
        <div class="ver-detail-body content" v-html="versionDetail.content || ''"></div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.parent-tip { margin-bottom: 14px; }
.edit { max-width: 960px; margin: 0 auto; color: #0f172a; }
.editor-extra { margin-bottom: 6px; }
.editor-extra :deep(.el-button) { color: #6366f1; }
.draft-tip { margin: 14px 0; }
.draft-tip :deep(.el-alert__content) { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.autosaved { color: #16a34a; font-size: 13px; margin: 10px 0 0; }
/* 自管全屏写作：整块面板固定全屏，操作按钮随面板一起、不再错位 */
.edit.writing-fullscreen {
  position: fixed; inset: 0; z-index: 3000; max-width: none; margin: 0;
  background: #fff; overflow: auto; padding: 18px 24px 24px;
}
.edit.writing-fullscreen :deep(.w-editor) { height: calc(100vh - 300px); }
.ver-empty { color: #94a3b8; font-size: 13px; padding: 12px 0; }
.ver-item { padding: 2px 0; }
.ver-meta { display: flex; align-items: center; gap: 8px; }
.ver-editor { font-size: 12px; color: #64748b; }
.ver-title { margin-top: 4px; font-weight: 600; color: #0f172a; }
.ver-detail { max-height: 50vh; overflow: auto; }
.ver-detail-title { font-weight: 700; margin-bottom: 8px; color: #111827; }
.ver-detail-body { font-size: 14px; line-height: 1.7; color: #334155; }

/* 暗色模式适配（页面正文色、全屏写作底色、自动保存提示、版本历史文字等硬编码浅色） */
html.dark .edit { color: var(--el-text-color-primary); }
html.dark .editor-extra :deep(.el-button) { color: #a5b4fc; }
html.dark .autosaved { color: #4ade80; }
html.dark .edit.writing-fullscreen { background: var(--el-bg-color); color: var(--el-text-color-primary); }
html.dark .ver-empty { color: var(--el-text-color-secondary); }
html.dark .ver-editor { color: var(--el-text-color-secondary); }
html.dark .ver-title { color: var(--el-text-color-primary); }
html.dark .ver-detail-title { color: var(--el-text-color-primary); }
html.dark .ver-detail-body { color: var(--el-text-color-regular); }

/* wangEditor 内部 DOM 由 JS 生成、不带 scoped 属性，必须用 :deep() 命中；仅作用于本页编辑器 */
html.dark .edit :deep(.w-e-toolbar) { background-color: var(--el-bg-color-overlay) !important; border-color: var(--el-border-color) !important; }
html.dark .edit :deep(.w-e-toolbar .w-e-bar-item button) { color: var(--el-text-color-regular) !important; }
html.dark .edit :deep(.w-e-text-container) { background-color: var(--el-bg-color-overlay) !important; }
html.dark .edit :deep(.w-e-text-container .w-e-text) { color: var(--el-text-color-primary) !important; }

/* ===== 响应式（统一断点 1024 / 640，只写布局与尺寸） ===== */
@media (max-width: 1024px) {
  .edit { max-width: 100%; }
  .el-form { margin-top: 14px !important; }
  .editor-extra { display: flex; flex-wrap: wrap; gap: 6px; }
  /* wangEditor：工具栏允许换行，编辑区不许撑破容器 */
  .edit :deep(.w-e-toolbar) { flex-wrap: wrap; max-width: 100%; height: auto; }
  .edit :deep(.w-e-text-container) { max-width: 100%; }
}
@media (max-width: 640px) {
  .edit { padding: 0 2px; }
  .el-form { margin-top: 10px !important; }
  .editor-extra :deep(.el-button) { padding-left: 6px; padding-right: 6px; }
  .edit :deep(.el-form-item__content) { flex-wrap: wrap; gap: 8px; }
  .edit :deep(.el-form-item__content) > .el-button { margin-left: 0; flex: 1 1 auto; }
  .draft-tip { margin: 10px 0; }
  .draft-tip :deep(.el-alert__content) { gap: 8px; }
  .edit.writing-fullscreen { padding: 12px 14px 18px; }
  .edit.writing-fullscreen :deep(.w-editor) { height: calc(100vh - 260px); }
  .ver-detail { max-height: 44vh; }
}
</style>
